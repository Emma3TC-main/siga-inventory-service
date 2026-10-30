package utp.siga.inventory.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.inventory.application.dto.InventoryPage;
import utp.siga.inventory.application.dto.StockItem;
import utp.siga.inventory.application.query.GetStockQuery;

@Repository
public class PostgresStockQuery implements GetStockQuery {

    private final JdbcTemplate jdbc;

    public PostgresStockQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public InventoryPage getStock(UUID productId, UUID locationId, UUID lotId, UUID assetId, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();

        if (productId != null) {
            where.append(" AND product_id = ?");
            args.add(productId);
        }
        if (locationId != null) {
            where.append(" AND location_id = ?");
            args.add(locationId);
        }
        if (lotId != null) {
            where.append(" AND lot_id = ?");
            args.add(lotId);
        }
        if (assetId != null) {
            where.append(" AND asset_id = ?");
            args.add(assetId);
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM inventory.stock_balance" + where, Long.class, args.toArray());

        args.add(size);
        args.add(page * size);

        List<StockItem> content = jdbc.query(
            "SELECT id, product_id, location_id, lot_id, asset_id, quantity, avg_cost_pen " +
            "FROM inventory.stock_balance" + where + " ORDER BY id LIMIT ? OFFSET ?",
            this::mapRow,
            args.toArray()
        );

        return new InventoryPage(content, page, size, total != null ? total : 0);
    }

    private StockItem mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new StockItem(
            rs.getObject("id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getObject("location_id", UUID.class),
            rs.getObject("lot_id", UUID.class),
            rs.getObject("asset_id", UUID.class),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("avg_cost_pen")
        );
    }
}
