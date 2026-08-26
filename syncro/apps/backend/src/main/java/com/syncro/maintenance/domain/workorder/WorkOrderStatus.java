package com.syncro.maintenance.domain.workorder;

/**
 * Workorder lifecycle (AD-4). The values are contract strings persisted in the
 * {@code work_orders.status} VARCHAR(20) CHECK column; the state machine (10.3)
 * owns the transitions, 10.2 only materialises OPEN on create and OPEN → ASSIGNED
 * on assign.
 */
public enum WorkOrderStatus {
  DRAFT,
  OPEN,
  ASSIGNED,
  IN_PROGRESS,
  ON_PROCUREMENT,
  DONE,
  CLOSED,
  CANCELLED
}
