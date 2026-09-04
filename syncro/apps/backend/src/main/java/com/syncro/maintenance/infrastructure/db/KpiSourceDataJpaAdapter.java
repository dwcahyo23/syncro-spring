package com.syncro.maintenance.infrastructure.db;

import com.syncro.kpi.application.KpiSourceDataReader;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.preventive.infrastructure.db.PmExecutionRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for the KPI materialization source port (story 20-1, AD-6/AD-20).
 * Lives in the maintenance module — the kpi module never touches these repositories
 * directly (modular-monolith invariant); it consumes {@link KpiSourceDataReader}.
 *
 * <p>Raw rows cross the boundary as plain records; every KPI formula lives in
 * {@code KpiMaterializationService}. The breakdown-stop read derives {@code woStopAt}
 * exactly like story 14-2 (latest PENDING_REVIEW transition, {@code updatedAt}
 * fallback for the sync edge case) so MTBF ordering is by stop time, never by id.
 *
 * <p>MAR: no persisted per-plant telemetry availability source exists yet (AD-12 —
 * machines may run without telemetry), so {@link #findMarInputs} returns empty and
 * the service writes an explicit INSUFFICIENT_DATA row — never a fabricated value.
 */
@Component
public class KpiSourceDataJpaAdapter implements KpiSourceDataReader {

  static final String BREAKDOWN_CODE = "01";

  private final WorkOrderRepository workOrders;
  private final WorkLogRepository workLogs;
  private final WorkLogRatingRepository workLogRatings;
  private final PmWorkOrderRepository pmWorkOrders;
  private final PmExecutionRepository pmExecutions;
  private final com.syncro.auth.infrastructure.PlantRepository plants;
  private final com.syncro.machine.infrastructure.MachineRepository machines;

  public KpiSourceDataJpaAdapter(WorkOrderRepository workOrders, WorkLogRepository workLogs,
      WorkLogRatingRepository workLogRatings, PmWorkOrderRepository pmWorkOrders,
      PmExecutionRepository pmExecutions, com.syncro.auth.infrastructure.PlantRepository plants,
      com.syncro.machine.infrastructure.MachineRepository machines) {
    this.workOrders = workOrders;
    this.workLogs = workLogs;
    this.workLogRatings = workLogRatings;
    this.pmWorkOrders = pmWorkOrders;
    this.pmExecutions = pmExecutions;
    this.plants = plants;
    this.machines = machines;
  }

  @Override
  @Transactional(readOnly = true)
  public List<BreakdownStop> findBreakdownStops(Instant from, Instant to) {
    return workOrders.findBreakdownStops(BREAKDOWN_CODE, WorkOrderStatus.CLOSED).stream()
        .filter(r -> !r.woStopAt().isBefore(from) && r.woStopAt().isBefore(to))
        .map(r -> new BreakdownStop(r.workOrderId(), r.machineId(), r.plantId(), r.woStopAt()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<RepairLogInterval> findClosedBreakdownRepairLogs() {
    return workLogs.findClosedBreakdownRepairLogIntervals(BREAKDOWN_CODE, WorkOrderStatus.CLOSED)
        .stream()
        .map(r -> new RepairLogInterval(r.workOrderId(), r.plantId(), r.machineId(),
            r.startTime(), r.endTime()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<TechnicianLog> findTechnicianLogs(Instant from, Instant to) {
    return workLogs.findTechnicianLogAttributions(from, to).stream()
        .map(r -> new TechnicianLog(r.technicianId(), r.plantId(), r.workOrderId()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<TechnicianRating> findTechnicianRatings(Instant from, Instant to) {
    return workLogRatings.findTechnicianRatingScores(from, to).stream()
        .map(r -> new TechnicianRating(r.technicianId(), r.plantId(), r.workOrderId(), r.score()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public long countPmPlanned(UUID plantId, LocalDate monthFrom, LocalDate monthTo) {
    return pmWorkOrders.countPlannedForPlantInMonth(plantId, monthFrom, monthTo);
  }

  @Override
  @Transactional(readOnly = true)
  public long countPmCompleted(UUID plantId, LocalDate monthFrom, LocalDate monthTo) {
    return pmExecutions.countCompletedForPlantInMonth(plantId, monthFrom, monthTo);
  }

  @Override
  public Optional<MarInputs> findMarInputs(UUID plantId, Instant from, Instant to) {
    // No persisted telemetry availability source exists (AD-12: preventive/workorders
    // run without telemetry). Explicit insufficient-data — never a fabricated zero.
    return Optional.empty();
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> findAllPlantIds() {
    return plants.findAll().stream().map(p -> p.getId()).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> findMachineIdsByGroupIds(List<UUID> groupIds) {
    return groupIds.isEmpty() ? List.of() : machines.findIdsByMachineGroupIdIn(groupIds);
  }
}
