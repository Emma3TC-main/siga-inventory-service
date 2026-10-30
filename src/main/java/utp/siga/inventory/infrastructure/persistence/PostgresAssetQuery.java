package utp.siga.inventory.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.inventory.application.dto.AssetAvailability;
import utp.siga.inventory.application.dto.AssetOperationalStatus;
import utp.siga.inventory.application.dto.AssetPage;
import utp.siga.inventory.application.dto.AssetView;
import utp.siga.inventory.application.port.out.AssetQueryPort;

@Repository
public class PostgresAssetQuery implements AssetQueryPort {

    private final JdbcTemplate jdbc;

    public PostgresAssetQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AssetPage findOperationalAssets(UUID productId, UUID locationId, UUID lotId,
            AssetAvailability availability, int page, int size) {
        StringBuilder where = new StringBuilder("""
                WHERE a.product_id = ?
                  AND a.operational_status = 'OPERATIVO'
                """);
        List<Object> args = new ArrayList<>(List.of(productId));

        if (availability == AssetAvailability.UNASSIGNED) {
            where.append(" AND sb.id IS NULL");
        } else {
            where.append(" AND sb.id IS NOT NULL AND sb.quantity = 1");
        }
        if (locationId != null) {
            where.append(" AND sb.location_id = ?");
            args.add(locationId);
        }
        if (lotId != null) {
            where.append(" AND sb.lot_id = ?");
            args.add(lotId);
        }

        String from = """
                FROM inventory.asset a
                LEFT JOIN inventory.stock_balance sb
                  ON sb.asset_id = a.id
                 AND sb.product_id = a.product_id
                 AND sb.quantity > 0
                """;
        Long total = jdbc.queryForObject("SELECT count(*) " + from + where, Long.class, args.toArray());

        List<Object> contentArgs = new ArrayList<>(args);
        contentArgs.add(size);
        contentArgs.add((long) page * size);
        List<AssetView> content = jdbc.query("""
                SELECT a.id, a.product_id, a.asset_code, a.serial_number,
                       a.operational_status, a.brand, a.model,
                       sb.location_id, sb.lot_id, (sb.id IS NOT NULL) AS available
                %s
                %s
                ORDER BY a.asset_code ASC, a.id ASC
                LIMIT ? OFFSET ?
                """.formatted(from, where), this::mapRow, contentArgs.toArray());

        return new AssetPage(content, page, size, total == null ? 0 : total);
    }

    private AssetView mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AssetView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("product_id", UUID.class),
                resultSet.getString("asset_code"),
                resultSet.getString("serial_number"),
                AssetOperationalStatus.valueOf(resultSet.getString("operational_status")),
                resultSet.getString("brand"),
                resultSet.getString("model"),
                resultSet.getObject("location_id", UUID.class),
                resultSet.getObject("lot_id", UUID.class),
                resultSet.getBoolean("available"));
    }
}
