package com.syncro.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RedisLatestTelemetryWriterTest {

  private static final UUID MACHINE_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

  @Mock
  private StringRedisTemplate redis;
  @Mock
  private HashOperations<String, Object, Object> hashOps;

  private RedisLatestTelemetryWriter writer;

  @BeforeEach
  void setUp() {
    writer = new RedisLatestTelemetryWriter(redis);
    when(redis.opsForHash()).thenReturn(hashOps);
  }

  @Test
  void readCountingReturnsValueWhenPresent() {
    when(hashOps.get("syncro:machine:" + MACHINE_ID + ":latest", "counting")).thenReturn("100");

    assertThat(writer.readCounting(MACHINE_ID)).contains(100L);
  }

  @Test
  void readCountingReturnsEmptyWhenAbsent() {
    when(hashOps.get("syncro:machine:" + MACHINE_ID + ":latest", "counting")).thenReturn(null);

    assertThat(writer.readCounting(MACHINE_ID)).isEmpty();
  }

  @Test
  void readCountingReturnsEmptyAndLogsWarnWhenMalformed() {
    when(hashOps.get("syncro:machine:" + MACHINE_ID + ":latest", "counting")).thenReturn("not-a-number");
    var appender = attachAppender();

    assertThat(writer.readCounting(MACHINE_ID)).isEmpty();

    assertThat(appender.list)
        .anyMatch(event -> event.getLevel() == Level.WARN
            && event.getFormattedMessage().startsWith("mqtt_telemetry_latest_counting_malformed")
            && event.getFormattedMessage().contains("machineId=" + MACHINE_ID)
            && event.getFormattedMessage().contains("rawValue=not-a-number"));
    detachAppender(appender);
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(RedisLatestTelemetryWriter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(RedisLatestTelemetryWriter.class);
    logger.detachAppender(appender);
    appender.stop();
  }
}
