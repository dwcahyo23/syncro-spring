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
      // TODO (DW-14): Spring Integration MQTT 7.x does not publish a mid-session disconnect event
      // observable here. With setAutomaticReconnect(true), Paho handles drops in its background
      // thread and does not emit MqttConnectionFailedEvent for reconnect cycles — only for initial
      // connect failures. The health indicator therefore stays SUBSCRIBED during a broker outage
      // that begins after initial subscribe. Revisit if a later Spring Integration version exposes
      // a connection-lost event, or if a Paho IMqttActionListener/connectionLost callback can be
      // wired to update this state.
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
