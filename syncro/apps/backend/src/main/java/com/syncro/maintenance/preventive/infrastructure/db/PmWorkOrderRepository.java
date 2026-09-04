package com.syncro.maintenance.preventive.infrastructure.db;

import com.syncro.maintenance.preventive.domain.PmWorkOrderStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code pm_work_orders} (blueprint F6, story 15-2; queries for 19-4). */
public interface PmWorkOrderRepository extends JpaRepository<PmWorkOrderEntity, UUID> {

  /** Generate pre-check: one workorder per (machine, template, scheduled_date) period. */
  boolean existsByMachineIdAndTemplateIdAndScheduledDate(UUID machineId, UUID templateId,
      LocalDate scheduledDate);

  Optional<PmWorkOrderEntity> findByMachineIdAndTemplateIdAndScheduledDate(UUID machineId,
      UUID templateId, LocalDate scheduledDate);

  /** Overdue sweep candidates: non-terminal statuses with a past scheduled_date. */
  List<PmWorkOrderEntity> findByStatusInAndScheduledDateBefore(List<PmWorkOrderStatus> statuses,
      LocalDate cutoff);

  /**
   * Pessimistic row lock for lifecycle transitions (story 19-4): serializes
   * concurrent assign/start/complete/sweep on the same row so the state
   * precondition cannot pass twice (19-3 findByIdForUpdate pattern).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from PmWorkOrderEntity w where w.id = :id")
  Optional<PmWorkOrderEntity> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Story 20-1 (AD-19/FR-170): planned PM workorders for the plant with a scheduled
   * date inside the month window — the PM-completion denominator. Plant resolves
   * through the machine (pm_work_orders carries no plant column).
   */
  @Query("""
      select count(w) from PmWorkOrderEntity w
      join MachineEntity m on m.id = w.machineId
      where m.plant.id = :plantId
        and w.scheduledDate >= :monthFrom and w.scheduledDate < :monthTo
      """)
  long countPlannedForPlantInMonth(@Param("plantId") UUID plantId,
      @Param("monthFrom") java.time.LocalDate monthFrom,
      @Param("monthTo") java.time.LocalDate monthTo);
}
