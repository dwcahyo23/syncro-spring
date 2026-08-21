package com.syncro.notification.application;

import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationWorker {

  private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

  private final NotificationJobRepository jobRepository;
  private final NotificationDispatchService dispatchService;
  private final Clock clock;

  private static final List<NotificationJobStatus> DISPATCHABLE_STATUSES =
      List.of(NotificationJobStatus.PENDING, NotificationJobStatus.RATE_LIMITED);

  public NotificationWorker(NotificationJobRepository jobRepository,
      NotificationDispatchService dispatchService,
      Clock clock) {
    this.jobRepository = jobRepository;
    this.dispatchService = dispatchService;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${syncro.notification.worker.poll-interval-ms:30000}")
  public void poll() {
    Instant now = Instant.now(clock);
    var jobs = jobRepository.findPendingJobsDue(DISPATCHABLE_STATUSES, now);
    if (jobs.isEmpty()) {
      return;
    }
    log.info("[NotificationWorker] Processing {} pending jobs", jobs.size());
    int processed = 0;
    for (var job : jobs) {
      try {
        dispatchService.dispatch(job);
        processed++;
      } catch (Exception e) {
        log.error("[NotificationWorker][traceId={}] Unexpected error dispatching job {}: {}",
            job.getTraceId(), job.getId(), e.getMessage(), e);
      }
    }
    log.info("[NotificationWorker] Completed: {}/{} jobs processed", processed, jobs.size());
  }
}
