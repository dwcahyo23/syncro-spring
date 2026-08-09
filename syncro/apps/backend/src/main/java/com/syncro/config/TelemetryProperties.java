package com.syncro.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "syncro.telemetry")
public record TelemetryProperties(
    @DefaultValue("PT5M") Duration latestTtl,
    @DefaultValue("PT30S") Duration dedupeWindow) {

  public TelemetryProperties {
    if (latestTtl == null || latestTtl.isZero() || latestTtl.isNegative()) {
      throw new IllegalArgumentException("syncro.telemetry.latest-ttl must be a positive duration");
    }
    if (dedupeWindow == null || dedupeWindow.isZero() || dedupeWindow.isNegative()) {
      throw new IllegalArgumentException("syncro.telemetry.dedupe-window must be a positive duration");
    }
  }
}
