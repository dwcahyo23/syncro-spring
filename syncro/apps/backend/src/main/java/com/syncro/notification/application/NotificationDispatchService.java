package com.syncro.notification.application;

import com.syncro.config.WahaResilienceProperties;
import com.syncro.notification.application.WahaTemplateRenderer.WahaTemplateRenderException;
import com.syncro.notification.domain.WhatsAppMessageLogStatus;
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
  private final WhatsAppMessageLogService messageLogService;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public NotificationDispatchService(NotificationJobRepository jobRepository,
      NotificationAttemptRepository attemptRepository,
      WahaClient wahaClient,
      WahaTemplateRenderer templateRenderer,
      WahaRateLimiter rateLimiter,
      WahaResilienceProperties resilienceProperties,
      WhatsAppMessageLogService messageLogService,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.jobRepository = jobRepository;
    this.attemptRepository = attemptRepository;
    this.wahaClient = wahaClient;
    this.templateRenderer = templateRenderer;
    this.rateLimiter = rateLimiter;
    this.resilienceProperties = resilienceProperties;
    this.messageLogService = messageLogService;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public void dispatch(NotificationJobEntity job) {
    Instant now = Instant.now(clock);
    int nextAttemptNumber = job.getAttemptCount() + 1;

    // Check rate limit before doing any work. Workorder jobs (story 14-4) carry a null
    // alertId — the rate limiter discriminates on the idempotency key instead.
    boolean workorderJob = job.getAlertId() == null;
    boolean rateLimited = workorderJob
        ? rateLimiter.isRateLimited(job.getIdempotencyKey(), job.getRecipientPhone())
        : rateLimiter.isRateLimited(job.getAlertId(), job.getRecipientPhone());
    if (rateLimited) {
      Instant retryAfter = workorderJob
          ? rateLimiter.getRateLimitExpiry(job.getIdempotencyKey(), job.getRecipientPhone())
          : rateLimiter.getRateLimitExpiry(job.getAlertId(), job.getRecipientPhone());
      transactionTemplate.executeWithoutResult(status -> {
        job.markRateLimited(now, retryAfter);
        jobRepository.save(job);
      });
      log.info("[WAHA][traceId={}] Job {} rate-limited, retryAfter={}", job.getTraceId(),
          job.getId(), retryAfter);
      return;
    }

    // Render message text: a pre-composed body (sparepart-request escalation jobs, story
    // 12-3) is sent verbatim; alert jobs fall back to the WAHA template renderer.
    String messageText;
    if (job.getMessageBody() != null && !job.getMessageBody().isBlank()) {
      messageText = job.getMessageBody();
    } else {
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
        // Story 22-4: render-fail is a dispatch outcome too — FAILED row, no text hash.
        recordMessageLog(job, WhatsAppMessageLogStatus.FAILED, null, now);
      });
      return;
      }
    }

    // Send via WAHA (WahaClient handles timeout + circuit breaker internally)
    var result = wahaClient.send(job.getRecipientPhone(), messageText, job.getTraceId());

    if (result.success()) {
      // Record rate-limit key so duplicates are suppressed within the dedup window.
      // Workorder jobs (story 14-4) use the idempotency key as discriminator.
      if (workorderJob) {
        rateLimiter.acquire(job.getIdempotencyKey(), job.getRecipientPhone());
      } else {
        rateLimiter.acquire(job.getAlertId(), job.getRecipientPhone());
      }

      transactionTemplate.executeWithoutResult(status -> {
        var attempt = new NotificationAttemptEntity(
            job.getId(), nextAttemptNumber, STATUS_SENT,
            truncate(result.detail()), job.getTraceId());
        attemptRepository.save(attempt);
        job.markSent(now);
        jobRepository.save(job);
        // Story 22-4: per-message outbound evidence, same tx as the attempt write.
        recordMessageLog(job, WhatsAppMessageLogStatus.SENT, messageText, now);
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
        // Story 22-4: failure + circuit-open are dispatch outcomes — FAILED row
        // carrying the text hash (the message did render; only the send failed).
        recordMessageLog(job, WhatsAppMessageLogStatus.FAILED, messageText, now);
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

  /**
   * Best-effort message-log evidence write (story 22-4, review P1): the upsert rides
   * the SAME transaction as the attempt write when it succeeds, but a log-write
   * failure must never roll back the attempt + job status update AFTER the WAHA send
   * already landed — that would leave the job PENDING with an unchanged attempt count,
   * re-polled forever into duplicate WhatsApp messages. An evidence gap is tolerated;
   * a duplicate send is not.
   */
  private void recordMessageLog(NotificationJobEntity job, WhatsAppMessageLogStatus outcome,
      String renderedText, Instant now) {
    try {
      messageLogService.upsertForDispatch(job, outcome, renderedText, now);
    } catch (RuntimeException e) {
      log.warn("[traceId={}] Message-log write failed for job {} (evidence gap tolerated, "
          + "dispatch outcome preserved): {}", job.getTraceId(), job.getId(), e.getMessage());
    }
  }

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
