package com.syncro.maintenance.domain.workorder;

/**
 * Why a work log execution session stopped (blueprint B4, story 15-2): values match
 * the {@code work_logs.stopped_reason} CHECK constraint in V1 exactly. Nullable on
 * the entity — a log still in progress has not stopped yet.
 */
public enum WorkLogStoppedReason {
  WAITING_SPAREPART,
  SHIFT_END,
  COMPLETED,
  OTHER
}
