package utp.siga.inventory.domain.model;

import java.time.Instant;
import java.util.UUID;

public record SupplierRef(
    UUID id,
    String code,
    String taxId,
    String businessName,
    boolean active,
    long sourceVersion,
    Instant updatedAt
) {}
