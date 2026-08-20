package com.syncro.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.notification.domain.NotificationJobStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationJobEntityTest {

  private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

  private NotificationJobEntity pendingJob() {
    return new NotificationJobEntity(
        UUID.randomUUID(), "TECHNICIAN", NotificationJobStatus.PENDING,
        UUID.randomUUID(), "6281234567890", "idem-key", "trace-5-5", null);
  }

  private NotificationJobEntity sentJob() {
    var job = pendingJob();
    job.markSent(NOW.minusSeconds(600));
    return job;
  }

  @Test
  void markCancelled_setsStatusAndClearsNextAttempt() {
    var job = pendingJob();
    job.markCancelled(NOW);

    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.CANCELLED);
    assertThat(job.getNextAttemptAt()).isNull();
    assertThat(job.getUpdatedAt()).isEqualTo(NOW);
  }

  @Test
  void markCancelled_onSentJob_setsStatusAndClearsNextAttempt() {
    var job = sentJob();
    job.markCancelled(NOW);

    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.CANCELLED);
    assertThat(job.getNextAttemptAt()).isNull();
    assertThat(job.getUpdatedAt()).isEqualTo(NOW);
  }
}
