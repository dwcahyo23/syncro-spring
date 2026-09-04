package com.syncro.kpi.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Source-data port for KPI materialization (story 20-1, AD-6/AD-20). Implemented by
 * the maintenance module's JPA adapter — the kpi module never reaches into another
 * module's repositories (modular-monolith invariant); raw rows cross the boundary as
 * plain records and ALL KPI arithmetic stays in {@link KpiMaterializationService}.
 *
 * <p>Windows are half-open {@code [from, to)} UTC instants; the monthly window is the
 * calendar month normalized to its first-of-month DATE (spec boundary).
 */
public interface KpiSourceDataReader {

  /**
   * Stopped breakdown workorders (category {@code 01}, status CLOSED) with the derived
   * {@code woStopAt} inside the window — the MTBF/breakdown-count source. Ordering is
   * NOT guaranteed; consumers must sort by {@code woStopAt} (the reference impl's
   * id-ordering bug is explicitly prevented here).
   */
  List<BreakdownStop> findBreakdownStops(Instant from, Instant to);

  /**
   * Closed-breakdown work-log intervals (both endpoints non-null) — the MTTR source.
   * Window filtering happens in the service against the stop map so the derived
   * {@code woStopAt} stays the single attribution key.
   */
  List<RepairLogInterval> findClosedBreakdownRepairLogs();

  /** Work-log activity rows (log started in window) — the technician total_wo/FTF source. */
  List<TechnicianLog> findTechnicianLogs(Instant from, Instant to);

  /** Work-log rating rows for logs started in window — the technician average_rating source. */
  List<TechnicianRating> findTechnicianRatings(Instant from, Instant to);

  /** Planned PM workorders for the plant with a scheduled date in the month window. */
  long countPmPlanned(UUID plantId, LocalDate monthFrom, LocalDate monthTo);

  /**
   * Completed PM workorders for the plant whose scheduled date falls in the month window
   * (review 20-1: same attribution key as {@link #countPmPlanned}, so the rate can never
   * exceed 100% from a cross-month completion).
   */
  long countPmCompleted(UUID plantId, LocalDate monthFrom, LocalDate monthTo);

  /**
   * Telemetry ingest availability inputs for MAR (planned/downtime minutes). Empty when
   * no persisted telemetry availability source covers the month — the service then
   * writes an explicit INSUFFICIENT_DATA row (never a fabricated value, AD-12).
   */
  Optional<MarInputs> findMarInputs(UUID plantId, Instant from, Instant to);

  /** All plant ids — the per-plant refresh iteration (MAR/PM/breakdown rows per plant). */
  List<UUID> findAllPlantIds();

  /** Machine ids belonging to the given machine groups — MTBF row scope filtering. */
  List<UUID> findMachineIdsByGroupIds(List<UUID> groupIds);

  /** One stopped breakdown workorder: id, machine, plant, and the derived stop instant. */
  record BreakdownStop(String workOrderId, UUID machineId, UUID plantId, Instant woStopAt) {
  }

  /** One closed-breakdown work-log interval attributed to its workorder and plant. */
  record RepairLogInterval(String workOrderId, UUID plantId, UUID machineId, Instant startTime,
      Instant endTime) {
  }

  /** One technician work-log attribution (plant, technician, workorder). */
  record TechnicianLog(UUID technicianId, UUID plantId, String workOrderId) {
  }

  /** One technician work-log rating score (1–5) on a log started in the window. */
  record TechnicianRating(UUID technicianId, UUID plantId, String workOrderId, int score) {
  }

  /** Telemetry-derived availability minutes for one plant-month. */
  record MarInputs(long plannedAvailableMinutes, long downtimeMinutes) {
  }
}
