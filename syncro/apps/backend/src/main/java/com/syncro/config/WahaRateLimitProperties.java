package com.syncro.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate-limiting configuration for WAHA notification sends.
 *
 * <p>The {@code windowMs} is the deduplication window in milliseconds. A Redis key
 * {@code waha:rl:{alertId}:{recipientPhone}} is set with this TTL after each successful send.
 * Any subsequent dispatch attempt for the same alert+recipient within the window is suppressed
 * and the job is marked {@code RATE_LIMITED} with {@code nextAttemptAt} set to the key's
 * expiry time, so the worker retries automatically after the window expires.
 */
@ConfigurationProperties(prefix = "syncro.notification.rate-limit")
public record WahaRateLimitProperties(
    /**
     * Deduplication window in milliseconds (default 5 minutes).
     * After a successful WAHA send the rate-limit key lives for this duration.
     */
    long windowMs) {

  public WahaRateLimitProperties {
    if (windowMs <= 0) {
      throw new IllegalArgumentException("syncro.notification.rate-limit.window-ms must be > 0");
    }
  }

  /** Convenience constructor with default. */
  public WahaRateLimitProperties() {
    this(300_000L);
  }
}
