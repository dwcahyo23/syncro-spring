package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code work_logs} (blueprint B4, AD-18, story 15-2). */
public interface WorkLogRepository extends JpaRepository<WorkLogEntity, UUID> {

  List<WorkLogEntity> findByWorkOrderIdOrderByStartTimeAsc(String workOrderId);

  long countByWorkOrderIdAndEndTimeIsNotNull(String workOrderId);

  List<WorkLogEntity> findByWorkOrderIdAndEndTimeIsNotNull(String workOrderId);

  /**
   * Story 20-1 (AD-6): closed-breakdown work-log intervals with both endpoints — the
   * MTTR source. Month attribution (by the workorder's derived woStopAt) happens in
   * the kpi service so the stop map stays the single attribution key.
   */
  @Query("""
      select new com.syncro.maintenance.infrastructure.db.KpiSourceRows$RepairLogRow(
        l.workOrderId, m.plant.id, w.machineId, l.startTime, l.endTime)
      from WorkLogEntity l
      join WorkOrderEntity w on w.id = l.workOrderId
      join WorkOrderCategoryEntity c on c.id = w.categoryId
      join MachineEntity m on m.id = w.machineId
      where c.code = :breakdownCode and w.status = :closedStatus and l.endTime is not null
        and l.endTime > l.startTime
      """)
  List<KpiSourceRows.RepairLogRow> findClosedBreakdownRepairLogIntervals(
      @Param("breakdownCode") String breakdownCode,
      @Param("closedStatus") WorkOrderStatus closedStatus);

  /**
   * Story 20-1 (FR-174): technician work-log attributions with the log started in the
   * window — the technician total_wo / first-time-fix source (plant via the machine).
   */
  @Query("""
      select new com.syncro.maintenance.infrastructure.db.KpiSourceRows$TechnicianLogRow(
        l.technicianId, m.plant.id, w.id)
      from WorkLogEntity l
      join WorkOrderEntity w on w.id = l.workOrderId
      join MachineEntity m on m.id = w.machineId
      where l.startTime >= :from and l.startTime < :to
      """)
  List<KpiSourceRows.TechnicianLogRow> findTechnicianLogAttributions(
      @Param("from") Instant from, @Param("to") Instant to);
}
