package utp.siga.inventory.application.dto;

import java.util.List;

public record MovementPage(
        List<MovementResponse> content,
        int page,
        int size,
        long totalElements
) {}
