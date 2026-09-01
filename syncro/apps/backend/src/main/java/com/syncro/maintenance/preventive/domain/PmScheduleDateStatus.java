package com.syncro.maintenance.preventive.domain;

/**
 * PM schedule date execution state (blueprint F5, story 15-2): values match the
 * {@code pm_schedule_dates.status} CHECK constraint in V1 exactly.
 */
public enum PmScheduleDateStatus {
  SCHEDULED,
  EXECUTED,
  MISSED,
  RESCHEDULED
}
