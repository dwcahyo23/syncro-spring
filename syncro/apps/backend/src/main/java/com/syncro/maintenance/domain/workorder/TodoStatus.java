package com.syncro.maintenance.domain.workorder;

/**
 * Todo status lifecycle: PENDING (default) → IN_PROGRESS → COMPLETED, or CANCELLED
 * from any non-terminal state. This is a separate lifecycle from the workorder's
 * overall status (FR-119, story 10-7).
 */
public enum TodoStatus {
  PENDING,
  IN_PROGRESS,
  COMPLETED,
  CANCELLED
}