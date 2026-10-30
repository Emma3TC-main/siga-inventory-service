package utp.siga.inventory.application.dto;

import java.util.List;

public record LotAdminPage(List<LotAdminView> content, int page, int size,
        long totalElements, int totalPages) { }
