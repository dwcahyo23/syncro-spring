package com.syncro.kpi.domain;

/**
 * Provenance status carried by materialized KPI rows whose source may be unavailable
 * (blueprint G5/G7, story 20-1). An unavailable metric is explicit — never a
 * fabricated zero. Values are uppercase contract strings persisted in
 * {@code source_status} (VARCHAR(20), no CHECK in V1 — the pair is free-form by design).
 */
public enum KpiSourceStatus {
  /** Source data covered the whole window; the metric is trustworthy. */
  COMPLETE,
  /** Source data was absent or too sparse; the metric value is NULL by design. */
  INSUFFICIENT_DATA
}
