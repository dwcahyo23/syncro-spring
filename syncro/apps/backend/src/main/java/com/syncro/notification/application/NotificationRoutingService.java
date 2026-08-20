package com.syncro.notification.application;

import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.notification.domain.AlertOpenedEvent;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Service
public class NotificationRoutingService {

  private static final Logger log = LoggerFactory.getLogger(NotificationRoutingService.class);

  private final MachineResponsibilityRepository machineResponsibilityRepository;
  private final AuthUserRepository authUserRepository;
  private final NotificationJobRepository notificationJobRepository;

  public NotificationRoutingService(MachineResponsibilityRepository machineResponsibilityRepository,
      AuthUserRepository authUserRepository,
      NotificationJobRepository notificationJobRepository) {
    this.machineResponsibilityRepository = machineResponsibilityRepository;
    this.authUserRepository = authUserRepository;
    this.notificationJobRepository = notificationJobRepository;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onAlertOpened(AlertOpenedEvent event) {
    try {
      var levelName = ResponsibilityLevel.TECHNICIAN.name();
      var responsibility = machineResponsibilityRepository
          .findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(event.machineId(), ResponsibilityLevel.TECHNICIAN);

      if (responsibility.isEmpty()) {
        var job = new NotificationJobEntity(
            event.alertId(),
            levelName,
            NotificationJobStatus.ROUTING_FAILED,
            null,
            null,
            event.alertId() + "::" + levelName,
            event.traceId(),
            "No TECHNICIAN assigned to machine " + event.machineId());
        try {
          notificationJobRepository.save(job);
        } catch (DataIntegrityViolationException ignored) {
          // duplicate event — idempotent skip
        }
        return;
      }

      var userId = responsibility.get().getUserId();
      var userOpt = authUserRepository.findById(userId);
      var whatsappNumber = userOpt.map(u -> u.getWhatsappNumber()).orElse(null);

      if (whatsappNumber == null || whatsappNumber.isBlank()) {
        var job = new NotificationJobEntity(
            event.alertId(),
            levelName,
            NotificationJobStatus.ROUTING_FAILED,
            userId,
            null,
            event.alertId() + "::" + levelName,
            event.traceId(),
            "TECHNICIAN userId=" + userId + " has no whatsappNumber");
        try {
          notificationJobRepository.save(job);
        } catch (DataIntegrityViolationException ignored) {
          // duplicate event — idempotent skip
        }
        return;
      }

      var job = new NotificationJobEntity(
          event.alertId(),
          levelName,
          NotificationJobStatus.PENDING,
          userId,
          whatsappNumber,
          event.alertId() + "::" + levelName,
          event.traceId(),
          null);
      try {
        notificationJobRepository.save(job);
      } catch (DataIntegrityViolationException ignored) {
        // duplicate event — idempotent skip
      }

    } catch (Exception e) {
      log.error("[traceId={}] Failed to route notification for alert {}: {}",
          event.traceId(), event.alertId(), e.getMessage(), e);
    }
  }
}
