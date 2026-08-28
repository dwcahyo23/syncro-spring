package com.syncro.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the external sync pipeline (story 13-1, AD-7/FR-150).
 * Bound from {@code syncro.sync.*} in {@code application.yml}. External sync is
 * disabled by default ({@code enabled = false}): the backend boots without an
 * external database connection, and the scheduled worker is only created when the
 * operator flips the flag and supplies {@code syncro.sync.datasource.*} credentials
 * via environment.
 */
@Validated
@ConfigurationProperties(prefix = "syncro.sync")
public record SyncProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue Datasource datasource,
    @DefaultValue("100") int batchSize,
    @DefaultValue("60000") long pollIntervalMs,
    @DefaultValue("900000") long lockTtlMs) {

  /**
   * Connection for the external PostgreSQL (the reference system's DB). Read-only
   * {@code JdbcTemplate} queries only — never JPA. When {@code enabled} is false the
   * values are ignored and the secondary DataSource bean is not created.
   */
  public record Datasource(
      @DefaultValue("") String url,
      @DefaultValue("") String username,
      @DefaultValue("") String password) {

    public Datasource {
      if (url == null) {
        throw new IllegalArgumentException("syncro.sync.datasource.url must not be null");
      }
    }
  }

  public SyncProperties {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("syncro.sync.batch-size must be positive");
    }
    if (pollIntervalMs <= 0) {
      throw new IllegalArgumentException("syncro.sync.poll-interval-ms must be positive");
    }
    if (lockTtlMs <= 0) {
      throw new IllegalArgumentException("syncro.sync.lock-ttl-ms must be positive");
    }
    if (enabled && (datasource == null || datasource.url() == null || datasource.url().isBlank())) {
      throw new IllegalArgumentException(
          "syncro.sync.datasource.url must be configured when syncro.sync.enabled=true");
    }
  }
}
