package com.syncro.health;

import java.time.Clock;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for Redis connectivity ({@code components.redis}).
 *
 * <p>Replaces the auto-configured {@code redisHealthContributor} (disabled via
 * {@code management.health.redis.enabled: false}) so the component also carries the operational
 * status contract fields. Performs a {@code PING} command and never throws — any failure is
 * mapped to DOWN.
 */
@Component("redis")
public class RedisHealthIndicator implements HealthIndicator {

  private final RedisConnectionFactory connectionFactory;
  private final Clock clock;

  public RedisHealthIndicator(RedisConnectionFactory connectionFactory, Clock clock) {
    this.connectionFactory = connectionFactory;
    this.clock = clock;
  }

  @Override
  public Health health() {
    try (RedisConnection connection = connectionFactory.getConnection()) {
      String pong = connection.ping();
      if ("PONG".equals(pong)) {
        return DependencyHealthSupport.enrich(Health.up().build(), clock);
      }
      return DependencyHealthSupport.enrich(
          Health.down().build(), clock, "PING returned unexpected reply: " + pong, null);
    } catch (Exception exception) {
      return DependencyHealthSupport.enrich(
          Health.down(exception).build(), clock, exception.getMessage(), null);
    }
  }
}