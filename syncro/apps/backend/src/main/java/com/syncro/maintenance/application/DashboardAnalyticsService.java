package com.syncro.maintenance.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.config.DashboardAnalyticsProperties;
import com.syncro.maintenance.api.DashboardDtos.MtbfMttrResponse;
import com.syncro.maintenance.api.DashboardDtos.MtbfView;
import com.syncro.maintenance.api.DashboardDtos.MttrView;
import com.syncro.maintenance.api.DashboardDtos.RatingDimensionView;
import com.syncro.maintenance.api.DashboardDtos.TechnicianKpiResponse;
import com.syncro.maintenance.api.DashboardDtos.TechnicianKpiRowView;
import com.syncro.maintenance.infrastructure.DashboardAnalyticsRedisCache;
import com.syncro.maintenance.infrastructure.db.RatingAverageRow;
import com.syncro.maintenance.infrastructure.db.RatingDimensionRepository;
import com.syncro.maintenance.infrastructure.db.TechnicianKpiRow;
import com.syncro.maintenance.infrastructure.db.WorkOrderAnalyticsRow;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingRepository;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Analytics orchestrator for the MTBF/MTTR and Technician KPI dashboards (story 14-2,
 * FR-173/FR-174). Mirrors the {@link DashboardService} structure: scope derive, empty-scope
 * guard, cached compute (read cache, recompute, write cache), dedicated DTO assembly.
 *
 * <p>MTBF is fleet-in-scope and counts ONLY stopped breakdown WOs (category code {@code 01},
 * status PENDING_REVIEW or CLOSED — the repository filters out OPEN/IN_PROGRESS/PENDING_SPAREPART rows). The
 * 30-day window is keyed on the derived {@code woStopAt} (PENDING_REVIEW transition
 * {@code transitionedAt}, {@code updatedAt} fallback when no PENDING_REVIEW row exists) — the same
 * key the repository query uses. Fewer than 2 stopped breakdown WOs → explicit
 * INSUFFICIENT_DATA state.
 *
 * <p>MTTR is the mean of per-WO {@code mttrMinutes} (persisted on the workorder by the
 * repair-session stop path) for completed breakdown WOs in the window.
 *
 * <p>Technician KPI deduplicates by (technician, workorder) from two sources: the
 * workorder's assigned technician and repair-session technicians. Average MTTR is scoped
 * to breakdown workorders only (MTTR is a breakdown metric). Ratings are per-dimension
 * averages from TECHNICIAN-type ratings joined to workorders in scope.
 *
 * <p>Both endpoints are role-gated for the same roles as the sidebar Analytics item
 * (SUPER_ADMIN, MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER,
 * PRODUCTION_LEADER, AUDITOR) — TECHNICIAN/STAFF users get an explicit FORBIDDEN.
 *
 * <p>Cache: look-aside via {@link DashboardAnalyticsRedisCache}, TTL from
 * {@code syncro.dashboard.analytics-ttl} (default PT30M). Redis failure degrades to
 * recompute — never throws. Empty-scope responses return null window bounds (no
 * fabricated window).
 */
@Service
public class DashboardAnalyticsService {

  static final String MTBF_MTTR_CACHE_KEY = "mtbf-mttr";
  static final String TECHNICIAN_KPI_CACHE_KEY = "technician-kpi";
  static final long ANALYTICS_WINDOW_DAYS = 30L;
  static final String BREAKDOWN_CODE = "01";
  static final Set<String> COMPLETED_STATUSES = Set.of("PENDING_REVIEW", "CLOSED");
  static final Set<com.syncro.maintenance.domain.workorder.WorkOrderStatus> STOPPED_STATUSES =
      EnumSet.of(com.syncro.maintenance.domain.workorder.WorkOrderStatus.PENDING_REVIEW,
          com.syncro.maintenance.domain.workorder.WorkOrderStatus.CLOSED);
  static final double MINUTES_TO_HOURS = 60.0;

  private static final Set<ApplicationRole> ANALYTICS_ROLES = EnumSet.of(
      ApplicationRole.SUPER_ADMIN,
      ApplicationRole.MANAGER_MAINTENANCE,
      ApplicationRole.MAINTENANCE_LEADER,
      ApplicationRole.SECTION_LEADER,
      ApplicationRole.PRODUCTION_LEADER,
      ApplicationRole.AUDITOR);

  private final OperationalScopeService scopes;
  private final WorkOrderRepository workOrders;
  private final WorkorderRatingRepository ratings;
  private final RatingDimensionRepository ratingDimensions;
  private final AuthUserRepository users;
  private final DashboardAnalyticsRedisCache cache;
  private final DashboardAnalyticsProperties properties;
  private final Clock clock;

