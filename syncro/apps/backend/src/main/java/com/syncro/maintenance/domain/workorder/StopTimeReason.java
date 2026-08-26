package com.syncro.maintenance.domain.workorder;

/**
 * Stop-time reason code (story 10-6, FR-122). The value is CHECK-constrained on
 * {@code work_orders.stop_time_reason} (V51): {@code IN ('ELECTRIC','MECHANICAL',
 * 'PNEUMATIC','HYDRAULIC','OTHER')}, NULL allowed. Required before DONE only for
 * Breakdown (01) workorders.
 */
public enum StopTimeReason {
  ELECTRIC,
  MECHANICAL,
  PNEUMATIC,
  HYDRAULIC,
  OTHER
}