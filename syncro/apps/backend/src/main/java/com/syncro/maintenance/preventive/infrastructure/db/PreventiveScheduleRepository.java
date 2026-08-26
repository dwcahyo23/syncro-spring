package com.syncro.maintenance.preventive.infrastructure.db;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PreventiveScheduleRepository extends JpaRepository<PreventiveScheduleEntity, UUID> {

  List<PreventiveScheduleEntity> findByProgramIdOrderByDueDateAsc(UUID programId);

  boolean existsByProgramIdAndDueDate(UUID programId, LocalDate dueDate);

  /**
   * Story 11-1 calendar read: every schedule visible in the derived scope (plant OR
   * machine-group) LEFT JOINed with its program and the machine's plant/group so the
   * application layer can compute derived status and attach shift context without N+1.
   * {@code unrestricted} (SUPER_ADMIN — plantIds null) bypasses the scope filter.
   */
  @Query("""
      select new com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleRow(
        s, p.category, p.scheduleType, m.plant.id, m.machineGroup.id, m.id)
      from PreventiveScheduleEntity s
      join PreventiveProgramEntity p on p.id = s.programId
      join MachineEntity m on m.id = s.machineId
      where (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
      order by s.dueDate
      """)
  List<PreventiveScheduleRow> findScopedSchedules(
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") java.util.Collection<UUID> plantIds,
      @Param("groupIds") java.util.Collection<UUID> groupIds);
}