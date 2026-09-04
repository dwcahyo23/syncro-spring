package com.syncro.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * KPI materialization configuration (story 20-1, AD-20). Typed binding per the spine
 * config rule — the scheduler cron and lookback are env-overridable, never hardcoded.
 *
 * @param refreshCron Spring cron for the monthly refresh sweep (default 03:00 UTC daily)
 * @param enabled     master switch for the scheduler + sync-invalidation listener
 * @param lookbackMonths how many past months a scheduled/invalidated pass re-materializes
 *                       (late corrections and backdated sync rows land in prior months)
 */
@ConfigurationProperties(prefix = "syncro.kpi")
public record KpiProperties(
    @DefaultValue("0 0 3 * * *") String refreshCron,
    @DefaultValue("true") boolean enabled,
    @DefaultValue("1") int lookbackMonths) {

  public KpiProperties {
    if (refreshCron == null || refreshCron.isBlank()) {
      throw new IllegalArgumentException("syncro.kpi.refresh-cron must not be blank");
    }
    if (lookbackMonths < 0) {
      throw new IllegalArgumentException("syncro.kpi.lookback-months must be >= 0");
    }
  }
}
