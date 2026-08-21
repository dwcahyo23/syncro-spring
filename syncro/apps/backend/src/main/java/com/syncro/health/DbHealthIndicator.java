package com.syncro.health;

import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for PostgreSQL connectivity ({@code components.db}).
 *
 * <p>Replaces the auto-configured {@code dbHealthContributor} (disabled via
 * {@code management.health.db.enabled: false}) so the component also carries the operational
 * status contract fields. Performs {@code Connection.isValid(2)} and never throws — any
 * failure is mapped to DOWN.
 */
@Component("db")
public class DbHealthIndicator implements HealthIndicator {

  private static final int VALIDATION_TIMEOUT_SECONDS = 2;

  private final DataSource dataSource;
  private final Clock clock;

  public DbHealthIndicator(DataSource dataSource, Clock clock) {
    this.dataSource = dataSource;
    this.clock = clock;
  }

  @Override
  public Health health() {
    try (Connection connection = dataSource.getConnection()) {
      if (connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
        return DependencyHealthSupport.enrich(Health.up().build(), clock);
      }
      return DependencyHealthSupport.enrich(
          Health.down().build(), clock, "Connection validation returned false", null);
    } catch (Exception exception) {
      return DependencyHealthSupport.enrich(
          Health.down(exception).build(), clock, exception.getMessage(), null);
    }
  }
}