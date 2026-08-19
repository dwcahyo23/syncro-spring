package com.syncro.telemetry.infrastructure;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisLatestTelemetryWriter {

  private static final Logger log = LoggerFactory.getLogger(RedisLatestTelemetryWriter.class);

  private final StringRedisTemplate redis;

  public RedisLatestTelemetryWriter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void putLatest(UUID machineId, Map<String, String> fields, Duration ttl) {
    String key = latestKey(machineId);
    Object result = redis.execute(new SessionCallback<Object>() {
      @Override
      public Object execute(RedisOperations operations) throws DataAccessException {
        operations.multi();
        operations.opsForHash().putAll(key, fields);
        operations.expire(key, ttl);
        return operations.exec();
      }
    });
    if (result == null) {
      throw new IllegalStateException("latest-state transaction aborted for machine " + machineId);
    }
  }

  public Optional<Long> readCounting(UUID machineId) {
    Object value = redis.opsForHash().get(latestKey(machineId), "counting");
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(value.toString()));
    } catch (NumberFormatException malformed) {
      log.warn("mqtt_telemetry_latest_counting_malformed machineId={} rawValue={}", machineId, value);
      return Optional.empty();
    }
  }

  public Map<String, String> readLatestAsMap(UUID machineId) {
    Map<Object, Object> entries = redis.opsForHash().entries(latestKey(machineId));
    if (entries.isEmpty()) {
      return Map.of();
    }
    Map<String, String> result = new HashMap<>();
    for (Map.Entry<Object, Object> entry : entries.entrySet()) {
      if (entry.getKey() != null && entry.getValue() != null) {
        result.put(entry.getKey().toString(), entry.getValue().toString());
      }
    }
    return result;
  }

  public void hdel(UUID machineId, Collection<String> fieldNames) {
    if (fieldNames == null || fieldNames.isEmpty()) {
      return;
    }
    String key = latestKey(machineId);
    Object[] fields = fieldNames.toArray(new Object[0]);
    redis.opsForHash().delete(key, fields);
  }

  private static String latestKey(UUID machineId) {
    return "syncro:machine:" + machineId + ":latest";
  }
}
