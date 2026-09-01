package com.syncro.compliance.domain;

/**
 * 8D report lifecycle (blueprint H2, story 15-2): values match the
 * {@code eight_d_reports.status} CHECK constraint in V1 exactly. EFFECTIVE /
 * INEFFECTIVE record the post-closure effectiveness verification verdict.
 */
public enum EightDStatus {
  DRAFT,
  IN_PROGRESS,
  CLOSED,
  EFFECTIVE,
  INEFFECTIVE
}
