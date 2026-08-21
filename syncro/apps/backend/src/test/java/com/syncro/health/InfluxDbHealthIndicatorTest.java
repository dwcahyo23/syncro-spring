package com.syncro.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import com.syncro.config.InfluxProperties;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.web.client.MockRestServiceServer;

class InfluxDbHealthIndicatorTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC);
  private static final String FIXED_TIMESTAMP = "2026-08-21T08:00:00Z";

  private final InfluxProperties properties =
      new InfluxProperties("http://localhost:9999", "secret-token", "syncro");
  private final InfluxDbHealthIndicator indicator = new InfluxDbHealthIndicator(properties, FIXED_CLOCK);
  private MockRestServiceServer server;

  @BeforeEach
  void setUp() {
    server = MockRestServiceServer.bindTo(indicator.restClientBuilder()).build();
  }

  @Test
  void health_whenPingSucceeds_returnsUpWithContractFields() {
    server.expect(requestTo("http://localhost:9999/ping")).andRespond(withSuccess());

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Up")
        .containsEntry("statusSeverity", "SUCCESS")
        .containsEntry("timestamp", FIXED_TIMESTAMP);
  }

  @Test
  void health_whenPingReturnsServerError_returnsDownWithoutThrowing() {
    server.expect(requestTo("http://localhost:9999/ping")).andRespond(withServerError());

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Down")
        .containsEntry("statusSeverity", "CRITICAL")
        .containsEntry("timestamp", FIXED_TIMESTAMP)
        .containsEntry("statusReason", "SERVER_ERROR");
  }

  @Test
  void health_whenPingFailsToConnect_returnsDownWithoutThrowing() {
    server.expect(requestTo("http://localhost:9999/ping"))
        .andRespond(withException(new IOException("Connection refused: localhost:9999")));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Down")
        .containsEntry("statusSeverity", "CRITICAL")
        .containsEntry("timestamp", FIXED_TIMESTAMP)
        .containsEntry("statusReason", "CONNECTION_REFUSED");
  }
}