  public DashboardAnalyticsService(OperationalScopeService scopes,
      WorkOrderRepository workOrders,
      WorkorderRatingRepository ratings,
      RatingDimensionRepository ratingDimensions,
      AuthUserRepository users,
      DashboardAnalyticsRedisCache cache,
      DashboardAnalyticsProperties properties,
      Clock clock) {
    this.scopes = scopes;
    this.workOrders = workOrders;
    this.ratings = ratings;
    this.ratingDimensions = ratingDimensions;
    this.users = users;
    this.cache = cache;
    this.properties = properties;
    this.clock = clock;
  }

  /** Role gate (story 14-2 review): the sidebar Analytics item's roles only. */
  static void requireAnalyticsRole(AuthenticatedUser user) {
    if (!ANALYTICS_ROLES.contains(user.applicationRole())) {
      throw new AnalyticsForbiddenException();
    }
  }

  // ---------------------------------------------------------------------------
  // MTBF/MTTR Dashboard (FR-173)
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public MtbfMttrResponse mtbfMttr(AuthenticatedUser user, UUID plantId) {
    requireAnalyticsRole(user);
    var scope = scopes.derive(user);
    var unrestricted = scope.plantIds() == null;
    var groupIds = mergeGroupIds(scope.machineGroupIds(), scope.activeTeamIds());
    var plantIds = scope.plantIds() == null ? List.<UUID>of() : scope.plantIds();

    // Empty-scope guard: restricted user with empty plantIds AND empty groupIds.
    if (!unrestricted && plantIds.isEmpty() && groupIds.isEmpty()) {
      return emptyMtbfMttr();
    }

    var now = Instant.now(clock);
    var windowTo = now;
    var windowFrom = now.minus(Duration.ofDays(ANALYTICS_WINDOW_DAYS));
    var scopeKey = scopeKey(user.id(), plantId);

    // Try cache.
    var cached = cache.get(MTBF_MTTR_CACHE_KEY, scopeKey, MtbfMttrResponse.class);
    if (cached.isPresent()) {
      var payload = cached.get();
      var cacheAge = Duration.between(payload.computedAt(), now).toMillis();
      if (cacheAge < properties.analyticsTtl().toMillis()) {
        return new MtbfMttrResponse(
            payload.mtbf(), payload.mttr(),
            payload.windowFrom(), payload.windowTo(),
            payload.computedAt(), cacheAge, false);
      }
      // Stale — recompute below.
    }

    // Compute. The repository already filters to stopped breakdowns (PENDING_REVIEW/CLOSED) and
    // keys the window on the derived stop time.
    var rows = workOrders.findStoppedBreakdownAnalyticsRows(
        BREAKDOWN_CODE, STOPPED_STATUSES, unrestricted, plantIds, groupIds, windowFrom, windowTo);

    // Apply plantId filter if specified.
    if (plantId != null) {
      rows = rows.stream()
          .filter(r -> plantId.equals(r.plantId()))
          .toList();
    }

    // Rows are already ordered by the derived stop time in SQL; re-sort defensively.
    var sorted = rows.stream()
        .sorted(Comparator.comparing(WorkOrderAnalyticsRow::woStopAt))
        .toList();

    var mtbf = computeMtbf(sorted);
    var mttr = computeMttr(sorted);

    var response = new MtbfMttrResponse(
        mtbf, mttr, windowFrom, windowTo, now, null, false);

    // Write cache.
    cache.put(MTBF_MTTR_CACHE_KEY, scopeKey, response);
    return response;
  }

  private MtbfMttrResponse emptyMtbfMttr() {
    return new MtbfMttrResponse(
        new MtbfView("INSUFFICIENT_DATA", null, 0),
        new MttrView("INSUFFICIENT_DATA", null, 0),
        null, null, Instant.now(clock), null, false);
  }

  /**
   * MTBF = mean interval between consecutive stops (fleet-in-scope, ordered by woStopAt).
   * Requires at least 2 stopped breakdown WOs; returns INSUFFICIENT_DATA otherwise.
   */
  static MtbfView computeMtbf(List<WorkOrderAnalyticsRow> rows) {
    if (rows.size() < 2) {
      return new MtbfView("INSUFFICIENT_DATA", null, rows.size());
    }
    long totalGapMinutes = 0;
    int gaps = 0;
    for (int i = 1; i < rows.size(); i++) {
      var gap = Duration.between(rows.get(i - 1).woStopAt(), rows.get(i).woStopAt()).toMinutes();
      if (gap > 0) {
        totalGapMinutes += gap;
        gaps++;
      }
    }
    if (gaps == 0) {
      return new MtbfView("INSUFFICIENT_DATA", null, rows.size());
    }
    var mtbfMinutes = (double) totalGapMinutes / gaps;
    return new MtbfView("AVAILABLE", mtbfMinutes / MINUTES_TO_HOURS, rows.size());
  }

