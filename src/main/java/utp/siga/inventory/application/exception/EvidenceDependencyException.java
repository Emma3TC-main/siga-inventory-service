package utp.siga.inventory.application.exception;

public class EvidenceDependencyException extends RuntimeException {
    public EvidenceDependencyException(String message) {
        super(message);
    }

    public EvidenceDependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
