package com.syncro.telemetry.infrastructure;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class MqttHealthIndicator implements HealthIndicator {

  private final MqttConnectionStatus status;

  public MqttHealthIndicator(MqttConnectionStatus status) {
    this.status = status;
  }

  @Override
  public Health health() {
    MqttConnectionStatus.State state = status.state();
    boolean up = state == MqttConnectionStatus.State.SUBSCRIBED;
    if (up) {
      return Health.up().build();
    }
    Health.Builder down = Health.down();
    if (status.lastError() != null) {
      down.withDetail("lastError", status.lastError());
    }
    return down.build();
  }
}
