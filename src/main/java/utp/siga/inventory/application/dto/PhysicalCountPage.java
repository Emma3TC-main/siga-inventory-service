package utp.siga.inventory.application.dto;

import java.util.List;
import utp.siga.inventory.domain.model.PhysicalCount;

public record PhysicalCountPage(List<PhysicalCount> content, int page, int size, long totalElements) {
}
