package com.syncro.maintenance.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Workorder ack persistence (story 14-4, FR-181). The UNIQUE(work_order_id) constraint
 * is the authoritative dedup — one ack per workorder.
 */
public interface WorkOrderAckRepository extends JpaRepository<WorkOrderAckEntity, UUID> {

  Optional<WorkOrderAckEntity> findByWorkOrderId(String workOrderId);

  boolean existsByWorkOrderId(String workOrderId);
}