package com.syncro.kpi.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.kpi.domain.KpiAggregateRefreshStatus;
import com.syncro.kpi.domain.KpiType;
import com.syncro.kpi.infrastructure.db.KpiAggregateRefreshLogRepository;
import com.syncro.kpi.infrastructure.db.KpiMarMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiMonthlyBreakdownRepository;
import com.syncro.kpi.infrastructure.db.KpiMtbfMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiMttrMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiPmCompletionMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiTargetEntity;
import com.syncro.kpi.infrastructure.db.KpiTargetRepository;
import com.syncro.kpi.infrastructure.db.KpiTechnicianMonthlyRepository;
import com.syncro.org.application.OperationalScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scope-filtered reads of materialized KPI rows (story 20-1, AD-20). Dashboards read
 * precomputed monthly rows here — never on-the-fly computation (spec "Never"). A month
 * with no row is an explicit INSUFFICIENT_DATA response, never a fabricated zero.
 *
 * <p>Every read is filtered by the caller's organizational scope (AD-2): leaders see
 * their machine groups, managers their plants, global roles everything. The actual-vs-
 * target comparison joins {@code kpi_targets} for the requested plant/month and
 * returns a stable status string (non-color-only communication, epic-20 UX rule).
 */
@Service
public class KpiQueryService {

  /** Read status of a materialized response (uppercase contract string). */
  public static final String STATUS_AVAILABLE = "AVAILABLE";
  public static final String STATUS_INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

  private final KpiScopeService scopeService;
  private final KpiSourceDataReader source;
  private final KpiMonthlyBreakdownRepository breakdowns;
  private final KpiMtbfMonthlyRepository mtbfMonthlies;
  private final KpiMttrMonthlyRepository mttrMonthlies;
  private final KpiMarMonthlyRepository marMonthlies;
  private final KpiTechnicianMonthlyRepository technicianMonthlies;
  private final KpiPmCompletionMonthlyRepository pmCompletionMonthlies;
  private final KpiTargetRepository targets;
  private final KpiAggregateRefreshLogRepository refreshLogs;

  public KpiQueryService(KpiScopeService scopeService, KpiSourceDataReader source,
      KpiMonthlyBreakdownRepository breakdowns, KpiMtbfMonthlyRepository mtbfMonthlies,
      KpiMttrMonthlyRepository mttrMonthlies, KpiMarMonthlyRepository marMonthlies,
      KpiTechnicianMonthlyRepository technicianMonthlies,
      KpiPmCompletionMonthlyRepository pmCompletionMonthlies, KpiTargetRepository targets,
      KpiAggregateRefreshLogRepository refreshLogs) {
    this.scopeService = scopeService;
    this.source = source;
    this.breakdowns = breakdowns;
    this.mtbfMonthlies = mtbfMonthlies;
    this.mttrMonthlies = mttrMonthlies;
    this.marMonthlies = marMonthlies;
    this.technicianMonthlies = technicianMonthlies;
    this.pmCompletionMonthlies = pmCompletionMonthlies;
    this.targets = targets;
    this.refreshLogs = refreshLogs;
  }

  // ---------------------------------------------------------------------------
  // Views (API read models — records, camelCase JSON)
  // ---------------------------------------------------------------------------

  public record MtbfRow(UUID plantId, UUID machineId, BigDecimal mtbfDays) {
  }

  public record MttrRow(UUID plantId, BigDecimal wallClockMinutes, BigDecimal actualWorkingMinutes) {
  }

  public record MarRow(UUID plantId, int plannedAvailableMinutes, int downtimeMinutes,
      BigDecimal marPercent, String sourceStatus, String sourceMessage) {
  }

  public record PmCompletionRow(UUID plantId, BigDecimal completionRate, int completedCount,
      int plannedCount, String sourceStatus, String sourceMessage) {
  }

  public record TechnicianRow(UUID plantId, UUID technicianId, BigDecimal averageRating,
      int totalWo, BigDecimal firstTimeFixRate) {
  }

  public record BreakdownRow(UUID plantId, int count) {
  }

  public record TargetView(UUID plantId, LocalDate month, Integer monthlyBreakdownTarget,
      BigDecimal mtbfTargetDays, BigDecimal mttrTargetMinutes, BigDecimal oeeQualityPercent,
      BigDecimal oeePerformancePercent) {
  }

  public record RefreshView(String refreshKey, KpiAggregateRefreshStatus status,
      Instant refreshedAt, String message) {
  }

