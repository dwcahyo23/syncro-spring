package com.syncro.maintenance.infrastructure.db;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Status-history persistence (10.2). One row per transition — written by the 10.2
 * create/assign flow and by the 10.3 state machine.
 */
public interface WorkOrderStatusHistoryRepository extends JpaRepository<WorkOrderStatusHistoryEntity, UUID> {

  @Query("select min(h.transitionedAt) from WorkOrderStatusHistoryEntity h "
      + "where h.workOrderId = :workOrderId and h.toStatus = 'OPEN'")
  Optional<Instant> findFirstOpenTransitionedAt(@Param("workOrderId") String workOrderId);

  /**
   * Story 14-2 (FR-173): derived woStopAt — the latest DONE transition timestamp.
   * Returns empty if the workorder has never transitioned to DONE.
   */
  @Query("select max(h.transitionedAt) from WorkOrderStatusHistoryEntity h "
      + "where h.workOrderId = :workOrderId and h.toStatus = 'DONE'")
  Optional<Instant> findLatestDoneTransitionedAt(@Param("workOrderId") String workOrderId);

  /**
   * Batch twin of {@link #findLatestDoneTransitionedAt} — one query for the whole
   * analytics window instead of N+1 per workorder (story 14-2).
   */
  @Query("""
      select h.workOrderId as workOrderId, max(h.transitionedAt) as latestTransitionedAt
      from WorkOrderStatusHistoryEntity h
      where h.workOrderId in :workOrderIds and h.toStatus = 'DONE'
      group by h.workOrderId
      """)
  List<DoneTransitionProjection> findLatestDoneTransitionedAts(
      @Param("workOrderIds") Collection<String> workOrderIds);

  interface DoneTransitionProjection {
    String getWorkOrderId();

    Instant getLatestTransitionedAt();
  }
}