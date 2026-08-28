package com.syncro.sparepart.request.infrastructure.db;

import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code sparepart_request_timeline} row (FR-141, story 12-2). One row per
 * status transition or MRE code recording, providing per-request operational evidence.
 */
@Entity
@Table(name = "sparepart_request_timeline")
public class SparepartRequestTimelineEntity {

  @Id
  private UUID id;

  @Column(name = "request_id", nullable = false)
  private UUID requestId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 20)
  private SparepartRequestStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 20)
  private SparepartRequestStatus toStatus;

  @Column(nullable = false)
  private UUID actor;

  @Column(nullable = false, length = 20)
  private String action;

  @Column(name = "mre_code", length = 64)
  private String mreCode;

  @Column(length = 500)
  private String note;

  @Column(name = "trace_id", length = 64)
  private String traceId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected SparepartRequestTimelineEntity() {
  }

  public SparepartRequestTimelineEntity(UUID id, UUID requestId, SparepartRequestStatus fromStatus,
      SparepartRequestStatus toStatus, UUID actor, String action, String mreCode, String note,
      String traceId, Instant createdAt) {
    this.id = id;
    this.requestId = requestId;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.actor = actor;
    this.action = action;
    this.mreCode = mreCode;
    this.note = note;
    this.traceId = traceId;
    this.createdAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getRequestId() { return requestId; }
  public SparepartRequestStatus getFromStatus() { return fromStatus; }
  public SparepartRequestStatus getToStatus() { return toStatus; }
  public UUID getActor() { return actor; }
  public String getAction() { return action; }
  public String getMreCode() { return mreCode; }
  public String getNote() { return note; }
  public String getTraceId() { return traceId; }
  public Instant getCreatedAt() { return createdAt; }
}