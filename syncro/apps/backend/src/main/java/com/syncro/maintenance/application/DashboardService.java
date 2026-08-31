package com.syncro.maintenance.application;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.machine.application.MachineService;
import com.syncro.machine.application.MachineService.MachineListView;
import com.syncro.machine.application.MachineService.MachineView;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.maintenance.api.DashboardDtos.CategoryCount;
import com.syncro.maintenance.api.DashboardDtos.LifetimeRiskView;
import com.syncro.maintenance.api.DashboardDtos.MachineDashboardResponse;
import com.syncro.maintenance.api.DashboardDtos.MachineDashboardRow;
import com.syncro.maintenance.api.DashboardDtos.MonthlyWorkorderCount;
import com.syncro.maintenance.api.DashboardDtos.PreventiveDashboardResponse;
import com.syncro.maintenance.api.DashboardDtos.PreventiveUpcomingRow;
import com.syncro.maintenance.api.DashboardDtos.StatusCount;
import com.syncro.maintenance.api.DashboardDtos.TelemetryState;
import com.syncro.maintenance.api.DashboardDtos.WorkorderDashboardResponse;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.preventive.domain.ScheduleStatus;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleDashboardRow;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleRepository;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.sparepart.application.SparepartLifetimeEvaluator;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.telemetry.application.LatestTelemetryDto;
import com.syncro.telemetry.application.LatestTelemetryQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard read surface (story 14-1, FR-170/FR-171/FR-172). All counts are computed
 * here (backend), never re-aggregated by the frontend. Scope derivation reuses the
 * canonical {@link OperationalScopeService} pattern; due/overdue derivation uses the
 * injected {@link Clock} (server clock) exactly like {@link PreventiveScheduleService}.
 *
 * <p>Machine dashboard rows reuse the scope-filtered, telemetry-hydrated
 * {@link MachineService#list} plus two aggregate queries (open workorders by machine,
 * open alerts by machine) and batch lifetime evaluation — no per-machine N+1.
 *
 * <p>Empty-scope guard: a restricted user with empty plantIds AND empty groupIds
 * returns explicit empty responses (never {@code in ()} JPQL).
 */
@Service
public class DashboardService {

  private static final int PREVENTIVE_UPCOMING_LIMIT = 50;
  private static final Set<WorkOrderStatus> TERMINAL_STATUSES = EnumSet.of(
      WorkOrderStatus.DONE, WorkOrderStatus.CLOSED, WorkOrderStatus.CANCELLED);
  private static final UUID NO_PLANT = new UUID(0L, 0L);
  private static final UUID NO_SECTION = new UUID(0L, 0L);
  private static final String NO_STATUS = "";

  private final OperationalScopeService scopes;
  private final MachineService machines;
  private final LatestTelemetryQueryService telemetryQuery;
  private final WorkOrderRepository workOrders;
  private final SparepartAlertRepository alerts;
  private final PreventiveScheduleRepository schedules;
  private final SparepartLifetimeEvaluator lifetimeEvaluator;
  private final MachineSparepartInstallationRepository installations;
  private final Clock clock;

