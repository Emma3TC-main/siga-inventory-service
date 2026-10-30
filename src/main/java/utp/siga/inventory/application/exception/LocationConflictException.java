package utp.siga.inventory.application.exception;

public class LocationConflictException extends RuntimeException {
    private final String code;

    public LocationConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
