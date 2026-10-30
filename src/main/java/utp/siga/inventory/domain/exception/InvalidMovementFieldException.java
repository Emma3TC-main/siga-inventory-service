package utp.siga.inventory.domain.exception;

public class InvalidMovementFieldException extends RuntimeException {
    public InvalidMovementFieldException(String message) {
        super(message);
    }
}
