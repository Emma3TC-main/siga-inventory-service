package utp.siga.inventory.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.inventory.application.port.out.ProductRefPort;
import utp.siga.inventory.domain.model.ProductRef;
import utp.siga.inventory.domain.model.UnitConversionRef;

@Repository
public class PostgresProductRefStore implements ProductRefPort {
    private final JdbcTemplate jdbc;

    public PostgresProductRefStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveIfNewer(ProductRef p) {
        int rows = jdbc.update("""
            INSERT INTO inventory.product_ref(product_id, sku, product_type, storage_unit_code, base_unit_code, min_stock,
                requires_lot, requires_heat_number, requires_serial, requires_expiry, active, source_version, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (product_id) DO UPDATE SET
                sku = EXCLUDED.sku,
                product_type = EXCLUDED.product_type,
                storage_unit_code = EXCLUDED.storage_unit_code,
                base_unit_code = EXCLUDED.base_unit_code,
                min_stock = EXCLUDED.min_stock,
                requires_lot = EXCLUDED.requires_lot,
                requires_heat_number = EXCLUDED.requires_heat_number,
                requires_serial = EXCLUDED.requires_serial,
                requires_expiry = EXCLUDED.requires_expiry,
                active = EXCLUDED.active,
                source_version = EXCLUDED.source_version,
                updated_at = EXCLUDED.updated_at
            WHERE inventory.product_ref.source_version < EXCLUDED.source_version
            """, p.id(), p.sku(), p.productType(), p.storageUnitCode(), p.baseUnitCode(), p.minStock(),
            p.requiresLot(), p.requiresHeatNumber(), p.requiresSerial(), p.requiresExpiry(), p.active(), p.sourceVersion(), java.sql.Timestamp.from(p.updatedAt()));

        if (rows > 0 && p.conversions() != null && !p.conversions().isEmpty()) {
            List<Object> deleteArguments = new ArrayList<>();
            deleteArguments.add(p.id());
            StringBuilder retainedPairs = new StringBuilder();
            for (int index = 0; index < p.conversions().size(); index++) {
                if (index > 0) retainedPairs.append(" OR ");
                retainedPairs.append("(from_unit_code=? AND to_unit_code=?)");
                UnitConversionRef conversion = p.conversions().get(index);
                deleteArguments.add(conversion.fromUnitCode());
                deleteArguments.add(conversion.toUnitCode());
            }
            jdbc.update("DELETE FROM inventory.product_unit_conversion_ref WHERE product_id=? AND NOT (" + retainedPairs + ")",
                    deleteArguments.toArray());

            for (UnitConversionRef c : p.conversions()) {
                jdbc.update("""
                    INSERT INTO inventory.product_unit_conversion_ref(product_id, from_unit_code, to_unit_code, factor, active, source_version, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (product_id, from_unit_code, to_unit_code) DO UPDATE SET
                        factor = EXCLUDED.factor,
                        active = EXCLUDED.active,
                        source_version = EXCLUDED.source_version,
                        updated_at = EXCLUDED.updated_at
                    WHERE inventory.product_unit_conversion_ref.source_version < EXCLUDED.source_version
                    """, p.id(), c.fromUnitCode(), c.toUnitCode(), c.factor(), c.active(), p.sourceVersion(), java.sql.Timestamp.from(p.updatedAt()));
            }
        } else if (rows > 0) {
            jdbc.update("DELETE FROM inventory.product_unit_conversion_ref WHERE product_id = ?", p.id());
        }
    }

    @Override
    public Optional<ProductRef> getProduct(UUID productId) {
        List<ProductRef> products = jdbc.query("""
                SELECT product_id, sku, product_type, storage_unit_code, base_unit_code, min_stock,
                    requires_lot, requires_heat_number, requires_serial, requires_expiry, active,
                    source_version, updated_at
                FROM inventory.product_ref
                WHERE product_id = ?
                """, (rs, row) -> new ProductRef(
                (UUID) rs.getObject("product_id"), rs.getString("sku"), rs.getString("product_type"),
                rs.getString("storage_unit_code"), rs.getString("base_unit_code"), rs.getBigDecimal("min_stock"),
                rs.getBoolean("requires_lot"), rs.getBoolean("requires_heat_number"),
                rs.getBoolean("requires_serial"), rs.getBoolean("requires_expiry"), rs.getBoolean("active"),
                rs.getLong("source_version"), rs.getTimestamp("updated_at").toInstant(), List.of()), productId);
        if (products.isEmpty()) return Optional.empty();

        ProductRef product = products.getFirst();
        List<UnitConversionRef> conversions = jdbc.query("""
                SELECT from_unit_code, to_unit_code, factor, active
                FROM inventory.product_unit_conversion_ref
                WHERE product_id = ?
                ORDER BY from_unit_code, to_unit_code
                """, (rs, row) -> new UnitConversionRef(rs.getString("from_unit_code"),
                rs.getString("to_unit_code"), rs.getBigDecimal("factor"), rs.getBoolean("active")), productId);
        return Optional.of(new ProductRef(product.id(), product.sku(), product.productType(),
                product.storageUnitCode(), product.baseUnitCode(), product.minStock(), product.requiresLot(),
                product.requiresHeatNumber(), product.requiresSerial(), product.requiresExpiry(), product.active(),
                product.sourceVersion(), product.updatedAt(), List.copyOf(conversions)));
    }
}
