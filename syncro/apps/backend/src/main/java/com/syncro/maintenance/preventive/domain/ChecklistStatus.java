package com.syncro.maintenance.preventive.domain;

/**
 * Derived checklist status for a preventive schedule (FR-132, story 11-2). Computed
 * server-side from the checklist result row, never stored: NONE (no result yet),
 * SUBMITTED (result exists, not yet approved), APPROVED (leader approved).
 */
public enum ChecklistStatus {
  NONE,
  SUBMITTED,
  APPROVED
}
