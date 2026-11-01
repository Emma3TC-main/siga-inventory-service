package utp.siga.inventory.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.inventory.application.dto.MovementDetailResponse;
import utp.siga.inventory.application.dto.MovementHistoryItem;
import utp.siga.inventory.application.dto.MovementPage;
import utp.siga.inventory.application.dto.MovementResponse;
import utp.siga.inventory.application.query.GetMovementQuery;
import utp.siga.inventory.application.query.MovementHistoryFilter;

@Repository
public class PostgresMovementQuery implements GetMovementQuery {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public PostgresMovementQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Optional<MovementResponse> getMovement(UUID id) {
        try {
            MovementResponse movement = jdbc.queryForObject(
                "SELECT id, movement_code, movement_type, status, registered_by_user_id, requested_by_user_id, " +
                "cost_center_code, cost_center_name_snapshot, reference_doc_type, reference_doc_number, reason, " +
                "supplier_id, supplier_name_snapshot, supplier_tax_id_snapshot, " +
                "is_sensitive, sensitivity_reason, created_at, submitted_at, confirmed_at " +
                "FROM inventory.movement WHERE id = ?",
                this::mapMovement,
                id
            );

            if (movement != null) {
                List<MovementDetailResponse> details = jdbc.query(
                    "SELECT id, line_number, product_id, entered_quantity, unit_code, conversion_factor, " +
                    "stock_quantity, origin_location_id, destination_location_id, lot_id, asset_id, " +
                    "original_currency, original_unit_cost, exchange_rate, exchange_rate_date, exchange_rate_source, " +
                    "base_unit_cost_pen, applied_avg_cost_pen " +
                    "FROM inventory.movement_detail WHERE movement_id = ? ORDER BY line_number",
                    this::mapDetail,
                    id
                );

                return Optional.of(new MovementResponse(
                    movement.id(), movement.movementCode(), movement.movementType(), movement.status(),
                    movement.registeredByUserId(), movement.requestedByUserId(), movement.costCenterCode(),
                    movement.costCenterNameSnapshot(), movement.referenceDocType(), movement.referenceDocNumber(),
                    movement.reason(), movement.supplierId(), movement.supplierNameSnapshot(),
                    movement.supplierTaxIdSnapshot(), movement.sensitive(), movement.sensitivityReason(),
                    details, movement.createdAt(), movement.submittedAt(), movement.confirmedAt()
                ));
            }
            return Optional.empty();
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public MovementPage getMovements(MovementHistoryFilter filter) {
        QueryParts query = buildQuery(filter);
        Long count = namedJdbc.queryForObject("SELECT count(*) FROM inventory.movement m" + query.where(),
                query.parameters(), Long.class);
        long total = count == null ? 0 : count;

        query.parameters().addValue("limit", filter.size())
                .addValue("offset", (long) filter.page() * filter.size());
        List<MovementHistoryItem> content = namedJdbc.query("""
                SELECT m.id, m.movement_code, m.movement_type, m.status, m.is_sensitive,
                       m.registered_by_user_id, m.requested_by_user_id, m.cost_center_code,
                       m.cost_center_name_snapshot, m.reference_doc_type, m.reference_doc_number,
                       m.reason, m.created_at, m.submitted_at, m.confirmed_at
                FROM inventory.movement m
                """ + query.where() + " ORDER BY m.created_at DESC, m.id DESC LIMIT :limit OFFSET :offset",
                query.parameters(), this::mapHistoryItem);
        return new MovementPage(content, filter.page(), filter.size(), total);
    }

    private QueryParts buildQuery(MovementHistoryFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        append(where, parameters, "movementCode", filter.movementCode(), "m.movement_code = :movementCode");
        append(where, parameters, "movementType",
                filter.movementType() == null ? null : filter.movementType().name(),
                "m.movement_type = :movementType");
        append(where, parameters, "status", filter.status() == null ? null : filter.status().name(),
                "m.status = :status");
        append(where, parameters, "registeredByUserId", filter.registeredByUserId(),
                "m.registered_by_user_id = :registeredByUserId");
        append(where, parameters, "requestedByUserId", filter.requestedByUserId(),
                "m.requested_by_user_id = :requestedByUserId");
        append(where, parameters, "from", filter.from() == null ? null : Timestamp.from(filter.from()),
                "m.created_at >= :from");
        append(where, parameters, "to", filter.to() == null ? null : Timestamp.from(filter.to()),
                "m.created_at < :to");
        append(where, parameters, "sensitive", filter.sensitive(), "m.is_sensitive = :sensitive");
        append(where, parameters, "referenceDocNumber", filter.referenceDocNumber(),
                "m.reference_doc_number = :referenceDocNumber");

        if (filter.hasDetailFilters()) {
            where.append(" AND EXISTS (SELECT 1 FROM inventory.movement_detail d WHERE d.movement_id = m.id");
            append(where, parameters, "productId", filter.productId(), "d.product_id = :productId");
            append(where, parameters, "lotId", filter.lotId(), "d.lot_id = :lotId");
            append(where, parameters, "assetId", filter.assetId(), "d.asset_id = :assetId");
            append(where, parameters, "sourceLocationId", filter.sourceLocationId(),
                    "d.origin_location_id = :sourceLocationId");
            append(where, parameters, "destinationLocationId", filter.destinationLocationId(),
                    "d.destination_location_id = :destinationLocationId");
            if (filter.locationId() != null) {
                where.append(" AND (d.origin_location_id = :locationId OR d.destination_location_id = :locationId)");
                parameters.addValue("locationId", filter.locationId());
            }
            where.append(')');
        }
        return new QueryParts(where.toString(), parameters);
    }

    private void append(StringBuilder where, MapSqlParameterSource parameters, String name, Object value,
            String predicate) {
        if (value != null) {
            where.append(" AND ").append(predicate);
            parameters.addValue(name, value);
        }
    }

    private MovementResponse mapMovement(ResultSet rs, int rowNum) throws SQLException {
        return new MovementResponse(
            rs.getObject("id", UUID.class),
            rs.getString("movement_code"),
            rs.getString("movement_type"),
            rs.getString("status"),
            rs.getObject("registered_by_user_id", UUID.class),
            rs.getObject("requested_by_user_id", UUID.class),
            rs.getString("cost_center_code"),
            rs.getString("cost_center_name_snapshot"),
            rs.getString("reference_doc_type"),
            rs.getString("reference_doc_number"),
            rs.getString("reason"),
            rs.getObject("supplier_id", UUID.class),
            rs.getString("supplier_name_snapshot"),
            rs.getString("supplier_tax_id_snapshot"),
            rs.getBoolean("is_sensitive"),
            rs.getString("sensitivity_reason"),
            null, // Details loaded separately
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("submitted_at")),
            toInstant(rs.getTimestamp("confirmed_at"))
        );
    }