  /**
   * MTTR = mean of per-WO mttrMinutes for completed breakdown WOs (status PENDING_REVIEW or CLOSED)
   * that have a persisted mttrMinutes value. Returns INSUFFICIENT_DATA when no completed
   * breakdown WOs with mttrMinutes exist.
   */
  static MttrView computeMttr(List<WorkOrderAnalyticsRow> rows) {
    var completedWithMttr = rows.stream()
        .filter(r -> COMPLETED_STATUSES.contains(r.status().name())
            && r.mttrMinutes() != null && r.mttrMinutes() > 0)
        .toList();
    if (completedWithMttr.isEmpty()) {
      return new MttrView("INSUFFICIENT_DATA", null, 0);
    }
    var totalMttr = completedWithMttr.stream()
        .mapToLong(WorkOrderAnalyticsRow::mttrMinutes)
        .sum();
    var meanMinutes = (double) totalMttr / completedWithMttr.size();
    return new MttrView("AVAILABLE", meanMinutes / MINUTES_TO_HOURS, completedWithMttr.size());
  }

  // ---------------------------------------------------------------------------
  // Technician KPI Dashboard (FR-174)
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public TechnicianKpiResponse technicianKpi(AuthenticatedUser user, UUID plantId) {
    requireAnalyticsRole(user);
    var scope = scopes.derive(user);
    var unrestricted = scope.plantIds() == null;
    var groupIds = mergeGroupIds(scope.machineGroupIds(), scope.activeTeamIds());
    var plantIds = scope.plantIds() == null ? List.<UUID>of() : scope.plantIds();

    // Empty-scope guard.
    if (!unrestricted && plantIds.isEmpty() && groupIds.isEmpty()) {
      return emptyTechnicianKpi();
    }

    var now = Instant.now(clock);
    var windowTo = now;
    var windowFrom = now.minus(Duration.ofDays(ANALYTICS_WINDOW_DAYS));
    var scopeKey = scopeKey(user.id(), plantId);

    // Try cache.
    var cached = cache.get(TECHNICIAN_KPI_CACHE_KEY, scopeKey, TechnicianKpiResponse.class);
    if (cached.isPresent()) {
      var payload = cached.get();
      var cacheAge = Duration.between(payload.computedAt(), now).toMillis();
      if (cacheAge < properties.analyticsTtl().toMillis()) {
        return new TechnicianKpiResponse(
            payload.technicians(), payload.windowFrom(), payload.windowTo(),
            payload.computedAt(), cacheAge, false);
      }
    }

    // Compute objective KPIs from two sources (assigned + session technicians).
    var fromAssigned = workOrders.findTechnicianObjectiveRows(
        unrestricted, plantIds, groupIds, windowFrom, windowTo);
    var fromSessions = workOrders.findTechnicianObjectiveRowsFromSessions(
        unrestricted, plantIds, groupIds, windowFrom, windowTo);

    // Apply plantId filter if specified.
    if (plantId != null) {
      fromAssigned = fromAssigned.stream()
          .filter(r -> plantId.equals(r.plantId()))
          .toList();
      fromSessions = fromSessions.stream()
          .filter(r -> plantId.equals(r.plantId()))
          .toList();
    }

    // Deduplicate by (technician, workorder) — merge both sources.
    var deduped = new HashMap<String, TechnicianKpiRow>();
    for (var row : fromAssigned) {
      deduped.put(dedupKey(row.technicianId(), row.workOrderId()), row);
    }
    for (var row : fromSessions) {
      deduped.putIfAbsent(dedupKey(row.technicianId(), row.workOrderId()), row);
    }

    // Group by technician.
    var rowsByTech = new HashMap<UUID, List<TechnicianKpiRow>>();
    for (var row : deduped.values()) {
      rowsByTech.computeIfAbsent(row.technicianId(), k -> new ArrayList<>()).add(row);
    }

    // Collect all workorder ids for the rating query.
    var allWoIds = deduped.values().stream()
        .map(TechnicianKpiRow::workOrderId)
        .collect(Collectors.toSet());

    // Resolve technician names. A technician with no auth_users row falls back to the
    // raw UUID (sync-attributed session technicians may not exist locally).
    var techIds = rowsByTech.keySet();
    var nameById = users.findByIds(techIds).stream()
        .collect(Collectors.toMap(
            u -> u.getId(),
            u -> u.getDisplayName() != null ? u.getDisplayName() : u.getLoginIdentifier(),
            (a, b) -> a));

    // Fetch rating averages for all technicians in scope.
    var ratingsByTech = ratings.findRatingAveragesByWorkorderIds(allWoIds).stream()
        .collect(Collectors.groupingBy(RatingAverageRow::ratedUserId));

    // Fetch all dimensions for the rating dimension view.
    var dimById = ratingDimensions.findAllByOrderBySortOrderAsc().stream()
        .collect(Collectors.toMap(d -> d.getId(), d -> d, (a, b) -> a));

    // Assemble technician rows.
    var techRows = new ArrayList<TechnicianKpiRowView>();
    for (var entry : rowsByTech.entrySet()) {
      var techId = entry.getKey();
      var woRows = entry.getValue();
      var name = nameById.getOrDefault(techId, techId.toString());

      long completedCount = woRows.stream()
          .filter(r -> COMPLETED_STATUSES.contains(r.status().name()))
          .count();
      Double avgMttrHours = computeAverageMttrHours(woRows);
      Double onTimePct = computeOnTimePercentage(woRows);

      // Ratings.
      var ratingViews = new ArrayList<RatingDimensionView>();
      for (var r : ratingsByTech.getOrDefault(techId, List.of())) {
        var dim = dimById.get(r.dimensionId());
        if (dim != null) {
          ratingViews.add(new RatingDimensionView(
              dim.getId(), dim.getCode(), dim.getLabel(), r.averageScore()));
        }
      }
      ratingViews.sort(Comparator.comparing(RatingDimensionView::dimensionCode));

      techRows.add(new TechnicianKpiRowView(techId, name, completedCount, avgMttrHours, onTimePct, ratingViews));
    }
    techRows.sort(Comparator.comparing(TechnicianKpiRowView::technicianName));

    var response = new TechnicianKpiResponse(techRows, windowFrom, windowTo, now, null, false);
    cache.put(TECHNICIAN_KPI_CACHE_KEY, scopeKey, response);
    return response;
  }

