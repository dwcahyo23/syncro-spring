package com.syncro.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Analytics cache configuration (story 14-2, FR-173/FR-174). Mirrors the
 * {@link ProjectionProperties} pattern for derived-data caching.
 *
 * @param analyticsTtl Redis cache TTL for computed dashboard analytics (default PT30M)
 */
@ConfigurationProperties(prefix = "syncro.dashboard")
public record DashboardAnalyticsProperties(
    @DefaultValue("PT30M") Duration analyticsTtl
) {

  public DashboardAnalyticsProperties {
    if (analyticsTtl == null || analyticsTtl.isZero() || analyticsTtl.isNegative()) {
      throw new IllegalArgumentException("syncro.dashboard.analytics-ttl must be a positive duration");
    }
  }
}