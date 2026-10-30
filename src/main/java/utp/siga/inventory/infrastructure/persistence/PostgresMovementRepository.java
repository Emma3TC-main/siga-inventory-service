package utp.siga.inventory.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.inventory.application.port.out.MovementRepository;
import utp.siga.inventory.domain.model.Movement;
import utp.siga.inventory.domain.model.MovementAuthorization;
import utp.siga.inventory.domain.model.MovementDetail;
import utp.siga.inventory.domain.model.MovementStatus;
import utp.siga.inventory.domain.model.MovementType;

@Repository
public class PostgresMovementRepository implements MovementRepository {

    private final JdbcTemplate jdbc;

    public PostgresMovementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String nextMovementCode(Instant createdAt) {
        long sequence = jdbc.queryForObject("SELECT nextval('inventory.movement_code_sequence')", Long.class);
        int year = createdAt.atZone(ZoneOffset.UTC).getYear();
        return String.format(Locale.ROOT, "MOV-%04d-%05d", year, sequence);
    }

    @Override
    @Transactional
    public void save(Movement m) {
        int movementRows = jdbc.update("""
            INSERT INTO inventory.movement (
                id, movement_code, movement_type, status, registered_by_user_id, requested_by_user_id,
                cost_center_code, cost_center_name_snapshot, supplier_id, supplier_name_snapshot, supplier_tax_id_snapshot,
                reference_doc_type, reference_doc_number, reason, notes, is_sensitive, sensitivity_reason,
                created_at, submitted_at, confirmed_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                is_sensitive = EXCLUDED.is_sensitive,
                sensitivity_reason = EXCLUDED.sensitivity_reason,
                submitted_at = EXCLUDED.submitted_at,
                confirmed_at = EXCLUDED.confirmed_at,
                updated_at = now()
            WHERE (inventory.movement.status = 'DRAFT'
                    AND EXCLUDED.status IN ('PENDING_AUTHORIZATION', 'CANCELLED', 'CONFIRMED'))
               OR (inventory.movement.status = 'PENDING_AUTHORIZATION'
                    AND EXCLUDED.status IN ('AUTHORIZED', 'REJECTED', 'CANCELLED'))
               OR (inventory.movement.status = 'AUTHORIZED'
                    AND EXCLUDED.status IN ('CANCELLED', 'CONFIRMED'))
            """,
            m.getId(), m.getMovementCode(), m.getType().name(), m.getStatus().name(), m.getRegisteredByUserId(), m.getRequestedByUserId(),
            m.getCostCenterCode(), m.getCostCenterNameSnapshot(), m.getSupplierId(), m.getSupplierNameSnapshot(), m.getSupplierTaxIdSnapshot(),
            m.getReferenceDocType(), m.getReferenceDocNumber(), m.getReason(), m.getNotes(), m.isSensitive(), m.getSensitivityReason(),
            m.getCreatedAt() != null ? Timestamp.from(m.getCreatedAt()) : null,
            m.getSubmittedAt() != null ? Timestamp.from(m.getSubmittedAt()) : null,
            m.getConfirmedAt() != null ? Timestamp.from(m.getConfirmedAt()) : null
        );
        if (movementRows != 1) {
            throw new IllegalStateException("Movement was changed or is not in a valid state for this transition");
        }

        // Movement details are snapshots: a later aggregate save must never rewrite them.
        for (MovementDetail d : m.getDetails()) {
            jdbc.update("""
                INSERT INTO inventory.movement_detail (
                    id, movement_id, line_number, product_id, entered_quantity, unit_code,
                    conversion_factor, stock_quantity, origin_location_id, destination_location_id,
                    lot_id, asset_id, original_currency, original_unit_cost, exchange_rate,
                    exchange_rate_date, exchange_rate_source
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                d.getId(), m.getId(), d.getLineNumber(), d.getProductId(), d.getEnteredQuantity(), d.getUnitCode(),
                d.getConversionFactor(), d.getStockQuantity(), d.getOriginLocationId(), d.getDestinationLocationId(),
                d.getLotId(), d.getAssetId(), d.getOriginalCurrency() != null ? d.getOriginalCurrency() : "PEN",
                d.getOriginalUnitCost(), d.getExchangeRate(),
                d.getExchangeRateDate() != null ? java.sql.Date.valueOf(d.getExchangeRateDate()) : null,
                d.getExchangeRateSource()
            );
        }

        // An authorization can be completed once, but a decided row is historical and never overwritten.
        for (MovementAuthorization a : m.getAuthorizations()) {
            jdbc.update("""
                INSERT INTO inventory.movement_authorization (
                    id, movement_id, requested_at, decided_at, authorized_by_user_id,
                    decision, step_up_verified, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    decided_at = EXCLUDED.decided_at,
                    authorized_by_user_id = EXCLUDED.authorized_by_user_id,
                    decision = EXCLUDED.decision,
                    step_up_verified = EXCLUDED.step_up_verified,
                    reason = EXCLUDED.reason
                WHERE inventory.movement_authorization.decision IS NULL
                    AND EXCLUDED.decision IS NOT NULL
                """,
                a.getId(), m.getId(),
                a.getRequestedAt() != null ? Timestamp.from(a.getRequestedAt()) : null,
                a.getDecidedAt() != null ? Timestamp.from(a.getDecidedAt()) : null,
                a.getAuthorizedByUserId(), a.getDecision(), a.isStepUpVerified(), a.getReason()
            );
        }
    }

    @Override
    public Optional<Movement> findById(UUID id) {
        List<Movement.Builder> builders = jdbc.query("SELECT * FROM inventory.movement WHERE id = ?", new MovementRowMapper(), id);
        if (builders.isEmpty()) {
            return Optional.empty();
        }
        Movement.Builder builder = builders.get(0);

        List<MovementDetail> details = jdbc.query("SELECT * FROM inventory.movement_detail WHERE movement_id = ? ORDER BY line_number", new MovementDetailRowMapper(), id);
        List<MovementAuthorization> authorizations = jdbc.query("SELECT * FROM inventory.movement_authorization WHERE movement_id = ? ORDER BY requested_at", new MovementAuthorizationRowMapper(), id);

        for (MovementDetail d : details) {
            builder.addDetail(d);
        }
        for (MovementAuthorization a : authorizations) {
            builder.addAuthorization(a);
        }

        return Optional.of(builder.build());
    }

    private static class MovementRowMapper implements RowMapper<Movement.Builder> {
        @Override
        public Movement.Builder mapRow(ResultSet rs, int rowNum) throws SQLException {
            Movement.Builder b = new Movement.Builder()
                .id((UUID) rs.getObject("id"))
                .movementCode(rs.getString("movement_code"))
                .type(MovementType.valueOf(rs.getString("movement_type")))
                .status(MovementStatus.valueOf(rs.getString("status")))
                .registeredByUserId((UUID) rs.getObject("registered_by_user_id"))
                .requestedByUserId((UUID) rs.getObject("requested_by_user_id"))
                .costCenterCode(rs.getString("cost_center_code"))
                .costCenterNameSnapshot(rs.getString("cost_center_name_snapshot"))
                .supplierId((UUID) rs.getObject("supplier_id"))
                .supplierNameSnapshot(rs.getString("supplier_name_snapshot"))
                .supplierTaxIdSnapshot(rs.getString("supplier_tax_id_snapshot"))
                .referenceDocType(rs.getString("reference_doc_type"))
                .referenceDocNumber(rs.getString("reference_doc_number"))
                .reason(rs.getString("reason"))
                .notes(rs.getString("notes"))
                .isSensitive(rs.getBoolean("is_sensitive"))
                .sensitivityReason(rs.getString("sensitivity_reason"));

            Timestamp created = rs.getTimestamp("created_at");
            if (created != null) b.createdAt(created.toInstant());

            Timestamp submitted = rs.getTimestamp("submitted_at");
            if (submitted != null) b.submittedAt(submitted.toInstant());

            Timestamp confirmed = rs.getTimestamp("confirmed_at");
            if (confirmed != null) b.confirmedAt(confirmed.toInstant());

            return b;
        }
    }

    private static class MovementDetailRowMapper implements RowMapper<MovementDetail> {
        @Override
        public MovementDetail mapRow(ResultSet rs, int rowNum) throws SQLException {
            java.sql.Date exchangeDate = rs.getDate("exchange_rate_date");
            return new MovementDetail(
                (UUID) rs.getObject("id"),
                rs.getInt("line_number"),
                (UUID) rs.getObject("product_id"),
                rs.getBigDecimal("entered_quantity"),
                rs.getString("unit_code"),
                rs.getBigDecimal("conversion_factor"),
                (UUID) rs.getObject("origin_location_id"),
                (UUID) rs.getObject("destination_location_id"),
                (UUID) rs.getObject("lot_id"),
                (UUID) rs.getObject("asset_id"),
                rs.getString("original_currency"),
                rs.getBigDecimal("original_unit_cost"),
                rs.getBigDecimal("exchange_rate"),
                exchangeDate != null ? exchangeDate.toLocalDate() : null,
                rs.getString("exchange_rate_source")
            );
        }
    }

    private static class MovementAuthorizationRowMapper implements RowMapper<MovementAuthorization> {
        @Override
        public MovementAuthorization mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp req = rs.getTimestamp("requested_at");
            Timestamp decided = rs.getTimestamp("decided_at");
            return MovementAuthorization.rehydrate(
                    (UUID) rs.getObject("id"), req != null ? req.toInstant() : null,
                    decided != null ? decided.toInstant() : null,
                    (UUID) rs.getObject("authorized_by_user_id"), rs.getString("decision"),
                    rs.getBoolean("step_up_verified"), rs.getString("reason"));
        }
    }
}
