package utp.siga.inventory.application.exception;

public class AssetNotFoundException extends RuntimeException {
    public AssetNotFoundException() { super("Asset was not found"); }
}
