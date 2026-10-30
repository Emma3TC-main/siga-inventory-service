package utp.siga.inventory.application.exception;

public class LotNotFoundException extends RuntimeException {
    public LotNotFoundException() { super("Lot not found"); }
}
