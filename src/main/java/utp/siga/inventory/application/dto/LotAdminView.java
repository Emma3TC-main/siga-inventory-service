package utp.siga.inventory.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LotAdminView(
        UUID id,
        UUID productId,
        String lotNumber,
        String heatNumber,
        LocalDate manufacturedAt,
        LocalDate receivedAt,
        LocalDate expiresAt,
        LotStatus status,
        boolean expired,
        BigDecimal totalQuantity,
        List<LotLocationQuantity> locations,
        long version,
        Instant createdAt,
        Instant updatedAt) { }
