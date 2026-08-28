package com.syncro.sparepart.request.application;

import com.syncro.sparepart.request.domain.SparepartRequest;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestEntity;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestTimelineEntity;

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

  /** Maps a timeline entity to a view record (story 12-2, FR-141). */
  public static TimelineEventView toTimelineView(SparepartRequestTimelineEntity entity) {
    return new TimelineEventView(entity.getId(), entity.getRequestId(), entity.getFromStatus(),
        entity.getToStatus(), entity.getActor(), entity.getAction(), entity.getMreCode(), entity.getNote(),
        entity.getTraceId(), entity.getCreatedAt());
  }

  /** Timeline event view record (story 12-2). */
  public record TimelineEventView(
      java.util.UUID id,
      java.util.UUID requestId,
      com.syncro.sparepart.request.domain.SparepartRequestStatus fromStatus,
      com.syncro.sparepart.request.domain.SparepartRequestStatus toStatus,
      java.util.UUID actor,
      String action,
      String mreCode,
      String note,
      String traceId,
      java.time.Instant createdAt) {
  }
}
