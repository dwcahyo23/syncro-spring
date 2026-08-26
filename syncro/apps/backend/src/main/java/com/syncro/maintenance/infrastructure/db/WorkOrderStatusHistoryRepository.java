package com.syncro.maintenance.infrastructure.db;

import java.time.Instant;
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
}