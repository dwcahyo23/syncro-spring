package com.syncro.maintenance.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Workorder todo persistence (story 10-7). Todos are always scoped by workorder id
 * so a todo can never be addressed through the wrong workorder. The kanban query
 * uses a separate repository method on WorkOrderRepository for the joined read.
 */
public interface WorkOrderTodoRepository extends JpaRepository<WorkOrderTodoEntity, UUID> {

  List<WorkOrderTodoEntity> findByWorkorderIdOrderBySortOrderAsc(String workorderId);
}