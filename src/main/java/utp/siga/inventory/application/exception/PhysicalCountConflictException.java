package utp.siga.inventory.application.exception;

public class PhysicalCountConflictException extends RuntimeException {
    private final String code;

    public PhysicalCountConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
