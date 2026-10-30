package utp.siga.inventory.application.exception;

public class LotConflictException extends RuntimeException {
    private final String code;

    public LotConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
