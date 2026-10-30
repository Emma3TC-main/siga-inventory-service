package utp.siga.inventory.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.inventory.application.dto.LotPage;
import utp.siga.inventory.application.dto.LotStatus;
import utp.siga.inventory.application.dto.LotView;
import utp.siga.inventory.application.port.out.LotQueryPort;

@Repository
public class PostgresLotQuery implements LotQueryPort {

    private final JdbcTemplate jdbc;

    public PostgresLotQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LotPage findOperationalLots(UUID productId, UUID locationId, boolean availableOnly,
            LocalDate currentDate, int page, int size) {
        String stockLocation = locationId == null ? "" : " AND sb.location_id = ?";
        String availableQuantity = """
                (SELECT COALESCE(SUM(sb.quantity), 0)
                   FROM inventory.stock_balance sb
                  WHERE sb.product_id = l.product_id
                    AND sb.lot_id = l.id%s)
                """.formatted(stockLocation);

        StringBuilder where = new StringBuilder("""
                WHERE l.product_id = ?
                  AND l.status = 'ACTIVE'
                  AND (l.expires_at IS NULL OR l.expires_at >= ?)
                """);
        List<Object> whereArgs = new ArrayList<>(List.of(productId, currentDate));
        if (availableOnly) {
            where.append("""
                    AND EXISTS (
                        SELECT 1 FROM inventory.stock_balance sb
                         WHERE sb.product_id = l.product_id
                           AND sb.lot_id = l.id
                           AND sb.quantity > 0%s)
                    """.formatted(stockLocation));
            if (locationId != null) whereArgs.add(locationId);
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM inventory.lot l " + where,
                Long.class, whereArgs.toArray());

        List<Object> contentArgs = new ArrayList<>();
        if (locationId != null) contentArgs.add(locationId);
        contentArgs.addAll(whereArgs);
        contentArgs.add(size);
        contentArgs.add((long) page * size);

        List<LotView> content = jdbc.query("""
                SELECT l.id, l.product_id, l.lot_number, l.heat_number,
                       l.manufactured_at, l.received_at, l.expires_at, l.status,
                       %s AS available_quantity
                  FROM inventory.lot l
                %s
                 ORDER BY l.lot_number ASC NULLS LAST,
                          l.heat_number ASC NULLS LAST,
                          l.id ASC
                 LIMIT ? OFFSET ?
                """.formatted(availableQuantity, where),
                (resultSet, rowNumber) -> mapRow(resultSet, locationId), contentArgs.toArray());

        return new LotPage(content, page, size, total == null ? 0 : total);
    }

    private LotView mapRow(ResultSet resultSet, UUID locationId) throws SQLException {
        return new LotView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("product_id", UUID.class),
                resultSet.getString("lot_number"),
                resultSet.getString("heat_number"),
                resultSet.getObject("manufactured_at", LocalDate.class),
                resultSet.getObject("received_at", LocalDate.class),
                resultSet.getObject("expires_at", LocalDate.class),
                LotStatus.valueOf(resultSet.getString("status")),
                locationId,
                resultSet.getBigDecimal("available_quantity"));
    }
}
