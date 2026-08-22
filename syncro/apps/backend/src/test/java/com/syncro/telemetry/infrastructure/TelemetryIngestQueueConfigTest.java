package com.syncro.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.config.TelemetryProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.integration.channel.QueueChannel;

class TelemetryIngestQueueConfigTest {

  private final TelemetryIngestQueueConfig config = new TelemetryIngestQueueConfig();

  private TelemetryProperties propsWithIngest(int queueCapacity, int workerThreads) {
    return new TelemetryProperties(
        Duration.ofMinutes(5),
        Duration.ofSeconds(30),
        new TelemetryProperties.Ingest(queueCapacity, workerThreads, Duration.ofMinutes(5)),
        new TelemetryProperties.DataQuality(Duration.ofHours(1)));
  }

  @Test
  void queueChannelRespectsBoundedCapacity() {
    var props = propsWithIngest(42, 2);
    QueueChannel channel = config.telemetryIngestQueue(props);
    assertThat(channel.getRemainingCapacity()).isEqualTo(42);
    assertThat(channel.getQueueSize()).isZero();
  }

  @Test
  void telemetryIngestExecutorSizedByWorkerThreads() {
    var props = propsWithIngest(1000, 2);
    var executor = config.telemetryIngestExecutor(props);
    assertThat(executor.getCorePoolSize()).isEqualTo(2);
    assertThat(executor.getMaxPoolSize()).isEqualTo(2);
  }

  @Test
  void gaugesRegisteredInRegistry() {
    var props = propsWithIngest(100, 2);
    QueueChannel channel = config.telemetryIngestQueue(props);
    var registry = new SimpleMeterRegistry();

    config.telemetryIngestQueueMetrics(channel).bindTo(registry);

    Gauge depthGauge = registry.find("telemetry.ingest.queue.depth").gauge();
    Gauge remainingGauge = registry.find("telemetry.ingest.queue.remaining").gauge();

    assertThat(depthGauge).isNotNull();
    assertThat(remainingGauge).isNotNull();
    assertThat(depthGauge.value()).isZero();
    assertThat(remainingGauge.value()).isEqualTo(100.0);
  }

  @Test
  void gaugeDepthReflectsQueueSize() {
    var props = propsWithIngest(10, 1);
    QueueChannel channel = config.telemetryIngestQueue(props);
    var registry = new SimpleMeterRegistry();
    config.telemetryIngestQueueMetrics(channel).bindTo(registry);

    // send a message to the channel without consuming it
    channel.send(new org.springframework.messaging.support.GenericMessage<>("test"));

    assertThat(registry.find("telemetry.ingest.queue.depth").gauge().value()).isEqualTo(1.0);
    assertThat(registry.find("telemetry.ingest.queue.remaining").gauge().value()).isEqualTo(9.0);
  }

  @Test
  void propertiesValidationRejectsZeroCapacity() {
    assertThatThrownBy(() -> new TelemetryProperties.Ingest(0, 2, Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("queue-capacity");
  }

  @Test
  void propertiesValidationRejectsZeroWorkers() {
    assertThatThrownBy(() -> new TelemetryProperties.Ingest(100, 0, Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("worker-threads");
  }

  @Test
  void propertiesValidationRejectsZeroStaleThreshold() {
    assertThatThrownBy(() -> new TelemetryProperties.Ingest(100, 2, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stale-threshold");
  }
}
