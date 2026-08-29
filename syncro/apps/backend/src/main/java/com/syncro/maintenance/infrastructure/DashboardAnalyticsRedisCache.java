package com.syncro.maintenance.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.syncro.config.DashboardAnalyticsProperties;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Look-aside JSON cache for computed dashboard analytics (story 14-2, FR-173/FR-174),
 * copying the {@code ProjectionRedisCache} pattern: {@code StringRedisTemplate} + Jackson,
 * keys {@code syncro:dashboard:mtbf-mttr} / {@code syncro:dashboard:technician-kpi}, TTL
 * from {@code syncro.dashboard.analytics-ttl} (default PT30M). Cache failures degrade to
 * recompute — Redis is never a source of truth here.
 *
 * <p>Key shape: {@code syncro:dashboard:{key}:{userId}:{plantId-or-all}} so one user's
 * scope never serves another user's payload (scopes are per-user by design).
 */
@Component
public class DashboardAnalyticsRedisCache {

  private static final Logger log = LoggerFactory.getLogger(DashboardAnalyticsRedisCache.class);
  static final String KEY_PREFIX = "syncro:dashboard:";
  static final String MTBF_MTTR_KEY = "mtbf-mttr";
  static final String TECHNICIAN_KPI_KEY = "technician-kpi";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Duration ttl;

  public DashboardAnalyticsRedisCache(StringRedisTemplate redis,
      DashboardAnalyticsProperties properties) {
    this.redis = redis;
    this.objectMapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        // Cached payloads must survive DTO field additions across deploys; unknown fields are
        // ignored on read instead of poisoning every entry until its TTL expires.
        .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
    this.ttl = properties.analyticsTtl();
  }

  public <T> Optional<T> get(String analyticsKey, String scopeKey, Class<T> type) {
    try {
      String json = redis.opsForValue().get(key(analyticsKey, scopeKey));
      if (json == null) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readValue(json, type));
    } catch (Exception failure) {
      log.warn("dashboard_analytics_cache_read_failed key={} error={}",
          analyticsKey, failure.getMessage());
      return Optional.empty();
    }
  }

  public <T> void put(String analyticsKey, String scopeKey, T value) {
    try {
      redis.opsForValue().set(key(analyticsKey, scopeKey), objectMapper.writeValueAsString(value), ttl);
    } catch (Exception failure) {
      log.warn("dashboard_analytics_cache_write_failed key={} error={}",
          analyticsKey, failure.getMessage());
    }
  }

  static String key(String analyticsKey, String scopeKey) {
    return KEY_PREFIX + analyticsKey + ":" + scopeKey;
  }
}
