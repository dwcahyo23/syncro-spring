package com.syncro.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EscalationWorker {

  private static final Logger log = LoggerFactory.getLogger(EscalationWorker.class);

  private final com.syncro.notification.infrastructure.NotificationJobRepository jobRepository;
  private final EscalationService escalationService;
  private final Clock clock;

  @Value("${syncro.notification.escalation.interval-ms:900000}")
  private long escalationIntervalMs;

  public EscalationWorker(
      com.syncro.notification.infrastructure.NotificationJobRepository jobRepository,
      EscalationService escalationService,
      Clock clock) {
    this.jobRepository = jobRepository;
    this.escalationService = escalationService;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${syncro.notification.escalation.poll-interval-ms:60000}")
  public void poll() {
    Instant now = Instant.now(clock);
    Instant cutoff = now.minus(escalationIntervalMs, ChronoUnit.MILLIS);
    var jobs = jobRepository.findSentJobsDueForEscalation(cutoff);
    if (jobs.isEmpty()) {
      return;
    }
    log.info("[EscalationWorker] Processing {} SENT jobs due for escalation", jobs.size());
    int processed = 0;
    for (var job : jobs) {
      try {
        escalationService.escalate(job);
        processed++;
      } catch (ObjectOptimisticLockingFailureException e) {
        log.warn("[EscalationWorker][traceId={}] Optimistic lock conflict on job {} — skipping this cycle",
            job.getTraceId(), job.getId());
      } catch (Exception e) {
        log.error("[EscalationWorker][traceId={}] Unexpected error escalating job {}: {}",
            job.getTraceId(), job.getId(), e.getMessage(), e);
      }
    }
    log.info("[EscalationWorker] Completed: {}/{} jobs processed", processed, jobs.size());
  }
}