  public record MaterializedResponse(String type, LocalDate month, String status,
      List<MtbfRow> mtbfRows, List<MttrRow> mttrRows, List<MarRow> marRows,
      List<PmCompletionRow> pmCompletionRows, List<TechnicianRow> technicianRows,
      List<BreakdownRow> breakdownRows, TargetView target, RefreshView refresh) {

    /** Explicit insufficient-data response — empty lists, never fabricated zeros. */
    static MaterializedResponse insufficient(KpiType type, LocalDate month, TargetView target,
        RefreshView refresh) {
      return new MaterializedResponse(type.key(), month, STATUS_INSUFFICIENT_DATA,
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), target, refresh);
    }
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /**
   * Scope-filtered materialized read for one KPI type/month. {@code plantId} optionally
   * narrows the response to one plant (must be in scope). No rows in scope → explicit
   * INSUFFICIENT_DATA with empty lists (never a fabricated zero).
   */
  @Transactional(readOnly = true)
  public MaterializedResponse materialized(AuthenticatedUser user, KpiType type, LocalDate month,
      UUID plantId) {
    var scope = scopeService.derive(user);
    if (plantId != null && !scopeService.plantVisible(scope, plantId)) {
      throw new KpiPlantForbiddenException();
    }
    var refresh = refreshLogs.findByRefreshKey(type.refreshKey(month))
        .map(l -> new RefreshView(l.getRefreshKey(), l.getStatus(), l.getRefreshedAt(),
            l.getMessage()))
        .orElse(null);
    var target = plantId == null ? null
        : targets.findByPlantIdAndMonth(plantId, month).map(KpiQueryService::toTargetView).orElse(null);

    return switch (type) {
      case MTBF -> {
        // Same OR semantics as the 14-2 analytics filter: plant-scoped users see their
        // plants' rows; leaders additionally see rows of machines in their groups.
        var scopedMachines = scope.plantIds() == null ? null
            : new HashSet<>(source.findMachineIdsByGroupIds(scopeService.groupIds(scope)));
        var rows = mtbfMonthlies.findByMonth(month).stream()
            .filter(r -> visiblePlant(scope, r.getPlantId())
                || (scopedMachines != null && scopedMachines.contains(r.getMachineId())))
            .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
            .map(r -> new MtbfRow(r.getPlantId(), r.getMachineId(), r.getMtbfDays()))
            .sorted(Comparator.comparing(MtbfRow::machineId))
            .toList();
        yield rows.isEmpty()
            ? MaterializedResponse.insufficient(type, month, target, refresh)
            : new MaterializedResponse(type.key(), month, STATUS_AVAILABLE, rows,
                List.of(), List.of(), List.of(), List.of(), List.of(), target, refresh);
      }
      case MTTR -> {
        var rows = mttrMonthlies.findByMonth(month).stream()
            .filter(r -> visiblePlant(scope, r.getPlantId()))
            .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
            .map(r -> new MttrRow(r.getPlantId(), r.getWallClockMttrMinutes(),
                r.getActualWorkingMttrMinutes()))
            .sorted(Comparator.comparing(MttrRow::plantId))
            .toList();
        yield rows.isEmpty()
            ? MaterializedResponse.insufficient(type, month, target, refresh)
            : new MaterializedResponse(type.key(), month, STATUS_AVAILABLE, List.of(), rows,
                List.of(), List.of(), List.of(), List.of(), target, refresh);
      }
      case MAR -> {
        var rows = marMonthlies.findByMonth(month).stream()
            .filter(r -> visiblePlant(scope, r.getPlantId()))
            .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
            .map(r -> new MarRow(r.getPlantId(), r.getPlannedAvailableMinutes(),
                r.getDowntimeMinutes(), r.getMarPercent(), r.getSourceStatus(),
                r.getSourceMessage()))
            .sorted(Comparator.comparing(MarRow::plantId))
            .toList();
        yield rows.isEmpty()
            ? MaterializedResponse.insufficient(type, month, target, refresh)
            : new MaterializedResponse(type.key(), month, STATUS_AVAILABLE, List.of(), List.of(),
                rows, List.of(), List.of(), List.of(), target, refresh);
      }
      case PM_COMPLETION -> {
        var rows = pmCompletionMonthlies.findByMonth(month).stream()
            .filter(r -> visiblePlant(scope, r.getPlantId()))
            .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
            .map(r -> new PmCompletionRow(r.getPlantId(), r.getCompletionRate(),
                r.getCompletedCount(), r.getPlannedCount(), r.getSourceStatus(),
                r.getSourceMessage()))
            .sorted(Comparator.comparing(PmCompletionRow::plantId))
            .toList();
        yield rows.isEmpty()
            ? MaterializedResponse.insufficient(type, month, target, refresh)
            : new MaterializedResponse(type.key(), month, STATUS_AVAILABLE, List.of(), List.of(),
                List.of(), rows, List.of(), List.of(), target, refresh);
      }
      case TECHNICIAN -> {
        var rows = technicianMonthlies.findByMonth(month).stream()
            .filter(r -> visiblePlant(scope, r.getPlantId()))
            .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
            .map(r -> new TechnicianRow(r.getPlantId(), r.getTechnicianId(), r.getAverageRating(),
                r.getTotalWo(), r.getFirstTimeFixRate()))
            .sorted(Comparator.comparing(TechnicianRow::technicianId))
            .toList();
        yield rows.isEmpty()
            ? MaterializedResponse.insufficient(type, month, target, refresh)
            : new MaterializedResponse(type.key(), month, STATUS_AVAILABLE, List.of(), List.of(),
                List.of(), List.of(), rows, List.of(), target, refresh);
      }
      case BREAKDOWN -> {
        var rows = breakdowns.findByMonth(month).stream()
            .filter(r -> visiblePlant(scope, r.getPlantId()))
            .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
            .map(r -> new BreakdownRow(r.getPlantId(), r.getCount()))
            .sorted(Comparator.comparing(BreakdownRow::plantId))
            .toList();
        yield rows.isEmpty()
            ? MaterializedResponse.insufficient(type, month, target, refresh)
            : new MaterializedResponse(type.key(), month, STATUS_AVAILABLE, List.of(), List.of(),
                List.of(), List.of(), List.of(), rows, target, refresh);
      }
    };
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private boolean visiblePlant(OperationalScope scope, UUID plantId) {
    return scope.plantIds() == null || scope.plantIds().contains(plantId);
  }

  private static TargetView toTargetView(KpiTargetEntity t) {
    return new TargetView(t.getPlantId(), t.getMonth(), t.getMonthlyBreakdownTarget(),
        t.getMtbfTargetDays(), t.getMttrTargetMinutes(), t.getOeeQualityPercent(),
        t.getOeePerformancePercent());
  }

  /** Scope-denied when the requested plant is outside the caller's derived scope. */
  public static class KpiPlantForbiddenException extends RuntimeException {
  }
}
