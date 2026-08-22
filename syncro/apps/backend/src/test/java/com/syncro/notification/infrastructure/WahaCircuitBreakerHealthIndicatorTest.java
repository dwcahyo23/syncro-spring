package com.syncro.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

@ExtendWith(MockitoExtension.class)
class WahaCircuitBreakerHealthIndicatorTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC);
  private static final String FIXED_TIMESTAMP = "2026-08-21T08:00:00Z";

  @Mock
  private WahaClient wahaClient;

  private WahaCircuitBreakerHealthIndicator indicator;
  private CircuitBreaker circuitBreaker;

  @BeforeEach
  void setUp() {
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .failureRateThreshold(50)
        .minimumNumberOfCalls(2)
        .slidingWindowSize(2)
        .build();
    circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("waha-test");
    when(wahaClient.getCircuitBreaker()).thenReturn(circuitBreaker);
    indicator = new WahaCircuitBreakerHealthIndicator(wahaClient, FIXED_CLOCK);
  }

  @Test
  void health_whenCircuitClosed_returnsUp() {
    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("state", "CLOSED");
  }

  @Test
  void health_whenCircuitOpen_returnsDown() {
    circuitBreaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
        new RuntimeException("fail1"));
    circuitBreaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
        new RuntimeException("fail2"));
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

  @Test
  void upHealthCarriesOperationalStatusContractFields() {
    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Up")
        .containsEntry("statusSeverity", "SUCCESS")
        .containsEntry("timestamp", FIXED_TIMESTAMP);
  }

  @Test
  void openHealthCarriesOperationalStatusContractFieldsWithReason() {
    circuitBreaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
        new RuntimeException("fail1"));
    circuitBreaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
        new RuntimeException("fail2"));
    assertThat(circuitBreaker.getState()).isEqualTo(State.OPEN);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Down")
        .containsEntry("statusSeverity", "CRITICAL")
        .containsEntry("timestamp", FIXED_TIMESTAMP)
        .containsKey("statusReason");
    assertThat((String) health.getDetails().get("statusReason")).startsWith("Circuit breaker is OPEN");
  }

  @Test
  void outOfServiceHealthCarriesOperationalStatusContractFields() {
    circuitBreaker.transitionToForcedOpenState();

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Out of Service")
        .containsEntry("statusSeverity", "WARNING")
        .containsEntry("timestamp", FIXED_TIMESTAMP);
  }

  @Test
  void health_beforeMinimumNumberOfCalls_clampsFailureRateToZero() {
    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("state", "CLOSED")
        .containsEntry("failureRate", 0.0f);
    assertThat(health.getDetails()).doesNotContainKey("statusReason");
  }
}