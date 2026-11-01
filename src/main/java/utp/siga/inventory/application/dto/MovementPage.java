package utp.siga.inventory.application.dto;

import java.util.List;

public record MovementPage(
        List<MovementHistoryItem> content,
        int page,
        int size,
        long totalElements
) {}
