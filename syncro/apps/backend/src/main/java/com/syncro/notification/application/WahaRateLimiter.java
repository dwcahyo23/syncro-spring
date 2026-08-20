package com.syncro.notification.application;

import com.syncro.config.WahaRateLimitProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed rate limiter for WAHA notification sends.
 *
 * <p>Key format: {@code waha:rl:{alertId}:{recipientPhone}}
 *
 * <p>{@link #isRateLimited} is a pure read: it checks whether the deduplication key exists
 * without modifying Redis. This is safe because the write ({@link #acquire}) uses SET NX PX,
 * which is atomic — only the first concurrent caller wins, and subsequent callers on the next
 * poll cycle will see the key and be correctly suppressed.
 *
 * <p>The JPA {@code @Version} optimistic lock on {@code NotificationJobEntity} provides a
 * second guard: even if two workers both read "not limited" for the same job before either
 * writes the Redis key, only one will succeed in persisting the entity state change;
 * the other will throw an {@code OptimisticLockingFailureException} and be retried.
 *
 * <p>Phone numbers are never logged.
 */
@Service
public class WahaRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(WahaRateLimiter.class);
  private static final String KEY_PREFIX = "waha:rl:";

  private final StringRedisTemplate redisTemplate;
  private final WahaRateLimitProperties properties;

  public WahaRateLimiter(StringRedisTemplate redisTemplate, WahaRateLimitProperties properties) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
  }

  /**
   * Returns {@code true} if the alert+recipient combination is currently within the
   * rate-limit window (i.e. a successful send already happened recently and the key is
   * still alive in Redis).
   *
   * <p>This is a pure read — it does not modify Redis state.
   */
  public boolean isRateLimited(UUID alertId, String recipientPhone) {
    String key = buildKey(alertId, recipientPhone);
    Boolean exists = redisTemplate.hasKey(key);
    boolean limited = Boolean.TRUE.equals(exists);
    if (limited) {
      log.info("[WAHA] rate-limited alertId={} — key present, window not yet expired", alertId);
    }
    return limited;
  }

  /**
   * Returns the {@link Instant} at which the rate-limit window for this alert+recipient expires.
   * Falls back to {@code now + windowMs} when the key is absent, has no TTL, or has already
   * expired (pttl == 0), or has a persistent TTL (pttl == -1).
   *
   * <p>Used to set {@code nextAttemptAt} on the job so the worker retries at the right time.
   */
  public Instant getRateLimitExpiry(UUID alertId, String recipientPhone) {
    String key = buildKey(alertId, recipientPhone);
    Long pttl = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
    // pttl == null: key absent; pttl == -2: key does not exist; pttl == -1: no TTL (persistent key)
    // pttl == 0: already expired. All non-positive values fall back to a full-window delay.
    if (pttl == null || pttl <= 0) {
      return Instant.now().plus(Duration.ofMillis(properties.windowMs()));
    }
    return Instant.now().plus(Duration.ofMillis(pttl));
  }

  /**
   * Records a successful send by atomically setting the rate-limit key (SET NX PX).
   *
   * <p>Using SET NX PX ensures that in a multi-node deployment where two workers both
   * pass the {@link #isRateLimited} check before either acquires the key, only the first
   * successful-send winner sets the key — the second will find NX fails and the key is
   * already live. This, combined with the JPA {@code @Version} optimistic lock on the job
   * entity, prevents duplicate WAHA sends within the deduplication window.
   */
  public void acquire(UUID alertId, String recipientPhone) {
    String key = buildKey(alertId, recipientPhone);
    // SET NX PX — only sets if absent; idempotent if already set by a concurrent winner
    Boolean set = redisTemplate.opsForValue()
        .setIfAbsent(key, "1", Duration.ofMillis(properties.windowMs()));
    if (Boolean.TRUE.equals(set)) {
      log.info("[WAHA] rate-limit key set alertId={} ttlMs={}", alertId, properties.windowMs());
    } else {
      log.info("[WAHA] rate-limit key already present alertId={} (concurrent acquire)", alertId);
    }
  }

  private String buildKey(UUID alertId, String recipientPhone) {
    return KEY_PREFIX + alertId + ":" + recipientPhone;
  }
}