    private MovementHistoryItem mapHistoryItem(ResultSet rs, int rowNum) throws SQLException {
        return new MovementHistoryItem(
                rs.getObject("id", UUID.class), rs.getString("movement_code"),
                rs.getString("movement_type"), rs.getString("status"), rs.getBoolean("is_sensitive"),
                rs.getObject("registered_by_user_id", UUID.class),
                rs.getObject("requested_by_user_id", UUID.class), rs.getString("cost_center_code"),
                rs.getString("cost_center_name_snapshot"), rs.getString("reference_doc_type"),
                rs.getString("reference_doc_number"), rs.getString("reason"),
                toInstant(rs.getTimestamp("created_at")), toInstant(rs.getTimestamp("submitted_at")),
                toInstant(rs.getTimestamp("confirmed_at")));
    }

    private MovementDetailResponse mapDetail(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date exchangeRateDate = rs.getDate("exchange_rate_date");
        return new MovementDetailResponse(
            rs.getObject("id", UUID.class),
            rs.getInt("line_number"),
            rs.getObject("product_id", UUID.class),
            rs.getBigDecimal("entered_quantity"),
            rs.getString("unit_code"),
            rs.getBigDecimal("conversion_factor"),
            rs.getBigDecimal("stock_quantity"),
            rs.getObject("origin_location_id", UUID.class),
            rs.getObject("destination_location_id", UUID.class),
            rs.getObject("lot_id", UUID.class),
            rs.getObject("asset_id", UUID.class),
            rs.getString("original_currency"),
            rs.getBigDecimal("original_unit_cost"),
            rs.getBigDecimal("exchange_rate"),
            exchangeRateDate != null ? exchangeRateDate.toLocalDate() : null,
            rs.getString("exchange_rate_source"),
            rs.getBigDecimal("base_unit_cost_pen"),
            rs.getBigDecimal("applied_avg_cost_pen")
        );
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private record QueryParts(String where, MapSqlParameterSource parameters) {}
}
