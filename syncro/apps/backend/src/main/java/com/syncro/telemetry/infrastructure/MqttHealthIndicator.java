package com.syncro.telemetry.infrastructure;

import com.syncro.health.DependencyHealthSupport;
import java.time.Clock;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class MqttHealthIndicator implements HealthIndicator {

  private final MqttConnectionStatus status;
  private final Clock clock;

  public MqttHealthIndicator(MqttConnectionStatus status, Clock clock) {
    this.status = status;
    this.clock = clock;
  }

  @Override
  public Health health() {
    MqttConnectionStatus.State state = status.state();
    boolean up = state == MqttConnectionStatus.State.SUBSCRIBED;
    Health.Builder builder = up ? Health.up() : Health.down();
    String reason = null;
    if (!up) {
      // Never emit the raw lastError text here — /actuator/health is unauthenticated with
      // show-details=always, so the cause is logged server-side by MqttConnectionStatus and
      // only a stable code is exposed (AC 6 / sanitisation decision).
      reason = state == MqttConnectionStatus.State.FAILED
          ? "MQTT_CONNECTION_FAILED"
          : "MQTT_NOT_SUBSCRIBED";
    }
    return DependencyHealthSupport.enrich(builder.build(), clock, reason, null);
  }
}
