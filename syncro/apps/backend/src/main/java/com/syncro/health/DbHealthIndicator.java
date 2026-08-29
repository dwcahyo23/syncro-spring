package com.syncro.health;

import java.sql.Connection;
import java.time.Clock;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for PostgreSQL connectivity ({@code components.db}).
 *
 * <p>Replaces the auto-configured {@code dbHealthContributor} (disabled via
 * {@code management.health.db.enabled: false}) so the component also carries the operational
 * status contract fields. Performs {@code Connection.isValid(2)} and never throws — any
 * failure is mapped to DOWN. Exception details are sanitised to a stable reason code for
 * the public endpoint; the full exception is logged at WARN level.
 *
 * <p>DW-46: the connection acquire is bounded to {@value #ACQUIRE_TIMEOUT_SECONDS} seconds so a
 * pool that cannot hand out a connection (e.g. Hikari waiting up to its 30s connection-timeout)
 * reports DOWN with {@code POOL_ACQUIRE_TIMEOUT} instead of blocking the health endpoint.
 */
@Component("db")
public class DbHealthIndicator implements HealthIndicator {

  private static final Logger log = LoggerFactory.getLogger(DbHealthIndicator.class);
  private static final int VALIDATION_TIMEOUT_SECONDS = 2;
  private static final int ACQUIRE_TIMEOUT_SECONDS = 2;

  private final DataSource dataSource;
  private final Clock clock;

  public DbHealthIndicator(DataSource dataSource, Clock clock) {
    this.dataSource = dataSource;
    this.clock = clock;
  }

  @Override
  public Health health() {
    try (Connection connection = acquireBounded()) {
      if (connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
        return DependencyHealthSupport.enrich(Health.up().build(), clock);
      }
      return DependencyHealthSupport.enrich(
          Health.down().build(), clock, "VALIDATION_FAILED", null);
    } catch (TimeoutException timeout) {
      log.warn("Database health check timed out acquiring a connection");
      return DependencyHealthSupport.enrich(
          Health.down().build(), clock, "POOL_ACQUIRE_TIMEOUT", null);
    } catch (Exception exception) {
      log.warn("Database health check failed", exception);
      return DependencyHealthSupport.enrich(
          Health.down().build(), clock, DependencyHealthSupport.reasonCode(exception), null);
    }
  }

  /**
   * Acquires a connection from the pool on a bounded future so a stalled pool cannot block the
   * health endpoint beyond {@value #ACQUIRE_TIMEOUT_SECONDS} seconds. The pool's own
   * {@code getConnection} is called on a helper thread; a timeout surfaces as
   * {@link TimeoutException} and the helper thread is left to finish on its own (it will return
   * the connection to the pool when it eventually completes).
   */
  private Connection acquireBounded() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Connection> future = executor.submit(() -> dataSource.getConnection());
      return future.get(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException execution) {
      throw asException(execution);
    } finally {
      executor.shutdownNow();
    }
  }

  private static Exception asException(ExecutionException execution) {
    Throwable cause = execution.getCause();
    if (cause instanceof Exception exception) {
      return exception;
    }
    return new IllegalStateException("Connection acquire failed", cause);
  }
}