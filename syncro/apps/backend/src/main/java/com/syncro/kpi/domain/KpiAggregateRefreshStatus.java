package com.syncro.kpi.domain;

/**
 * KPI aggregate refresh outcome (blueprint G7, story 15-2): values match the
 * {@code kpi_aggregate_refresh_logs.status} CHECK constraint in V1 exactly. One row
 * per refresh attempt keyed by {@code refresh_key}; RUNNING marks an in-flight pass.
 */
public enum KpiAggregateRefreshStatus {
  RUNNING,
  SUCCESS,
  FAILED
}
