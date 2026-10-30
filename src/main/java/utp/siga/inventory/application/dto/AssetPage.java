package utp.siga.inventory.application.dto;

import java.util.List;

public record AssetPage(List<AssetView> content, int page, int size, long totalElements) {
}
