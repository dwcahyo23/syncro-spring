package com.syncro.sparepart.request.application;

import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestEntity;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Escalation worker logic for sparepart requests (story 12-3, FR-147). For each configured
 * escalation step (ACK_WAITING / PROCESS_WAITING / PURCHASE_WAITING) it finds requests that
 * have been stuck in the step's status set longer than the configured duration, resolves
 * recipients (the machine's LEADER responsibility user + every INVENTORY_MAINTENANCE /
 * STOREKEEPER user with a phone number) and enqueues one notification job per recipient
 * with a hardcoded request-summary body. Enqueue goes through the notification module's
 * repository with the saveIgnoreDuplicate pattern — never WAHA inline.
 */
@Service
public class SparepartRequestEscalationService {

  private static final Logger log = LoggerFactory.getLogger(SparepartRequestEscalationService.class);

  private static final List<ApplicationRole> INVENTORY_ROLES = List.of(
      ApplicationRole.INVENTORY_MAINTENANCE,
      ApplicationRole.STOREKEEPER);

  private static final Map<String, List<SparepartRequestStatus>> STEP_STATUSES = Map.of(
      "ACK_WAITING", List.of(SparepartRequestStatus.REQUESTED, SparepartRequestStatus.PENDING_COMPLETION),
      "PROCESS_WAITING", List.of(SparepartRequestStatus.ACKED, SparepartRequestStatus.PROCESSING),
      "PURCHASE_WAITING", List.of(SparepartRequestStatus.PURCHASE_REQUESTED, SparepartRequestStatus.PART_RECEIVED));

  private static final String IDEMPOTENCY_KEY_PREFIX = "SPAREPART_REQUEST:";

  private final SparepartRequestRepository requests;
  private final EscalationConfigService escalationConfigs;
  private final MachineResponsibilityRepository responsibilities;
  private final AuthUserRepository users;
  private final WorkOrderRepository workOrders;
  private final NotificationJobRepository notificationJobs;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public SparepartRequestEscalationService(
      SparepartRequestRepository requests,
      EscalationConfigService escalationConfigs,
      MachineResponsibilityRepository responsibilities,
      AuthUserRepository users,
      WorkOrderRepository workOrders,
      NotificationJobRepository notificationJobs,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.requests = requests;
    this.escalationConfigs = escalationConfigs;
    this.responsibilities = responsibilities;
    this.users = users;
    this.workOrders = workOrders;
    this.notificationJobs = notificationJobs;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Evaluates every configured step once: requests whose status is in the step's set and
   * whose updated_at is older than the step duration get one notification job per recipient.
   * Idempotency keys are per (request, step) so duplicates are skipped by the unique
   * constraint. Per-step/request error isolation — a failure never aborts the whole run.
   */
  @Transactional
  public void escalateStale() {
    Instant now = Instant.now(clock);
    for (var step : STEP_STATUSES.keySet()) {
      int durationMinutes = escalationConfigs.durationMinutes(step);
      if (durationMinutes <= 0) {
        log.warn("[SparepartRequestEscalationService] No escalation_configs row for step {} — skipping", step);
        continue;
      }
      Instant cutoff = now.minusSeconds(durationMinutes * 60L);
      List<SparepartRequestEntity> stale = requests.findStaleByStatusInAndUpdatedAtBefore(
          STEP_STATUSES.get(step), cutoff);
      if (stale.isEmpty()) {
        continue;
      }
      log.info("[SparepartRequestEscalationService] Step {} has {} stale request(s)", step, stale.size());
      for (var request : stale) {
        try {
          escalateRequest(request, step);
        } catch (Exception e) {
          log.error("[SparepartRequestEscalationService] Failed to escalate request {} for step {}: {}",
              request.getId(), step, e.getMessage(), e);
        }
      }
    }
  }

  private void escalateRequest(SparepartRequestEntity request, String step) {
    UUID machineId = resolveMachineId(request);
    if (machineId == null) {
      log.info("[SparepartRequestEscalationService] Request {} has no machine target — skipping", request.getId());
      return;
    }

    String body = composeBody(request);
    String traceId = null;

    // 1. Machine LEADER responsibility user (FR-147: "section leaders and above")
    var responsibility = responsibilities
        .findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId, ResponsibilityLevel.LEADER);

    if (responsibility.isPresent()) {
      var user = users.findById(responsibility.get().getUserId()).orElse(null);
      if (user == null) {
        log.warn("[SparepartRequestEscalationService] LEADER userId {} not found for machine {} — ROUTING_FAILED",
            responsibility.get().getUserId(), machineId);
        saveIgnoreDuplicate(notificationJob(
            request, step, idempotencyKey(request, step, responsibility.get().getUserId()),
            NotificationJobStatus.ROUTING_FAILED,
            responsibility.get().getUserId(), null, traceId,
            "LEADER userId=" + responsibility.get().getUserId() + " not found", body));
      } else if (user.getWhatsappNumber() == null || user.getWhatsappNumber().isBlank()) {
        log.warn("[SparepartRequestEscalationService] LEADER userId {} has no whatsappNumber — ROUTING_FAILED",
            responsibility.get().getUserId());
        saveIgnoreDuplicate(notificationJob(
            request, step, idempotencyKey(request, step, user.getId()),
            NotificationJobStatus.ROUTING_FAILED,
            user.getId(), null, traceId,
            "LEADER userId=" + user.getId() + " has no whatsappNumber", body));
      } else {
        saveIgnoreDuplicate(notificationJob(
            request, step, idempotencyKey(request, step, user.getId()),
            NotificationJobStatus.PENDING,
            user.getId(), user.getWhatsappNumber(), traceId, null, body));
      }
    } else {
      // No LEADER assignment — the FR-147 recipient set is still partially satisfiable
      // by inventory/stores roles; the no-responsibility case is logged + skipped below.
      log.info("[SparepartRequestEscalationService] Machine {} has no LEADER assignment — skipping leader job",
          machineId);
    }

    // 2. Every INVENTORY_MAINTENANCE / STOREKEEPER user with a phone
    var inventoryUsers = users.findAllByApplicationRoleInWithWhatsapp(INVENTORY_ROLES);
    for (var user : inventoryUsers) {
      if (user.getWhatsappNumber() == null || user.getWhatsappNumber().isBlank()) {
        continue;
      }
      saveIgnoreDuplicate(notificationJob(
          request, step, idempotencyKey(request, step, user.getId()),
          NotificationJobStatus.PENDING,
          user.getId(), user.getWhatsappNumber(), traceId, null, body));
    }

    // 3. No recipients at all → log + skip (no job)
    if (responsibility.isEmpty() && inventoryUsers.isEmpty()) {
      log.info("[SparepartRequestEscalationService] Request {} step {} has no recipients — skipping",
          request.getId(), step);
    }
  }

