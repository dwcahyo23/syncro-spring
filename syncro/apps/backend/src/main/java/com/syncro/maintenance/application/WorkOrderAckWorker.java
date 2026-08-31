package com.syncro.maintenance.application;

import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderAckEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderAckRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryRepository;
import com.syncro.notification.application.WahaTemplateRenderer;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import com.syncro.sparepart.request.application.EscalationConfigService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled worker that computes net IN_PROGRESS time from work_order_status_history
 * (excluding PENDING_SPAREPART spans) and sends a WAHA message with an auto-login link to
 * the PRODUCTION_LEADER when the threshold is exceeded (story 14-4, FR-181).
 *
 * <p>The threshold is configured via {@code escalation_configs} WORKORDER scope
 * {@code ACK_WAITING} step (default 480 min).
 */
@Component
public class WorkOrderAckWorker {

  private static final Logger log = LoggerFactory.getLogger(WorkOrderAckWorker.class);
  static final String WORKORDER_SCOPE = "WORKORDER";
  static final String ACK_WAITING_STEP = "ACK_WAITING";
  /** Default threshold in minutes when no config row exists. */
  static final int DEFAULT_THRESHOLD_MINUTES = 480;
  /** Default TTL for the auto-login token (15 min). */
  static final Duration AUTO_LOGIN_TTL = Duration.ofMinutes(15);

  private final WorkOrderRepository workOrders;
  private final WorkOrderStatusHistoryRepository statusHistory;
  private final WorkOrderAckRepository acks;
  private final NotificationJobRepository notificationJobs;
  private final AuthUserRepository users;
  private final EscalationConfigService escalationConfigs;
  private final WahaTemplateRenderer templateRenderer;
  private final JwtTokenService jwtTokens;
  private final Clock clock;

