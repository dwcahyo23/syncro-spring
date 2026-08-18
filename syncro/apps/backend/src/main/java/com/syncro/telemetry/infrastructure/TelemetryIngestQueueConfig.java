package com.syncro.telemetry.infrastructure;

import com.syncro.config.TelemetryProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.QueueChannel;

@Configuration
public class TelemetryIngestQueueConfig {

  @Bean
  QueueChannel telemetryIngestQueue(TelemetryProperties props) {
    return new QueueChannel(props.ingest().queueCapacity());
  }

  @Bean
  MeterBinder telemetryIngestQueueMetrics(QueueChannel telemetryIngestQueue) {
    return registry -> {
      Gauge.builder("telemetry.ingest.queue.depth",
              telemetryIngestQueue, QueueChannel::getQueueSize)
          .description("Current number of messages waiting in the telemetry ingest queue")
          .register(registry);
      Gauge.builder("telemetry.ingest.queue.remaining",
              telemetryIngestQueue, QueueChannel::getRemainingCapacity)
          .description("Remaining capacity in the telemetry ingest queue")
          .register(registry);
    };
  }
}
