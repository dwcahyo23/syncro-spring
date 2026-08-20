package com.syncro.notification.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EscalationWorkerTest {

  @Mock private NotificationJobRepository jobRepository;
  @Mock private EscalationService escalationService;

  private Clock clock;
  private EscalationWorker worker;

  private static final Instant FIXED_NOW = Instant.parse("2026-08-20T10:00:00Z");
  // Default escalationIntervalMs = 900000 (15 min)
  private static final long INTERVAL_MS = 900_000L;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
    worker = new EscalationWorker(jobRepository, escalationService, clock);
    ReflectionTestUtils.setField(worker, "escalationIntervalMs", INTERVAL_MS);
  }

  private NotificationJobEntity sentJob(String level) {
    var alertId = UUID.randomUUID();
    return new NotificationJobEntity(
        alertId, level, NotificationJobStatus.SENT,
        UUID.randomUUID(), "628111", alertId + "::" + level, "trace-w", null);
  }

  // ---- Happy path: jobs are dispatched ----

  @Test
  void poll_withPendingJobs_callsEscalateForEach() {
    var job1 = sentJob("TECHNICIAN");
    var job2 = sentJob("STAFF");
    when(jobRepository.findSentJobsDueForEscalation(any())).thenReturn(List.of(job1, job2));

    worker.poll();

    verify(escalationService).escalate(job1);
    verify(escalationService).escalate(job2);
  }

  @Test
  void poll_withNoJobs_doesNotCallEscalate() {
    when(jobRepository.findSentJobsDueForEscalation(any())).thenReturn(List.of());

    worker.poll();

    verify(escalationService, never()).escalate(any());
  }

  // ---- Optimistic lock conflict per-job is caught and loop continues ----

  @Test
  void poll_whenOptimisticLockOnFirstJob_skipsItAndProcessesSecond() {
    var job1 = sentJob("TECHNICIAN");
    var job2 = sentJob("STAFF");
    when(jobRepository.findSentJobsDueForEscalation(any())).thenReturn(List.of(job1, job2));
    doThrow(new ObjectOptimisticLockingFailureException("NotificationJobEntity", null))
        .when(escalationService).escalate(job1);

    worker.poll();

    // job1 threw but job2 must still be processed
    verify(escalationService).escalate(job1);
    verify(escalationService).escalate(job2);
  }

  // ---- Generic exception per-job is caught and loop continues ----

  @Test
  void poll_whenGenericExceptionOnFirstJob_skipsItAndProcessesSecond() {
    var job1 = sentJob("TECHNICIAN");
    var job2 = sentJob("LEADER");
    when(jobRepository.findSentJobsDueForEscalation(any())).thenReturn(List.of(job1, job2));
    doThrow(new RuntimeException("unexpected"))
        .when(escalationService).escalate(job1);

    worker.poll();

    verify(escalationService, times(1)).escalate(job1);
    verify(escalationService, times(1)).escalate(job2);
  }

  // ---- Cutoff is computed from clock - intervalMs ----

  @Test
  void poll_passesCutoffComputedFromClockAndInterval() {
    when(jobRepository.findSentJobsDueForEscalation(any())).thenReturn(List.of());

    worker.poll();

    var expectedCutoff = FIXED_NOW.minusMillis(INTERVAL_MS);
    verify(jobRepository).findSentJobsDueForEscalation(expectedCutoff);
  }
}
