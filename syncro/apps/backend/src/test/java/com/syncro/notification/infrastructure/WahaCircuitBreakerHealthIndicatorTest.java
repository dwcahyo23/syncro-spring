package com.syncro.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

@ExtendWith(MockitoExtension.class)
class WahaCircuitBreakerHealthIndicatorTest {

  @Mock
  private WahaClient wahaClient;

  private WahaCircuitBreakerHealthIndicator indicator;
  private CircuitBreaker circuitBreaker;

  @BeforeEach
  void setUp() {
    // Use a real circuit breaker with a very low threshold for test control
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .failureRateThreshold(50)
        .minimumNumberOfCalls(2)
        .slidingWindowSize(2)
        .build();
    circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("waha-test");
    when(wahaClient.getCircuitBreaker()).thenReturn(circuitBreaker);
    indicator = new WahaCircuitBreakerHealthIndicator(wahaClient);
  }

  @Test
  void health_whenCircuitClosed_returnsUp() {
    // Default state is CLOSED
    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("state", "CLOSED");
  }

  @Test
  void health_whenCircuitOpen_returnsDown() {
    // Force circuit to OPEN by recording failures above threshold
    circuitBreaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
        new RuntimeException("fail1"));
    circuitBreaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
        new RuntimeException("fail2"));
    // After 2 failures with 50% threshold on window=2, circuit should be OPEN
    assertThat(circuitBreaker.getState()).isEqualTo(State.OPEN);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("state", "OPEN");
  }

  @Test
  void health_whenCircuitClosed_exposesMetrics() {
    circuitBreaker.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS);

    Health health = indicator.health();

    assertThat(health.getDetails()).containsKey("failureRate");
    assertThat(health.getDetails()).containsKey("bufferedCalls");
    assertThat(health.getDetails()).containsKey("failedCalls");
    assertThat(health.getDetails()).containsKey("successfulCalls");
    assertThat(health.getDetails()).containsKey("notPermittedCalls");
  }

  @Test
  void health_whenForceOpen_returnsOutOfService() {
    circuitBreaker.transitionToForcedOpenState();

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(health.getDetails()).containsEntry("state", "FORCED_OPEN");
  }
}
