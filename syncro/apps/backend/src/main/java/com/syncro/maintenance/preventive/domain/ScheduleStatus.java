package com.syncro.maintenance.preventive.domain;

/**
 * Preventive schedule status lifecycle (FR-131, AD-12, story 11-1). OVERDUE is
 * derived server-side when stored status is SCHEDULED and due_date < today.
 */
public enum ScheduleStatus {
  SCHEDULED,
  IN_PROGRESS,
  PERFORMED,
  SKIPPED
}