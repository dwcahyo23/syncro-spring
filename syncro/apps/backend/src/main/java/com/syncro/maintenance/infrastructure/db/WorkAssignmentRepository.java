package com.syncro.maintenance.infrastructure.db;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code work_assignments} (blueprint B3, AD-17, story 15-2). */
public interface WorkAssignmentRepository extends JpaRepository<WorkAssignmentEntity, UUID> {

  List<WorkAssignmentEntity> findByWorkOrderIdOrderByAssignedAtAsc(String workOrderId);

  /** True when the technician has an active assignment on the workorder (17-1 active-duplicate pre-check). */
  boolean existsByWorkOrderIdAndTechnicianIdAndActiveTrue(String workOrderId, UUID technicianId);

  /**
   * Atomic soft-deactivate (17-1 drop): the WHERE {@code is_active = true} makes two
   * concurrent drops race-safe — exactly one wins, the loser updates 0 rows. Returns
   * the number of rows updated (0 = already dropped).
   */
  @Modifying(clearAutomatically = true)
  @Query("""
      update WorkAssignmentEntity a
      set a.active = false, a.droppedBy = :droppedBy, a.droppedAt = :droppedAt, a.updatedAt = :droppedAt
      where a.id = :id and a.active = true
      """)
  int deactivateIfActive(@Param("id") UUID id, @Param("droppedBy") UUID droppedBy,
      @Param("droppedAt") Instant droppedAt);
}
