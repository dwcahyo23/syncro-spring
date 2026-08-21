package com.syncro.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "syncro.notification")
public record NotificationProperties(@DefaultValue Worker worker) {

  public record Worker(
      @DefaultValue("PT5M") Duration staleThreshold,
      @DefaultValue("PT1H") Duration failedWindow) {

    public Worker {
      if (staleThreshold == null || staleThreshold.isZero() || staleThreshold.isNegative()) {
        throw new IllegalArgumentException(
            "syncro.notification.worker.stale-threshold must be a positive duration");
      }
      if (failedWindow == null || failedWindow.isZero() || failedWindow.isNegative()) {
        throw new IllegalArgumentException(
            "syncro.notification.worker.failed-window must be a positive duration");
      }
    }
  }

  public NotificationProperties {
    if (worker == null || worker.staleThreshold == null || worker.failedWindow == null) {
      throw new IllegalArgumentException("syncro.notification.worker must be configured");
    }
  }

  public NotificationProperties() {
    this(new Worker(Duration.ofMinutes(5), Duration.ofHours(1)));
  }
}
