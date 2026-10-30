package utp.siga.inventory.application.dto;

import java.util.List;

public record InventoryPage(
    List<StockItem> content,
    int page,
    int size,
    long totalElements
) {}
