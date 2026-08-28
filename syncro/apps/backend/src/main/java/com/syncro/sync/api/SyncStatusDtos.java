package com.syncro.sync.api;

import com.syncro.sync.infrastructure.SyncQuarantineEntity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTOs for sync observability endpoints (story 13-3, FR-153).
 *
 * <p>{@link SyncQuarantineListRow} omits the potentially large {@code raw_payload}
 * JSONB column; the detail endpoint {@link SyncQuarantineDetailView} includes it.
 */
public final class SyncStatusDtos {

  private SyncStatusDtos() {
  }

  /** One row in the paginated quarantine list — no raw_payload. */
  public record SyncQuarantineListRow(
      UUID id,
      String sheetNo,
      String reason,
      String traceId,
      Instant createdAt) {

    public static SyncQuarantineListRow fromEntity(SyncQuarantineEntity entity) {
      return new SyncQuarantineListRow(
          entity.getId(),
          entity.getSheetNo(),
          entity.getReason(),
          entity.getTraceId(),
          entity.getCreatedAt());
    }
  }

  /** Full quarantine detail including the raw_payload JSONB. */
  public record SyncQuarantineDetailView(
      UUID id,
      String sheetNo,
      String reason,
      Map<String, Object> rawPayload,
      String traceId,
      Instant createdAt) {

    public static SyncQuarantineDetailView fromEntity(SyncQuarantineEntity entity) {
      return new SyncQuarantineDetailView(
          entity.getId(),
          entity.getSheetNo(),
          entity.getReason(),
          entity.getRawPayload(),
          entity.getTraceId(),
          entity.getCreatedAt());
    }
  }
}