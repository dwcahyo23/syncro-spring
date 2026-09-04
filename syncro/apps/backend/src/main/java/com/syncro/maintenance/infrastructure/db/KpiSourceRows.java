package com.syncro.maintenance.infrastructure.db;

import java.time.Instant;
import java.util.UUID;

/**
 * Raw source rows for KPI materialization (story 20-1). Plain projections across the
 * kpi module's source port — no KPI arithmetic here (that lives in
 * {@code KpiMaterializationService}); these records only carry the fields the monthly
 * refresh needs. Returned by the maintenance repositories and mapped to the port's
 * records by {@link KpiSourceDataJpaAdapter}.
 */
public final class KpiSourceRows {

  private KpiSourceRows() {
  }

  /** Stopped breakdown workorder with its derived woStopAt (14-2 key: latest PENDING_REVIEW transition, updatedAt fallback). */
  public record BreakdownStopRow(String workOrderId, UUID machineId, UUID plantId, Instant woStopAt) {
  }

  /** Closed-breakdown work-log interval (MTTR source). */
  public record RepairLogRow(String workOrderId, UUID plantId, UUID machineId, Instant startTime,
      Instant endTime) {
  }

  /** Technician work-log attribution (total_wo / first-time-fix source). */
  public record TechnicianLogRow(UUID technicianId, UUID plantId, String workOrderId) {
  }

  /** Technician work-log rating score (average_rating source). */
  public record TechnicianRatingRow(UUID technicianId, UUID plantId, String workOrderId, int score) {
  }
}
