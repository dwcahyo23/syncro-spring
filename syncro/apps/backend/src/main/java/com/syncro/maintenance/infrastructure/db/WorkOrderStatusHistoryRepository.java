package com.syncro.maintenance.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Status-history persistence (10.2). One row per transition — written by the 10.2
 * create/assign flow and by the 10.3 state machine.
 */
public interface WorkOrderStatusHistoryRepository extends JpaRepository<WorkOrderStatusHistoryEntity, UUID> {
}