package com.syncro.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link com.syncro.authz.infrastructure.OpaClient} circuit breaker
 * behaviour, following the {@code WahaClientCircuitBreakerTest} JDK HttpServer pattern.
 */
class OpaClientCircuitBreakerTest {

  private com.syncro.authz.infrastructure.OpaClient client;
  private HttpServer server;
  private int serverPort;
  private final AtomicInteger hits = new AtomicInteger();

  // Circuit opens after 2 failures with window=2, threshold=50%
  private static final com.syncro.config.OpaResilienceProperties RESILIENCE_PROPS =
      new com.syncro.config.OpaResilienceProperties(
          Duration.ofSeconds(5), 50, 2, 2, Duration.ofSeconds(60), 1);

  @FunctionalInterface
  interface ThrowingHandler {
    void accept(HttpExchange exchange) throws IOException;
  }

  @BeforeEach
  void setUp() throws IOException {
    hits.set(0);
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

  private com.syncro.authz.infrastructure.OpaClient createClientWithHandler(ThrowingHandler handler) {
    return createClientWithHandler(handler, hits::incrementAndGet);
  }

  private com.syncro.authz.infrastructure.OpaClient createClientWithHandler(
      ThrowingHandler handler,
      Runnable onHit) {
    server.createContext("/", exchange -> {
      onHit.run();
      try {
        handler.accept(exchange);
      } catch (Exception e) {
        try { exchange.sendResponseHeaders(500, -1); } catch (IOException ignored) {}
      } finally {
        exchange.close();
      }
    });
    var opaProps = new com.syncro.config.OpaProperties("http://localhost:" + serverPort);
    return new com.syncro.authz.infrastructure.OpaClient(
        opaProps, RESILIENCE_PROPS, CircuitBreakerRegistry.ofDefaults());
  }

  @Test
  void post_whenOpaReturns200WithTrueResult_parsesDecisionEnvelope() throws IOException {
    client = createClientWithHandler(exchange -> respond(exchange,
        "{\"decision_id\":\"d1\",\"result\":true}"));

    var result = client.post("allow", input());

    assertThat(result.success()).isTrue();
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.decisionId()).isEqualTo("d1");
    assertThat(result.allowed()).isTrue();
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);
  }

  @Test
  void post_whenOpaReturns200WithFalseResult_reportsDeniedButSuccessfulCall() throws IOException {
    client = createClientWithHandler(exchange -> respond(exchange,
        "{\"decision_id\":\"d2\",\"result\":false}"));

    var result = client.post("allow", input());

    assertThat(result.success()).isTrue();
    assertThat(result.decisionId()).isEqualTo("d2");
    assertThat(result.allowed()).isFalse();
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);
  }

  @Test
  void post_whenOpaReturns500_opensCircuitAfterThreshold() throws IOException {
    client = createClientWithHandler(exchange ->
        exchange.sendResponseHeaders(500, -1));

    var result1 = client.post("allow", input());
    assertThat(result1.success()).isFalse();
    assertThat(result1.httpStatus()).isEqualTo(500);
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);

    var result2 = client.post("allow", input());
    assertThat(result2.success()).isFalse();
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.OPEN);
  }

  @Test
  void post_whenOpaSlowerThanTimeout_returnsFailedResultInBoundTime() throws Exception {
    var timeoutProps = new com.syncro.config.OpaResilienceProperties(
        Duration.ofMillis(300), 50, 2, 2, Duration.ofSeconds(60), 1);
    var handler = new AtomicReference<ThrowingHandler>();
    server.createContext("/", exchange -> {
      try {
        handler.get().accept(exchange);
      } finally {
        exchange.close();
      }
    });
    var opaProps = new com.syncro.config.OpaProperties("http://localhost:" + serverPort);
    client = new com.syncro.authz.infrastructure.OpaClient(
        opaProps, timeoutProps, CircuitBreakerRegistry.ofDefaults());
    handler.set(ex -> {
      try {
        Thread.sleep(5_000);
        ex.sendResponseHeaders(200, -1);
      } catch (InterruptedException | IOException ignored) {
      }
    });

    long start = System.nanoTime();
    var result = client.post("allow", input());
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isZero();
    assertThat(elapsedMs).isGreaterThan(0).isLessThan(4_000);
  }

  @Test
  void post_whenCircuitForcedOpen_failsImmediatelyWithoutHittingTheServer() throws IOException {
    client = createClientWithHandler(exchange ->
        exchange.sendResponseHeaders(500, -1));

    client.post("allow", input());
    client.post("allow", input());
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.OPEN);
    int hitsBeforeForcedOpen = hits.get();

    client.getCircuitBreaker().transitionToForcedOpenState();
    var result = client.post("allow", input());

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isZero();
    assertThat(result.body())
        .isEqualTo(com.syncro.authz.infrastructure.OpaClient.CIRCUIT_OPEN_DETAIL);
    assertThat(hits.get()).isEqualTo(hitsBeforeForcedOpen);
  }

  @Test
  void post_whenConnectionRefused_returnsFailedResultWithoutOpeningCircuit() {
    var closedPort = 1;
    var opaProps = new com.syncro.config.OpaProperties("http://127.0.0.1:" + closedPort);
    client = new com.syncro.authz.infrastructure.OpaClient(
        opaProps, RESILIENCE_PROPS, CircuitBreakerRegistry.ofDefaults());

    var result = client.post("allow", input());

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isZero();
    assertThat(result.body()).isNotBlank();
    assertThat(client.getCircuitBreaker().getState()).isEqualTo(State.CLOSED);
  }

  private static Map<String, String> input() {
    return Map.of("ping", "pong");
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    var bytes = body.getBytes();
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
