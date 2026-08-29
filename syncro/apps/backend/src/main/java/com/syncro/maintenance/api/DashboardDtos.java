package com.syncro.maintenance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dashboard read-only DTOs (story 14-1, FR-170/FR-171/FR-172).
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