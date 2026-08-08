package com.syncro.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.event.MqttConnectionFailedEvent;
import org.springframework.integration.mqtt.event.MqttSubscribedEvent;

class MqttConnectionStatusTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-08-08T10:00:00Z");

  private final MqttConnectionStatus status =
      new MqttConnectionStatus(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

  @Test
  void initialStateIsUnknown() {
    assertThat(status.state()).isEqualTo(MqttConnectionStatus.State.UNKNOWN);
  }

  @Test
  void subscribedEventTransitionsToSubscribed() {
    status.onApplicationEvent(new MqttSubscribedEvent(status, "factory/+/+/telemetry"));

    assertThat(status.state()).isEqualTo(MqttConnectionStatus.State.SUBSCRIBED);
    assertThat(status.lastChange()).isEqualTo(FIXED_NOW);
  }

  @Test
  void connectionFailedEventTransitionsToFailedWithCause() {
    status.onApplicationEvent(new MqttConnectionFailedEvent(status, new RuntimeException("boom")));

    assertThat(status.state()).isEqualTo(MqttConnectionStatus.State.FAILED);
    assertThat(status.lastError()).isEqualTo("boom");
    assertThat(status.lastChange()).isEqualTo(FIXED_NOW);
  }

  @Test
  void subscribedEventAfterFailureClearsLastError() {
    status.onApplicationEvent(new MqttConnectionFailedEvent(status, new RuntimeException("boom")));
    status.onApplicationEvent(new MqttSubscribedEvent(status, "factory/+/+/telemetry"));

    assertThat(status.state()).isEqualTo(MqttConnectionStatus.State.SUBSCRIBED);
    assertThat(status.lastError()).isNull();
  }
}
