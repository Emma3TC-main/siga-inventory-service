package utp.siga.inventory.application.port.out;
import java.util.UUID;
public interface EventIdempotencyPort {
    boolean claim(UUID eventId, String consumer);
}
