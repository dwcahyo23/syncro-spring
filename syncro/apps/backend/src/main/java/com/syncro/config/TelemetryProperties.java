package com.syncro.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "syncro.telemetry")
public record TelemetryProperties(
    @DefaultValue("PT5M") Duration latestTtl,
    @DefaultValue("PT30S") Duration dedupeWindow,
    @DefaultValue Ingest ingest,
    @DefaultValue DataQuality dataQuality) {

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

  /** Data-quality metrics window for the health dashboard panel (page-spec 4.4, default 1 hour). */
  public record DataQuality(
      @DefaultValue("PT1H") Duration window) {

    public DataQuality {
      if (window == null || window.isZero() || window.isNegative()) {
        throw new IllegalArgumentException(
            "syncro.telemetry.data-quality.window must be a positive duration");
      }
      // Upper bound protects the tracker's minute-bucket ring: beyond ~30 days the ring would
      // allocate hundreds of millions of buckets or overflow the int bucket count. Compared
      // in seconds so a 30-days-plus-sub-minute duration cannot slip through toMinutes().
      if (window.toSeconds() > 43_200L * 60) {
        throw new IllegalArgumentException(
            "syncro.telemetry.data-quality.window must be at most 30 days");
      }
    }
  }

  public TelemetryProperties {
    if (ingest == null) {
      throw new IllegalArgumentException("syncro.telemetry.ingest must be configured");
    }
    if (dataQuality == null) {
      throw new IllegalArgumentException("syncro.telemetry.data-quality must be configured");
    }
    if (latestTtl == null || latestTtl.isZero() || latestTtl.isNegative()) {
      throw new IllegalArgumentException("syncro.telemetry.latest-ttl must be a positive duration");
    }
    if (dedupeWindow == null || dedupeWindow.isZero() || dedupeWindow.isNegative()) {
      throw new IllegalArgumentException("syncro.telemetry.dedupe-window must be a positive duration");
    }
  }
}
