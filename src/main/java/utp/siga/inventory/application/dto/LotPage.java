package utp.siga.inventory.application.dto;

import java.util.List;

public record LotPage(List<LotView> content, int page, int size, long totalElements) {
}
