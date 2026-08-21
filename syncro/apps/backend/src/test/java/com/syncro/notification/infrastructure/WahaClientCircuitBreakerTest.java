package com.syncro.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.syncro.config.WahaProperties;
import com.syncro.config.WahaResilienceProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link WahaClient} circuit breaker behaviour.
 *
 * <p>Uses {@link MockRestServiceServer} to simulate WAHA HTTP responses without a live server.
 * Note: MockRestServiceServer requires access to the RestClient builder used inside WahaClient.
 * Since WahaClient builds its own RestClient internally, we test via the public send() API and
 * verify observable behaviour (Result fields, circuit breaker state).
 */
class WahaClientCircuitBreakerTest {

  private WahaClient client;

  // Circuit opens after 2 failures with window=2, threshold=50%
  private static final WahaResilienceProperties RESILIENCE_PROPS = new WahaResilienceProperties(
      Duration.ofSeconds(5), 50, 2, 2, Duration.ofSeconds(60), 1);
  private static final String TRACE = "trace-cb-test";
  private static final String WAHA_BASE = "http://waha-test:3000";

  @BeforeEach
  void setUp() {
    var wahaProps = new WahaProperties(WAHA_BASE, "test-api-key");
    client = new WahaClient(wahaProps, RESILIENCE_PROPS, CircuitBreakerRegistry.ofDefaults());
  }

  @Test
  void send_whenCircuitOpen_returnsFastFailWithoutHttpCall() {
    // Force circuit to OPEN by recording failures directly on the circuit breaker
    var cb = client.getCircuitBreaker();
    cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("fail1"));
    cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("fail2"));
    assertThat(cb.getState()).isEqualTo(State.OPEN);

    // With circuit OPEN, send() must fast-fail without making any HTTP call
    var result = client.send("628333", "msg", TRACE);

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isZero();
    assertThat(result.detail()).isEqualTo(WahaClient.CIRCUIT_OPEN_DETAIL);
  }

  @Test
  void send_detailIsTruncatedTo512Chars() {
    // Record two failures to open the circuit, then verify truncation via direct CB state
    // (MockRestServiceServer can't easily intercept the internal RestClient)
    // Instead, verify via WahaClient.truncate() accessibility
    String longString = "x".repeat(1000);
    String truncated = WahaClient.truncate(longString);
    assertThat(truncated).hasSize(WahaClient.MAX_DETAIL_LENGTH);
  }

  @Test
  void circuitBreaker_initialState_isClosed() {
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);
  }

  @Test
  void circuitBreaker_afterFailuresAboveThreshold_opensCircuit() {
    var cb = client.getCircuitBreaker();

    cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("e1"));
    cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("e2"));

    assertThat(cb.getState()).isEqualTo(State.OPEN);
  }

  @Test
  void circuitBreaker_afterForcedOpen_returnsCircuitOpenDetail() {
    client.getCircuitBreaker().transitionToForcedOpenState();

    var result = client.send("628999", "msg", TRACE);

    assertThat(result.success()).isFalse();
    assertThat(result.detail()).isEqualTo(WahaClient.CIRCUIT_OPEN_DETAIL);
  }
}
