package com.syncro.sync.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted {@code sync_quarantine} row (story 13-2, NFR-P2-9). Append-only evidence
 * for rows rejected by sync protection gates. Shared with story 13-3 (observability).
 * {@code raw_payload} holds the external {@code SyncSourceRow} as JSON so the operator
 * can diagnose the rejection.
 */
@Entity
@Table(name = "sync_quarantine", indexes = {
    @Index(name = "idx_sync_quarantine_sheet_no", columnList = "sheet_no")
})
public class SyncQuarantineEntity {

  @Id
  private UUID id;

  @Column(name = "sheet_no", length = 50)
  private String sheetNo;

  @Column(nullable = false, length = 64)
  private String reason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "raw_payload", columnDefinition = "jsonb")
  private Map<String, Object> rawPayload;

  @Column(name = "trace_id", length = 36)
  private String traceId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected SyncQuarantineEntity() {
  }

  public SyncQuarantineEntity(UUID id, String sheetNo, String reason, Map<String, Object> rawPayload,
      String traceId, Instant createdAt) {
    this.id = id;
    this.sheetNo = sheetNo;
    this.reason = reason;
    this.rawPayload = rawPayload;
    this.traceId = traceId;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getSheetNo() {
    return sheetNo;
  }

  public String getReason() {
    return reason;
  }

  public Map<String, Object> getRawPayload() {
    return rawPayload;
  }

  public String getTraceId() {
    return traceId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}