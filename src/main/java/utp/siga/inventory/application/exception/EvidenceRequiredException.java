package utp.siga.inventory.application.exception;

public class EvidenceRequiredException extends RuntimeException {
    public EvidenceRequiredException() {
        super("El ajuste requiere al menos una evidencia disponible.");
    }
}
