package com.syncro.notification.application;

import com.syncro.config.WahaResilienceProperties;
import com.syncro.notification.application.WahaTemplateRenderer.WahaTemplateRenderException;
import com.syncro.notification.infrastructure.NotificationAttemptEntity;
import com.syncro.notification.infrastructure.NotificationAttemptRepository;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import com.syncro.notification.infrastructure.WahaClient;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationDispatchService {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);
  private static final String STATUS_SENT = "SENT";
  private static final String STATUS_FAILED = "FAILED";
  private static final int MAX_BACKOFF_MINUTES = 60;

  private final NotificationJobRepository jobRepository;
  private final NotificationAttemptRepository attemptRepository;
  private final WahaClient wahaClient;
  private final WahaTemplateRenderer templateRenderer;
  private final WahaRateLimiter rateLimiter;
  private final WahaResilienceProperties resilienceProperties;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public NotificationDispatchService(NotificationJobRepository jobRepository,
      NotificationAttemptRepository attemptRepository,
      WahaClient wahaClient,
      WahaTemplateRenderer templateRenderer,
      WahaRateLimiter rateLimiter,
      WahaResilienceProperties resilienceProperties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.jobRepository = jobRepository;
    this.attemptRepository = attemptRepository;
    this.wahaClient = wahaClient;
    this.templateRenderer = templateRenderer;
    this.rateLimiter = rateLimiter;
    this.resilienceProperties = resilienceProperties;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public void dispatch(NotificationJobEntity job) {
    Instant now = Instant.now(clock);
    int nextAttemptNumber = job.getAttemptCount() + 1;

    // Check rate limit before doing any work
    if (rateLimiter.isRateLimited(job.getAlertId(), job.getRecipientPhone())) {
      Instant retryAfter = rateLimiter.getRateLimitExpiry(job.getAlertId(), job.getRecipientPhone());
      transactionTemplate.executeWithoutResult(status -> {
        job.markRateLimited(now, retryAfter);
        jobRepository.save(job);
      });
      log.info("[WAHA][traceId={}] Job {} rate-limited, retryAfter={}", job.getTraceId(),
          job.getId(), retryAfter);
      return;
    }

    // Render template
    String messageText;
    try {
      messageText = templateRenderer.render(job.getAlertId());
    } catch (WahaTemplateRenderException e) {
      log.error("[traceId={}] Template render failed for job {}: {}", job.getTraceId(), job.getId(),
          e.getMessage());
      transactionTemplate.executeWithoutResult(status -> {
        var attempt = new NotificationAttemptEntity(
            job.getId(), nextAttemptNumber, STATUS_FAILED,
            truncate(e.getMessage()), job.getTraceId());
        attemptRepository.save(attempt);
        job.markExhausted(now);
        jobRepository.save(job);
      });
      return;
    }

    // Send via WAHA (WahaClient handles timeout + circuit breaker internally)
    var result = wahaClient.send(job.getRecipientPhone(), messageText, job.getTraceId());

    if (result.success()) {
      // Record rate-limit key so duplicates are suppressed within the dedup window
      rateLimiter.acquire(job.getAlertId(), job.getRecipientPhone());

      transactionTemplate.executeWithoutResult(status -> {
        var attempt = new NotificationAttemptEntity(
            job.getId(), nextAttemptNumber, STATUS_SENT,
            truncate(result.detail()), job.getTraceId());
        attemptRepository.save(attempt);
        job.markSent(now);
        jobRepository.save(job);
      });
      log.info("[traceId={}] Notification job {} sent successfully", job.getTraceId(), job.getId());
    } else {
      // Determine nextAttemptAt — circuit-open uses waitDurationInOpenState, others use backoff
      Instant nextAttemptAt = isCircuitOpen(result)
          ? now.plus(resilienceProperties.waitDurationInOpenState().toMillis(), ChronoUnit.MILLIS)
          : computeNextAttemptAt(now, job.getAttemptCount());

      String attemptDetail = buildFailureDetail(result);
      transactionTemplate.executeWithoutResult(status -> {
        var attempt = new NotificationAttemptEntity(
            job.getId(), nextAttemptNumber, STATUS_FAILED,
            truncate(attemptDetail), job.getTraceId());
        attemptRepository.save(attempt);
        if (isCircuitOpen(result)) {
          job.markCircuitOpen(now, nextAttemptAt);
        } else {
          job.markAttemptFailed(now, nextAttemptAt);
        }
        jobRepository.save(job);
      });

      if (isCircuitOpen(result)) {
        log.warn("[traceId={}] Notification job {} circuit OPEN — retryAfter={}",
            job.getTraceId(), job.getId(), nextAttemptAt);
      } else {
        log.warn("[traceId={}] Notification job {} failed attempt {}/{}: HTTP {}",
            job.getTraceId(), job.getId(), nextAttemptNumber, job.getMaxAttempts(),
            result.httpStatus());
      }
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static boolean isCircuitOpen(WahaClient.Result result) {
    return !result.success()
        && result.httpStatus() == 0
        && WahaClient.CIRCUIT_OPEN_DETAIL.equals(result.detail());
  }

  private static String buildFailureDetail(WahaClient.Result result) {
    if (isCircuitOpen(result)) {
      return result.detail(); // "Circuit breaker is OPEN"
    }
    return "HTTP " + result.httpStatus() + ": " + result.detail();
  }

  private Instant computeNextAttemptAt(Instant now, int currentAttemptCount) {
    long backoffMinutes = (long) Math.min(Math.pow(2, currentAttemptCount), MAX_BACKOFF_MINUTES);
    return now.plus(backoffMinutes, ChronoUnit.MINUTES);
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() > 512 ? value.substring(0, 512) : value;
  }
}
