package com.syncro.projection.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Look-aside JSON cache for computed machine projections, key
 * {@code syncro:machine:{id}:projections} with an explicit TTL. Cache failures degrade to
 * recompute — Redis is never a source of truth here.
 */
@Component
public class ProjectionRedisCache {

  private static final Logger log = LoggerFactory.getLogger(ProjectionRedisCache.class);
  static final String KEY_PATTERN = "syncro:machine:*:projections";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Duration ttl;

  /**
   * Machines whose eviction failed (DW-124). An eviction that could not delete the key must
   * not be silently followed by a read that serves the pre-message cached view — the 8-7
   * procurement-risk alert would evaluate against stale telemetry. The signal lives here,
   * not in Redis, because a failed eviction means Redis was unreachable at that moment.
   */
  private final Set<UUID> failedEvictions = ConcurrentHashMap.newKeySet();

  public ProjectionRedisCache(StringRedisTemplate redis,
      com.syncro.config.ProjectionProperties properties) {
    this.redis = redis;
    this.objectMapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        // Cached payloads must survive DTO field additions across deploys; unknown fields are
        // ignored on read instead of poisoning every entry until its TTL expires.
        .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
    this.ttl = properties.cacheTtl();
  }

  public <T> Optional<T> get(UUID machineId, Class<T> type) {
    // DW-124: a failed eviction means the stored view may predate the latest message; the next
    // read must recompute (cache miss) instead of serving a stale entry. One-shot per failure.
    if (failedEvictions.remove(machineId)) {
      log.warn("projection_cache_serving_recompute machineId={} (previous eviction failed)",
          machineId);
      return Optional.empty();
    }
    try {
      String json = redis.opsForValue().get(key(machineId));
      if (json == null) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readValue(json, type));
    } catch (Exception failure) {
      log.warn("projection_cache_read_failed machineId={} error={}", machineId, failure.getMessage());
      return Optional.empty();
    }
  }

  public <T> void put(UUID machineId, T value) {
    try {
      redis.opsForValue().set(key(machineId), objectMapper.writeValueAsString(value), ttl);
    } catch (Exception failure) {
      log.warn("projection_cache_write_failed machineId={} error={}", machineId, failure.getMessage());
    }
  }

  public void evictMachine(UUID machineId) {
    try {
      redis.delete(key(machineId));
    } catch (Exception failure) {
      log.warn("projection_cache_evict_failed machineId={} error={}", machineId, failure.getMessage());
      failedEvictions.add(machineId);
    }
  }

  public void evictAll() {
    try {
      Set<String> keys = redis.keys(KEY_PATTERN);
      if (keys != null && !keys.isEmpty()) {
        redis.delete(keys);
      }
    } catch (Exception failure) {
      log.warn("projection_cache_evict_all_failed error={}", failure.getMessage());
    }
  }

  static String key(UUID machineId) {
    return "syncro:machine:" + machineId + ":projections";
  }
}
