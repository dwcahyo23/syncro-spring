package com.syncro.notification.application;

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
import org.springframework.transaction.annotation.Transactional;

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
  private final Clock clock;

  public NotificationDispatchService(NotificationJobRepository jobRepository,
      NotificationAttemptRepository attemptRepository,
      WahaClient wahaClient,
      WahaTemplateRenderer templateRenderer,
      Clock clock) {
    this.jobRepository = jobRepository;
    this.attemptRepository = attemptRepository;
    this.wahaClient = wahaClient;
    this.templateRenderer = templateRenderer;
    this.clock = clock;
  }

  @Transactional
  public void dispatch(NotificationJobEntity job) {
    Instant now = Instant.now(clock);
    int nextAttemptNumber = job.getAttemptCount() + 1;

    // Render template
    String messageText;
    try {
      messageText = templateRenderer.render(job.getAlertId());
    } catch (WahaTemplateRenderException e) {
      log.error("[traceId={}] Template render failed for job {}: {}", job.getTraceId(), job.getId(),
          e.getMessage());
      var attempt = new NotificationAttemptEntity(
          job.getId(), job.getAttemptCount() + 1, STATUS_FAILED,
          truncate(e.getMessage()), job.getTraceId());
      attemptRepository.save(attempt);
      job.markExhausted(now);
      jobRepository.save(job);
      return;
    }

    // Send via WAHA
    WahaClient.Result result = wahaClient.send(job.getRecipientPhone(), messageText,
        job.getTraceId());

    if (result.success()) {
      var attempt = new NotificationAttemptEntity(
          job.getId(), nextAttemptNumber, STATUS_SENT,
          truncate(result.detail()), job.getTraceId());
      attemptRepository.save(attempt);
      job.markSent(now);
      jobRepository.save(job);
      log.info("[traceId={}] Notification job {} sent successfully", job.getTraceId(), job.getId());
    } else {
      Instant nextAttemptAt = computeNextAttemptAt(now, job.getAttemptCount());
      var attempt = new NotificationAttemptEntity(
          job.getId(), nextAttemptNumber, STATUS_FAILED,
          truncate("HTTP " + result.httpStatus() + ": " + result.detail()), job.getTraceId());
      attemptRepository.save(attempt);
      job.markAttemptFailed(now, nextAttemptAt);
      jobRepository.save(job);
      log.warn("[traceId={}] Notification job {} failed attempt {}/{}: HTTP {}",
          job.getTraceId(), job.getId(), nextAttemptNumber, job.getMaxAttempts(),
          result.httpStatus());
    }
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
