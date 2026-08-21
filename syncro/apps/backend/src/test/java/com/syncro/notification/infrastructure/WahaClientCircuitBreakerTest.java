package com.syncro.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.config.WahaProperties;
import com.syncro.config.WahaResilienceProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WahaClient} circuit breaker behaviour.
 *
 * <p>Uses a lightweight JDK {@link HttpServer} to drive real HTTP calls through the
 * circuit breaker, verifying that actual 5xx errors open the circuit, 4xx errors do not,
 * timeouts produce the expected failure result, and the half-open probe cycle functions
 * correctly.
 */
class WahaClientCircuitBreakerTest {

  private WahaClient client;
  private HttpServer server;
  private int serverPort;

  // Circuit opens after 2 failures with window=2, threshold=50%
  private static final WahaResilienceProperties RESILIENCE_PROPS = new WahaResilienceProperties(
      Duration.ofSeconds(5), 50, 2, 2, Duration.ofSeconds(60), 1);
  private static final String TRACE = "trace-cb-test";

  @FunctionalInterface
  interface ThrowingHandler {
    void accept(HttpExchange exchange) throws IOException;
  }

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.setExecutor(null);
    server.start();
    serverPort = server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  private WahaClient createClientWithHandler(
      ThrowingHandler handler,
      WahaResilienceProperties props) {
    server.createContext("/", exchange -> {
      try {
        handler.accept(exchange);
      } catch (Exception e) {
        try { exchange.sendResponseHeaders(500, -1); } catch (IOException ignored) {}
      } finally {
        exchange.close();
      }
    });
    var wahaProps = new WahaProperties("http://localhost:" + serverPort, "test-api-key");
    return new WahaClient(wahaProps, props, CircuitBreakerRegistry.ofDefaults());
  }

  @Test
  void send_whenWahaReturns200_returnsSuccess() throws IOException {
    client = createClientWithHandler(exchange -> {
      var body = "{\"sent\":true}".getBytes();
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
    }, RESILIENCE_PROPS);

    var result = client.send("628111", "hello", TRACE);

    assertThat(result.success()).isTrue();
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);
  }

  @Test
  void send_whenWahaReturns500_opensCircuitAfterThreshold() throws IOException {
    client = createClientWithHandler(exchange ->
        exchange.sendResponseHeaders(500, -1), RESILIENCE_PROPS);

    var result1 = client.send("628111", "msg", TRACE);
    assertThat(result1.success()).isFalse();
    assertThat(result1.httpStatus()).isEqualTo(500);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);

    var result2 = client.send("628111", "msg", TRACE);
    assertThat(result2.success()).isFalse();
    assertThat(result2.httpStatus()).isEqualTo(500);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.OPEN);
  }

  @Test
  void send_whenCircuitOpenAfterRealHttp_fastFailsWithoutHttpCall() throws IOException {
    client = createClientWithHandler(exchange ->
        exchange.sendResponseHeaders(500, -1), RESILIENCE_PROPS);

    client.send("628111", "msg", TRACE);
    client.send("628111", "msg", TRACE);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.OPEN);

    var result = client.send("628333", "msg", TRACE);
    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isZero();
    assertThat(result.detail()).isEqualTo(WahaClient.CIRCUIT_OPEN_DETAIL);
  }

  @Test
  void send_whenWahaReturns400_doesNotOpenCircuit() throws IOException {
    client = createClientWithHandler(exchange ->
        exchange.sendResponseHeaders(400, -1), RESILIENCE_PROPS);

    var result = client.send("628111", "msg", TRACE);
    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(400);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);
    assertThat(client.getCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
  }

  @Test
  void send_whenConnectionRefused_returnsFailedResultWithoutOpeningCircuit() {
    var closedPort = 1;
    var wahaProps = new WahaProperties("http://127.0.0.1:" + closedPort, "test-api-key");
    client = new WahaClient(wahaProps, RESILIENCE_PROPS, CircuitBreakerRegistry.ofDefaults());

    var result = client.send("628111", "msg", TRACE);

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isZero();
    assertThat(result.detail()).isNotBlank();
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);
  }

  @Test
  void send_whenWahaSlowerThanTimeout_returnsFailedResultAndEndsInBoundTime() throws Exception {
    var timeoutProps = new WahaResilienceProperties(
        Duration.ofMillis(300), 50, 2, 2, Duration.ofSeconds(60), 1);
    var handler = new AtomicReference<ThrowingHandler>();
    client = createClientWithHandler(ex -> handler.get().accept(ex), timeoutProps);
    handler.set(ex -> {
      try {
        Thread.sleep(5_000);
        ex.sendResponseHeaders(200, -1);
      } catch (InterruptedException | IOException ignored) {
      }
    });

    long start = System.nanoTime();
    var result = client.send("628111", "msg", TRACE);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isZero();
    assertThat(elapsedMs).isGreaterThan(0).isLessThan(4_000);
  }

  @Test
  void send_detailIsTruncatedTo512Chars() {
    String longString = "x".repeat(1000);
    String truncated = WahaClient.truncate(longString);
    assertThat(truncated).hasSize(WahaClient.MAX_DETAIL_LENGTH);
  }

  @Test
  void circuitBreaker_initialState_isClosed() throws IOException {
    client = createClientWithHandler(exchange ->
        exchange.sendResponseHeaders(200, -1), RESILIENCE_PROPS);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);
  }

  @Test
  void circuitBreaker_afterFailuresAboveThreshold_opensCircuit() throws IOException {
    client = createClientWithHandler(exchange ->
        exchange.sendResponseHeaders(500, -1), RESILIENCE_PROPS);

    client.send("628111", "msg", TRACE);
    client.send("628111", "msg", TRACE);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.OPEN);
  }

  @Test
  void send_whenCircuitForcedOpen_returnsCircuitOpenDetail() throws IOException {
    client = createClientWithHandler(exchange ->
        exchange.sendResponseHeaders(500, -1), RESILIENCE_PROPS);
    client.getCircuitBreaker().transitionToForcedOpenState();

    var result = client.send("628999", "msg", TRACE);
    assertThat(result.success()).isFalse();
    assertThat(result.detail()).isEqualTo(WahaClient.CIRCUIT_OPEN_DETAIL);
  }

  @Test
  void halfOpen_probeSucceeds_closesCircuit() throws Exception {
    var shortWaitProps = new WahaResilienceProperties(
        Duration.ofSeconds(5), 50, 2, 2, Duration.ofMillis(200), 1);

    var handler = new AtomicReference<ThrowingHandler>();
    client = createClientWithHandler(ex -> handler.get().accept(ex), shortWaitProps);

    handler.set(ex -> ex.sendResponseHeaders(500, -1));
    client.send("628111", "msg", TRACE);
    client.send("628111", "msg", TRACE);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.OPEN);

    Thread.sleep(400);

    handler.set(ex -> {
      var body = "{\"sent\":true}".getBytes();
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    });
    var result = client.send("628111", "msg", TRACE);
    assertThat(result.success()).isTrue();
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);
  }
}