  public WorkOrderAckWorker(
      WorkOrderRepository workOrders,
      WorkOrderStatusHistoryRepository statusHistory,
      WorkOrderAckRepository acks,
      NotificationJobRepository notificationJobs,
      AuthUserRepository users,
      EscalationConfigService escalationConfigs,
      WahaTemplateRenderer templateRenderer,
      JwtTokenService jwtTokens,
      Clock clock) {
    this.workOrders = workOrders;
    this.statusHistory = statusHistory;
    this.acks = acks;
    this.notificationJobs = notificationJobs;
    this.users = users;
    this.escalationConfigs = escalationConfigs;
    this.templateRenderer = templateRenderer;
    this.jwtTokens = jwtTokens;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${syncro.notification.ack.poll-interval-ms:60000}")
  public void poll() {
    Instant now = Instant.now(clock);
    int thresholdMin = thresholdMinutes();
    if (thresholdMin <= 0) {
      log.debug("[WorkOrderAckWorker] ACK_WAITING threshold is 0 or not configured — skipping");
      return;
    }

    // Find all IN_PROGRESS workorders (non-EXTERNAL) that are not already ack'd
    var candidates = workOrders.findAllNonSyncedByStatus(WorkOrderStatus.IN_PROGRESS);
    if (candidates.isEmpty()) {
      return;
    }
    log.info("[WorkOrderAckWorker] Evaluating {} IN_PROGRESS workorders for ack", candidates.size());

    for (var wo : candidates) {
      if (acks.existsByWorkOrderId(wo.getId())) {
        continue; // already acknowledged
      }
      try {
        long netInProgressMinutes = computeNetInProgressMinutes(wo.getId(), now);
        if (netInProgressMinutes >= thresholdMin) {
          sendAck(wo.getId(), wo.getMachineId(), now);
        }
      } catch (Exception e) {
        log.error("[WorkOrderAckWorker] Failed to evaluate ack for workorder {}: {}",
            wo.getId(), e.getMessage(), e);
      }
    }
  }

  /**
   * Computes net IN_PROGRESS minutes from status history, excluding PENDING_SPAREPART
   * spans. Sums up every segment that starts with a transition to IN_PROGRESS and ends at
   * the next transition (or now if still in progress). When a transition to
   * PENDING_SPAREPART occurs, the IN_PROGRESS time before procurement is counted but the
   * procurement period itself is excluded (the next IN_PROGRESS transition starts a fresh
   * segment).
   */
  long computeNetInProgressMinutes(String workOrderId, Instant now) {
    var history = statusHistory.findByWorkOrderIdOrderByTransitionedAtAsc(workOrderId);
    if (history.isEmpty()) {
      return 0;
    }
    long totalMinutes = 0;
    Instant segmentStart = null;
    for (var row : history) {
      String toStatus = row.getToStatus();
      if (WorkOrderStatus.IN_PROGRESS.name().equals(toStatus)) {
        segmentStart = row.getTransitionedAt();
      } else if (WorkOrderStatus.PENDING_SPAREPART.name().equals(toStatus) && segmentStart != null) {
        // Count the IN_PROGRESS time before procurement, then reset
        totalMinutes += Duration.between(segmentStart, row.getTransitionedAt()).toMinutes();
        segmentStart = null;
      } else if (segmentStart != null) {
        // Transition out of IN_PROGRESS to a non-procurement status
        totalMinutes += Duration.between(segmentStart, row.getTransitionedAt()).toMinutes();
        segmentStart = null;
      }
    }
    // If still in progress with no exit, count until now
    if (segmentStart != null) {
      totalMinutes += Duration.between(segmentStart, now).toMinutes();
    }
    return totalMinutes;
  }

  private void sendAck(String workOrderId, UUID machineId, Instant now) {
    // Resolve PRODUCTION_LEADER recipients — find the first user with that role
    // who has a WhatsApp number (the ack goes to the production leader).
    String traceId = "wo-ack-" + workOrderId + "-" + now.toEpochMilli();
    String machineCode = templateRenderer.machineCodeFor(workOrderId, machineId);

    // Resolve leader recipients: PRODUCTION_LEADER role with WhatsApp
    var leaderUsers = users.findAllByApplicationRoleInWithWhatsapp(
        List.of(com.syncro.auth.domain.ApplicationRole.PRODUCTION_LEADER));
    if (leaderUsers.isEmpty()) {
      log.warn("[WorkOrderAckWorker][traceId={}] No PRODUCTION_LEADER with WhatsApp found — skipping ack", traceId);
      return;
    }

    Instant deadline = now.plus(Duration.ofMinutes(thresholdMinutes()));

    for (var leader : leaderUsers) {
      String idempotencyKey = "WORKORDER_ACK:" + workOrderId + ":" + leader.getId();
      try {
        // Mint a short-lived JWT: typ=AUTO_LOGIN, wa=whatsappNumber, wo=workOrderId
        var extraClaims = new java.util.LinkedHashMap<String, String>();
        extraClaims.put("typ", "AUTO_LOGIN");
        extraClaims.put("wa", leader.getWhatsappNumber());
        extraClaims.put("wo", workOrderId);
        String autoLoginToken = jwtTokens.createToken(leader, AUTO_LOGIN_TTL, extraClaims);

        // Build the ack link (frontend URL)
        String ackLink = "/dashboard/workorders/ack-task-list?token=" + autoLoginToken;

        // Render the ack message
        String messageBody = templateRenderer.renderWorkorderAck(
            workOrderId, machineCode, deadline, ackLink);

        // Enqueue a notification job (idempotency key dedups repeated runs)
        var job = new NotificationJobEntity(
            null, "ACK_WAITING", NotificationJobStatus.PENDING,
            leader.getId(), leader.getWhatsappNumber(),
            idempotencyKey, traceId, null, messageBody);
        try {
          notificationJobs.saveAndFlush(job);
        } catch (DataIntegrityViolationException ignored) {
          // duplicate — idempotent skip
        }
      } catch (Exception e) {
        log.error("[WorkOrderAckWorker][traceId={}] Failed to enqueue ack for leader {}: {}",
            traceId, leader.getId(), e.getMessage(), e);
      }
    }
  }

  private int thresholdMinutes() {
    var config = escalationConfigs.findByScope(WORKORDER_SCOPE, ACK_WAITING_STEP);
    return config.map(c -> c.getDurationMinutes() > 0 ? c.getDurationMinutes() : DEFAULT_THRESHOLD_MINUTES)
        .orElse(DEFAULT_THRESHOLD_MINUTES);
  }
}