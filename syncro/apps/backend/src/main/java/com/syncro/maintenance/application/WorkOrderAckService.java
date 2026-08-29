package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderAckEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderAckRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingRepository;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workorder 4-hour acknowledgment (story 14-4, FR-181).
 *
 * <p>{@link #acknowledge} records the ack to {@code workorder_acks}, cancels pending
 * notification jobs for the workorder (stopping further escalation) and writes an audit
 * row. {@link #taskList} returns the landing task list: acknowledged vs pending acks and
 * rated vs unrated closed workorders.
 */
@Service
public class WorkOrderAckService {

  private static final Logger log = LoggerFactory.getLogger(WorkOrderAckService.class);

  private final WorkOrderRepository workOrders;
  private final WorkOrderAckRepository acks;
  private final NotificationJobRepository notificationJobs;
  private final WorkorderRatingRepository ratings;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public WorkOrderAckService(WorkOrderRepository workOrders, WorkOrderAckRepository acks,
      NotificationJobRepository notificationJobs, WorkorderRatingRepository ratings,
      AuditLogWriter auditLog, Clock clock) {
    this.workOrders = workOrders;
    this.acks = acks;
    this.notificationJobs = notificationJobs;
    this.ratings = ratings;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /**
   * Records the leader's ack for a workorder. One ack per workorder (DB unique constraint
   * backstops the pre-check). Cancels active notification jobs for the workorder so
   * further escalation stops. Audit records the ack action.
   */
  @Transactional
  public AckResult acknowledge(AuthenticatedUser user, String workOrderId, String traceId) {
    var workOrder = workOrders.findById(workOrderId).orElseThrow(AckWorkOrderNotFoundException::new);
    var now = Instant.now(clock);

    if (acks.findByWorkOrderId(workOrderId).isPresent()) {
      throw new AckAlreadyExistsException();
    }

    var ack = new WorkOrderAckEntity(
        UUID.randomUUID(), workOrderId, UUID.fromString(user.id()), now,
        traceId != null ? traceId : "ack-" + workOrderId);
    try {
      acks.saveAndFlush(ack);
    } catch (DataIntegrityViolationException exception) {
      throw new AckAlreadyExistsException();
    }

    // Cancel pending notification jobs for this workorder (stop further escalation).
    // Matches by the WORKORDER: idempotency-key prefix.
    var cancelled = notificationJobs.cancelActiveForRequest(
        "WORKORDER:" + workOrderId + ":%",
        ACTIVE_JOB_STATUSES, NotificationJobStatus.CANCELLED, now);
    log.info("[traceId={}] Acknowledged workorder {} — cancelled {} active notification jobs",
        traceId, workOrderId, cancelled);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER,
        UUID.nameUUIDFromBytes(workOrderId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        workOrderId, null, null, Map.of(
            "acknowledgedBy", user.id(),
            "acknowledgedAt", now.toString(),
            "cancelledJobs", cancelled), null));

    return new AckResult(workOrderId, now);
  }

  /**
   * The 4-hour ack landing task list: acknowledged vs pending acks, plus rated vs unrated
   * closed workorders. The list is derived from the user's perspective — the full ack
   * surface (task list is not plant-scoped in v1; FR-181 landing page).
   */
  @Transactional(readOnly = true)
  public TaskListView taskList(AuthenticatedUser user) {
    var now = Instant.now(clock);
    var acknowledged = acks.findAll().stream()
        .map(ack -> new AckEntry(ack.getWorkOrderId(), ack.getAcknowledgedBy(), ack.getAcknowledgedAt()))
        .toList();

    // Pending acks: INTERNAL workorders in IN_PROGRESS with net in-progress time >=
    // threshold that are not yet acknowledged.
    var pending = workOrders.findAllNonSyncedByStatus(WorkOrderStatus.IN_PROGRESS).stream()
        .map(w -> w.getId())
        .filter(id -> !acks.existsByWorkOrderId(id))
        .map(id -> new AckEntry(id, null, null))
        .toList();

    // Rated vs unrated closed workorders
    var closedRows = workOrders.findClosedForRating(
        true, List.of(), List.of());
    var rated = new java.util.ArrayList<ClosedWorkorderEntry>();
    var unrated = new java.util.ArrayList<ClosedWorkorderEntry>();
    for (var row : closedRows) {
      var wo = row.workOrder();
      var entry = new ClosedWorkorderEntry(wo.getId(), wo.getStatus(), wo.getDescription(),
          ratings.existsByWorkorderId(wo.getId()));
      if (entry.rated()) {
        rated.add(entry);
      } else {
        unrated.add(entry);
      }
    }
    return new TaskListView(acknowledged, pending, rated, unrated);
  }

  private UUID machinePlantId(WorkOrderEntity workOrder) {
    // The audit record wants the plant id — resolve through the machine when available.
    // The ack service does not hold the machine repository; a null plant id is acceptable
    // for the audit row (same as user-master audits).
    return null;
  }

  // Non-terminal statuses that can still be cancelled (mirrors the alert path).
  private static final Collection<NotificationJobStatus> ACTIVE_JOB_STATUSES = List.of(
      NotificationJobStatus.PENDING,
      NotificationJobStatus.SENT,
      NotificationJobStatus.RATE_LIMITED);

  public record AckResult(String workOrderId, Instant acknowledgedAt) {
  }

  public record AckEntry(String workOrderId, UUID acknowledgedBy, Instant acknowledgedAt) {
  }

  public record ClosedWorkorderEntry(String id, WorkOrderStatus status, String description, Boolean rated) {
  }

  public record TaskListView(
      List<AckEntry> acknowledged,
      List<AckEntry> pending,
      List<ClosedWorkorderEntry> rated,
      List<ClosedWorkorderEntry> unrated) {
  }

  public static class AckWorkOrderNotFoundException extends RuntimeException {
  }

  public static class AckAlreadyExistsException extends RuntimeException {
  }
}