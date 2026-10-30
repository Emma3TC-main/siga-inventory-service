package utp.siga.inventory.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import utp.siga.inventory.application.command.ConfirmMovementCommand;
import utp.siga.inventory.application.port.out.MovementConfirmationPort;
import utp.siga.inventory.application.port.out.MovementRepository;
import utp.siga.inventory.application.exception.LotUnavailableException;
import utp.siga.inventory.application.exception.AssetUnavailableException;
import utp.siga.inventory.domain.model.Movement;
import utp.siga.inventory.domain.model.MovementDetail;
import utp.siga.inventory.domain.model.MovementType;

@Component
public class PostgresMovementConfirmationPort implements MovementConfirmationPort {
    private static final int SCALE = 6;
    private static final MathContext MATH = MathContext.DECIMAL128;
    private final JdbcTemplate jdbc;
    private final MovementRepository movements;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final LotChangedEventWriter lotEvents;

    public PostgresMovementConfirmationPort(JdbcTemplate jdbc, MovementRepository movements, ObjectMapper objectMapper,
            Clock clock, LotChangedEventWriter lotEvents) {
        this.jdbc = jdbc;
        this.movements = movements;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.lotEvents = lotEvents;
    }

    @Override
    public Movement confirm(ConfirmMovementCommand command) {
        LocationLocks.lockHierarchy(jdbc);
        String requestHash = sha256(command.movementId() + ":" + command.actorUserId());
        Integer inserted = jdbc.update("""
                INSERT INTO inventory.idempotency_record(idempotency_key, request_hash, resource_type, resource_id,
                    response_status, response_body, expires_at)
                VALUES (?, ?, 'MovementConfirmation', ?, 202, '{}'::jsonb, now() + interval '24 hours')
                ON CONFLICT (idempotency_key) DO NOTHING
                """, command.idempotencyKey(), requestHash, command.movementId());
        if (inserted == 0) {
            Map<String, Object> existing = jdbc.queryForMap("SELECT request_hash, resource_id FROM inventory.idempotency_record WHERE idempotency_key=?",
                    command.idempotencyKey());
            if (!requestHash.equals(existing.get("request_hash"))) {
                throw new IllegalStateException("Idempotency key was already used with a different request");
            }
            UUID resourceId = (UUID) existing.get("resource_id");
            return movements.findById(resourceId).orElseThrow(() -> new IllegalStateException("Idempotent movement result is unavailable"));
        }

        lockMovement(command.movementId());
        Movement movement = movements.findById(command.movementId())
                .orElseThrow(() -> new IllegalArgumentException("Movement not found"));
        requireActiveLocations(movement);
        if (movement.getStatus() == utp.siga.inventory.domain.model.MovementStatus.DRAFT
                && (!command.actorCanAuthorize() || requiresSensitivityApproval(movement))) {
            throw new utp.siga.inventory.domain.exception.InvalidMovementStateException(
                    "Direct confirmation requires MOVEMENT_AUTHORIZE and a non-sensitive movement");
        }
        List<Target> targets = targetsFor(movement);
        Map<UUID, LockedLot> lots = lockAndValidateLots(movement);
        lockAndValidateAssets(movement);
        ensureCreatableBalances(targets);
        Map<BalanceKey, Balance> balances = lockBalances(targets);
        List<DetailCost> costs = applyStockAndCosts(movement, balances);
        materializeDepletedLots(lots, command.correlationId());
        movement.confirm();
        movements.save(movement);

        UUID eventId = UUID.randomUUID();
        String payload = payload(eventId, command, movement, costs, balances);
        jdbc.update("""
                INSERT INTO inventory.outbox_event(id, aggregate_type, aggregate_id, event_type, schema_version,
                    correlation_id, payload, status, occurred_at)
                VALUES (?, 'Movement', ?, 'MovementConfirmed', 1, ?, ?::jsonb, 'PENDING', now())
                """, eventId, movement.getId(), command.correlationId(), payload);
        jdbc.update("UPDATE inventory.idempotency_record SET response_status=200, response_body=?::jsonb WHERE idempotency_key=?",
                "{\"movementId\":\"" + movement.getId() + "\",\"status\":\"CONFIRMED\"}", command.idempotencyKey());
        return movement;
    }

