package com.syncro.integration.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.config.WebhookProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story 22-1: real-HttpServer circuit-breaker coverage for {@link WebhookHttpClient},
 * mirroring {@code WahaClientCircuitBreakerTest}: 5xx opens the circuit, 4xx returns a
 * failed Result without tripping it, timeouts fail bounded, and an OPEN breaker
 * fast-fails without an HTTP call.
 */
class WebhookHttpClientCircuitBreakerTest {

  private HttpServer server;
  private int serverPort;

  // Circuit opens after 2 failures with window=2, threshold=50%
  private static final WebhookProperties RESILIENCE_PROPS = new WebhookProperties(
      new WebhookProperties.Worker(30_000), 3, 60,
      new WebhookProperties.Resilience(Duration.ofSeconds(5), 50, 2, 2, Duration.ofSeconds(60), 1));
  private static final String TRACE = "trace-cb-test";
  private static final String BODY = "{\"eventType\":\"CLOSED\"}";
  private static final String SIGNATURE = "deadbeef";

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

  private WebhookHttpClient createClientWithHandler(ThrowingHandler handler,
      WebhookProperties props) {
    server.createContext("/", exchange -> {
      try {
        handler.accept(exchange);
      } catch (Exception e) {
        try {
          exchange.sendResponseHeaders(500, -1);
        } catch (IOException ignored) {
        }
      } finally {
        exchange.close();
      }
    });
    return new WebhookHttpClient(props, CircuitBreakerRegistry.ofDefaults());
  }

  private String url() {
    return "http://localhost:" + serverPort + "/hook";
  }

  @Test
  @DisplayName("22.1-CB-001 P0 2xx response → success Result, circuit CLOSED")
  void post_whenEndpointReturns200_returnsSuccess() {
    var client = createClientWithHandler(exchange -> {
      var body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
    }, RESILIENCE_PROPS);

    var result = client.post(url(), BODY, SIGNATURE, TRACE);

    assertThat(result.success()).isTrue();
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);
  }

  @Test
  @DisplayName("22.1-CB-002 P0 signature header reaches the subscriber verbatim")
  void post_sendsSignatureHeader() throws IOException {
    var received = new AtomicReference<String>();
    var client = createClientWithHandler(exchange -> {
      received.set(exchange.getRequestHeaders().getFirst("X-Syncro-Signature"));
      exchange.sendResponseHeaders(200, -1);
    }, RESILIENCE_PROPS);

    client.post(url(), BODY, SIGNATURE, TRACE);

    assertThat(received.get()).isEqualTo(SIGNATURE);
  }

  @Test
  @DisplayName("22.1-CB-003 P0 5xx opens the circuit after the failure threshold")
  void post_whenEndpointReturns500_opensCircuitAfterThreshold() {
    var client = createClientWithHandler(exchange ->
        exchange.sendResponseHeaders(500, -1), RESILIENCE_PROPS);

    var result1 = client.post(url(), BODY, SIGNATURE, TRACE);
    assertThat(result1.success()).isFalse();
    assertThat(result1.httpStatus()).isEqualTo(500);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);

    var result2 = client.post(url(), BODY, SIGNATURE, TRACE);
    assertThat(result2.success()).isFalse();
    assertThat(result2.httpStatus()).isEqualTo(500);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.OPEN);
  }

  @Test
  @DisplayName("22.1-CB-004 P0 OPEN breaker fast-fails with the stable circuit-open detail")
  void post_whenCircuitOpen_fastFailsWithoutHttpCall() {
    var client = createClientWithHandler(exchange ->
        exchange.sendResponseHeaders(500, -1), RESILIENCE_PROPS);

    client.post(url(), BODY, SIGNATURE, TRACE);
    client.post(url(), BODY, SIGNATURE, TRACE);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.OPEN);

    var result = client.post(url(), BODY, SIGNATURE, TRACE);
    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isZero();
    assertThat(result.detail()).isEqualTo(WebhookHttpClient.CIRCUIT_OPEN_DETAIL);
  }

  @Test
  @DisplayName("22.1-CB-005 P0 4xx returns failed Result WITHOUT tripping the circuit")
  void post_whenEndpointReturns400_doesNotOpenCircuit() {
    var client = createClientWithHandler(exchange ->
        exchange.sendResponseHeaders(400, -1), RESILIENCE_PROPS);

    var result = client.post(url(), BODY, SIGNATURE, TRACE);

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(400);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);
    assertThat(client.getCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
  }

  @Test
  @DisplayName("22.1-CB-006 P1 connection refused → failed Result, circuit untouched")
  void post_whenConnectionRefused_returnsFailedResult() {
    var props = new WebhookProperties(new WebhookProperties.Worker(30_000), 3, 60,
        new WebhookProperties.Resilience(Duration.ofMillis(500), 50, 2, 2, Duration.ofSeconds(60), 1));
    var client = new WebhookHttpClient(props, CircuitBreakerRegistry.ofDefaults());

    var result = client.post("http://127.0.0.1:1/hook", BODY, SIGNATURE, TRACE);

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isZero();
    assertThat(result.detail()).isNotBlank();
  }

  @Test
  @DisplayName("22.1-CB-007 P1 slow subscriber times out within the configured bound")
  void post_whenEndpointSlowerThanTimeout_failsInBoundTime() throws Exception {
    var timeoutProps = new WebhookProperties(new WebhookProperties.Worker(30_000), 3, 60,
        new WebhookProperties.Resilience(Duration.ofMillis(300), 50, 2, 2, Duration.ofSeconds(60), 1));
    var handler = new AtomicReference<ThrowingHandler>();
    var client = createClientWithHandler(ex -> handler.get().accept(ex), timeoutProps);
    handler.set(ex -> {
      try {
        Thread.sleep(5_000);
        ex.sendResponseHeaders(200, -1);
      } catch (InterruptedException | IOException ignored) {
      }
    });

    long start = System.nanoTime();
    var result = client.post(url(), BODY, SIGNATURE, TRACE);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isZero();
    assertThat(elapsedMs).isGreaterThan(0).isLessThan(4_000);
  }

  @Test
  @DisplayName("22.1-CB-008 P2 response detail truncated to 512 characters")
  void truncate_capsAtMaxDetailLength() {
    assertThat(WebhookHttpClient.truncate("x".repeat(1000)))
        .hasSize(WebhookHttpClient.MAX_DETAIL_LENGTH);
    assertThat(WebhookHttpClient.truncate(null)).isNull();
  }

  @Test
  @DisplayName("22.1-CB-009 P1 half-open probe success closes the circuit")
  void halfOpen_probeSucceeds_closesCircuit() throws Exception {
    var shortWaitProps = new WebhookProperties(new WebhookProperties.Worker(30_000), 3, 60,
        new WebhookProperties.Resilience(Duration.ofSeconds(5), 50, 2, 2, Duration.ofMillis(200), 1));
    var handler = new AtomicReference<ThrowingHandler>();
    var client = createClientWithHandler(ex -> handler.get().accept(ex), shortWaitProps);

    handler.set(ex -> ex.sendResponseHeaders(500, -1));
    client.post(url(), BODY, SIGNATURE, TRACE);
    client.post(url(), BODY, SIGNATURE, TRACE);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.OPEN);

    Thread.sleep(400);

    handler.set(ex -> {
      var body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    var result = client.post(url(), BODY, SIGNATURE, TRACE);

    assertThat(result.success()).isTrue();
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);
  }
}
