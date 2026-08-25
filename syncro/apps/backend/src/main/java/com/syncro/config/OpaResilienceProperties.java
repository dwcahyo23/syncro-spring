package com.syncro.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Resilience configuration for OPA authorization calls.
 *
 * <p>Controls timeout, circuit-breaker thresholds, and half-open probe settings used by
 * {@code OpaClient} so a failing OPA sidecar fails fast and the authz layer degrades
 * instead of blocking every request.
 *
 * <p>Bound from {@code syncro.opa.resilience.*} in {@code application.yml}. All fields have
 * safe production defaults so the service starts without any explicit configuration.
 */
@Validated
@ConfigurationProperties(prefix = "syncro.opa.resilience")
public record OpaResilienceProperties(

    /**
     * HTTP connect + read timeout per OPA call (default 5 000 ms).
     * Applied to {@code RestClient} request factory.
     */
    @NotNull Duration timeout,

    /**
     * Failure-rate threshold (%) at which the circuit opens (default 50).
     * Evaluated over the last {@code slidingWindowSize} calls.
     */
    @Min(1) int failureRateThreshold,

    /**
     * Minimum number of calls required before failure rate is evaluated (default 5).
     */
    @Positive int minimumNumberOfCalls,

    /**
     * Size of the sliding window used to compute the failure rate (default 10).
     */
    @Positive int slidingWindowSize,

    /**
     * Time the circuit stays OPEN before transitioning to HALF_OPEN (default 60 000 ms).
     */
    @NotNull Duration waitDurationInOpenState,

    /**
     * Number of probe calls allowed in HALF_OPEN state (default 3).
     */
    @Positive int permittedCallsInHalfOpen

) {

  /** Canonical constructor — validates constraints. */
  public OpaResilienceProperties {
    if (failureRateThreshold < 1 || failureRateThreshold > 100) {
      throw new IllegalArgumentException(
          "syncro.opa.resilience.failure-rate-threshold must be between 1 and 100");
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException(
          "syncro.opa.resilience.timeout must be a positive duration");
    }
    if (waitDurationInOpenState == null || waitDurationInOpenState.isZero()
        || waitDurationInOpenState.isNegative()) {
      throw new IllegalArgumentException(
          "syncro.opa.resilience.wait-duration-in-open-state must be a positive duration");
    }
  }

  /** Convenience factory with production-safe defaults. */
  public OpaResilienceProperties() {
    this(
        Duration.ofMillis(5_000),
        50,
        5,
        10,
        Duration.ofMillis(60_000),
        3
    );
  }
}
