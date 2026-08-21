package com.syncro.notification.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
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
 * already configured in {@code application.yml}.
 */
@Component("wahaCircuitBreaker")
public class WahaCircuitBreakerHealthIndicator implements HealthIndicator {

  private final WahaClient wahaClient;

  public WahaCircuitBreakerHealthIndicator(WahaClient wahaClient) {
    this.wahaClient = wahaClient;
  }

  @Override
  public Health health() {
    CircuitBreaker cb = wahaClient.getCircuitBreaker();
    State state = cb.getState();
    var metrics = cb.getMetrics();

    Health.Builder builder = switch (state) {
      case OPEN -> Health.down();
      case FORCED_OPEN, DISABLED -> Health.outOfService();
      default -> Health.up();
    };

    return builder
        .withDetail("state", state.name())
        .withDetail("failureRate", metrics.getFailureRate())
        .withDetail("bufferedCalls", metrics.getNumberOfBufferedCalls())
        .withDetail("failedCalls", metrics.getNumberOfFailedCalls())
        .withDetail("successfulCalls", metrics.getNumberOfSuccessfulCalls())
        .withDetail("notPermittedCalls", metrics.getNumberOfNotPermittedCalls())
        .build();
  }
}
