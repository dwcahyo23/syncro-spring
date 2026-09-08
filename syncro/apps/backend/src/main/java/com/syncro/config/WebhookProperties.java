package com.syncro.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Outbound webhook dispatch configuration (story 22-1, blueprint I4), bound from
 * {@code syncro.webhook.*} in {@code application.yml}. Mirrors the WAHA properties
 * template: typed, validated, safe production defaults so the service boots without
 * any explicit configuration.
 *
 * <p>Retry is DB-driven (the worker sweep + {@code next_retry_at} backoff), never a
 * library {@code @Retry}: {@code maxAttempts} is the per-row budget stamped at enqueue
 * time and {@code backoffBaseSeconds} is the exponential base — the delay is
 * {@code min(base * 2^attemptCount, 3600)} seconds, which at the 60-second default is
 * exactly the {@code min(2^attemptCount, 60)} minutes precedent from
 * NotificationDispatchService.
 */
@Validated
@ConfigurationProperties(prefix = "syncro.webhook")
public record WebhookProperties(

    @DefaultValue Worker worker,

    /** Per-delivery retry budget stamped onto each queued row (default 3). */
    @DefaultValue("3") @Min(1) int maxAttempts,

    /**
     * Exponential backoff base in seconds (default 60): retry N waits
     * {@code min(base * 2^N, 3600)} seconds.
     */
    @DefaultValue("60") @Positive int backoffBaseSeconds,

    @DefaultValue Resilience resilience

) {

  /** Worker sweep cadence (the @Scheduled interval reads the raw property directly). */
  public record Worker(@DefaultValue("30000") long pollIntervalMs) {
    public Worker {
      if (pollIntervalMs <= 0) {
        throw new IllegalArgumentException(
            "syncro.webhook.worker.poll-interval-ms must be a positive number of milliseconds");
      }
    }
  }

  /** HTTP timeout + circuit-breaker thresholds for subscriber calls (WahaResilience precedent). */
  public record Resilience(
      @DefaultValue("PT5S") @NotNull Duration timeout,
      @DefaultValue("50") @Min(1) int failureRateThreshold,
      @DefaultValue("5") @Positive int minimumNumberOfCalls,
      @DefaultValue("10") @Positive int slidingWindowSize,
      @DefaultValue("PT60S") @NotNull Duration waitDurationInOpenState,
      @DefaultValue("3") @Positive int permittedCallsInHalfOpen) {

    public Resilience {
      if (failureRateThreshold < 1 || failureRateThreshold > 100) {
        throw new IllegalArgumentException(
            "syncro.webhook.resilience.failure-rate-threshold must be between 1 and 100");
      }
      if (timeout == null || timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException(
            "syncro.webhook.resilience.timeout must be a positive duration");
      }
      if (waitDurationInOpenState == null || waitDurationInOpenState.isZero()
          || waitDurationInOpenState.isNegative()) {
        throw new IllegalArgumentException(
            "syncro.webhook.resilience.wait-duration-in-open-state must be a positive duration");
      }
    }
  }

  /** Convenience factory with production-safe defaults (tests, standalone use). */
  public WebhookProperties() {
    this(new Worker(30_000), 3, 60, new Resilience(Duration.ofSeconds(5), 50, 5, 10,
        Duration.ofSeconds(60), 3));
  }
}
