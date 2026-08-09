package com.syncro.telemetry.infrastructure;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisLatestTelemetryWriter {

  private final StringRedisTemplate redis;

  public RedisLatestTelemetryWriter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void putLatest(UUID machineId, Map<String, String> fields, Duration ttl) {
    String key = "syncro:machine:" + machineId + ":latest";
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
}
