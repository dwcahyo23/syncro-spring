package com.syncro.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.integration.mqtt.event.MqttConnectionFailedEvent;
import org.springframework.integration.mqtt.event.MqttSubscribedEvent;

class MqttHealthIndicatorTest {

  private final MqttConnectionStatus status =
      new MqttConnectionStatus(Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC));
  private final MqttHealthIndicator indicator = new MqttHealthIndicator(status);

  @Test
  void unknownStateIsDown() {
    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void subscribedStateIsUp() {
    status.onApplicationEvent(new MqttSubscribedEvent(status, "factory/+/+/telemetry"));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void failedStateIsDownWithLastErrorDetail() {
    status.onApplicationEvent(new MqttConnectionFailedEvent(status, new RuntimeException("boom")));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("lastError", "boom");
  }

  @Test
  void recoveryAfterFailureIsUpWithoutStaleLastError() {
    status.onApplicationEvent(new MqttConnectionFailedEvent(status, new RuntimeException("boom")));
    status.onApplicationEvent(new MqttSubscribedEvent(status, "factory/+/+/telemetry"));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).doesNotContainKey("lastError");
  }
}
