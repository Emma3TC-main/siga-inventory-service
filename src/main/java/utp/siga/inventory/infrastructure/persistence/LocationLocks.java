package utp.siga.inventory.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

/** Serializes location hierarchy/lifecycle decisions with operations that require an active location. */
public final class LocationLocks {
    public static final long HIERARCHY_ADVISORY_LOCK_KEY = 4_752_601_001L;

    private LocationLocks() { }

    public static void lockHierarchy(JdbcTemplate jdbc) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", result -> null, HIERARCHY_ADVISORY_LOCK_KEY);
    }
}
