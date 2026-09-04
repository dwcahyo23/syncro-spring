package com.syncro.kpi.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.kpi.domain.KpiAggregateRefreshStatus;
import com.syncro.kpi.domain.KpiType;
import com.syncro.kpi.infrastructure.db.KpiAggregateRefreshLogRepository;
import com.syncro.kpi.infrastructure.db.KpiMarMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiMarMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiMonthlyBreakdownEntity;
import com.syncro.kpi.infrastructure.db.KpiMonthlyBreakdownRepository;
import com.syncro.kpi.infrastructure.db.KpiMtbfMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiMtbfMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiMttrMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiMttrMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiPmCompletionMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiPmCompletionMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiTargetEntity;
import com.syncro.kpi.infrastructure.db.KpiTargetRepository;
import com.syncro.kpi.infrastructure.db.KpiTechnicianMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiTechnicianMonthlyRepository;
import com.syncro.org.application.OperationalScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scope-filtered reads of materialized KPI rows (story 20-1, AD-20) with the
 * actual-vs-target verdict computed server-side (story 20-2). Dashboards read
 * precomputed monthly rows here — never on-the-fly computation (spec "Never"). A month
 * with no row is an explicit INSUFFICIENT_DATA response, never a fabricated zero.
 *
 * <p>Every read is filtered by the caller's organizational scope (AD-2): leaders see
 * their machine groups, managers their plants, global roles everything. The verdict is
 * joined per row (every plant-level row carries its plant's target status), not only
 * when the caller passes {@code plantId}, and is a stable uppercase contract string —
 * the frontend renders labels from it and never branches on color (epic-20 UX rule).
 */
@Service
public class KpiQueryService {

  /** Read status of a materialized response (uppercase contract string). */
  public static final String STATUS_AVAILABLE = "AVAILABLE";
  public static final String STATUS_INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

  /** Actual-vs-target verdict per row (story 20-2, uppercase contract strings). */
  public static final String VERDICT_ON_TARGET = "ON_TARGET";
  public static final String VERDICT_BELOW_TARGET = "BELOW_TARGET";
  public static final String VERDICT_ABOVE_TARGET = "ABOVE_TARGET";
  public static final String VERDICT_NO_TARGET = "NO_TARGET";

  /** Direction flags for {@link #verdict} — MTBF/percentages higher-better, MTTR/breakdown lower-better. */
  private static final boolean HIGHER_IS_BETTER = true;
  private static final boolean LOWER_IS_BETTER = false;

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
  // Views (API read models — records, camelCase JSON). Story 20-2 adds the
  // per-row targetValue/targetStatus pair to every row record (additive).
  // ---------------------------------------------------------------------------

  public record MtbfRow(UUID plantId, UUID machineId, BigDecimal mtbfDays,
      BigDecimal targetValue, String targetStatus) {
  }

  public record MttrRow(UUID plantId, BigDecimal wallClockMinutes,
      BigDecimal actualWorkingMinutes, BigDecimal targetValue, String targetStatus) {
  }

  public record MarRow(UUID plantId, int plannedAvailableMinutes, int downtimeMinutes,
      BigDecimal marPercent, String sourceStatus, String sourceMessage,
      BigDecimal targetValue, String targetStatus) {
  }

  public record PmCompletionRow(UUID plantId, BigDecimal completionRate, int completedCount,
      int plannedCount, String sourceStatus, String sourceMessage,
      BigDecimal targetValue, String targetStatus) {
  }

  public record TechnicianRow(UUID plantId, UUID technicianId, BigDecimal averageRating,
      int totalWo, BigDecimal firstTimeFixRate, BigDecimal targetValue, String targetStatus) {
  }

  public record BreakdownRow(UUID plantId, int count, BigDecimal targetValue,
      String targetStatus) {
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
   * INSUFFICIENT_DATA with empty lists (never a fabricated zero). Every returned row
   * carries its plant's target verdict (story 20-2), joined per row regardless of
   * whether the caller passed {@code plantId}.
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
        var entities = mtbfMonthlies.findByMonth(month).stream()
            .filter(r -> visiblePlant(scope, r.getPlantId())
                || (scopedMachines != null && scopedMachines.contains(r.getMachineId())))
            .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
            .sorted(Comparator.comparing(KpiMtbfMonthlyEntity::getMachineId))
            .toList();
        var plantTargets = targetsByPlant(
            entities.stream().map(KpiMtbfMonthlyEntity::getPlantId).toList(), month);
        var rows = entities.stream()
            .map(r -> {
              var mtbfTarget = targetValue(plantTargets.get(r.getPlantId()),
                  KpiTargetEntity::getMtbfTargetDays);
              return new MtbfRow(r.getPlantId(), r.getMachineId(), r.getMtbfDays(), mtbfTarget,
                  verdict(r.getMtbfDays(), mtbfTarget, HIGHER_IS_BETTER));
            })
            .toList();
        yield rows.isEmpty()
            ? MaterializedResponse.insufficient(type, month, target, refresh)
            : new MaterializedResponse(type.key(), month, STATUS_AVAILABLE, rows,
                List.of(), List.of(), List.of(), List.of(), List.of(), target, refresh);
      }
      case MTTR -> {
        var entities = mttrMonthlies.findByMonth(month).stream()
            .filter(r -> visiblePlant(scope, r.getPlantId()))
            .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
            .sorted(Comparator.comparing(KpiMttrMonthlyEntity::getPlantId))
            .toList();
        var plantTargets = targetsByPlant(
            entities.stream().map(KpiMttrMonthlyEntity::getPlantId).toList(), month);
        var rows = entities.stream()
            .map(r -> {
              // Verdict follows the reference dashboard: actual-working when present,
              // wall-clock otherwise; both null → INSUFFICIENT_DATA (never fabricated).
              var actual = r.getActualWorkingMttrMinutes() != null
                  ? r.getActualWorkingMttrMinutes() : r.getWallClockMttrMinutes();
              var mttrTarget = targetValue(plantTargets.get(r.getPlantId()),
                  KpiTargetEntity::getMttrTargetMinutes);
              return new MttrRow(r.getPlantId(), r.getWallClockMttrMinutes(),
                  r.getActualWorkingMttrMinutes(), mttrTarget,
                  verdict(actual, mttrTarget, LOWER_IS_BETTER));
            })
            .toList();
        yield rows.isEmpty()
            ? MaterializedResponse.insufficient(type, month, target, refresh)
            : new MaterializedResponse(type.key(), month, STATUS_AVAILABLE, List.of(), rows,
                List.of(), List.of(), List.of(), List.of(), target, refresh);
      }
      case MAR -> {
        var entities = marMonthlies.findByMonth(month).stream()
            .filter(r -> visiblePlant(scope, r.getPlantId()))
            .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
            .sorted(Comparator.comparing(KpiMarMonthlyEntity::getPlantId))
            .toList();
        // kpi_target carries no MAR column (the OEE percents are baseline factors, not
        // MAR targets) — rows render actuals with NO_TARGET, never a fabricated verdict.
        var rows = entities.stream()
            .map(r -> new MarRow(r.getPlantId(), r.getPlannedAvailableMinutes(),
                r.getDowntimeMinutes(), r.getMarPercent(), r.getSourceStatus(),
                r.getSourceMessage(), null, VERDICT_NO_TARGET))
            .toList();
        yield rows.isEmpty()
            ? MaterializedResponse.insufficient(type, month, target, refresh)
            : new MaterializedResponse(type.key(), month, STATUS_AVAILABLE, List.of(), List.of(),
                rows, List.of(), List.of(), List.of(), target, refresh);
      }
      case PM_COMPLETION -> {
        var entities = pmCompletionMonthlies.findByMonth(month).stream()
            .filter(r -> visiblePlant(scope, r.getPlantId()))
            .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
            .sorted(Comparator.comparing(KpiPmCompletionMonthlyEntity::getPlantId))
            .toList();
        // No PM-completion column in kpi_target → NO_TARGET (same rule as MAR).
        var rows = entities.stream()
            .map(r -> new PmCompletionRow(r.getPlantId(), r.getCompletionRate(),
                r.getCompletedCount(), r.getPlannedCount(), r.getSourceStatus(),
                r.getSourceMessage(), null, VERDICT_NO_TARGET))
            .toList();
        yield rows.isEmpty()
            ? MaterializedResponse.insufficient(type, month, target, refresh)
            : new MaterializedResponse(type.key(), month, STATUS_AVAILABLE, List.of(), List.of(),
                List.of(), rows, List.of(), List.of(), target, refresh);
      }
      case TECHNICIAN -> {
        var entities = technicianMonthlies.findByMonth(month).stream()
            .filter(r -> visiblePlant(scope, r.getPlantId()))
            .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
            .sorted(Comparator.comparing(KpiTechnicianMonthlyEntity::getTechnicianId))
            .toList();
        // Technician metrics have no target column in kpi_target → always NO_TARGET
        // (spec design note); rows still render actuals.
        var rows = entities.stream()
            .map(r -> new TechnicianRow(r.getPlantId(), r.getTechnicianId(), r.getAverageRating(),
                r.getTotalWo(), r.getFirstTimeFixRate(), null, VERDICT_NO_TARGET))
            .toList();
        yield rows.isEmpty()
            ? MaterializedResponse.insufficient(type, month, target, refresh)
            : new MaterializedResponse(type.key(), month, STATUS_AVAILABLE, List.of(), List.of(),
                List.of(), List.of(), rows, List.of(), target, refresh);
      }
      case BREAKDOWN -> {
        var entities = breakdowns.findByMonth(month).stream()
            .filter(r -> visiblePlant(scope, r.getPlantId()))
            .filter(r -> plantId == null || plantId.equals(r.getPlantId()))
            .sorted(Comparator.comparing(KpiMonthlyBreakdownEntity::getPlantId))
            .toList();
        var plantTargets = targetsByPlant(
            entities.stream().map(KpiMonthlyBreakdownEntity::getPlantId).toList(), month);
        var rows = entities.stream()
            .map(r -> {
              var breakdownTarget = targetValue(plantTargets.get(r.getPlantId()),
                  t -> t.getMonthlyBreakdownTarget() == null ? null
                      : BigDecimal.valueOf(t.getMonthlyBreakdownTarget()));
              return new BreakdownRow(r.getPlantId(), r.getCount(), breakdownTarget,
                  verdict(BigDecimal.valueOf(r.getCount()), breakdownTarget, LOWER_IS_BETTER));
            })
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

  /**
   * Story 20-2: one {@code findByPlantIdAndMonth} per distinct plant in the result set
   * (small N; no N+1 concern at pilot scale).
   * ponytail: per-plant lookup, batch query if a sweep ever returns many plants.
   */
  private Map<UUID, KpiTargetEntity> targetsByPlant(Collection<UUID> plantIds, LocalDate month) {
    var byPlant = new HashMap<UUID, KpiTargetEntity>();
    var seen = new HashSet<UUID>();
    for (var plant : plantIds) {
      if (seen.add(plant)) {
        targets.findByPlantIdAndMonth(plant, month).ifPresent(t -> byPlant.put(plant, t));
      }
    }
    return byPlant;
  }

  private static <T> T targetValue(KpiTargetEntity target,
      Function<KpiTargetEntity, T> getter) {
    return target == null ? null : getter.apply(target);
  }

  /**
   * Server-side actual-vs-target verdict (story 20-2). Direction per metric: MTBF and
   * percentage metrics higher-better ({@code actual >= target} → ON_TARGET, else
   * BELOW_TARGET); MTTR and breakdown count lower-better ({@code actual <= target} →
   * ON_TARGET, else ABOVE_TARGET). Missing target → NO_TARGET; missing actual →
   * INSUFFICIENT_DATA — never a fabricated verdict (spec "Always").
   */
  private static String verdict(BigDecimal actual, BigDecimal target, boolean higherIsBetter) {
    if (target == null) {
      return VERDICT_NO_TARGET;
    }
    if (actual == null) {
      return STATUS_INSUFFICIENT_DATA;
    }
    int comparison = actual.compareTo(target);
    if (higherIsBetter ? comparison >= 0 : comparison <= 0) {
      return VERDICT_ON_TARGET;
    }
    return higherIsBetter ? VERDICT_BELOW_TARGET : VERDICT_ABOVE_TARGET;
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
