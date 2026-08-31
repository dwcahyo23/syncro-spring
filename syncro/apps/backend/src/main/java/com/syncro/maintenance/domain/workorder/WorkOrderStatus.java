package com.syncro.maintenance.domain.workorder;

/**
 * Workorder lifecycle (AD-4 as redesigned by the ORM target blueprint, story
 * 15-1): exactly six values, persisted in the {@code work_orders.status}
 * VARCHAR(20) CHECK column. {@code assign} materialises OPEN → IN_PROGRESS via
 * the work_assignments flow; the derived sparepart-wait state is
 * PENDING_SPAREPART and completion lands in PENDING_REVIEW before CLOSED.
 * The state machine ({@link WorkOrderStateMachine}) owns the transitions.
 */
public enum WorkOrderStatus {
  OPEN,
  IN_PROGRESS,
  PENDING_SPAREPART,
  PENDING_REVIEW,
  CLOSED,
  CANCELLED
}
