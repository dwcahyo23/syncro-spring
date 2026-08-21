package com.syncro.telemetry.application;

import com.syncro.config.TelemetryProperties;
import com.syncro.telemetry.infrastructure.MqttConnectionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.stereotype.Service;

/**
 * Assembles the telemetry ingest worker health status from the MQTT subscription state,
 * the ingest tracker (last accepted telemetry) and the ingest queue.
 *
 * <p>State derivation (message-driven pipeline, so state is inferred — there is no poller
 * thread to inspect):
 * <ul>
 *   <li>{@code MQTT UNKNOWN} → {@code STOPPED} (adapter never connected/subscribed)</li>
 *   <li>{@code MQTT FAILED} → {@code DEGRADED} (connectivity failure, may auto-recover)</li>
 *   <li>{@code MQTT SUBSCRIBED} + no telemetry yet → {@code RUNNING} (awaiting first message)</li>
 *   <li>{@code MQTT SUBSCRIBED} + last accepted within stale threshold → {@code RUNNING}</li>
 *   <li>{@code MQTT SUBSCRIBED} + last accepted older than stale threshold → {@code DEGRADED}
 *       (stale telemetry, reason + timestamps)</li>
 * </ul>
 */
@Service
public class IngestWorkerStatusService {

  private final MqttConnectionStatus mqttStatus;
  private final TelemetryIngestTracker tracker;
  private final QueueChannel telemetryIngestQueue;
  private final TelemetryProperties properties;
  private final Clock clock;

  public IngestWorkerStatusService(MqttConnectionStatus mqttStatus,
      TelemetryIngestTracker tracker, QueueChannel telemetryIngestQueue,
      TelemetryProperties properties, Clock clock) {
    this.mqttStatus = mqttStatus;
    this.tracker = tracker;
    this.telemetryIngestQueue = telemetryIngestQueue;
    this.properties = properties;
    this.clock = clock;
  }

  public IngestWorkerStatus status() {
    MqttConnectionStatus.State mqttState = mqttStatus.state();
    Instant lastAcceptedAt = tracker.lastAcceptedAt();
    Duration staleThreshold = properties.ingest().staleThreshold();
    Instant now = clock.instant();

    IngestWorkerState state;
    String reason;
    Instant staleSince = null;

    switch (mqttState) {
      case UNKNOWN -> {
        state = IngestWorkerState.STOPPED;
        reason = "MQTT not subscribed";
      }
      case FAILED -> {
        state = IngestWorkerState.DEGRADED;
        String lastError = mqttStatus.lastError();
        reason = lastError == null ? "MQTT connection failed" : "MQTT connection failed: " + lastError;
      }
      case SUBSCRIBED -> {
        if (lastAcceptedAt == null) {
          state = IngestWorkerState.RUNNING;
          reason = null;
        } else if (Duration.between(lastAcceptedAt, now).compareTo(staleThreshold) <= 0) {
          state = IngestWorkerState.RUNNING;
          reason = null;
        } else {
          state = IngestWorkerState.DEGRADED;
          reason = "No telemetry accepted since " + lastAcceptedAt;
          staleSince = lastAcceptedAt.plus(staleThreshold);
        }
      }
      default -> {
        state = IngestWorkerState.STOPPED;
        reason = "MQTT state unknown: " + mqttState;
      }
    }

    int queueDepth = telemetryIngestQueue.getQueueSize();
    int queueCapacity = properties.ingest().queueCapacity();

    return new IngestWorkerStatus(
        state,
        state.statusLabel(),
        state.statusSeverity(),
        reason,
        now.toString(),
        mqttState.name(),
        lastAcceptedAt == null ? null : lastAcceptedAt.toString(),
        staleSince == null ? null : staleSince.toString(),
        queueDepth,
        queueCapacity,
        tracker.acceptedCount());
  }
}
