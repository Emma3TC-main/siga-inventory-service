package utp.siga.inventory.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import utp.siga.inventory.application.port.out.SupplierRefPort;
import utp.siga.inventory.domain.model.SupplierRef;

@Repository
public class PostgresSupplierRefStore implements SupplierRefPort {
    private final JdbcTemplate jdbc;

    public PostgresSupplierRefStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveIfNewer(SupplierRef s) {
        jdbc.update("""
            INSERT INTO inventory.supplier_ref(supplier_id, code, tax_id, business_name, active, source_version, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (supplier_id) DO UPDATE SET
                code = EXCLUDED.code,
                tax_id = EXCLUDED.tax_id,
                business_name = EXCLUDED.business_name,
                active = EXCLUDED.active,
                source_version = EXCLUDED.source_version,
                updated_at = EXCLUDED.updated_at
            WHERE inventory.supplier_ref.source_version < EXCLUDED.source_version
            """, s.id(), s.code(), s.taxId(), s.businessName(), s.active(), s.sourceVersion(), java.sql.Timestamp.from(s.updatedAt()));
    }

    @Override
    public Optional<SupplierRef> getSupplier(UUID supplierId) {
        return jdbc.query("""
                SELECT supplier_id, code, tax_id, business_name, active, source_version, updated_at
                FROM inventory.supplier_ref
                WHERE supplier_id = ?
                """, (rs, row) -> new SupplierRef((UUID) rs.getObject("supplier_id"), rs.getString("code"),
                rs.getString("tax_id"), rs.getString("business_name"), rs.getBoolean("active"),
                rs.getLong("source_version"), rs.getTimestamp("updated_at").toInstant()), supplierId).stream().findFirst();
    }
}