  public DashboardService(OperationalScopeService scopes, MachineService machines,
      LatestTelemetryQueryService telemetryQuery, WorkOrderRepository workOrders,
      SparepartAlertRepository alerts, PreventiveScheduleRepository schedules,
      SparepartLifetimeEvaluator lifetimeEvaluator,
      MachineSparepartInstallationRepository installations, Clock clock) {
    this.scopes = scopes;
    this.machines = machines;
    this.telemetryQuery = telemetryQuery;
    this.workOrders = workOrders;
    this.alerts = alerts;
    this.schedules = schedules;
    this.lifetimeEvaluator = lifetimeEvaluator;
    this.installations = installations;
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------
  // Machine dashboard (FR-170)
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public MachineDashboardResponse machineDashboard(AuthenticatedUser user, UUID plantId) {
    var scope = scopes.derive(user);
    var unrestricted = scope.plantIds() == null;
    var groupIds = mergeGroupIds(scope.machineGroupIds(), scope.activeTeamIds());

    // Empty-scope guard: restricted user with empty plantIds AND empty groupIds.
    if (!unrestricted && scope.plantIds().isEmpty() && groupIds.isEmpty()) {
      return new MachineDashboardResponse(List.of());
    }

    // MachineService.list applies the scope + plant filter itself. An out-of-scope
    // plantId raises PlantAccessDeniedException; the dashboard contract (I/O matrix)
    // turns that into an empty payload — never a 403, never out-of-scope rows.
    MachineListView listView;
    try {
      listView = machines.list(user, plantId, null, null);
    } catch (com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException denied) {
      return new MachineDashboardResponse(List.of());
    } catch (com.syncro.machine.application.MachineService.PlantNotFoundForMachineException notFound) {
      return new MachineDashboardResponse(List.of());
    }
    var machineViews = listView.items();
    if (machineViews.isEmpty()) {
      return new MachineDashboardResponse(List.of());
    }

    // Telemetry freshness in one batch.
    Map<UUID, MachineStatus> statusesByMachine = new LinkedHashMap<>();
    for (var machine : machineViews) {
      statusesByMachine.put(machine.id(), machine.status());
    }
    Map<UUID, LatestTelemetryDto.TelemetryData> telemetryByMachine =
        telemetryQuery.latestTelemetryBatch(statusesByMachine);

    // Open workorder count per machine (non-terminal).
    var openWoByMachine = workOrders
        .countOpenByMachineScoped(TERMINAL_STATUSES, unrestricted,
            scope.plantIds() == null ? List.of() : scope.plantIds(), groupIds)
        .stream()
        .collect(Collectors.toMap(
            WorkOrderRepository.OpenByMachineProjection::getMachineId,
            WorkOrderRepository.OpenByMachineProjection::getCount,
            (a, b) -> a + b));

    // Open alert count per machine (non-RESOLVED).
    var openAlertByMachine = alerts
        .countOpenByMachineScoped(SparepartAlertStatus.RESOLVED, unrestricted,
            scope.plantIds() == null ? List.of() : scope.plantIds(), groupIds)
        .stream()
        .collect(Collectors.toMap(
            SparepartAlertRepository.AlertCountByMachineProjection::getMachineId,
            SparepartAlertRepository.AlertCountByMachineProjection::getCount,
            (a, b) -> a + b));

    // Batch lifetime risk evaluation (single Redis round trip, no per-machine N+1).
    var machineIds = machineViews.stream().map(MachineView::id).toList();
    var lifetimeByMachine = lifetimeEvaluator.evaluateBatch(machineIds);

    // Threshold lookup (batch).
    var thresholdByInstallation = installations
        .findAllByMachineIdIn(machineIds)
        .stream()
        .collect(Collectors.toMap(
            i -> i.getId(),
            i -> i.getThresholdPercentage(),
            (first, second) -> first));

    var rows = machineViews.stream()
        .map(machine -> toMachineRow(machine, telemetryByMachine.get(machine.id()),
            openWoByMachine.getOrDefault(machine.id(), 0L),
            openAlertByMachine.getOrDefault(machine.id(), 0L),
            lifetimeByMachine.getOrDefault(machine.id(), Map.of()), thresholdByInstallation))
        .sorted(Comparator.comparing(MachineDashboardRow::code))
        .toList();
    return new MachineDashboardResponse(rows);
  }

  private static MachineDashboardRow toMachineRow(MachineView machine,
      LatestTelemetryDto.TelemetryData telemetry, long openWorkOrders, long openAlerts,
      Map<UUID, SparepartLifetimeEvaluator.EvaluationResult> lifetimeResults,
      Map<UUID, Integer> thresholdByInstallation) {
    TelemetryState telemetryState = telemetry == null
        ? null
        : new TelemetryState(telemetry.freshnessState() == null ? null : telemetry.freshnessState().name(),
            telemetry.running(), telemetry.runtimeHours(), telemetry.counting(), telemetry.lastReceivedAt());
    return new MachineDashboardRow(
        machine.id(),
        machine.code(),
        machine.name(),
        machine.status().name(),
        machine.machineGroupName(),
        machine.plantCode(),
        machine.plantName(),
        openWorkOrders,
        openAlerts,
        telemetryState,
        lifetimeRisk(lifetimeResults, thresholdByInstallation));
  }

  /**
   * Lifetime risk = the installation with the maximum consumed percentage. {@code status}
   * is {@code AT_RISK} when consumed % is >= the installation's threshold, {@code NORMAL}
   * otherwise; absent evaluations → {@code NO_DATA}. Never null. Uses {@code >=} boundary
   * with null-threshold guard.
   */
  private static LifetimeRiskView lifetimeRisk(
      Map<UUID, SparepartLifetimeEvaluator.EvaluationResult> results,
      Map<UUID, Integer> thresholdByInstallation) {
    if (results.isEmpty()) {
      return new LifetimeRiskView(null, null, "NO_DATA");
    }
    String maxPct = null;
    String threshold = null;
    boolean atRisk = false;
    for (var entry : results.entrySet()) {
      var evaluation = entry.getValue();
      if (evaluation == null || evaluation.consumedPercentage() == null) {
        continue;
      }
      var pct = evaluation.consumedPercentage();
      var installationThreshold = thresholdByInstallation.get(entry.getKey());
      if (maxPct == null || pct.compareTo(new BigDecimal(maxPct)) > 0) {
        maxPct = pct.toPlainString();
        threshold = installationThreshold == null ? null : installationThreshold.toString();
        // AT_RISK uses >= boundary; null thresholds don't downgrade AT_RISK.
        if (installationThreshold != null && pct.compareTo(BigDecimal.valueOf(installationThreshold)) >= 0) {
          atRisk = true;
        }
      }
    }
    if (maxPct == null) {
      return new LifetimeRiskView(null, null, "NO_DATA");
    }
    return new LifetimeRiskView(maxPct, threshold, atRisk ? "AT_RISK" : "NORMAL");
  }

  // ---------------------------------------------------------------------------
  // Workorder dashboard (FR-171)
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public WorkorderDashboardResponse workorderDashboard(AuthenticatedUser user, UUID plantId,
      UUID sectionId, WorkOrderStatus status, String categoryCode) {
    var scope = scopes.derive(user);
    var unrestricted = scope.plantIds() == null;
    var groupIds = mergeGroupIds(scope.machineGroupIds(), scope.activeTeamIds());
    var plantIds = scope.plantIds() == null ? List.<UUID>of() : scope.plantIds();

    // Empty-scope guard.
    if (!unrestricted && plantIds.isEmpty() && groupIds.isEmpty()) {
      return new WorkorderDashboardResponse(0L, List.of(), List.of(), List.of());
    }

    var plantFilter = plantId == null ? NO_PLANT : plantId;
    var sectionFilter = sectionId == null ? NO_SECTION : sectionId;
    var statusFilter = status == null ? NO_STATUS : status.name();
    var catFilter = categoryCode == null || categoryCode.isBlank() ? "" : categoryCode.trim();

    List<WorkOrderRepository.StatusCountProjection> statusRows =
        workOrders.countByStatusScoped(unrestricted, plantIds, groupIds, plantFilter, NO_PLANT,
            sectionFilter, NO_SECTION, statusFilter, catFilter);
    List<WorkOrderRepository.CategoryCountProjection> categoryRows =
        workOrders.countByCategoryScoped(unrestricted, plantIds, groupIds, plantFilter, NO_PLANT,
            sectionFilter, NO_SECTION, statusFilter, catFilter);
    long total = statusRows.stream().mapToLong(WorkOrderRepository.StatusCountProjection::getCount).sum();

    var byStatus = statusRows.stream()
        .map(row -> new StatusCount(row.getStatus().name(), row.getCount()))
        .toList();
    var byCategory = categoryRows.stream()
        .map(row -> new CategoryCount(row.getCategoryCode(), row.getCategoryLabel(), row.getCount()))
        .toList();
    var byMonth = monthlyWorkorderCounts(user, plantFilter, sectionFilter, statusFilter, catFilter);
    return new WorkorderDashboardResponse(total, byStatus, byCategory, byMonth);
  }

  /**
   * DW-148: Jan–Dec Open/Close counts for the current calendar year. Open = non-terminal
   * statuses, Close = DONE/CLOSED/CANCELLED. Months with no workorders are zero-filled so
   * the chart renders all 12 bars.
   */
  private List<MonthlyWorkorderCount> monthlyWorkorderCounts(AuthenticatedUser user,
      UUID plantFilter, UUID sectionFilter, String statusFilter, String catFilter) {
    var scope = scopes.derive(user);
    var unrestricted = scope.plantIds() == null;
    var groupIds = mergeGroupIds(scope.machineGroupIds(), scope.activeTeamIds());
    var plantIds = scope.plantIds() == null ? List.<UUID>of() : scope.plantIds();

    var now = LocalDate.now(clock);
    var from = now.withDayOfYear(1).atStartOfDay().atZone(java.time.ZoneOffset.UTC).toInstant();
    var to = now.plusYears(1).withDayOfYear(1).atStartOfDay().atZone(java.time.ZoneOffset.UTC).toInstant();

    var rows = workOrders.countMonthlyByStatusScoped(unrestricted, plantIds, groupIds,
        plantFilter, NO_PLANT, sectionFilter, NO_SECTION, statusFilter, catFilter, from, to);

    var open = new long[12];
    var closed = new long[12];
    for (var row : rows) {
      if (row.getMonth() < 1 || row.getMonth() > 12) {
        continue;
      }
      if (TERMINAL_STATUSES.contains(row.getStatus())) {
        closed[row.getMonth() - 1] += row.getCount();
      } else {
        open[row.getMonth() - 1] += row.getCount();
      }
    }
    var result = new ArrayList<MonthlyWorkorderCount>(12);
    for (int i = 0; i < 12; i++) {
      result.add(new MonthlyWorkorderCount(i + 1, open[i], closed[i]));
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // Preventive dashboard (FR-172)
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public PreventiveDashboardResponse preventiveDashboard(AuthenticatedUser user, UUID plantId) {
    var scope = scopes.derive(user);
    var unrestricted = scope.plantIds() == null;
    var groupIds = mergeGroupIds(scope.machineGroupIds(), scope.activeTeamIds());

    // Empty-scope guard.
    if (!unrestricted && scope.plantIds().isEmpty() && groupIds.isEmpty()) {
      return new PreventiveDashboardResponse(0L, 0L, List.of());
    }

    var rows = schedules.findScopedSchedulesWithDetails(
        unrestricted, scope.plantIds() == null ? List.of() : scope.plantIds(), groupIds,
        PageRequest.of(0, PREVENTIVE_UPCOMING_LIMIT));
    var today = LocalDate.now(clock);
    long dueCount = 0;
    long overdueCount = 0;
    var upcoming = new ArrayList<PreventiveUpcomingRow>();
    for (var row : rows) {
      if (plantId != null && !plantId.equals(row.plantId())) {
        continue;
      }
      var schedule = row.schedule();
      var derived = schedule.getStatus() == ScheduleStatus.SCHEDULED
          && schedule.getDueDate().isBefore(today)
          ? "OVERDUE"
          : schedule.getStatus().name();
      if ("OVERDUE".equals(derived)) {
        overdueCount++;
      } else if (ScheduleStatus.SCHEDULED.name().equals(derived)) {
        dueCount++;
      }
      upcoming.add(new PreventiveUpcomingRow(
          schedule.getId(), schedule.getMachineId(), schedule.getProgramId(), schedule.getDueDate(),
          schedule.getStatus().name(), derived, row.category().name(), row.scheduleType().name(),
          row.machineCode(), row.machineName(), row.programTitle()));
    }
    upcoming.sort(Comparator.comparing(PreventiveUpcomingRow::dueDate));
    return new PreventiveDashboardResponse(dueCount, overdueCount, upcoming);
  }

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  /**
   * Merges machineGroupIds and activeTeamIds with null guards. Both sets can be
   * null (from {@code Set.copyOf} or {@code Set.of} on null input) or empty.
   */
  private static Set<UUID> mergeGroupIds(Set<UUID> machineGroupIds, Set<UUID> activeTeamIds) {
    var merged = new HashSet<UUID>();
    if (machineGroupIds != null) {
      merged.addAll(machineGroupIds);
    }
    if (activeTeamIds != null) {
      merged.addAll(activeTeamIds);
    }
    return merged;
  }
}