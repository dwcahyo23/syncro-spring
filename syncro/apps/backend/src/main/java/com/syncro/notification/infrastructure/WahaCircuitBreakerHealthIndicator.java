package com.syncro.notification.infrastructure;

import com.syncro.health.DependencyHealthSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import java.time.Clock;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the WAHA circuit breaker.
 *
 * <p>Exposes the circuit state and key metrics under {@code /actuator/health} as the
 * {@code wahaCircuitBreaker} component. Reports {@code DOWN} when the circuit is OPEN,
 * {@code OUT_OF_SERVICE} when FORCED_OPEN or DISABLED, and {@code UP} otherwise.
 *
 * <p>Shown in the existing {@code /actuator/health?show-details=always} endpoint which is
 * already configured in {@code application.yml}. The component is excluded from the
 * {@code readiness} health group — WAHA unavailability must not flip {@code /ready} to DOWN.
 */
@Component("wahaCircuitBreaker")
public class WahaCircuitBreakerHealthIndicator implements HealthIndicator {

  private final WahaClient wahaClient;
  private final Clock clock;

  public WahaCircuitBreakerHealthIndicator(WahaClient wahaClient, Clock clock) {
    this.wahaClient = wahaClient;
    this.clock = clock;
  }

  @Override
  public Health health() {
    CircuitBreaker cb = wahaClient.getCircuitBreaker();
    State state = cb.getState();
    var metrics = cb.getMetrics();
    // Resilience4j reports NaN or -1.0 as the failure rate until minimumNumberOfCalls is
    // reached — clamp to 0.0 so health JSON never carries an unrepresentable value.
    float rate = metrics.getFailureRate();
    float failureRate = Float.isNaN(rate) || rate < 0 ? 0.0f : rate;

    Health.Builder builder = switch (state) {
      case OPEN -> Health.down();
      case FORCED_OPEN, DISABLED -> Health.outOfService();
      default -> Health.up();
    };

    builder
        .withDetail("state", state.name())
        .withDetail("failureRate", failureRate)
        .withDetail("bufferedCalls", metrics.getNumberOfBufferedCalls())
        .withDetail("failedCalls", metrics.getNumberOfFailedCalls())
        .withDetail("successfulCalls", metrics.getNumberOfSuccessfulCalls())
        .withDetail("notPermittedCalls", metrics.getNumberOfNotPermittedCalls());

    String reason = switch (state) {
      case OPEN -> "Circuit breaker is OPEN (failure rate " + failureRate + "%)";
      case FORCED_OPEN -> "Circuit breaker is FORCED_OPEN";
      case DISABLED -> "Circuit breaker is DISABLED";
      default -> null;
    };

    return DependencyHealthSupport.enrich(builder.build(), clock, reason, null);
  }
}
