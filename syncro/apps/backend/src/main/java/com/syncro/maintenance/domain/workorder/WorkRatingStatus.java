package com.syncro.maintenance.domain.workorder;

/**
 * Workorder quality rating lifecycle (blueprint C4, story 15-2): values match the
 * {@code work_order_quality_ratings.status} CHECK constraint in V1 exactly. PENDING
 * is the assignment state before the section leader submits; EXPIRED covers the
 * un-rated-due-date path.
 */
public enum WorkRatingStatus {
  PENDING,
  SUBMITTED,
  EXPIRED
}
