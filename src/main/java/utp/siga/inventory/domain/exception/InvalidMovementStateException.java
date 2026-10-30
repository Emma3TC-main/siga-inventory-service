package utp.siga.inventory.domain.exception;

public class InvalidMovementStateException extends RuntimeException {
    public InvalidMovementStateException(String message) {
        super(message);
    }
}
