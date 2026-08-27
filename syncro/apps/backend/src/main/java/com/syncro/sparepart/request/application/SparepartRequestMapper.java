package com.syncro.sparepart.request.application;

import com.syncro.sparepart.request.domain.SparepartRequest;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestEntity;

/** Maps persisted sparepart-request entities to their domain values. */
public final class SparepartRequestMapper {
  private SparepartRequestMapper() {
  }

  public static SparepartRequest toDomain(SparepartRequestEntity entity) {
    return new SparepartRequest(entity.getId(), entity.getRequestType(), entity.getWorkOrderId(),
        entity.getMachineId(), entity.getSparepartId(), entity.getMaterialCode(), entity.getQuantity(),
        entity.getEstPriceId(), entity.getEstUnitPrice(), entity.getPurchaseReferenceUrl(), entity.getStatus(),
        entity.getRequestedBy(), entity.getRequestedAt(), entity.getNotes(), entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
