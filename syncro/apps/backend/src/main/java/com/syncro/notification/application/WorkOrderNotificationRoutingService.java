package com.syncro.notification.application;

import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.maintenance.application.WorkOrderLifecycleEvent;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

/**
 * AFTER_COMMIT listener for {@link WorkOrderLifecycleEvent} (story 14-4, FR-180).
 *
 * <p>Resolves recipients per workorder: machine responsibility LEADER/SPV/MANAGER users
 * plus all INVENTORY_MAINTENANCE/STOREKEEPER users with a WhatsApp number. Enqueues one
 * notification job per recipient with idempotency key {@code WORKORDER:{woId}:{event}:{recipientUserId}}.
 * EXTERNAL workorders are excluded (internal-only events).
 */
@Service
public class WorkOrderNotificationRoutingService {

  private static final Logger log = LoggerFactory.getLogger(WorkOrderNotificationRoutingService.class);

  private static final List<ResponsibilityLevel> LEADER_LEVELS = List.of(
      ResponsibilityLevel.LEADER,
      ResponsibilityLevel.SPV,
      ResponsibilityLevel.MANAGER);

  private static final List<ApplicationRole> INVENTORY_ROLES = List.of(
      ApplicationRole.INVENTORY_MAINTENANCE,
      ApplicationRole.STOREKEEPER);

  private static final String IDEMPOTENCY_KEY_PREFIX = "WORKORDER:";

  private final WorkOrderRepository workOrders;
  private final MachineResponsibilityRepository responsibilities;
  private final AuthUserRepository users;
  private final NotificationJobRepository notificationJobs;

  public WorkOrderNotificationRoutingService(
      WorkOrderRepository workOrders,
      MachineResponsibilityRepository responsibilities,
      AuthUserRepository users,
      NotificationJobRepository notificationJobs) {
    this.workOrders = workOrders;
    this.responsibilities = responsibilities;
    this.users = users;
    this.notificationJobs = notificationJobs;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onWorkOrderLifecycleEvent(WorkOrderLifecycleEvent event) {
    try {
      // EXTERNAL workorders are excluded — only INTERNAL events produce notifications
      var workOrderOpt = workOrders.findById(event.workOrderId());
      if (workOrderOpt.isEmpty()) {
        return;
      }
      var workOrder = workOrderOpt.get();
      if (!"INTERNAL".equals(workOrder.getSource())) {
        return;
      }

      UUID machineId = workOrder.getMachineId();
      String traceId = event.traceId();

      // 1. Resolve machine responsibility users (LEADER/SPV/MANAGER)
      for (var level : LEADER_LEVELS) {
        var responsibility = responsibilities
            .findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId, level);
        if (responsibility.isEmpty()) {
          enqueueRoutingFailed(event, level.name(), null, traceId,
              "No " + level.name() + " assignment for machineId=" + machineId);
          continue;
        }
        var userId = responsibility.get().getUserId();
        var userOpt = users.findById(userId);
        if (userOpt.isEmpty()) {
          enqueueRoutingFailed(event, level.name(), userId, traceId,
              level.name() + " userId=" + userId + " not found");
          continue;
        }
        var user = userOpt.get();
        if (user.getWhatsappNumber() == null || user.getWhatsappNumber().isBlank()) {
          enqueueRoutingFailed(event, level.name(), userId, traceId,
              level.name() + " userId=" + userId + " has no whatsappNumber");
          continue;
        }
        enqueuePending(event, level.name(), userId, user.getWhatsappNumber(), traceId);
      }

      // 2. Every INVENTORY_MAINTENANCE / STOREKEEPER user with a phone
      var inventoryUsers = users.findAllByApplicationRoleInWithWhatsapp(INVENTORY_ROLES);
      for (var user : inventoryUsers) {
        if (user.getWhatsappNumber() == null || user.getWhatsappNumber().isBlank()) {
          continue;
        }
        enqueuePending(event, "INVENTORY", user.getId(), user.getWhatsappNumber(), traceId);
      }

    } catch (Exception e) {
      log.error("[traceId={}] Failed to route workorder lifecycle event for {}: {}",
          event.traceId(), event.workOrderId(), e.getMessage(), e);
    }
  }

  private void enqueuePending(WorkOrderLifecycleEvent event, String level, UUID userId,
      String phone, String traceId) {
    var key = idempotencyKey(event, userId);
    var job = new NotificationJobEntity(
        null, level, NotificationJobStatus.PENDING, userId, phone, key, traceId, null);
    saveIgnoreDuplicate(job);
  }

  private void enqueueRoutingFailed(WorkOrderLifecycleEvent event, String level, UUID userId,
      String traceId, String errorDetail) {
    var key = idempotencyKey(event, userId != null ? userId : UUID.nameUUIDFromBytes(
        (event.workOrderId() + ":" + level).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var job = new NotificationJobEntity(
        null, level, NotificationJobStatus.ROUTING_FAILED, userId, null, key, traceId, errorDetail);
    saveIgnoreDuplicate(job);
  }

  private static String idempotencyKey(WorkOrderLifecycleEvent event, UUID userId) {
    return IDEMPOTENCY_KEY_PREFIX + event.workOrderId() + ":" + event.eventType() + ":" + userId;
  }

  private void saveIgnoreDuplicate(NotificationJobEntity job) {
    try {
      notificationJobs.saveAndFlush(job);
    } catch (DataIntegrityViolationException ignored) {
      // duplicate event — idempotent skip
    }
  }
}