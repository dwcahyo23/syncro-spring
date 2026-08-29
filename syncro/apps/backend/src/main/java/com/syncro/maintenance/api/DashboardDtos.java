package com.syncro.maintenance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dashboard read-only DTOs (story 14-1, FR-170/FR-171/FR-172; story 14-2, FR-173/FR-174).
 * All counts are backend-computed; the frontend renders only.
 */
public final class DashboardDtos {

  private DashboardDtos() {
  }

  // ---------------------------------------------------------------------------
  // Machine Dashboard (FR-170)
  // ---------------------------------------------------------------------------

  /** Per-machine dashboard row: status, telemetry freshness, open workorders/alerts, lifetime risk. */
  public record MachineDashboardRow(
      UUID machineId,
      String code,
      String name,
      String status,
      String machineGroupName,
      String plantCode,
      String plantName,
      Long openWorkOrderCount,
      Long openAlertCount,
      TelemetryState telemetryFreshness,
      LifetimeRiskView lifetimeRisk) {
  }

  /** Telemetry freshness state (null when no telemetry ever received). */
  public record TelemetryState(
      String freshnessState,
      Boolean running,
      Double runtimeHours,
      Long counting,
      Instant lastReceivedAt) {
  }

  /** Lifetime risk: max consumed percentage across all sparepart installations on the machine. */
  public record LifetimeRiskView(
      String maxConsumedPercentage,
      String thresholdPercentage,
      String status) {
  }

  public record MachineDashboardResponse(List<MachineDashboardRow> items) {
  }

  // ---------------------------------------------------------------------------
  // Workorder Dashboard (FR-171)
  // ---------------------------------------------------------------------------

  /** Counts by status. */
  public record StatusCount(String status, long count) {
  }

  /** Counts by category. */
  public record CategoryCount(String categoryCode, String categoryLabel, long count) {
  }

  public record WorkorderDashboardResponse(
      long total,
      List<StatusCount> byStatus,
      List<CategoryCount> byCategory) {
  }

  // ---------------------------------------------------------------------------
  // Preventive Dashboard (FR-172)
  // ---------------------------------------------------------------------------

  /** One upcoming preventive schedule row with machine and program details. */
  public record PreventiveUpcomingRow(
      UUID scheduleId,
      UUID machineId,
      UUID programId,
      LocalDate dueDate,
      String status,
      String derivedStatus,
      String category,
      String scheduleType,
      String machineCode,
      String machineName,
      String programTitle) {
  }

  public record PreventiveDashboardResponse(
      long dueCount,
      long overdueCount,
      List<PreventiveUpcomingRow> upcoming) {
  }

  // ---------------------------------------------------------------------------
  // MTBF/MTTR Dashboard (FR-173, story 14-2)
  // ---------------------------------------------------------------------------

  /**
   * MTBF view. {@code status} is {@code AVAILABLE} when at least 2 stopped breakdown
   * workorders are in the window, {@code INSUFFICIENT_DATA} otherwise (never a
   * fabricated value). {@code valueHours} is the mean interval between consecutive
   * stops across the fleet in scope (hours decimal); null when insufficient.
   * {@code workorderCount} is the number of stopped breakdown workorders in the window.
   */
  public record MtbfView(
      String status,
      Double valueHours,
      long workorderCount) {
  }

  /**
   * MTTR view. {@code status} is {@code AVAILABLE} when at least one completed breakdown
   * workorder with a persisted {@code mttrMinutes} is in the window, {@code
   * INSUFFICIENT_DATA} otherwise. {@code valueHours} is the mean per-workorder repair
   * time in hours (minutes / 60); null when insufficient. {@code workorderCount} counts
   * the completed breakdown workorders WITH a persisted {@code mttrMinutes} that were
   * included in the mean.
   */
  public record MttrView(
      String status,
      Double valueHours,
      long workorderCount) {
  }

  /**
   * MTBF/MTTR analytics response (FR-173). {@code windowFrom}/{@code windowTo} bound the
   * monthly rolling window keyed on the derived {@code woStopAt}; null when the scope is
   * empty (no fabricated window). {@code computedAt} is when the payload was computed;
   * {@code cacheAgeMs} is the age of the served cached payload (null when no cache was
   * involved — Redis down); {@code stale} is true when the served payload is older than
   * the analytics TTL (the backend recomputes stale entries, so this is normally false —
   * it exists as a non-color-only freshness signal).
   */
  public record MtbfMttrResponse(
      MtbfView mtbf,
      MttrView mttr,
      Instant windowFrom,
      Instant windowTo,
      Instant computedAt,
      Long cacheAgeMs,
      boolean stale) {
  }

  // ---------------------------------------------------------------------------
  // Technician KPI Dashboard (FR-174, story 14-2)
  // ---------------------------------------------------------------------------

  /** One per-dimension average rating (1-5 stars) for a technician. */
  public record RatingDimensionView(
      UUID dimensionId,
      String dimensionCode,
      String dimensionLabel,
      Double averageScore) {
  }

  /**
   * One technician KPI row: objective KPIs (completed count = DONE/CLOSED, average MTTR
   * hours scoped to breakdown workorders only, on-time % from responseTime vs category
   * target) plus per-dimension average ratings. {@code ratings} is empty when the
   * technician has no TECHNICIAN-type ratings — objective KPIs are still shown.
   */
  public record TechnicianKpiRowView(
      UUID technicianId,
      String technicianName,
      long completedCount,
      Double averageMttrHours,
      Double onTimePercentage,
      List<RatingDimensionView> ratings) {
  }

  /** Technician KPI analytics response (FR-174). Same window/freshness contract as MTBF/MTTR. */
  public record TechnicianKpiResponse(
      List<TechnicianKpiRowView> technicians,
      Instant windowFrom,
      Instant windowTo,
      Instant computedAt,
      Long cacheAgeMs,
      boolean stale) {
  }

  // ---------------------------------------------------------------------------
  // Error response
  // ---------------------------------------------------------------------------

  public record ErrorResponse(
      String code,
      String message,
      Map<String, String> fieldErrors,
      String timestamp,
      String traceId) {
  }
}