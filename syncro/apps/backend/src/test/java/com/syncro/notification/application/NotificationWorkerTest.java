package com.syncro.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationWorkerTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-08-21T08:00:00Z");
  private static final Clock FIXED_CLOCK =
      Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

  @Mock
  private NotificationJobRepository jobRepository;

  @Mock
  private NotificationDispatchService dispatchService;

  private final NotificationWorkerTracker tracker = new NotificationWorkerTracker(FIXED_CLOCK);
  private NotificationWorker worker;

  @BeforeEach
  void setUp() {
    worker = new NotificationWorker(jobRepository, dispatchService, tracker, FIXED_CLOCK);
  }

  @Test
  void pollRecordsPollEvenWhenNoJobs() {
    when(jobRepository.findPendingJobsDue(any(), any())).thenReturn(List.of());

    worker.poll();

    assertThat(tracker.lastPollAt()).isEqualTo(FIXED_NOW);
  }

  @Test
  void pollRecordsPollAndDispatchesJobs() {
    var job = new NotificationJobEntity(
        UUID.randomUUID(), "TECHNICIAN", NotificationJobStatus.PENDING,
        UUID.randomUUID(), "628111", UUID.randomUUID().toString(), "trace-1", null);
    when(jobRepository.findPendingJobsDue(any(), any())).thenReturn(List.of(job));

    worker.poll();

    assertThat(tracker.lastPollAt()).isEqualTo(FIXED_NOW);
    verify(dispatchService).dispatch(job);
  }
}
