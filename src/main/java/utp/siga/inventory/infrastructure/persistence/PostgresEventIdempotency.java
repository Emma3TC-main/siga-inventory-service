package utp.siga.inventory.infrastructure.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.inventory.application.port.out.EventIdempotencyPort;

@Repository
public class PostgresEventIdempotency implements EventIdempotencyPort {
    private final JdbcTemplate jdbc;

    public PostgresEventIdempotency(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(UUID eventId, String consumer) {
        return jdbc.update("INSERT INTO inventory.processed_event(event_id, consumer) VALUES (?, ?) ON CONFLICT DO NOTHING", eventId, consumer) == 1;
    }
}
