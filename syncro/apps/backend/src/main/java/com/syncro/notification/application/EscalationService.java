package com.syncro.notification.application;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EscalationService {

  private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

  private static final List<ResponsibilityLevel> ESCALATION_ORDER = List.of(
      ResponsibilityLevel.TECHNICIAN,
      ResponsibilityLevel.STAFF,
      ResponsibilityLevel.LEADER,
      ResponsibilityLevel.SPV,
      ResponsibilityLevel.MANAGER
  );

  private final NotificationJobRepository notificationJobRepository;
  private final SparepartAlertRepository sparepartAlertRepository;
  private final MachineResponsibilityRepository machineResponsibilityRepository;
  private final AuthUserRepository authUserRepository;
  private final Clock clock;

  public EscalationService(
      NotificationJobRepository notificationJobRepository,
      SparepartAlertRepository sparepartAlertRepository,
      MachineResponsibilityRepository machineResponsibilityRepository,
      AuthUserRepository authUserRepository,
      Clock clock) {
    this.notificationJobRepository = notificationJobRepository;
    this.sparepartAlertRepository = sparepartAlertRepository;
    this.machineResponsibilityRepository = machineResponsibilityRepository;
    this.authUserRepository = authUserRepository;
    this.clock = clock;
  }

  @Transactional
  public void escalate(NotificationJobEntity job) {
    Instant now = Instant.now(clock);

    // 1. Load alert — mark ESCALATED if missing (no retry on permanently gone alert)
    var alertOpt = sparepartAlertRepository.findById(job.getAlertId());
    if (alertOpt.isEmpty()) {
      log.error("[EscalationService][traceId={}] Alert {} not found for job {} — marking ESCALATED to stop retry",
          job.getTraceId(), job.getAlertId(), job.getId());
      job.markEscalated(now);
      notificationJobRepository.save(job);
      return;
    }
    var alert = alertOpt.get();

    // 2. Skip if alert is no longer OPEN (AC: 10)
    if (alert.getStatus() != SparepartAlertStatus.OPEN) {
      log.debug("[EscalationService][traceId={}] Alert {} is {} — skipping escalation for job {}",
          job.getTraceId(), job.getAlertId(), alert.getStatus(), job.getId());
      return;
    }

    // 3. Determine current and next escalation level
    ResponsibilityLevel currentLevel;
    try {
      currentLevel = ResponsibilityLevel.valueOf(job.getEscalationLevel());
    } catch (IllegalArgumentException e) {
      log.error("[EscalationService][traceId={}] Unknown escalation level '{}' on job {} — marking ESCALATED to stop retry",
          job.getTraceId(), job.getEscalationLevel(), job.getId());
      job.markEscalated(now);
      notificationJobRepository.save(job);
      return;
    }

    int currentIndex = ESCALATION_ORDER.indexOf(currentLevel);
    if (currentIndex < 0) {
      log.error("[EscalationService][traceId={}] Level '{}' on job {} is not in escalation chain — marking ESCALATED",
          job.getTraceId(), currentLevel, job.getId());
      job.markEscalated(now);
      notificationJobRepository.save(job);
      return;
    }
    boolean hasNext = currentIndex + 1 < ESCALATION_ORDER.size();

    // 4. End of chain — mark escalated with no further queuing (AC: 7)
    if (!hasNext) {
      log.info("[EscalationService][traceId={}] Alert {} reached end of escalation chain at {} — marking job {} ESCALATED",
          job.getTraceId(), job.getAlertId(), currentLevel, job.getId());
      job.markEscalated(now);
      notificationJobRepository.save(job);
      return;
    }

    ResponsibilityLevel nextLevel = ESCALATION_ORDER.get(currentIndex + 1);
    String nextLevelName = nextLevel.name();
    String idempotencyKey = job.getAlertId() + "::" + nextLevelName;

    // 5. Resolve recipient for next level
    var responsibilityOpt = machineResponsibilityRepository
        .findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(alert.getMachineId(), nextLevel);

    if (responsibilityOpt.isEmpty()) {
      // No assignment — record routing failure (AC: 5)
      log.warn("[EscalationService][traceId={}] No {} assignment for machine {} — recording ROUTING_FAILED",
          job.getTraceId(), nextLevelName, alert.getMachineId());
      var failedJob = new NotificationJobEntity(
          job.getAlertId(),
          nextLevelName,
          NotificationJobStatus.ROUTING_FAILED,
          null,
          null,
          idempotencyKey,
          job.getTraceId(),
          "No " + nextLevelName + " assignment for machineId=" + alert.getMachineId());
      saveIgnoreDuplicate(failedJob);
      job.markEscalated(now);
      notificationJobRepository.save(job);
      return;
    }

    var responsibility = responsibilityOpt.get();
    var userId = responsibility.getUserId();
    var userOpt = authUserRepository.findById(userId);

    if (userOpt.isEmpty()) {
      log.warn("[EscalationService][traceId={}] User {} not found for {} assignment — recording ROUTING_FAILED",
          job.getTraceId(), userId, nextLevelName);
      var failedJob = new NotificationJobEntity(
          job.getAlertId(),
          nextLevelName,
          NotificationJobStatus.ROUTING_FAILED,
          userId,
          null,
          idempotencyKey,
          job.getTraceId(),
          nextLevelName + " userId=" + userId + " not found");
      saveIgnoreDuplicate(failedJob);
      job.markEscalated(now);
      notificationJobRepository.save(job);
      return;
    }

    var user = userOpt.get();
    var whatsappNumber = user.getWhatsappNumber();

    if (whatsappNumber == null || whatsappNumber.isBlank()) {
      // No phone — routing failure (AC: 5)
      log.warn("[EscalationService][traceId={}] {} userId={} has no whatsappNumber — recording ROUTING_FAILED",
          job.getTraceId(), nextLevelName, userId);
      var failedJob = new NotificationJobEntity(
          job.getAlertId(),
          nextLevelName,
          NotificationJobStatus.ROUTING_FAILED,
          userId,
          null,
          idempotencyKey,
          job.getTraceId(),
          nextLevelName + " userId=" + userId + " has no whatsappNumber");
      saveIgnoreDuplicate(failedJob);
      job.markEscalated(now);
      notificationJobRepository.save(job);
      return;
    }

    // 6. Queue next-level PENDING job (AC: 4)
    var nextJob = new NotificationJobEntity(
        job.getAlertId(),
        nextLevelName,
        NotificationJobStatus.PENDING,
        userId,
        whatsappNumber,
        idempotencyKey,
        job.getTraceId(),
        null);
    saveIgnoreDuplicate(nextJob);

    // 7. Mark current job as ESCALATED (AC: 6)
    job.markEscalated(now);
    notificationJobRepository.save(job);

    log.info("[EscalationService][traceId={}] Alert {} escalated from {} to {} — new job queued",
        job.getTraceId(), job.getAlertId(), currentLevel, nextLevel);
  }

  private void saveIgnoreDuplicate(NotificationJobEntity job) {
    try {
      notificationJobRepository.save(job);
    } catch (DataIntegrityViolationException ignored) {
      // Duplicate escalation attempt — idempotent skip
    }
  }
}
