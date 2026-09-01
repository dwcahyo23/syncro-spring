package com.syncro.compliance.domain;

/**
 * Non-conformance lifecycle (blueprint H1, story 15-2): values match the
 * {@code non_conformances.status} CHECK constraint in V1 exactly.
 */
public enum NcStatus {
  OPEN,
  IN_PROGRESS,
  CLOSED,
  VERIFIED
}
