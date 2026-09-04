package com.syncro.kpi.application;

import com.syncro.authz.application.PolicyDecisionPoint;
import com.syncro.kpi.domain.KpiAggregateRefreshStatus;
import com.syncro.kpi.domain.KpiSourceStatus;
import com.syncro.kpi.domain.KpiType;
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
import com.syncro.kpi.infrastructure.db.KpiTechnicianMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiTechnicianMonthlyRepository;
import com.syncro.kpi.application.KpiSourceDataReader.BreakdownStop;
import com.syncro.kpi.application.KpiSourceDataReader.MarInputs;
import com.syncro.kpi.application.KpiSourceDataReader.RepairLogInterval;
import com.syncro.kpi.application.KpiSourceDataReader.TechnicianLog;
import com.syncro.kpi.application.KpiSourceDataReader.TechnicianRating;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Monthly KPI materialization (story 20-1, AD-6/AD-20). Recomputes MTBF, MTTR, MAR,
 * PM completion, technician, and breakdown-count rows for one calendar month and
 * upserts them per month/scope — idempotent by the V1 unique constraints. Every pass
 * is gated and evidenced through {@link KpiRefreshGate} (refresh_key per type/month,
 * traceId + row count in the message).
 *
 * <p>Formulas (spec "Always" boundaries):
 * <ul>
 *   <li>MTBF — per machine, consecutive CLOSED breakdown stops ordered by the derived
 *       {@code woStopAt} (never by id — the reference bug); mean gap in days, each gap
 *       attributed to the month of its later stop. Machines with fewer than 2 stops
 *       get no row (explicit insufficient-data downstream).</li>
 *   <li>MTTR — per plant/month, mean of per-WO cumulative work-log durations;
 *       wall-clock = calendar minutes, actual-working = business minutes per the plant
 *       working calendar. NULL values when no logs exist.</li>
 *   <li>MAR — (planned_available - downtime) / planned_available × 100 from the
 *       telemetry ingest state; no source → INSUFFICIENT_DATA row, never a zero.</li>
 *   <li>PM completion — completed/planned × 100 from PM execution data; no planned
 *       → INSUFFICIENT_DATA.</li>
 *   <li>Technician — average of 1–5 work-log ratings, distinct workorder count, and
 *       first-time-fix rate (WOs with exactly one work log / total).</li>
 * </ul>
 */
@Service
public class KpiMaterializationService {

