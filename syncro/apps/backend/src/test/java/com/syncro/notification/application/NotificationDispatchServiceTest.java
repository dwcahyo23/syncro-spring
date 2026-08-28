package com.syncro.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.config.WahaResilienceProperties;
import com.syncro.notification.application.WahaTemplateRenderer.WahaTemplateRenderException;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationAttemptEntity;
import com.syncro.notification.infrastructure.NotificationAttemptRepository;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import com.syncro.notification.infrastructure.WahaClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

  @Mock
  private NotificationJobRepository jobRepository;
  @Mock
  private NotificationAttemptRepository attemptRepository;
  @Mock
  private WahaClient wahaClient;
  @Mock
  private WahaTemplateRenderer templateRenderer;
  @Mock
  private WahaRateLimiter rateLimiter;
  @Mock
  private PlatformTransactionManager transactionManager;

  private Clock clock;
  private NotificationDispatchService service;

  private static final Instant FIXED_NOW = Instant.parse("2026-08-20T10:00:00Z");
  private static final UUID ALERT_ID = UUID.randomUUID();
  private static final String TRACE_ID = "trace-001";
  private static final String RENDERED_MSG = "PERINGATAN SPAREPART - PLT01";
  private static final String RECIPIENT_PHONE = "6281234567890";

  private static final Duration CB_WAIT = Duration.ofSeconds(60);

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
    var resilienceProperties = new WahaResilienceProperties(
        Duration.ofSeconds(5), 50, 5, 10, CB_WAIT, 3);
    service = new NotificationDispatchService(jobRepository, attemptRepository, wahaClient,
        templateRenderer, rateLimiter, resilienceProperties, clock, transactionManager);
  }

  // --- Happy path ---

  @Test
  void dispatch_whenWahaSucceeds_marksJobSentAndSavesAttempt() {
    var job = new NotificationJobEntity(
        ALERT_ID, "TECHNICIAN", NotificationJobStatus.PENDING,
        UUID.randomUUID(), RECIPIENT_PHONE,
        ALERT_ID + "::TECHNICIAN", TRACE_ID, null);

    when(rateLimiter.isRateLimited(ALERT_ID, RECIPIENT_PHONE)).thenReturn(false);
    when(templateRenderer.render(ALERT_ID)).thenReturn(RENDERED_MSG);
    when(wahaClient.send(eq(RECIPIENT_PHONE), eq(RENDERED_MSG), eq(TRACE_ID)))
        .thenReturn(new WahaClient.Result(true, 200, "OK"));
    when(jobRepository.save(any())).thenReturn(job);
    when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.dispatch(job);

    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.SENT);
    assertThat(job.getSentAt()).isEqualTo(FIXED_NOW);

    var attemptCaptor = ArgumentCaptor.forClass(NotificationAttemptEntity.class);
    verify(attemptRepository).save(attemptCaptor.capture());
    var attempt = attemptCaptor.getValue();
    assertThat(attempt.getStatus()).isEqualTo("SENT");
    assertThat(attempt.getAttemptNumber()).isEqualTo(1);
    assertThat(attempt.getTraceId()).isEqualTo(TRACE_ID);

    // Rate-limit key should be acquired after a successful send
    verify(rateLimiter).acquire(ALERT_ID, RECIPIENT_PHONE);
  }

  // --- Failure < maxAttempts ---

  @Test
  void dispatch_whenWahaFailsAndAttemptsRemain_staysAsPendingWithNextAttemptAt() {
    var job = new NotificationJobEntity(
        ALERT_ID, "TECHNICIAN", NotificationJobStatus.PENDING,
        UUID.randomUUID(), RECIPIENT_PHONE,
        ALERT_ID + "::TECHNICIAN", TRACE_ID, null);

    when(rateLimiter.isRateLimited(ALERT_ID, RECIPIENT_PHONE)).thenReturn(false);
    when(templateRenderer.render(ALERT_ID)).thenReturn(RENDERED_MSG);
    when(wahaClient.send(any(), any(), any()))
        .thenReturn(new WahaClient.Result(false, 503, "Service Unavailable"));
    when(jobRepository.save(any())).thenReturn(job);
    when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.dispatch(job);

    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.PENDING);
    assertThat(job.getAttemptCount()).isEqualTo(1);
    assertThat(job.getNextAttemptAt()).isNotNull();
    assertThat(job.getNextAttemptAt()).isAfter(FIXED_NOW);

    var attemptCaptor = ArgumentCaptor.forClass(NotificationAttemptEntity.class);
    verify(attemptRepository).save(attemptCaptor.capture());
    assertThat(attemptCaptor.getValue().getStatus()).isEqualTo("FAILED");

    // Rate-limit key must NOT be acquired on a failed send
    verify(rateLimiter, never()).acquire(any(), any());
  }

  // --- Failure at maxAttempts → EXHAUSTED ---

  @Test
  void dispatch_whenWahaFailsAtMaxAttempts_marksJobExhausted() {
    var job = new NotificationJobEntity(
        ALERT_ID, "TECHNICIAN", NotificationJobStatus.PENDING,
        UUID.randomUUID(), RECIPIENT_PHONE,
        ALERT_ID + "::TECHNICIAN", TRACE_ID, null);

    // Simulate 2 prior failures so attemptCount=2; one more exhausts (maxAttempts=3)
    job.markAttemptFailed(FIXED_NOW.minusSeconds(120), FIXED_NOW.minusSeconds(60));
    job.markAttemptFailed(FIXED_NOW.minusSeconds(60), FIXED_NOW.minusSeconds(30));

    when(rateLimiter.isRateLimited(ALERT_ID, RECIPIENT_PHONE)).thenReturn(false);
    when(templateRenderer.render(ALERT_ID)).thenReturn(RENDERED_MSG);
    when(wahaClient.send(any(), any(), any()))
        .thenReturn(new WahaClient.Result(false, 500, "Internal Server Error"));
    when(jobRepository.save(any())).thenReturn(job);
    when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.dispatch(job);

    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.EXHAUSTED);
    assertThat(job.getNextAttemptAt()).isNull();
  }

  // --- Template render failure → EXHAUSTED ---

  @Test
  void dispatch_whenTemplateRenderFails_marksJobExhausted() {
    var job = new NotificationJobEntity(
        ALERT_ID, "TECHNICIAN", NotificationJobStatus.PENDING,
        UUID.randomUUID(), RECIPIENT_PHONE,
        ALERT_ID + "::TECHNICIAN", TRACE_ID, null);

    when(rateLimiter.isRateLimited(ALERT_ID, RECIPIENT_PHONE)).thenReturn(false);
    when(templateRenderer.render(ALERT_ID))
        .thenThrow(new WahaTemplateRenderException("No active WAHA template found"));
    when(jobRepository.save(any())).thenReturn(job);
    when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.dispatch(job);

    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.EXHAUSTED);

    var attemptCaptor = ArgumentCaptor.forClass(NotificationAttemptEntity.class);
    verify(attemptRepository).save(attemptCaptor.capture());
    var attempt = attemptCaptor.getValue();
    assertThat(attempt.getStatus()).isEqualTo("FAILED");
    assertThat(attempt.getResponseDetail()).contains("No active WAHA template found");
  }

  // --- Rate limited → RATE_LIMITED, no WAHA call ---

  @Test
  void dispatch_whenRateLimited_marksJobRateLimitedAndSkipsWaha() {
    var job = new NotificationJobEntity(
        ALERT_ID, "TECHNICIAN", NotificationJobStatus.PENDING,
        UUID.randomUUID(), RECIPIENT_PHONE,
        ALERT_ID + "::TECHNICIAN", TRACE_ID, null);

    Instant retryAfter = FIXED_NOW.plusSeconds(300);
    when(rateLimiter.isRateLimited(ALERT_ID, RECIPIENT_PHONE)).thenReturn(true);
    when(rateLimiter.getRateLimitExpiry(ALERT_ID, RECIPIENT_PHONE)).thenReturn(retryAfter);
    when(jobRepository.save(any())).thenReturn(job);

    service.dispatch(job);

    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.RATE_LIMITED);
    assertThat(job.getNextAttemptAt()).isEqualTo(retryAfter);

    // No WAHA call, no attempt record, no rate-limit acquire
    verify(wahaClient, never()).send(any(), any(), any());
    verify(attemptRepository, never()).save(any());
    verify(rateLimiter, never()).acquire(any(), any());
  }

  @Test
  void dispatch_whenRateLimited_doesNotCallTemplateRenderer() {
    var job = new NotificationJobEntity(
        ALERT_ID, "TECHNICIAN", NotificationJobStatus.RATE_LIMITED,
        UUID.randomUUID(), RECIPIENT_PHONE,
        ALERT_ID + "::TECHNICIAN", TRACE_ID, null);

    Instant retryAfter = FIXED_NOW.plusSeconds(120);
    when(rateLimiter.isRateLimited(ALERT_ID, RECIPIENT_PHONE)).thenReturn(true);
    when(rateLimiter.getRateLimitExpiry(ALERT_ID, RECIPIENT_PHONE)).thenReturn(retryAfter);
    when(jobRepository.save(any())).thenReturn(job);

    service.dispatch(job);

    verify(templateRenderer, never()).render(any());
  }

  // --- Circuit breaker ---

  @Test
  void dispatch_whenCircuitOpen_savesFailedAttemptAndSetsNextAttemptAtUsingWaitDuration() {
    var job = new NotificationJobEntity(
        ALERT_ID, "TECHNICIAN", NotificationJobStatus.PENDING,
        UUID.randomUUID(), RECIPIENT_PHONE,
        ALERT_ID + "::TECHNICIAN", TRACE_ID, null);

    when(rateLimiter.isRateLimited(any(), any())).thenReturn(false);
    when(templateRenderer.render(ALERT_ID)).thenReturn(RENDERED_MSG);
    when(wahaClient.send(eq(RECIPIENT_PHONE), eq(RENDERED_MSG), eq(TRACE_ID)))
        .thenReturn(new WahaClient.Result(false, 0, WahaClient.CIRCUIT_OPEN_DETAIL));
    when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(jobRepository.save(any())).thenReturn(job);

    service.dispatch(job);

    // nextAttemptAt must be now + waitDurationInOpenState (60s), NOT exponential backoff
    Instant expectedRetry = FIXED_NOW.plus(CB_WAIT);
    assertThat(job.getNextAttemptAt()).isEqualTo(expectedRetry);
    // markAttemptFailed sets status to PENDING (retryable) when not yet exhausted
    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.PENDING);

    // Verify attempt record has the circuit-open detail
    var attemptCaptor = ArgumentCaptor.forClass(NotificationAttemptEntity.class);
    verify(attemptRepository).save(attemptCaptor.capture());
    assertThat(attemptCaptor.getValue().getResponseDetail())
        .isEqualTo(WahaClient.CIRCUIT_OPEN_DETAIL);

    // Rate-limit key must NOT be acquired when circuit is open
    verify(rateLimiter, never()).acquire(any(), any());
  }

  @Test
  void dispatch_whenCircuitOpen_doesNotExhaustJobAttempts() {
    // Circuit open should NOT count as "attempt exhausted" — job stays retryable
    var job = new NotificationJobEntity(
        ALERT_ID, "TECHNICIAN", NotificationJobStatus.PENDING,
        UUID.randomUUID(), RECIPIENT_PHONE,
        ALERT_ID + "::TECHNICIAN", TRACE_ID, null);

    when(rateLimiter.isRateLimited(any(), any())).thenReturn(false);
    when(templateRenderer.render(ALERT_ID)).thenReturn(RENDERED_MSG);
    when(wahaClient.send(any(), any(), any()))
        .thenReturn(new WahaClient.Result(false, 0, WahaClient.CIRCUIT_OPEN_DETAIL));
    when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(jobRepository.save(any())).thenReturn(job);

    service.dispatch(job);

    // Job should be FAILED (retryable), not EXHAUSTED
    assertThat(job.getStatus()).isNotEqualTo(NotificationJobStatus.EXHAUSTED);
  }

  // --- Pre-composed messageBody path (story 12-3 escalation jobs) ---

  @Test
  void dispatch_whenMessageBodyPresent_sendsBodyVerbatimWithoutTemplateRender() {
    String body = "Permintaan sparepart menunggu tindakan: ID abc · Part: MC-0001 · Qty: 2 · Status: REQUESTED";
    var job = new NotificationJobEntity(
        UUID.randomUUID(), "ACK_WAITING", NotificationJobStatus.PENDING,
        UUID.randomUUID(), RECIPIENT_PHONE,
        "SPAREPART_REQUEST:abc:ACK_WAITING:user-1", TRACE_ID, null, body);

    when(rateLimiter.isRateLimited(any(), any())).thenReturn(false);
    when(wahaClient.send(eq(RECIPIENT_PHONE), eq(body), eq(TRACE_ID)))
        .thenReturn(new WahaClient.Result(true, 200, "OK"));
    when(jobRepository.save(any())).thenReturn(job);
    when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.dispatch(job);

    verify(templateRenderer, never()).render(any());
    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.SENT);
  }
}
