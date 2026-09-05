package com.syncro.compliance.domain;

/**
 * Non-conformance severity (story 21-1, blueprint H1): values match the
 * {@code non_conformances.severity} CHECK constraint added by V13. Nullable at
 * the column level — an NC may be logged before its severity is triaged.
 */
public enum NcSeverity {
  MINOR,
  MAJOR,
  CRITICAL
}
