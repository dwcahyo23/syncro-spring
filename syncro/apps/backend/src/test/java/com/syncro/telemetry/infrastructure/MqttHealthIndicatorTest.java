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

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC);
  private static final String FIXED_TIMESTAMP = "2026-08-08T10:00:00Z";

  private final MqttConnectionStatus status = new MqttConnectionStatus(FIXED_CLOCK);
  private final MqttHealthIndicator indicator = new MqttHealthIndicator(status, FIXED_CLOCK);

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
  void failedStateIsDownWithoutLeakingRawLastError() {
    status.onApplicationEvent(new MqttConnectionFailedEvent(status, new RuntimeException("boom")));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    // Sanitisation: raw cause text must not reach the unauthenticated payload.
    assertThat(health.getDetails())
        .doesNotContainKey("lastError")
        .doesNotContainValue("boom")
        .containsEntry("statusReason", "MQTT_CONNECTION_FAILED");
  }

  @Test
  void recoveryAfterFailureIsUpWithoutStaleLastError() {
    status.onApplicationEvent(new MqttConnectionFailedEvent(status, new RuntimeException("boom")));
    status.onApplicationEvent(new MqttSubscribedEvent(status, "factory/+/+/telemetry"));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).doesNotContainKey("lastError");
  }

  @Test
  void upHealthCarriesOperationalStatusContractFields() {
    status.onApplicationEvent(new MqttSubscribedEvent(status, "factory/+/+/telemetry"));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Up")
        .containsEntry("statusSeverity", "SUCCESS")
        .containsEntry("timestamp", FIXED_TIMESTAMP);
  }

  @Test
  void downHealthCarriesOperationalStatusContractFieldsWithReason() {
    status.onApplicationEvent(new MqttConnectionFailedEvent(status, new RuntimeException("boom")));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Down")
        .containsEntry("statusSeverity", "CRITICAL")
        .containsEntry("statusReason", "MQTT_CONNECTION_FAILED")
        .containsEntry("timestamp", FIXED_TIMESTAMP);
  }

  @Test
  void unknownStateDownUsesStableNotSubscribedReason() {
    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("statusReason", "MQTT_NOT_SUBSCRIBED");
  }
}