    private Map<UUID, LockedLot> lockAndValidateLots(Movement movement) {
        Map<UUID, UUID> productsByLot = new HashMap<>();
        for (MovementDetail detail : movement.getDetails()) {
            if (detail.getLotId() != null) {
                UUID previous = productsByLot.putIfAbsent(detail.getLotId(), detail.getProductId());
                if (previous != null && !previous.equals(detail.getProductId())) {
                    throw new LotUnavailableException("Lot does not belong to movement product");
                }
            }
        }
        Map<UUID, LockedLot> locked = new HashMap<>();
        for (UUID lotId : productsByLot.keySet().stream().sorted().toList()) {
            List<LockedLot> rows = jdbc.query("""
                    SELECT id,product_id,status,expires_at,version,
                           COALESCE((SELECT SUM(quantity) FROM inventory.stock_balance WHERE lot_id=l.id),0) quantity
                    FROM inventory.lot l WHERE id=? FOR UPDATE
                    """, (rs, row) -> new LockedLot(rs.getObject("id", UUID.class),
                            rs.getObject("product_id", UUID.class), rs.getString("status"),
                            rs.getObject("expires_at", LocalDate.class), rs.getLong("version"),
                            rs.getBigDecimal("quantity")), lotId);
            if (rows.isEmpty()) throw new LotUnavailableException("Lot does not exist");
            LockedLot lot = rows.getFirst();
            if (!lot.productId().equals(productsByLot.get(lotId)) || !"ACTIVE".equals(lot.status())
                    || (lot.expiresAt() != null && lot.expiresAt().isBefore(LocalDate.now(clock)))) {
                throw new LotUnavailableException("Lot is not available for movement");
            }
            locked.put(lotId, lot);
        }
        return locked;
    }

