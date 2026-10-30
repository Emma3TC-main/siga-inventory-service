package utp.siga.inventory.application.dto;

import java.util.List;

public record AssetAdminPage(List<AssetAdminView> content, int page, int size, long totalElements) { }
