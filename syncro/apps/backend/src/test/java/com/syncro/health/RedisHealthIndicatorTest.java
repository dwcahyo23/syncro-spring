package com.syncro.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@ExtendWith(MockitoExtension.class)
class RedisHealthIndicatorTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC);
  private static final String FIXED_TIMESTAMP = "2026-08-21T08:00:00Z";

  @Mock
  private RedisConnectionFactory connectionFactory;

  @Mock
  private RedisConnection connection;

  @Test
  void health_whenPingPong_returnsUp() {
    when(connectionFactory.getConnection()).thenReturn(connection);
    when(connection.ping()).thenReturn("PONG");

    Health health = new RedisHealthIndicator(connectionFactory, FIXED_CLOCK).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Up")
        .containsEntry("statusSeverity", "SUCCESS")
        .containsEntry("timestamp", FIXED_TIMESTAMP);
  }

  @Test
  void health_whenPingReturnsUnexpectedReply_returnsDown() {
    when(connectionFactory.getConnection()).thenReturn(connection);
    when(connection.ping()).thenReturn("NOAUTH");

    Health health = new RedisHealthIndicator(connectionFactory, FIXED_CLOCK).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Down")
        .containsEntry("statusSeverity", "CRITICAL")
        .containsEntry("statusReason", "UNEXPECTED_REPLY");
  }

  @Test
  void health_whenConnectionFails_returnsDownWithoutThrowing() {
    when(connectionFactory.getConnection())
        .thenThrow(new RedisConnectionFailureException("Connection refused: localhost/127.0.0.1:6379"));

    Health health = new RedisHealthIndicator(connectionFactory, FIXED_CLOCK).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Down")
        .containsEntry("statusSeverity", "CRITICAL")
        .containsEntry("statusReason", "CONNECTION_REFUSED");
  }
}