package utp.siga.inventory.application.exception;

public class LocationNotFoundException extends RuntimeException {
    public LocationNotFoundException() {
        super("Location not found");
    }
}
