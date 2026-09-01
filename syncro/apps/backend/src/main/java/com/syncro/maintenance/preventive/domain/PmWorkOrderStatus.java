package com.syncro.maintenance.preventive.domain;

/**
 * PM workorder state (blueprint F6, story 15-2): values match the
 * {@code pm_work_orders.status} CHECK constraint in V1 exactly. Distinct from the
 * corrective {@code work_orders.status} six-value set — a PM workorder is not a
 * {@code work_orders} row.
 */
public enum PmWorkOrderStatus {
  SCHEDULED,
  ASSIGNED,
  IN_PROGRESS,
  COMPLETED,
  OVERDUE
}
