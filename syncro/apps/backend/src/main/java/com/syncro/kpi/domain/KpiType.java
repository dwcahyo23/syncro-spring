package com.syncro.kpi.domain;

/**
 * Materialized KPI families (story 20-1, AD-20). The {@code key} is the refresh_key
 * prefix in {@code kpi_aggregate_refresh_logs} (e.g. {@code mtbf:2026-08}) and the
 * path segment of {@code GET /api/v1/kpi/materialized/{type}}.
 */
public enum KpiType {
  MTBF("mtbf"),
  MTTR("mttr"),
  MAR("mar"),
  PM_COMPLETION("pm-completion"),
  TECHNICIAN("technician"),
  BREAKDOWN("breakdown");

  private final String key;

  KpiType(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** Refresh-log key for this type and month (month normalized to first-of-month). */
  public String refreshKey(java.time.LocalDate month) {
    return key + ":" + month.getYear() + "-" + String.format("%02d", month.getMonthValue());
  }

  /** Case-insensitive lookup for the API path segment; null when unknown. */
  public static KpiType fromPath(String value) {
    for (var type : values()) {
      if (type.key.equalsIgnoreCase(value)) {
        return type;
      }
    }
    return null;
  }
}
