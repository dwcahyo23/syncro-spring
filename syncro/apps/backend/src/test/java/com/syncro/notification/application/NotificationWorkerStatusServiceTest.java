package com.syncro.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.syncro.config.NotificationProperties;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationAttemptEntity;
import com.syncro.notification.infrastructure.NotificationAttemptRepository;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import com.syncro.notification.infrastructure.WahaClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NotificationWorkerStatusServiceTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-08-21T08:00:00Z");
  private static final Clock FIXED_CLOCK =
      Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
  private static final Duration FAILED_WINDOW = Duration.ofHours(1);

  @Mock
  private NotificationJobRepository jobRepository;

  @Mock
  private NotificationAttemptRepository attemptRepository;

  @Mock
  private WahaClient wahaClient;

  @Mock
  private CircuitBreaker circuitBreaker;

  private final NotificationWorkerTracker tracker = new NotificationWorkerTracker(FIXED_CLOCK);

  private NotificationWorkerStatusService service;

  @BeforeEach
  void setUp() {
    when(wahaClient.getCircuitBreaker()).thenReturn(circuitBreaker);
    when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);
    service = new NotificationWorkerStatusService(tracker, jobRepository, attemptRepository,
        wahaClient, properties(), FIXED_CLOCK);
  }

  private static NotificationProperties properties() {
    return new NotificationProperties(
        new NotificationProperties.Worker(STALE_THRESHOLD, FAILED_WINDOW));
  }

  @Test
  void neverPolledReportsStopped() {
    when(jobRepository.countByStatusIn(java.util.List.of(
        NotificationJobStatus.PENDING, NotificationJobStatus.RATE_LIMITED))).thenReturn(0L);
    when(attemptRepository.countByStatusAndAttemptedAtAfter(eq("FAILED"),
        eq(FIXED_NOW.minus(FAILED_WINDOW)))).thenReturn(0L);
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("FAILED")).thenReturn(Optional.empty());
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("SENT")).thenReturn(Optional.empty());

    NotificationWorkerStatus status = service.status();

    assertThat(status.status()).isEqualTo(NotificationWorkerState.STOPPED);
    assertThat(status.statusLabel()).isEqualTo("Stopped");
    assertThat(status.statusSeverity()).isEqualTo("CRITICAL");
    assertThat(status.statusReason()).isEqualTo("Worker never polled");
    assertThat(status.lastPollAt()).isNull();
  }

  @Test
  void freshPollWithClosedCircuitReportsRunning() {
    tracker.recordPoll();

    when(jobRepository.countByStatusIn(java.util.List.of(
        NotificationJobStatus.PENDING, NotificationJobStatus.RATE_LIMITED))).thenReturn(2L);
    when(attemptRepository.countByStatusAndAttemptedAtAfter(eq("FAILED"),
        eq(FIXED_NOW.minus(FAILED_WINDOW)))).thenReturn(0L);
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("FAILED")).thenReturn(Optional.empty());
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("SENT")).thenReturn(Optional.empty());

    NotificationWorkerStatus status = service.status();

    assertThat(status.status()).isEqualTo(NotificationWorkerState.RUNNING);
    assertThat(status.statusLabel()).isEqualTo("Running");
    assertThat(status.statusSeverity()).isEqualTo("SUCCESS");
    assertThat(status.statusReason()).isNull();
    assertThat(status.lastPollAt()).isEqualTo(FIXED_NOW.toString());
    assertThat(status.pendingJobCount()).isEqualTo(2);
  }

  @Test
  void stalePollReportsDegradedWithReasonAndStaleSince() {
    Clock pastClock = Clock.fixed(FIXED_NOW.minus(Duration.ofMinutes(10)), ZoneOffset.UTC);
    NotificationWorkerTracker staleTracker = new NotificationWorkerTracker(pastClock);
    staleTracker.recordPoll();
    service = new NotificationWorkerStatusService(staleTracker, jobRepository, attemptRepository,
        wahaClient, properties(), FIXED_CLOCK);

    when(jobRepository.countByStatusIn(java.util.List.of(
        NotificationJobStatus.PENDING, NotificationJobStatus.RATE_LIMITED))).thenReturn(0L);
    when(attemptRepository.countByStatusAndAttemptedAtAfter(eq("FAILED"),
        eq(FIXED_NOW.minus(FAILED_WINDOW)))).thenReturn(0L);
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("FAILED")).thenReturn(Optional.empty());
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("SENT")).thenReturn(Optional.empty());

    NotificationWorkerStatus status = service.status();

    assertThat(status.status()).isEqualTo(NotificationWorkerState.DEGRADED);
    assertThat(status.statusReason())
        .contains("Worker last polled at")
        .contains(FIXED_NOW.minus(Duration.ofMinutes(10)).toString());
    assertThat(status.lastPollAt()).isEqualTo(FIXED_NOW.minus(Duration.ofMinutes(10)).toString());
    assertThat(status.staleSince()).isEqualTo(FIXED_NOW.minus(Duration.ofMinutes(5)).toString());
  }

  @Test
  void openCircuitReportsDegradedEvenWithFreshPoll() {
    tracker.recordPoll();
    when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.OPEN);

    when(jobRepository.countByStatusIn(java.util.List.of(
        NotificationJobStatus.PENDING, NotificationJobStatus.RATE_LIMITED))).thenReturn(0L);
    when(attemptRepository.countByStatusAndAttemptedAtAfter(eq("FAILED"),
        eq(FIXED_NOW.minus(FAILED_WINDOW)))).thenReturn(0L);
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("FAILED")).thenReturn(Optional.empty());
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("SENT")).thenReturn(Optional.empty());

    NotificationWorkerStatus status = service.status();

    assertThat(status.status()).isEqualTo(NotificationWorkerState.DEGRADED);
    assertThat(status.statusReason()).isEqualTo("WAHA circuit breaker is OPEN");
  }

  @Test
  void exactEqualityBoundaryStaysRunning() {
    Clock boundaryClock = Clock.fixed(FIXED_NOW.minus(STALE_THRESHOLD), ZoneOffset.UTC);
    NotificationWorkerTracker boundaryTracker = new NotificationWorkerTracker(boundaryClock);
    boundaryTracker.recordPoll();
    service = new NotificationWorkerStatusService(boundaryTracker, jobRepository, attemptRepository,
        wahaClient, properties(), FIXED_CLOCK);

    when(jobRepository.countByStatusIn(java.util.List.of(
        NotificationJobStatus.PENDING, NotificationJobStatus.RATE_LIMITED))).thenReturn(0L);
    when(attemptRepository.countByStatusAndAttemptedAtAfter(eq("FAILED"),
        eq(FIXED_NOW.minus(FAILED_WINDOW)))).thenReturn(0L);
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("FAILED")).thenReturn(Optional.empty());
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("SENT")).thenReturn(Optional.empty());

    NotificationWorkerStatus status = service.status();

    assertThat(status.status()).isEqualTo(NotificationWorkerState.RUNNING);
  }

  @Test
  void negativeElapsedIsTreatedAsFresh() {
    // lastPollAt recorded in the future relative to now (clock moved backward) => fresh
    NotificationWorkerTracker futureTracker =
        new NotificationWorkerTracker(Clock.fixed(FIXED_NOW.plusSeconds(30), ZoneOffset.UTC));
    futureTracker.recordPoll();
    service = new NotificationWorkerStatusService(futureTracker, jobRepository, attemptRepository,
        wahaClient, properties(), FIXED_CLOCK);

    when(jobRepository.countByStatusIn(java.util.List.of(
        NotificationJobStatus.PENDING, NotificationJobStatus.RATE_LIMITED))).thenReturn(0L);
    when(attemptRepository.countByStatusAndAttemptedAtAfter(eq("FAILED"),
        eq(FIXED_NOW.minus(FAILED_WINDOW)))).thenReturn(0L);
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("FAILED")).thenReturn(Optional.empty());
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("SENT")).thenReturn(Optional.empty());

    NotificationWorkerStatus status = service.status();

    assertThat(status.status()).isEqualTo(NotificationWorkerState.RUNNING);
  }

  @Test
  void statusSurfacesMetricsAndTimestamps() {
    tracker.recordPoll();

    when(jobRepository.countByStatusIn(java.util.List.of(
        NotificationJobStatus.PENDING, NotificationJobStatus.RATE_LIMITED))).thenReturn(3L);
    when(attemptRepository.countByStatusAndAttemptedAtAfter(eq("FAILED"),
        eq(FIXED_NOW.minus(FAILED_WINDOW)))).thenReturn(1L);
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("FAILED"))
        .thenReturn(Optional.of(attempt("FAILED", "HTTP 500: internal error")));
    when(attemptRepository.findTopByStatusOrderByAttemptedAtDesc("SENT"))
        .thenReturn(Optional.of(attempt("SENT", "ok")));

    NotificationWorkerStatus status = service.status();

    assertThat(status.pendingJobCount()).isEqualTo(3);
    assertThat(status.recentFailedCount()).isEqualTo(1);
    assertThat(status.lastFailureReason()).isEqualTo("HTTP 500: internal error");
    assertThat(status.lastSuccessfulSendAt()).isEqualTo(FIXED_NOW.toString());
    assertThat(status.circuitBreakerState()).isEqualTo("CLOSED");
    assertThat(status.timestamp()).isEqualTo(FIXED_NOW.toString());
  }

  private static NotificationAttemptEntity attempt(String status, String detail) {
    var attempt = new NotificationAttemptEntity(
        UUID.randomUUID(), 1, status, detail, "trace");
    ReflectionTestUtils.setField(attempt, "attemptedAt", FIXED_NOW);
    return attempt;
  }
}