  /** Per-recipient idempotency key so one job per (request, step, recipient) is enqueued. */
  private static String idempotencyKey(SparepartRequestEntity request, String step, UUID userId) {
    return IDEMPOTENCY_KEY_PREFIX + request.getId() + ":" + step + ":" + userId;
  }

  /** Resolve the machine a request is scoped against: its own machine, else the bound workorder's machine. */
  private UUID resolveMachineId(SparepartRequestEntity request) {
    if (request.getMachineId() != null) {
      return request.getMachineId();
    }
    if (request.getWorkOrderId() != null) {
      return workOrders.findById(request.getWorkOrderId())
          .map(w -> w.getMachineId())
          .orElse(null);
    }
    return null;
  }

  /**
   * v1 request-summary body (FR-147): requestId, material code or 'new', qty, status,
   * machine code. Composed here and carried on the job's message_body column.
   */
  private String composeBody(SparepartRequestEntity request) {
    return "Permintaan sparepart menunggu tindakan: ID " + request.getId()
        + " · Part: " + (request.getMaterialCode() != null ? request.getMaterialCode() : "new")
        + " · Qty: " + request.getQuantity()
        + " · Status: " + request.getStatus().name();
  }

  private static NotificationJobEntity notificationJob(SparepartRequestEntity request, String step,
      String idempotencyKey, NotificationJobStatus status, UUID recipientUserId, String recipientPhone,
      String traceId, String errorDetail, String messageBody) {
    // alertId is non-null in the schema (V24) — escalation jobs reuse the idempotency
    // key as the discriminator; a random UUID satisfies the NOT NULL constraint without
    // implying an alert relationship.
    return new NotificationJobEntity(
        UUID.randomUUID(), step, status, recipientUserId, recipientPhone, idempotencyKey,
        traceId, errorDetail, messageBody);
  }

  /**
   * Saves a job in its own REQUIRES_NEW transaction (via TransactionTemplate) so a unique
   * violation rolls back only that job's insert, never the whole escalation run. Without
   * this, a flush-time DataIntegrityViolationException marks the outer transaction
   * rollback-only and discards every legitimately new job in the same run.
   */
  private void saveIgnoreDuplicate(NotificationJobEntity job) {
    try {
      // saveAndFlush inside the REQUIRES_NEW transaction surfaces the unique violation
      // here (per-job try/catch) instead of at commit of the outer transaction, where it
      // would mark the whole escalation run rollback-only and discard new jobs.
      transactionTemplate.executeWithoutResult(status -> notificationJobs.saveAndFlush(job));
    } catch (DataIntegrityViolationException ignored) {
      // Duplicate escalation job — idempotent skip (per-request, per-step, per-recipient)
    }
  }
}