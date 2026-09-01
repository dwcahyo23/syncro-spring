package com.syncro.kpi.infrastructure.db;

import com.syncro.kpi.domain.KpiAggregateRefreshStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code kpi_aggregate_refresh_logs} row (blueprint G7, story 15-2). One
 * row per aggregate refresh attempt, keyed by the unique {@code refresh_key}
 * (e.g. {@code mtbf:2026-08}); RUNNING while in flight, then SUCCESS/FAILED.
 */
@Entity
@Table(name = "kpi_aggregate_refresh_logs")
public class KpiAggregateRefreshLogEntity {

  @Id
  private UUID id;

  @Column(name = "refresh_key", nullable = false, length = 200)
  private String refreshKey;

  @Column(name = "refreshed_at", nullable = false)
  private Instant refreshedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private KpiAggregateRefreshStatus status;

  @Column(columnDefinition = "text")
  private String message;

  protected KpiAggregateRefreshLogEntity() {
  }

  public KpiAggregateRefreshLogEntity(UUID id, String refreshKey, Instant refreshedAt,
      KpiAggregateRefreshStatus status, String message) {
    this.id = id;
    this.refreshKey = refreshKey;
    this.refreshedAt = refreshedAt;
    this.status = status;
    this.message = message;
  }

  public UUID getId() {
    return id;
  }

  public String getRefreshKey() {
    return refreshKey;
  }

  public Instant getRefreshedAt() {
    return refreshedAt;
  }

  public KpiAggregateRefreshStatus getStatus() {
    return status;
  }

  public String getMessage() {
    return message;
  }

  /** Terminal outcome (G7): flips RUNNING to SUCCESS/FAILED with a diagnostic. */
  public void finish(KpiAggregateRefreshStatus status, String message, Instant refreshedAt) {
    this.status = status;
    this.message = message;
    this.refreshedAt = refreshedAt;
  }
}
