package utp.siga.inventory.application.exception;

public class PhysicalCountValidationException extends RuntimeException {
    private final String code;

    public PhysicalCountValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
