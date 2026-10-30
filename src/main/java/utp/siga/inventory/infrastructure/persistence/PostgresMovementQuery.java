package utp.siga.inventory.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.inventory.application.dto.MovementDetailResponse;
import utp.siga.inventory.application.dto.MovementPage;
import utp.siga.inventory.application.dto.MovementResponse;
import utp.siga.inventory.application.query.GetMovementQuery;

@Repository
public class PostgresMovementQuery implements GetMovementQuery {

    private final JdbcTemplate jdbc;

    public PostgresMovementQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MovementResponse> getMovement(UUID id) {
        try {
            MovementResponse movement = jdbc.queryForObject(
                "SELECT id, movement_code, movement_type, status, registered_by_user_id, requested_by_user_id, " +
                "cost_center_code, cost_center_name_snapshot, supplier_id, supplier_name_snapshot, supplier_tax_id_snapshot, " +
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
                    movement.costCenterNameSnapshot(), movement.supplierId(), movement.supplierNameSnapshot(),
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
    public MovementPage getMovements(int page, int size) {
        validatePage(page, size);
        Long count = jdbc.queryForObject("SELECT count(*) FROM inventory.movement", Long.class);
        long total = count != null ? count : 0;

        List<MovementResponse> movements = jdbc.query("""
                SELECT id, movement_code, movement_type, status, registered_by_user_id, requested_by_user_id,
                       cost_center_code, cost_center_name_snapshot, supplier_id, supplier_name_snapshot,
                       supplier_tax_id_snapshot, is_sensitive, sensitivity_reason, created_at, submitted_at,
                       confirmed_at
                FROM inventory.movement
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, this::mapMovement, size, (long) page * size);

        if (movements.isEmpty()) {
            return new MovementPage(List.of(), page, size, total);
        }

        List<UUID> ids = movements.stream().map(MovementResponse::id).toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<MovementDetailRow> detailRows = jdbc.query("""
                SELECT movement_id, id, line_number, product_id, entered_quantity, unit_code, conversion_factor,
                       stock_quantity, origin_location_id, destination_location_id, lot_id, asset_id,
                       original_currency, original_unit_cost, exchange_rate, exchange_rate_date,
                       exchange_rate_source, base_unit_cost_pen, applied_avg_cost_pen
                FROM inventory.movement_detail
                WHERE movement_id IN (%s)
                ORDER BY movement_id, line_number
                """.formatted(placeholders), (rs, row) ->
                        new MovementDetailRow(rs.getObject("movement_id", UUID.class), mapDetail(rs, row)),
                ids.toArray());

        Map<UUID, List<MovementDetailResponse>> detailsByMovement = new HashMap<>();
        for (MovementDetailRow row : detailRows) {
            detailsByMovement.computeIfAbsent(row.movementId(), ignored -> new ArrayList<>()).add(row.detail());
        }

        List<MovementResponse> content = movements.stream()
                .map(movement -> withDetails(movement,
                        detailsByMovement.getOrDefault(movement.id(), List.of())))
                .toList();
        return new MovementPage(content, page, size, total);
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be >= 0 and size must be between 1 and 100");
        }
    }

    private MovementResponse withDetails(MovementResponse movement, List<MovementDetailResponse> details) {
        return new MovementResponse(
                movement.id(), movement.movementCode(), movement.movementType(), movement.status(),
                movement.registeredByUserId(), movement.requestedByUserId(), movement.costCenterCode(),
                movement.costCenterNameSnapshot(), movement.supplierId(), movement.supplierNameSnapshot(),
                movement.supplierTaxIdSnapshot(), movement.sensitive(), movement.sensitivityReason(), details,
                movement.createdAt(), movement.submittedAt(), movement.confirmedAt());
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

    private record MovementDetailRow(UUID movementId, MovementDetailResponse detail) {}
}
