package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.syncro.config.TelemetryProperties;
import com.syncro.telemetry.infrastructure.MqttConnectionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.channel.QueueChannel;

@ExtendWith(MockitoExtension.class)
class IngestWorkerStatusServiceTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-08-21T08:00:00Z");
  private static final Clock FIXED_CLOCK =
      Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);

  @Mock
  private MqttConnectionStatus mqttStatus;

  @Mock
  private QueueChannel telemetryIngestQueue;

  private final TelemetryIngestTracker tracker = new TelemetryIngestTracker(FIXED_CLOCK);

  private IngestWorkerStatusService service(int queueDepth) {
    when(telemetryIngestQueue.getQueueSize()).thenReturn(queueDepth);
    return new IngestWorkerStatusService(mqttStatus, tracker, telemetryIngestQueue,
        properties(), FIXED_CLOCK);
  }

  private static TelemetryProperties properties() {
    return new TelemetryProperties(
        Duration.ofMinutes(5),
        Duration.ofSeconds(30),
        new TelemetryProperties.Ingest(1000, 2, STALE_THRESHOLD));
  }

  @Test
  void unknownMqttStateReportsStopped() {
    when(mqttStatus.state()).thenReturn(MqttConnectionStatus.State.UNKNOWN);

    IngestWorkerStatus status = service(0).status();

    assertThat(status.status()).isEqualTo(IngestWorkerState.STOPPED);
    assertThat(status.statusLabel()).isEqualTo("Stopped");
    assertThat(status.statusSeverity()).isEqualTo("CRITICAL");
    assertThat(status.statusReason()).isEqualTo("MQTT not subscribed");
    assertThat(status.mqttState()).isEqualTo("UNKNOWN");
  }

  @Test
  void failedMqttStateReportsDegradedWithLastError() {
    when(mqttStatus.state()).thenReturn(MqttConnectionStatus.State.FAILED);
    when(mqttStatus.lastError()).thenReturn("connection lost");

    IngestWorkerStatus status = service(0).status();

    assertThat(status.status()).isEqualTo(IngestWorkerState.DEGRADED);
    assertThat(status.statusLabel()).isEqualTo("Degraded");
    assertThat(status.statusSeverity()).isEqualTo("WARNING");
    assertThat(status.statusReason()).contains("connection lost");
  }

  @Test
  void subscribedWithNoTelemetryYetReportsRunning() {
    when(mqttStatus.state()).thenReturn(MqttConnectionStatus.State.SUBSCRIBED);

    IngestWorkerStatus status = service(0).status();

    assertThat(status.status()).isEqualTo(IngestWorkerState.RUNNING);
    assertThat(status.statusSeverity()).isEqualTo("SUCCESS");
    assertThat(status.statusReason()).isNull();
    assertThat(status.lastAcceptedAt()).isNull();
    assertThat(status.staleSince()).isNull();
  }

  @Test
  void subscribedWithRecentTelemetryReportsRunning() {
    when(mqttStatus.state()).thenReturn(MqttConnectionStatus.State.SUBSCRIBED);
    tracker.recordAccepted();

    IngestWorkerStatus status = service(2).status();

    assertThat(status.status()).isEqualTo(IngestWorkerState.RUNNING);
    assertThat(status.statusReason()).isNull();
    assertThat(status.lastAcceptedAt()).isEqualTo(FIXED_NOW.toString());
    assertThat(status.acceptedCount()).isEqualTo(1);
  }

  @Test
  void subscribedWithStaleTelemetryReportsDegradedWithReasonAndTimestamps() {
    when(mqttStatus.state()).thenReturn(MqttConnectionStatus.State.SUBSCRIBED);
    TelemetryIngestTracker staleTracker = new TelemetryIngestTracker(
        Clock.fixed(FIXED_NOW.minus(Duration.ofMinutes(10)), ZoneOffset.UTC));
    staleTracker.recordAccepted();
    IngestWorkerStatusService staleService = new IngestWorkerStatusService(mqttStatus, staleTracker,
        telemetryIngestQueue, properties(), FIXED_CLOCK);
    when(telemetryIngestQueue.getQueueSize()).thenReturn(0);

    IngestWorkerStatus status = staleService.status();

    assertThat(status.status()).isEqualTo(IngestWorkerState.DEGRADED);
    assertThat(status.statusReason())
        .contains("No telemetry accepted since")
        .contains(FIXED_NOW.minus(Duration.ofMinutes(10)).toString());
    assertThat(status.lastAcceptedAt()).isEqualTo(FIXED_NOW.minus(Duration.ofMinutes(10)).toString());
    assertThat(status.staleSince()).isEqualTo(FIXED_NOW.minus(Duration.ofMinutes(5)).toString());
  }

  @Test
  void statusSurfacesQueueMetricsTimestampAndCapacity() {
    when(mqttStatus.state()).thenReturn(MqttConnectionStatus.State.SUBSCRIBED);
    tracker.recordAccepted();

    IngestWorkerStatus status = service(7).status();

    assertThat(status.queueDepth()).isEqualTo(7);
    assertThat(status.queueCapacity()).isEqualTo(1000);
    assertThat(status.timestamp()).isEqualTo(FIXED_NOW.toString());
    assertThat(status.mqttState()).isEqualTo("SUBSCRIBED");
  }
}
