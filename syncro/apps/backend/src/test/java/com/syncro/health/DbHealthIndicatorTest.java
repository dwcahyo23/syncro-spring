package com.syncro.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

@ExtendWith(MockitoExtension.class)
class DbHealthIndicatorTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC);
  private static final String FIXED_TIMESTAMP = "2026-08-21T08:00:00Z";

  @Mock
  private DataSource dataSource;

  @Mock
  private Connection connection;

  @Test
  void health_whenValid_returnsUp() throws Exception {
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.isValid(anyInt())).thenReturn(true);

    Health health = new DbHealthIndicator(dataSource, FIXED_CLOCK).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Up")
        .containsEntry("statusSeverity", "SUCCESS")
        .containsEntry("timestamp", FIXED_TIMESTAMP);
  }

  @Test
  void health_whenConnectionInvalid_returnsDown() throws Exception {
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.isValid(anyInt())).thenReturn(false);

    Health health = new DbHealthIndicator(dataSource, FIXED_CLOCK).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Down")
        .containsEntry("statusSeverity", "CRITICAL")
        .containsEntry("statusReason", "VALIDATION_FAILED");
  }

  @Test
  void health_whenGetConnectionFails_returnsDownWithoutThrowing() throws Exception {
    when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused: connect"));

    Health health = new DbHealthIndicator(dataSource, FIXED_CLOCK).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Down")
        .containsEntry("statusSeverity", "CRITICAL")
        .containsEntry("statusReason", "CONNECTION_REFUSED");
  }

  @Test
  void health_whenPoolAcquireBlocks_returnsDownAfterBoundedTimeout() throws Exception {
    // DW-46: a pool that never hands out a connection (Hikari waiting up to its 30s
    // connection-timeout) must report DOWN with POOL_ACQUIRE_TIMEOUT after ~2s, not block.
    when(dataSource.getConnection()).thenAnswer(invocation -> {
      Thread.sleep(60_000);
      throw new AssertionError("should have timed out");
    });

    long startedAt = System.nanoTime();
    Health health = new DbHealthIndicator(dataSource, FIXED_CLOCK).health();
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Down")
        .containsEntry("statusSeverity", "CRITICAL")
        .containsEntry("statusReason", "POOL_ACQUIRE_TIMEOUT");
    assertThat(elapsedMillis).isLessThan(10_000);
  }
}