package com.syncro.telemetry.infrastructure;

import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationListener;
import org.springframework.integration.mqtt.event.MqttConnectionFailedEvent;
import org.springframework.integration.mqtt.event.MqttIntegrationEvent;
import org.springframework.integration.mqtt.event.MqttSubscribedEvent;
import org.springframework.stereotype.Component;

@Component
public class MqttConnectionStatus implements ApplicationListener<MqttIntegrationEvent> {

  public enum State {
    UNKNOWN,
    SUBSCRIBED,
    FAILED
  }

  private final Clock clock;
  private volatile State state = State.UNKNOWN;
  private volatile String lastError;
  private volatile Instant lastChange = Instant.EPOCH;

  public MqttConnectionStatus(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void onApplicationEvent(MqttIntegrationEvent event) {
    if (event instanceof MqttSubscribedEvent) {
      lastError = null;
      setState(State.SUBSCRIBED);
    } else if (event instanceof MqttConnectionFailedEvent failedEvent) {
      lastError = failedEvent.getCause() == null ? "unknown" : failedEvent.getCause().getMessage();
      setState(State.FAILED);
    }
  }

  private void setState(State newState) {
    state = newState;
    lastChange = Instant.now(clock);
  }

  public State state() {
    return state;
  }

  public String lastError() {
    return lastError;
  }

  public Instant lastChange() {
    return lastChange;
  }
}
