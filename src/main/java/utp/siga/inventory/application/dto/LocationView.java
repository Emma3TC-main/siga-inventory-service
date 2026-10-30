package utp.siga.inventory.application.dto;

import java.util.UUID;

public record LocationView(
        UUID id,
        String code,
        String name,
        LocationType locationType,
        UUID parentId,
        boolean active,
        long version) {
}
