package com.syncro.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "syncro.projection")
public record ProjectionProperties(
    @DefaultValue("30") int windowDays,
    @DefaultValue("PT48H") Duration staleness,
    @DefaultValue("PT5M") Duration cacheTtl,
    @DefaultValue("Asia/Jakarta") String plantTimezone) {

  public ProjectionProperties {
    if (windowDays < 1) {
      throw new IllegalArgumentException("syncro.projection.window-days must be >= 1");
    }
    if (staleness == null || staleness.isZero() || staleness.isNegative()) {
      throw new IllegalArgumentException("syncro.projection.staleness must be a positive duration");
    }
    if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
      throw new IllegalArgumentException("syncro.projection.cache-ttl must be a positive duration");
    }
    try {
      ZoneId.of(plantTimezone);
    } catch (Exception malformed) {
      throw new IllegalArgumentException(
          "syncro.projection.plant-timezone must be a valid IANA zone id, got '" + plantTimezone + "'");
    }
  }

  public ZoneId plantZoneId() {
    return ZoneId.of(plantTimezone);
  }
}
