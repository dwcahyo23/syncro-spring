package com.syncro.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "syncro.telemetry")
public record TelemetryProperties(
    @DefaultValue("PT5M") Duration latestTtl,
    @DefaultValue("PT30S") Duration dedupeWindow,
    @DefaultValue Ingest ingest) {

  public record Ingest(
      @DefaultValue("1000") int queueCapacity,
      @DefaultValue("2") int workerThreads,
      @DefaultValue("PT5M") Duration staleThreshold) {

    public Ingest {
      if (queueCapacity < 1) {
        throw new IllegalArgumentException(
            "syncro.telemetry.ingest.queue-capacity must be >= 1");
      }
      if (workerThreads < 1) {
        throw new IllegalArgumentException(
            "syncro.telemetry.ingest.worker-threads must be >= 1");
      }
      if (staleThreshold == null || staleThreshold.isZero() || staleThreshold.isNegative()) {
        throw new IllegalArgumentException(
            "syncro.telemetry.ingest.stale-threshold must be a positive duration");
      }
    }
  }

  public TelemetryProperties {
    if (latestTtl == null || latestTtl.isZero() || latestTtl.isNegative()) {
      throw new IllegalArgumentException("syncro.telemetry.latest-ttl must be a positive duration");
    }
    if (dedupeWindow == null || dedupeWindow.isZero() || dedupeWindow.isNegative()) {
      throw new IllegalArgumentException("syncro.telemetry.dedupe-window must be a positive duration");
    }
  }
}
