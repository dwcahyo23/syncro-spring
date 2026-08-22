package com.syncro.telemetry.infrastructure;

import com.syncro.config.TelemetryProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class TelemetryIngestQueueConfig {

  @Bean
  QueueChannel telemetryIngestQueue(TelemetryProperties props) {
    return new QueueChannel(props.ingest().queueCapacity());
  }

  @Bean
  ThreadPoolTaskExecutor telemetryIngestExecutor(TelemetryProperties props) {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(props.ingest().workerThreads());
    executor.setMaxPoolSize(props.ingest().workerThreads());
    executor.setQueueCapacity(props.ingest().queueCapacity());
    executor.setThreadNamePrefix("telemetry-ingest-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    return executor;
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
