package com.syncro.sync.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code sync_watermarks} row (AD-7/FR-150). Single-row table guarded by a
 * CHECK constraint on the PK ({@code id = '00000000-0000-0000-0000-000000000001'}).
 * The fixed UUID gives JPA a stable {@code findById} key for the upsert.
 */
@Entity
@Table(name = "sync_watermarks")
public class SyncWatermarkEntity {

  @Id
  private UUID id = SyncWatermarkRepository.FIXED_ID;

  @Column(name = "last_sheet_no", length = 50)
  private String lastSheetNo;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SyncWatermarkEntity() {
  }

  public SyncWatermarkEntity(String lastSheetNo, Instant updatedAt) {
    this.lastSheetNo = lastSheetNo;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getLastSheetNo() {
    return lastSheetNo;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}