    private void materializeDepletedLots(Map<UUID, LockedLot> lots, UUID correlationId) {
        for (UUID lotId : lots.keySet().stream().sorted().toList()) {
            LockedLot lot = lots.get(lotId);
            BigDecimal afterQuantity = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(quantity),0) FROM inventory.stock_balance WHERE lot_id=?",
                    BigDecimal.class, lotId);
            if (lot.quantity().signum() > 0 && afterQuantity.signum() == 0) {
                var before = lotEvents.snapshot(lotId);
                int changed = jdbc.update("UPDATE inventory.lot SET status='DEPLETED',version=version+1,updated_at=now() "
                        + "WHERE id=? AND version=? AND status='ACTIVE'", lotId, lot.version());
                if (changed != 1) throw new IllegalStateException("Lot lifecycle changed during confirmation");
                var after = lotEvents.snapshot(lotId);
                lotEvents.append("STATUS_CHANGED", null, correlationId, before, after);
            }
        }
    }

    private void lockAndValidateAssets(Movement movement) {
        Map<UUID, UUID> productsByAsset = new HashMap<>();
        Map<UUID, MovementDetail> detailsByAsset = new HashMap<>();
        for (MovementDetail detail : movement.getDetails()) {
            if (detail.getAssetId() != null) {
                UUID previous = productsByAsset.putIfAbsent(detail.getAssetId(), detail.getProductId());
                MovementDetail previousDetail = detailsByAsset.putIfAbsent(detail.getAssetId(), detail);
                if (previousDetail != null || (previous != null && !previous.equals(detail.getProductId()))) {
                    throw new AssetUnavailableException("Asset cannot be repeated or assigned to another product");
                }
            }
        }
        for (UUID assetId : productsByAsset.keySet().stream().sorted().toList()) {
            List<LockedAsset> rows = jdbc.query("""
                    SELECT id,product_id,operational_status
                    FROM inventory.asset WHERE id=? FOR UPDATE
                    """, (rs, row) -> new LockedAsset(rs.getObject("id", UUID.class),
                            rs.getObject("product_id", UUID.class), rs.getString("operational_status")), assetId);
            if (rows.isEmpty()) throw new AssetUnavailableException("Asset does not exist");
            LockedAsset asset = rows.getFirst();
            if (!asset.productId().equals(productsByAsset.get(assetId))
                    || !"OPERATIVO".equals(asset.status())) {
                throw new AssetUnavailableException("Asset is not available for movement");
            }
            validateAssetStock(movement.getType(), detailsByAsset.get(assetId));
        }
    }

    private void validateAssetStock(MovementType type, MovementDetail detail) {
        List<AssetStock> positive = jdbc.query("""
                SELECT product_id,location_id,lot_id,quantity
                FROM inventory.stock_balance WHERE asset_id=? AND quantity>0
                ORDER BY location_id
                """, (rs, row) -> new AssetStock(rs.getObject("product_id", UUID.class),
                        rs.getObject("location_id", UUID.class), rs.getObject("lot_id", UUID.class),
                        rs.getBigDecimal("quantity")), detail.getAssetId());
        if (type == MovementType.ENTRY || type == MovementType.ADJUST_POSITIVE) {
            if (!positive.isEmpty()) throw new AssetUnavailableException("Asset is already in stock");
            return;
        }
        if (positive.size() != 1) throw new AssetUnavailableException("Asset is not in stock");
        AssetStock stock = positive.getFirst();
        if (!stock.productId().equals(detail.getProductId())
                || !stock.locationId().equals(detail.getOriginLocationId())
                || !java.util.Objects.equals(stock.lotId(), detail.getLotId())
                || stock.quantity().compareTo(BigDecimal.ONE) != 0) {
            throw new AssetUnavailableException("Asset stock dimension does not match movement origin");
        }
    }

    private void lockMovement(UUID id) {
        if (jdbc.queryForList("SELECT id FROM inventory.movement WHERE id=? FOR UPDATE", UUID.class, id).isEmpty()) {
            throw new IllegalArgumentException("Movement not found");
        }
    }

    private void requireActiveLocations(Movement movement) {
        movement.getDetails().stream()
                .flatMap(detail -> java.util.stream.Stream.of(
                        detail.getOriginLocationId(), detail.getDestinationLocationId()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(id -> {
                    List<Boolean> rows = jdbc.query(
                            "SELECT active FROM inventory.location WHERE id=? FOR KEY SHARE",
                            (rs, row) -> rs.getBoolean(1), id);
                    if (rows.isEmpty() || !rows.getFirst()) {
                        throw new IllegalStateException("Movement references a missing or inactive location");
                    }
                });
    }

    private boolean requiresSensitivityApproval(Movement movement) {
        if (movement.getType() == MovementType.ADJUST_POSITIVE
                || movement.getType() == MovementType.ADJUST_NEGATIVE) {
            return true;
        }
        return (movement.getType() == MovementType.EXIT || movement.getType() == MovementType.TRANSFER)
                && movement.getDetails().stream().anyMatch(detail -> detail.getAssetId() != null);
    }

    private List<Target> targetsFor(Movement movement) {
        List<Target> targets = new ArrayList<>();
        for (MovementDetail detail : movement.getDetails()) {
            switch (movement.getType()) {
                case ENTRY, ADJUST_POSITIVE -> targets.add(Target.destination(detail, true));
                case EXIT, ADJUST_NEGATIVE -> targets.add(Target.origin(detail, false));
                case TRANSFER -> {
                    targets.add(Target.origin(detail, false));
                    targets.add(Target.destination(detail, true));
                }
            }
        }
        return targets;
    }

    private void ensureCreatableBalances(List<Target> targets) {
        targets.stream()
                .filter(Target::createAllowed)
                .map(Target::key)
                .distinct()
                .sorted(BalanceKey.ORDER)
                .forEach(key ->
                jdbc.update("""
                        INSERT INTO inventory.stock_balance(product_id, location_id, lot_id, asset_id, quantity, avg_cost_pen)
                        VALUES (?, ?, ?, ?, 0, 0)
                        ON CONFLICT DO NOTHING
                        """, key.productId(), key.locationId(), key.lotId(), key.assetId()));
    }

    private Map<BalanceKey, Balance> lockBalances(List<Target> targets) {
        Map<BalanceKey, Boolean> requirements = new HashMap<>();
        for (Target target : targets) requirements.merge(target.key(), target.createAllowed(), Boolean::logicalOr);
        List<BalanceKey> keys = requirements.keySet().stream().sorted(BalanceKey.ORDER).toList();
        Map<BalanceKey, Balance> locked = new HashMap<>();
        for (BalanceKey key : keys) {
            List<Balance> rows = jdbc.query("""
                    SELECT b.id, b.product_id, b.location_id, b.lot_id, b.asset_id, b.quantity, b.avg_cost_pen, p.min_stock
                    FROM inventory.stock_balance b JOIN inventory.product_ref p ON p.product_id=b.product_id
                    WHERE b.product_id=? AND b.location_id=?
                      AND b.lot_id IS NOT DISTINCT FROM ? AND b.asset_id IS NOT DISTINCT FROM ?
                    FOR UPDATE
                    """, (rs, row) -> new Balance((UUID) rs.getObject("id"), key, rs.getBigDecimal("quantity"),
                    rs.getBigDecimal("avg_cost_pen"), rs.getBigDecimal("min_stock")),
                    key.productId(), key.locationId(), key.lotId(), key.assetId());
            if (rows.isEmpty()) throw new IllegalStateException("Required stock balance does not exist");
            locked.put(key, rows.getFirst());
        }
        return locked;
    }

    private List<DetailCost> applyStockAndCosts(Movement movement, Map<BalanceKey, Balance> balances) {
        List<DetailCost> costs = new ArrayList<>();
        for (MovementDetail detail : movement.getDetails()) {
            BigDecimal base = null;
            BigDecimal applied = null;
            switch (movement.getType()) {
                case ENTRY -> {
                    base = entryUnitCost(detail, true);
                    Balance destination = balances.get(BalanceKey.destination(detail));
                    BigDecimal value = entryValue(detail);
                    destination.set(destination.quantity.add(detail.getStockQuantity()), weighted(destination, value, detail.getStockQuantity()));
                    applied = destination.average;
                }
                case EXIT, ADJUST_NEGATIVE -> {
                    Balance origin = balances.get(BalanceKey.origin(detail));
                    requireNoClientCost(detail, movement.getType());
                    requireSufficient(origin, detail.getStockQuantity());
                    applied = origin.average;
                    origin.set(origin.quantity.subtract(detail.getStockQuantity()), origin.average);
                }
                case TRANSFER -> {
                    Balance origin = balances.get(BalanceKey.origin(detail));
                    Balance destination = balances.get(BalanceKey.destination(detail));
                    requireNoClientCost(detail, movement.getType());
                    requireSufficient(origin, detail.getStockQuantity());
                    applied = origin.average;
                    origin.set(origin.quantity.subtract(detail.getStockQuantity()), origin.average);
                    BigDecimal transferValue = detail.getStockQuantity().multiply(applied, MATH);
                    destination.set(destination.quantity.add(detail.getStockQuantity()), weighted(destination, transferValue, detail.getStockQuantity()));
                }
                case ADJUST_POSITIVE -> {
                    Balance destination = balances.get(BalanceKey.destination(detail));
                    if (detail.getOriginalUnitCost() != null) {
                        base = entryUnitCost(detail, false);
                        applied = base;
                        destination.set(destination.quantity.add(detail.getStockQuantity()), weighted(destination, entryValue(detail), detail.getStockQuantity()));
                    } else {
                        if (destination.quantity.signum() == 0) throw new IllegalStateException("Positive adjustment without cost requires positive existing stock");
                        applied = destination.average;
                        destination.set(destination.quantity.add(detail.getStockQuantity()), destination.average);
                    }
                }
            }
            jdbc.update("UPDATE inventory.movement_detail SET base_unit_cost_pen=?, applied_avg_cost_pen=? WHERE id=?",
                    base, applied, detail.getId());
            costs.add(new DetailCost(detail, base, applied));
        }
        List<Balance> writeOrder = balances.values().stream()
                .sorted(Comparator.comparingInt((Balance balance) -> balance.quantity.signum() == 0 ? 0 : 1)
                        .thenComparing(balance -> balance.key, BalanceKey.ORDER))
                .toList();
        for (Balance balance : writeOrder) {
            jdbc.update("UPDATE inventory.stock_balance SET quantity=?, avg_cost_pen=?, version=version+1, updated_at=now() WHERE id=?",
                    scale(balance.quantity), scale(balance.average), balance.id);
        }
        return costs;
    }

    private BigDecimal entryUnitCost(MovementDetail detail, boolean required) {
        if (detail.getOriginalUnitCost() == null) {
            if (required) throw new IllegalArgumentException("originalUnitCost is required for ENTRY");
            return null;
        }
        return scale(entryValue(detail).divide(detail.getStockQuantity(), MATH));
    }

    private BigDecimal entryValue(MovementDetail detail) {
        BigDecimal rate = "PEN".equals(detail.getOriginalCurrency()) ? BigDecimal.ONE : detail.getExchangeRate();
        if (rate == null) throw new IllegalArgumentException("exchangeRate is required for foreign currency");
        return detail.getEnteredQuantity().multiply(detail.getOriginalUnitCost(), MATH).multiply(rate, MATH);
    }

    private BigDecimal weighted(Balance balance, BigDecimal incomingValue, BigDecimal incomingQuantity) {
        BigDecimal total = balance.quantity.add(incomingQuantity);
        return scale(balance.quantity.multiply(balance.average, MATH).add(incomingValue, MATH).divide(total, MATH));
    }

    private void requireSufficient(Balance balance, BigDecimal quantity) {
        if (balance.quantity.compareTo(quantity) < 0) throw new IllegalStateException("Insufficient stock");
    }

    private void requireNoClientCost(MovementDetail detail, MovementType type) {
        if (detail.getOriginalUnitCost() != null) throw new IllegalArgumentException(type + " does not accept originalUnitCost");
    }

    private String payload(UUID eventId, ConfirmMovementCommand command, Movement movement, List<DetailCost> costs,
            Map<BalanceKey, Balance> balances) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("eventId", eventId.toString()); root.put("eventType", "MovementConfirmed"); root.put("schemaVersion", 1);
            root.put("aggregateType", "Movement"); root.put("aggregateId", movement.getId().toString());
            root.put("occurredAt", movement.getConfirmedAt().toString()); root.put("correlationId", command.correlationId().toString());
            root.put("producer", "inventory-service");
            ObjectNode payload = root.putObject("payload"); ObjectNode m = payload.putObject("movement");
            m.put("id", movement.getId().toString()); m.put("movementCode", movement.getMovementCode());
            m.put("movementType", movement.getType().name()); m.put("status", "CONFIRMED");
            m.put("registeredByUserId", movement.getRegisteredByUserId().toString()); m.put("confirmedByUserId", command.actorUserId().toString());
            if (movement.getRequestedByUserId()!=null) m.put("requestedByUserId", movement.getRequestedByUserId().toString());
            putString(m, "costCenterCode", movement.getCostCenterCode()); putString(m, "costCenterNameSnapshot", movement.getCostCenterNameSnapshot());
            putUuid(m, "supplierId", movement.getSupplierId()); putString(m, "supplierNameSnapshot", movement.getSupplierNameSnapshot()); putString(m, "supplierTaxIdSnapshot", movement.getSupplierTaxIdSnapshot());
            putString(m, "referenceDocType", movement.getReferenceDocType()); putString(m, "referenceDocNumber", movement.getReferenceDocNumber()); putString(m, "reason", movement.getReason());
            m.put("isSensitive", movement.isSensitive()); putString(m, "sensitivityReason", movement.getSensitivityReason());
            movement.getAuthorizations().stream().filter(a -> "APPROVED".equals(a.getDecision())).map(a -> a.getAuthorizedByUserId()).filter(java.util.Objects::nonNull).findFirst().ifPresent(id -> m.put("authorizedByUserId", id.toString()));
            m.put("confirmedAt", movement.getConfirmedAt().toString());
            BigDecimal total=BigDecimal.ZERO; for(DetailCost cost:costs) total=total.add(cost.detail().getStockQuantity().multiply(cost.base()!=null?cost.base():cost.applied(), MATH), MATH);
            m.put("totalBaseCostPen", scale(total));
            ArrayNode details=payload.putArray("details");
            for(DetailCost cost:costs){ MovementDetail d=cost.detail(); ObjectNode n=details.addObject(); n.put("id",d.getId().toString()); n.put("lineNumber",d.getLineNumber()); n.put("productId",d.getProductId().toString()); n.put("enteredQuantity",d.getEnteredQuantity()); n.put("unitCode",d.getUnitCode()); n.put("conversionFactor",d.getConversionFactor()); n.put("stockQuantity",d.getStockQuantity()); putUuid(n,"originLocationId",d.getOriginLocationId()); putUuid(n,"destinationLocationId",d.getDestinationLocationId()); putUuid(n,"lotId",d.getLotId()); putUuid(n,"assetId",d.getAssetId()); if(cost.base()!=null)n.put("baseUnitCostPen",cost.base()); if(cost.applied()!=null)n.put("appliedAvgCostPen",cost.applied()); }
            ArrayNode stock=payload.putArray("stockBalances");
            balances.values().stream().sorted(Comparator.comparing(b -> b.id.toString())).forEach(b->{ ObjectNode n=stock.addObject(); n.put("stockBalanceId",b.id.toString()); n.put("productId",b.key.productId().toString()); n.put("locationId",b.key.locationId().toString()); putUuid(n,"lotId",b.key.lotId()); putUuid(n,"assetId",b.key.assetId()); n.put("quantity",scale(b.quantity)); n.put("avgCostPen",scale(b.average)); n.put("minStock",b.minStock); });
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot serialize MovementConfirmed", exception); }
    }

    private static void putUuid(ObjectNode node, String field, UUID value) { if(value!=null) node.put(field, value.toString()); }
    private static void putString(ObjectNode node, String field, String value) { if(value!=null) node.put(field, value); }
    private static BigDecimal scale(BigDecimal value) { return value.setScale(SCALE, RoundingMode.HALF_UP); }
    private static String sha256(String value) { try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder(); for(byte v:bytes)b.append(String.format("%02x",v)); return b.toString(); } catch(NoSuchAlgorithmException e){ throw new IllegalStateException(e); } }

    private record BalanceKey(UUID productId, UUID locationId, UUID lotId, UUID assetId) {
        static final Comparator<BalanceKey> ORDER=Comparator.comparing((BalanceKey k)->k.productId().toString()).thenComparing(k->k.locationId().toString()).thenComparing(k->k.lotId()==null?"":k.lotId().toString()).thenComparing(k->k.assetId()==null?"":k.assetId().toString());
        static BalanceKey origin(MovementDetail d){ return new BalanceKey(d.getProductId(), require(d.getOriginLocationId(),"origin"),d.getLotId(),d.getAssetId()); }
        static BalanceKey destination(MovementDetail d){ return new BalanceKey(d.getProductId(), require(d.getDestinationLocationId(),"destination"),d.getLotId(),d.getAssetId()); }
        static UUID require(UUID value,String label){if(value==null)throw new IllegalArgumentException(label+" location is required for confirmation");return value;}
    }
    private record Target(BalanceKey key, boolean createAllowed) { static Target origin(MovementDetail d,boolean create){return new Target(BalanceKey.origin(d),create);} static Target destination(MovementDetail d,boolean create){return new Target(BalanceKey.destination(d),create);} }
    private static final class Balance { final UUID id; final BalanceKey key; BigDecimal quantity; BigDecimal average; final BigDecimal minStock; Balance(UUID id,BalanceKey key,BigDecimal quantity,BigDecimal average,BigDecimal minStock){this.id=id;this.key=key;this.quantity=quantity;this.average=average;this.minStock=minStock;} void set(BigDecimal quantity,BigDecimal average){this.quantity=quantity;this.average=average;} }
    private record DetailCost(MovementDetail detail, BigDecimal base, BigDecimal applied) { }
    private record LockedLot(UUID id, UUID productId, String status, LocalDate expiresAt,
            long version, BigDecimal quantity) { }
    private record LockedAsset(UUID id, UUID productId, String status) { }
    private record AssetStock(UUID productId, UUID locationId, UUID lotId, BigDecimal quantity) { }
}
