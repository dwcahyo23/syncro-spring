package com.syncro.sync.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code sync_runs} row (AD-7/FR-150). One row per sync cycle recording
 * status, rows read, rows upserted, and error detail.
 */
@Entity
@Table(name = "sync_runs")
public class SyncRunEntity {

  @Id
  private UUID id;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(name = "rows_read", nullable = false)
  private int rowsRead;

  @Column(name = "rows_upserted", nullable = false)
  private int rowsUpserted;

  @Column(name = "error_message", columnDefinition = "text")
  private String errorMessage;

  protected SyncRunEntity() {
  }

  public SyncRunEntity(UUID id, Instant startedAt, String status, int rowsRead, int rowsUpserted,
      String errorMessage) {
    this.id = id;
    this.startedAt = startedAt;
    this.status = status;
    this.rowsRead = rowsRead;
    this.rowsUpserted = rowsUpserted;
    this.errorMessage = errorMessage;
  }

  public UUID getId() {
    return id;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public String getStatus() {
    return status;
  }

  public int getRowsRead() {
    return rowsRead;
  }

  public int getRowsUpserted() {
    return rowsUpserted;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void complete(String status, int rowsRead, int rowsUpserted, String errorMessage,
      Instant completedAt) {
    this.status = status;
    this.rowsRead = rowsRead;
    this.rowsUpserted = rowsUpserted;
    this.errorMessage = errorMessage;
    this.completedAt = completedAt;
  }
}