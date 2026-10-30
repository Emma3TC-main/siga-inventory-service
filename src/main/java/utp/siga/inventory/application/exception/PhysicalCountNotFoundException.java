package utp.siga.inventory.application.exception;

public class PhysicalCountNotFoundException extends RuntimeException {
    private final String code;

    public PhysicalCountNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
