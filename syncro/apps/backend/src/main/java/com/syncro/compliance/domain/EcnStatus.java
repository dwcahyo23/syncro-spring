package com.syncro.compliance.domain;

/**
 * Equipment change notice lifecycle (blueprint H4, story 15-2): values match the
 * {@code equipment_change_notices.status} CHECK constraint in V1 exactly.
 */
public enum EcnStatus {
  DRAFT,
  UNDER_REVIEW,
  APPROVED,
  EXECUTED,
  CLOSED
}