  private TechnicianKpiResponse emptyTechnicianKpi() {
    return new TechnicianKpiResponse(List.of(), null, null, Instant.now(clock), null, false);
  }

  /**
   * Average MTTR hours for a technician's BREAKDOWN workorders (MTTR is a breakdown
   * metric — story 14-2 review). Only rows with category code {@code 01} AND a persisted
   * mttrMinutes are included; returns null when none qualify.
   */
  static Double computeAverageMttrHours(List<TechnicianKpiRow> rows) {
    var breakdownWithMttr = rows.stream()
        .filter(r -> BREAKDOWN_CODE.equals(r.categoryCode())
            && r.mttrMinutes() != null && r.mttrMinutes() > 0)
        .toList();
    if (breakdownWithMttr.isEmpty()) {
      return null;
    }
    var total = breakdownWithMttr.stream().mapToLong(TechnicianKpiRow::mttrMinutes).sum();
    return (total / (double) breakdownWithMttr.size()) / MINUTES_TO_HOURS;
  }

  /**
   * On-time %: percentage of workorders where BOTH responseTimeMinutes and
   * targetResponseMinutes are present, and responseTimeMinutes <= targetResponseMinutes.
   * WOs without either value are excluded from both numerator and denominator.
   */
  static Double computeOnTimePercentage(List<TechnicianKpiRow> rows) {
    var countable = rows.stream()
        .filter(r -> r.responseTimeMinutes() != null && r.targetResponseMinutes() != null)
        .toList();
    if (countable.isEmpty()) {
      return null;
    }
    var onTime = countable.stream()
        .filter(r -> r.responseTimeMinutes() <= r.targetResponseMinutes())
        .count();
    return (double) onTime / countable.size() * 100.0;
  }

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  private static String dedupKey(UUID technicianId, String workOrderId) {
    return technicianId + ":" + workOrderId;
  }

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

  private static String scopeKey(String userId, UUID plantId) {
    return userId + ":" + (plantId == null ? "all" : plantId.toString());
  }

  /** Role-denied gate for the analytics endpoints (story 14-2 review). */
  public static class AnalyticsForbiddenException extends RuntimeException {
  }
}