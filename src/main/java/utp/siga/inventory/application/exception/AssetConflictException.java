package utp.siga.inventory.application.exception;

public class AssetConflictException extends RuntimeException {
    private final String code;

    public AssetConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
