package com.syncro.maintenance.preventive.domain;

/**
 * PM schedule approval state (blueprint F5, story 15-2): values match the
 * {@code pm_schedules.status} CHECK constraint in V1 exactly. The approval chain is
 * SPV first, then production.
 */
public enum PmScheduleStatus {
  DRAFT,
  PENDING_SPV_APPROVAL,
  PENDING_PRODUCTION_APPROVAL,
  APPROVED,
  ACTIVE
}
