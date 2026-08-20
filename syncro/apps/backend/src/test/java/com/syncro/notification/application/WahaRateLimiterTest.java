package com.syncro.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.config.WahaRateLimitProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class WahaRateLimiterTest {

  @Mock
  private StringRedisTemplate redisTemplate;
  @Mock
  private ValueOperations<String, String> valueOps;

  private WahaRateLimiter rateLimiter;

  private static final long WINDOW_MS = 300_000L;
  private static final UUID ALERT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String PHONE = "6281234567890";
  private static final String EXPECTED_KEY = "waha:rl:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:6281234567890";

  @BeforeEach
  void setUp() {
    var properties = new WahaRateLimitProperties(WINDOW_MS);
    rateLimiter = new WahaRateLimiter(redisTemplate, properties);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
  }

  // --- isRateLimited (pure read: hasKey) ---

  @Test
  void isRateLimited_whenKeyAbsent_returnsFalse() {
    when(redisTemplate.hasKey(EXPECTED_KEY)).thenReturn(false);

    assertThat(rateLimiter.isRateLimited(ALERT_ID, PHONE)).isFalse();
  }

  @Test
  void isRateLimited_whenKeyPresent_returnsTrue() {
    when(redisTemplate.hasKey(EXPECTED_KEY)).thenReturn(true);

    assertThat(rateLimiter.isRateLimited(ALERT_ID, PHONE)).isTrue();
  }

  @Test
  void isRateLimited_whenHasKeyReturnsNull_returnsFalse() {
    when(redisTemplate.hasKey(EXPECTED_KEY)).thenReturn(null);

    assertThat(rateLimiter.isRateLimited(ALERT_ID, PHONE)).isFalse();
  }

  @Test
  void isRateLimited_usesCorrectKeyFormat() {
    when(redisTemplate.hasKey(any())).thenReturn(false);

    rateLimiter.isRateLimited(ALERT_ID, PHONE);

    verify(redisTemplate).hasKey(EXPECTED_KEY);
  }

  // --- acquire (atomic SET NX PX via setIfAbsent) ---

  @Test
  void acquire_setsKeyWithWindowTtlUsingSetNxPx() {
    when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(true);

    rateLimiter.acquire(ALERT_ID, PHONE);

    verify(valueOps).setIfAbsent(
        eq(EXPECTED_KEY),
        eq("1"),
        eq(Duration.ofMillis(WINDOW_MS)));
  }

  @Test
  void acquire_whenKeyAlreadyPresent_doesNotThrow() {
    // setIfAbsent returns false when key already exists — should not throw
    when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(false);

    rateLimiter.acquire(ALERT_ID, PHONE);

    verify(valueOps).setIfAbsent(eq(EXPECTED_KEY), eq("1"), eq(Duration.ofMillis(WINDOW_MS)));
  }

  // --- getRateLimitExpiry ---

  @Test
  void getRateLimitExpiry_whenKeyHasRemainingTtl_returnsExpiryNearFuture() {
    long remainingMs = 120_000L; // 2 minutes remaining
    when(redisTemplate.getExpire(EXPECTED_KEY, TimeUnit.MILLISECONDS))
        .thenReturn(remainingMs);

    Instant expiry = rateLimiter.getRateLimitExpiry(ALERT_ID, PHONE);

    Instant expected = Instant.now().plus(Duration.ofMillis(remainingMs));
    assertThat(expiry).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
  }

  @Test
  void getRateLimitExpiry_whenKeyAbsent_returnsWindowFallback() {
    // Redis returns -2 when key does not exist
    when(redisTemplate.getExpire(EXPECTED_KEY, TimeUnit.MILLISECONDS))
        .thenReturn(-2L);

    Instant expiry = rateLimiter.getRateLimitExpiry(ALERT_ID, PHONE);

    Instant expected = Instant.now().plus(Duration.ofMillis(WINDOW_MS));
    assertThat(expiry).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
  }

  @Test
  void getRateLimitExpiry_whenKeyHasNoTtl_returnsWindowFallback() {
    // pttl == -1: key exists but is persistent (no TTL set) — treat as full-window fallback
    when(redisTemplate.getExpire(EXPECTED_KEY, TimeUnit.MILLISECONDS))
        .thenReturn(-1L);

    Instant expiry = rateLimiter.getRateLimitExpiry(ALERT_ID, PHONE);

    Instant expected = Instant.now().plus(Duration.ofMillis(WINDOW_MS));
    assertThat(expiry).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
  }

  @Test
  void getRateLimitExpiry_whenGetExpireReturnsNull_returnsWindowFallback() {
    when(redisTemplate.getExpire(EXPECTED_KEY, TimeUnit.MILLISECONDS))
        .thenReturn(null);

    Instant expiry = rateLimiter.getRateLimitExpiry(ALERT_ID, PHONE);

    Instant expected = Instant.now().plus(Duration.ofMillis(WINDOW_MS));
    assertThat(expiry).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
  }
}