  private static final Logger log = LoggerFactory.getLogger(KpiMaterializationService.class);
  private static final int SCALE = 2;
  private static final BigDecimal MINUTES_PER_DAY = BigDecimal.valueOf(24L * 60L);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);

  private final KpiSourceDataReader source;
  private final KpiCalendarReader calendar;
  private final KpiRefreshGate gate;
  private final KpiMonthlyBreakdownRepository breakdowns;
  private final KpiMtbfMonthlyRepository mtbfMonthlies;
  private final KpiMttrMonthlyRepository mttrMonthlies;
  private final KpiMarMonthlyRepository marMonthlies;
  private final KpiTechnicianMonthlyRepository technicianMonthlies;
  private final KpiPmCompletionMonthlyRepository pmCompletionMonthlies;
  private final Clock clock;
  /**
   * The compute runs through a TransactionTemplate (not {@code @Transactional}) because
   * {@link #refreshType} calls it internally — a proxy annotation would be bypassed by
   * self-invocation (NotificationDispatchService pattern).
   */
  private final TransactionTemplate transactionTemplate;

  public KpiMaterializationService(KpiSourceDataReader source, KpiCalendarReader calendar,
      KpiRefreshGate gate, KpiMonthlyBreakdownRepository breakdowns,
      KpiMtbfMonthlyRepository mtbfMonthlies, KpiMttrMonthlyRepository mttrMonthlies,
      KpiMarMonthlyRepository marMonthlies, KpiTechnicianMonthlyRepository technicianMonthlies,
      KpiPmCompletionMonthlyRepository pmCompletionMonthlies, Clock clock,
      PlatformTransactionManager transactionManager) {
    this.source = source;
    this.calendar = calendar;
    this.gate = gate;
    this.breakdowns = breakdowns;
    this.mtbfMonthlies = mtbfMonthlies;
    this.mttrMonthlies = mttrMonthlies;
    this.marMonthlies = marMonthlies;
    this.technicianMonthlies = technicianMonthlies;
    this.pmCompletionMonthlies = pmCompletionMonthlies;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /** Outcome of one refresh pass for one KPI type/month. */
  public record RefreshOutcome(KpiType type, LocalDate month, KpiAggregateRefreshStatus status,
      String message) {

    static RefreshOutcome skipped(KpiType type, LocalDate month) {
      return new RefreshOutcome(type, month, KpiAggregateRefreshStatus.RUNNING,
          "refresh already in flight for " + type.refreshKey(month));
    }
  }

  /** Refreshes every KPI type for the month; each type gets its own gated pass. */
  public List<RefreshOutcome> refreshMonth(LocalDate month) {
    var outcomes = new ArrayList<RefreshOutcome>();
    for (var type : KpiType.values()) {
      outcomes.add(refreshType(type, month));
    }
    return outcomes;
  }

  /**
   * One gated refresh pass: RUNNING blocks a concurrent same-key pass; SUCCESS/FAILED
   * transitions are logged with traceId + row-count evidence. The compute runs in a
   * transaction so a failure rolls back partial upserts while the FAILED marker
   * (REQUIRES_NEW) survives.
   */
  public RefreshOutcome refreshType(KpiType type, LocalDate month) {
    var key = type.refreshKey(month);
    if (!gate.tryStart(key)) {
      log.info("[KPI] refresh {} already RUNNING — skipping concurrent pass", key);
      return RefreshOutcome.skipped(type, month);
    }
    var traceId = PolicyDecisionPoint.currentTraceId();
    try {
      var rows = transactionTemplate.execute(status -> compute(type, month));
      var message = "traceId=" + traceId + " rows=" + rows;
      gate.finish(key, KpiAggregateRefreshStatus.SUCCESS, message);
      log.info("[KPI] refresh {} SUCCESS {}", key, message);
      return new RefreshOutcome(type, month, KpiAggregateRefreshStatus.SUCCESS, message);
    } catch (RuntimeException exception) {
      var message = "traceId=" + traceId + " error=" + safeMessage(exception);
      gate.finish(key, KpiAggregateRefreshStatus.FAILED, message);
      log.error("[KPI] refresh {} FAILED {}", key, message, exception);
      return new RefreshOutcome(type, month, KpiAggregateRefreshStatus.FAILED, message);
    }
  }

  private int compute(KpiType type, LocalDate month) {
    var from = month.atStartOfDay().toInstant(ZoneOffset.UTC);
    var to = month.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    return switch (type) {
      case BREAKDOWN -> refreshBreakdown(month, from, to);
      case MTBF -> refreshMtbf(month, from, to);
      case MTTR -> refreshMttr(month, from, to);
      case MAR -> refreshMar(month, from, to);
      case PM_COMPLETION -> refreshPmCompletion(month, from, to);
      case TECHNICIAN -> refreshTechnician(month, from, to);
    };
  }

  // ---------------------------------------------------------------------------
  // Per-type refreshes
  // ---------------------------------------------------------------------------

  private int refreshBreakdown(LocalDate month, Instant from, Instant to) {
    var stops = source.findBreakdownStops(from, to);
    var countByPlant = new LinkedHashMap<UUID, Integer>();
    for (var stop : stops) {
      countByPlant.merge(stop.plantId(), 1, Integer::sum);
    }
    var now = Instant.now(clock);
    int rows = 0;
    for (var plantId : source.findAllPlantIds()) {
      var count = countByPlant.getOrDefault(plantId, 0);
      var existing = breakdowns.findByPlantIdAndMonth(plantId, month);
      var entity = new KpiMonthlyBreakdownEntity(
          existing.map(KpiMonthlyBreakdownEntity::getId).orElseGet(UUID::randomUUID),
          plantId, month, count,
          existing.map(KpiMonthlyBreakdownEntity::getCreatedAt).orElse(now), now);
      breakdowns.saveAndFlush(entity);
      rows++;
    }
    return rows;
  }

  private int refreshMtbf(LocalDate month, Instant from, Instant to) {
    // Full stop history per machine (window only selects the month's gaps); ordering
    // by the derived woStopAt is the spec's explicit fix for the reference id-ordering bug.
    var allStops = source.findBreakdownStops(Instant.EPOCH, to);
    var byMachine = new LinkedHashMap<UUID, List<BreakdownStop>>();
    var machinePlant = new HashMap<UUID, UUID>();
    for (var stop : allStops) {
      byMachine.computeIfAbsent(stop.machineId(), k -> new ArrayList<>()).add(stop);
      machinePlant.put(stop.machineId(), stop.plantId());
    }
    var now = Instant.now(clock);
    int rows = 0;
    var computedMachines = new HashSet<UUID>();
    for (var entry : byMachine.entrySet()) {
      var stops = entry.getValue().stream()
          .sorted(Comparator.comparing(BreakdownStop::woStopAt))
          .toList();
      if (stops.size() < 2) {
        continue; // explicit insufficient-data: no row, never a fabricated value
      }
      BigDecimal totalDays = BigDecimal.ZERO;
      int gaps = 0;
      for (int i = 1; i < stops.size(); i++) {
        var gapMinutes = Duration.between(stops.get(i - 1).woStopAt(), stops.get(i).woStopAt()).toMinutes();
        if (gapMinutes <= 0) {
          continue;
        }
        var gapMonth = LocalDate.ofInstant(stops.get(i).woStopAt(), ZoneOffset.UTC)
            .withDayOfMonth(1);
        if (gapMonth.equals(month)) {
          totalDays = totalDays.add(BigDecimal.valueOf(gapMinutes).divide(MINUTES_PER_DAY, 6, RoundingMode.HALF_UP));
          gaps++;
        }
      }
      if (gaps == 0) {
        continue;
      }
      var mtbfDays = totalDays.divide(BigDecimal.valueOf(gaps), SCALE, RoundingMode.HALF_UP);
      var machineId = entry.getKey();
      var existing = mtbfMonthlies.findByMachineIdAndMonth(machineId, month);
      var entity = new KpiMtbfMonthlyEntity(
          existing.map(KpiMtbfMonthlyEntity::getId).orElseGet(UUID::randomUUID),
          machinePlant.get(machineId), machineId, month, mtbfDays,
          existing.map(KpiMtbfMonthlyEntity::getCreatedAt).orElse(now), now);
      mtbfMonthlies.saveAndFlush(entity);
      computedMachines.add(machineId);
      rows++;
    }
    // Review 20-1: a machine that dropped below two stops after a data correction must
    // not keep serving a stale row — delete month rows absent from the computed set.
    mtbfMonthlies.findByMonth(month).stream()
        .filter(r -> !computedMachines.contains(r.getMachineId()))
        .forEach(mtbfMonthlies::delete);
    return rows;
  }

  private int refreshMttr(LocalDate month, Instant from, Instant to) {
    var stops = source.findBreakdownStops(Instant.EPOCH, to);
    var stopByWorkOrder = new HashMap<String, BreakdownStop>();
    for (var stop : stops) {
      stopByWorkOrder.put(stop.workOrderId(), stop);
    }
    // Cumulative per workorder FIRST (spec: MTTR = cumulative work-log durations per
    // workorder), then the mean across the plant's workorders stopped in this month.
    var wallByWorkOrder = new LinkedHashMap<String, BigDecimal>();
    var workingByWorkOrder = new LinkedHashMap<String, BigDecimal>();
    for (var interval : source.findClosedBreakdownRepairLogs()) {
      var stop = stopByWorkOrder.get(interval.workOrderId());
      if (stop == null || !stop.woStopAt().isBefore(to)
          || !LocalDate.ofInstant(stop.woStopAt(), ZoneOffset.UTC).withDayOfMonth(1).equals(month)) {
        continue;
      }
      var wall = BigDecimal.valueOf(
          Duration.between(interval.startTime(), interval.endTime()).toMinutes());
      // Review 20-1: the v1 calendar counts whole working days, so business minutes can
      // exceed the interval's wall duration — clamp working <= wall per log.
      var working = BigDecimal.valueOf(
          calendar.workingMinutes(interval.plantId(), interval.startTime(), interval.endTime()))
          .min(wall);
      wallByWorkOrder.merge(interval.workOrderId(), wall, BigDecimal::add);
      workingByWorkOrder.merge(interval.workOrderId(), working, BigDecimal::add);
    }
    var wallByPlant = new HashMap<UUID, List<BigDecimal>>();
    var workingByPlant = new HashMap<UUID, List<BigDecimal>>();
    for (var entry : wallByWorkOrder.entrySet()) {
      var plantId = stopByWorkOrder.get(entry.getKey()).plantId();
      wallByPlant.computeIfAbsent(plantId, k -> new ArrayList<>()).add(entry.getValue());
      workingByPlant.computeIfAbsent(plantId, k -> new ArrayList<>())
          .add(workingByWorkOrder.get(entry.getKey()));
    }
    var now = Instant.now(clock);
    int rows = 0;
    for (var plantId : source.findAllPlantIds()) {
      BigDecimal wallMean = mean(wallByPlant.get(plantId));
      BigDecimal workingMean = mean(workingByPlant.get(plantId));
      var existing = mttrMonthlies.findByPlantIdAndMonth(plantId, month);
      var entity = new KpiMttrMonthlyEntity(
          existing.map(KpiMttrMonthlyEntity::getId).orElseGet(UUID::randomUUID),
          plantId, month, wallMean, workingMean,
          existing.map(KpiMttrMonthlyEntity::getCreatedAt).orElse(now), now);
      mttrMonthlies.saveAndFlush(entity);
      rows++;
    }
    return rows;
  }

  private int refreshMar(LocalDate month, Instant from, Instant to) {
    var now = Instant.now(clock);
    int rows = 0;
    for (var plantId : source.findAllPlantIds()) {
      var inputs = source.findMarInputs(plantId, from, to);
      BigDecimal marPercent = null;
      String sourceStatus;
      String sourceMessage;
      long planned = 0L;
      long downtime = 0L;
      if (inputs.isPresent() && inputs.get().plannedAvailableMinutes() > 0) {
        var in = inputs.get();
        planned = in.plannedAvailableMinutes();
        downtime = in.downtimeMinutes();
        // Review 20-1: downtime exceeding planned (bad telemetry) must not persist a
        // negative availability ratio — clamp to [0, 100].
        marPercent = BigDecimal.valueOf(Math.max(0L, planned - downtime))
            .multiply(HUNDRED)
            .divide(BigDecimal.valueOf(planned), SCALE, RoundingMode.HALF_UP)
            .min(HUNDRED);
        sourceStatus = KpiSourceStatus.COMPLETE.name();
        sourceMessage = null;
      } else {
        sourceStatus = KpiSourceStatus.INSUFFICIENT_DATA.name();
        sourceMessage = inputs.isEmpty()
            ? "no telemetry availability source for month " + month
            : "planned available minutes is zero for month " + month;
      }
      var existing = marMonthlies.findByPlantIdAndMonth(plantId, month);
      var entity = new KpiMarMonthlyEntity(
          existing.map(KpiMarMonthlyEntity::getId).orElseGet(UUID::randomUUID),
          plantId, month, clampInt(planned), clampInt(downtime), marPercent, sourceStatus,
          sourceMessage, existing.map(KpiMarMonthlyEntity::getCreatedAt).orElse(now), now);
      marMonthlies.saveAndFlush(entity);
      rows++;
    }
    return rows;
  }

  private int refreshPmCompletion(LocalDate month, Instant from, Instant to) {
    var now = Instant.now(clock);
    int rows = 0;
    for (var plantId : source.findAllPlantIds()) {
      var planned = source.countPmPlanned(plantId, month, month.plusMonths(1));
      var completed = source.countPmCompleted(plantId, month, month.plusMonths(1));
      BigDecimal rate = null;
      String sourceStatus;
      String sourceMessage;
      if (planned == 0) {
        sourceStatus = KpiSourceStatus.INSUFFICIENT_DATA.name();
        sourceMessage = "no planned PM workorders for month " + month;
      } else {
        // Review 20-1: completed executions can exceed planned when a WO scheduled in a
        // prior month completes in this one — clamp the rate to 100.
        rate = BigDecimal.valueOf(completed).multiply(HUNDRED)
            .divide(BigDecimal.valueOf(planned), SCALE, RoundingMode.HALF_UP)
            .min(HUNDRED);
        sourceStatus = KpiSourceStatus.COMPLETE.name();
        sourceMessage = null;
      }
      var existing = pmCompletionMonthlies.findByPlantIdAndMonth(plantId, month);
      var entity = new KpiPmCompletionMonthlyEntity(
          existing.map(KpiPmCompletionMonthlyEntity::getId).orElseGet(UUID::randomUUID),
          plantId, month, rate, clampInt(completed), clampInt(planned), sourceStatus,
          sourceMessage, existing.map(KpiPmCompletionMonthlyEntity::getCreatedAt).orElse(now), now);
      pmCompletionMonthlies.saveAndFlush(entity);
      rows++;
    }
    return rows;
  }

  private int refreshTechnician(LocalDate month, Instant from, Instant to) {
    var logs = source.findTechnicianLogs(from, to);
    var ratings = source.findTechnicianRatings(from, to);

    // total_wo: distinct workorders per (plant, technician); logCount per workorder for FTF.
    // Review 20-1: keyed by (plant, technician) — a technician working in two plants gets
    // one row per plant (matching the uq_kpi_technician_monthlies constraint), never a
    // first-plant-wins fold.
    var wosByTechPlant = new LinkedHashMap<UUID, Map<UUID, Set<String>>>();
    var logCountByWorkOrder = new HashMap<String, Integer>();
    for (var row : logs) {
      wosByTechPlant.computeIfAbsent(row.technicianId(), k -> new LinkedHashMap<>())
          .computeIfAbsent(row.plantId(), k -> new HashSet<>()).add(row.workOrderId());
      logCountByWorkOrder.merge(row.workOrderId(), 1, Integer::sum);
    }
    var ratingSumByTechPlant = new HashMap<UUID, Map<UUID, long[]>>();
    for (var row : ratings) {
      var byPlant = ratingSumByTechPlant.computeIfAbsent(row.technicianId(), k -> new HashMap<>());
      var acc = byPlant.computeIfAbsent(row.plantId(), k -> new long[2]);
      acc[0] += row.score();
      acc[1] += 1;
    }

    var now = Instant.now(clock);
    int rows = 0;
    var computedKeys = new HashSet<String>();
    for (var techEntry : wosByTechPlant.entrySet()) {
      var technicianId = techEntry.getKey();
      for (var plantEntry : techEntry.getValue().entrySet()) {
        var plantId = plantEntry.getKey();
        var wos = plantEntry.getValue();
        BigDecimal averageRating = null;
        var acc = ratingSumByTechPlant.getOrDefault(technicianId, Map.of()).get(plantId);
        if (acc != null && acc[1] > 0) {
          averageRating = BigDecimal.valueOf(acc[0]).divide(BigDecimal.valueOf(acc[1]),
              SCALE, RoundingMode.HALF_UP);
        }
        var firstTimeFix = wos.stream().filter(wo -> logCountByWorkOrder.getOrDefault(wo, 0) == 1).count();
        BigDecimal ftfRate = wos.isEmpty() ? null
            : BigDecimal.valueOf(firstTimeFix).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(wos.size()), SCALE, RoundingMode.HALF_UP);
        var existing = technicianMonthlies.findByPlantIdAndTechnicianIdAndMonth(plantId, technicianId, month);
        var entity = new KpiTechnicianMonthlyEntity(
            existing.map(KpiTechnicianMonthlyEntity::getId).orElseGet(UUID::randomUUID),
            plantId, technicianId, month, averageRating, wos.size(), ftfRate,
            existing.map(KpiTechnicianMonthlyEntity::getCreatedAt).orElse(now), now);
        technicianMonthlies.saveAndFlush(entity);
        computedKeys.add(plantId + ":" + technicianId);
        rows++;
      }
    }
    // Review 20-1: delete month rows whose (plant, technician) is absent from the
    // computed set — a technician whose logs were corrected away must not keep a stale row.
    technicianMonthlies.findByMonth(month).stream()
        .filter(r -> !computedKeys.contains(r.getPlantId() + ":" + r.getTechnicianId()))
        .forEach(technicianMonthlies::delete);
    return rows;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static BigDecimal mean(List<BigDecimal> values) {
    if (values == null || values.isEmpty()) {
      return null; // explicit insufficient-data downstream, never a fabricated zero
    }
    var total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    return total.divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
  }

  private static int clampInt(long value) {
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
  }

  private static String safeMessage(RuntimeException exception) {
    // Review 20-1: the refresh-log message is returned by the materialized-read API —
    // store only the exception class name; the full detail goes to the server log.
    return exception.getClass().getSimpleName();
  }
}
