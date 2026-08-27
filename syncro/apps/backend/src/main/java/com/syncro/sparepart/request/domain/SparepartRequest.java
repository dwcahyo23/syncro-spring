package com.syncro.sparepart.request.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sparepart request aggregate (FR-140/FR-143/FR-144, story 12-1). material_code is a
 * denormalized snapshot for storekeeper display; the authoritative code lives on
 * spareparts.material_code. est_price_id references a sparepart_price_entries row OR
 * est_unit_price carries a free-form unit price — both nullable so an unpriced request
 * can still be created (12-3 computes approval thresholds from these).
 */
public record SparepartRequest(
    UUID id,
    SparepartRequestType requestType,
    String workOrderId,
    UUID machineId,
    UUID sparepartId,
    String materialCode,
    short quantity,
    UUID estPriceId,
    BigDecimal estUnitPrice,
    String purchaseReferenceUrl,
    SparepartRequestStatus status,
    UUID requestedBy,
    Instant requestedAt,
    String notes,
    Instant createdAt,
    Instant updatedAt) {
}
