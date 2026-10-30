package utp.siga.inventory.presentation.error;

public class MovementNotFoundException extends RuntimeException {
    public MovementNotFoundException() {
        super("Movement not found");
    }
}
