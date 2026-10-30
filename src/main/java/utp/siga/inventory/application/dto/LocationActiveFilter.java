package utp.siga.inventory.application.dto;

public enum LocationActiveFilter {
    TRUE,
    FALSE,
    ALL;

    public static LocationActiveFilter parse(String value) {
        if (value == null || value.isBlank() || "true".equals(value)) return TRUE;
        if ("false".equals(value)) return FALSE;
        if ("all".equals(value)) return ALL;
        throw new IllegalArgumentException("active must be true, false or all");
    }
}
