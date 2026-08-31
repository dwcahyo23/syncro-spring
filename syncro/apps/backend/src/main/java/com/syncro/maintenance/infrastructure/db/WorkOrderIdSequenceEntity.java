package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Per-prefix sequence row for the WO-YYMMXXXX generator (story 10-1/15-1). One row per
 * YYMM prefix; {@code nextId()} takes a pessimistic write lock on the row so monthly
 * sequences stay gapless under concurrency. A new month is a new prefix row.
 */
@Entity
@Table(name = "workorder_id_sequences")
public class WorkOrderIdSequenceEntity {

  @Id
  @Column(length = 6)
  private String prefix;

  @Column(name = "last_seq", nullable = false)
  private int lastSeq;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WorkOrderIdSequenceEntity() {
  }

  public WorkOrderIdSequenceEntity(String prefix, int lastSeq, Instant updatedAt) {
    this.prefix = prefix;
    this.lastSeq = lastSeq;
    this.updatedAt = updatedAt;
  }

  public String getPrefix() {
    return prefix;
  }

  public int getLastSeq() {
    return lastSeq;
  }

  public void setLastSeq(int lastSeq) {
    this.lastSeq = lastSeq;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
