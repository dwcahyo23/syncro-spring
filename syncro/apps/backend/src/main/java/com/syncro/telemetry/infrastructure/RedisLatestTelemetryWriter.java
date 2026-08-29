package com.syncro.telemetry.infrastructure;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  /**
   * Reads the latest-telemetry hashes for many machines in ONE pipelined round trip.
   *
   * <p>Machines whose hash is absent or empty are simply missing from the returned map —
   * absence is not an error. Null id elements are ignored and duplicates collapse to one
   * command. Connection/pipeline failures propagate to the caller so the query service can
   * degrade the whole page with a single warning instead of one per row.
   */
  public Map<UUID, Map<String, String>> readLatestBatch(Collection<UUID> machineIds) {
    if (machineIds == null || machineIds.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids = machineIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    List<Object> pipelineResults = redis.executePipelined(new SessionCallback<Object>() {
      @Override
      public Object execute(RedisOperations operations) throws DataAccessException {
        for (UUID machineId : ids) {
          operations.opsForHash().entries(latestKey(machineId));
        }
        return null;
      }
    });
    Map<UUID, Map<String, String>> result = new HashMap<>();
    for (int i = 0; i < ids.size(); i++) {
      Object raw = pipelineResults.get(i);
      if (!(raw instanceof Map<?, ?> entries) || entries.isEmpty()) {
        continue;
      }
      Map<String, String> fields = new HashMap<>();
      for (Map.Entry<?, ?> entry : entries.entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          fields.put(entry.getKey().toString(), entry.getValue().toString());
        }
      }
      if (!fields.isEmpty()) {
        result.put(ids.get(i), fields);
      }
    }
    return result;
  }

  /**
   * Reads the {@code counting} value of the latest-telemetry hashes for many machines
   * in ONE pipelined round trip (story 14-1 batch lifetime evaluation). Machines without
   * a counting value (or without a hash at all) are absent from the result.
   */
  public Map<UUID, Long> readCountingBatch(Collection<UUID> machineIds) {
    if (machineIds == null || machineIds.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids = machineIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    List<Object> pipelineResults = redis.executePipelined(new SessionCallback<Object>() {
      @Override
      public Object execute(RedisOperations operations) throws DataAccessException {
        for (UUID machineId : ids) {
          operations.opsForHash().get(latestKey(machineId), "counting");
        }
        return null;
      }
    });
    Map<UUID, Long> result = new HashMap<>();
    for (int i = 0; i < ids.size(); i++) {
      Object raw = pipelineResults.get(i);
      if (raw == null) {
        continue;
      }
      try {
        result.put(ids.get(i), Long.parseLong(raw.toString()));
      } catch (NumberFormatException malformed) {
        log.warn("mqtt_telemetry_latest_counting_malformed machineId={} rawValue={}", ids.get(i), raw);
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
