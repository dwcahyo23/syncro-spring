package com.syncro.notification.application;

import com.syncro.notification.api.NotificationHistoryDtos;
import com.syncro.notification.domain.WahaTemplate;
import com.syncro.notification.domain.WhatsAppMessageLogStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.WhatsAppMessageLogEntity;
import com.syncro.notification.infrastructure.WhatsAppMessageLogRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Outbound message-log evidence (story 22-4, blueprint I5). One OUTBOUND
 * {@code whatsapp_message_logs} row per logical notification job, upserted across
 * dispatch retries — the physical dedupe anchor is {@code notification_job_id}
 * (unique): the job's idempotency key already encodes target+template+recipient+event,
 * while the masked phone is lossy (two numbers sharing prefix+last-3 collide) and the
 * raw phone must never be stored.
 *
 * <p>Privacy posture: recipient stored masked only ({@link NotificationHistoryDtos#maskPhone}),
 * rendered text stored as a SHA-256 hex reference (22-3 precedent), never raw; no WAHA
 * secret in any column. {@code template_name} is derived for every job so distinct events
 * never collide — alert jobs map to {@code alert_notification}; pre-composed lifecycle/ack/
 * sparepart jobs map to a stable per-event/step marker parsed from the idempotency-key
 * prefix. The raw {@code idempotency_key} is stored too so any derivation is recoverable.
 * ponytail: prefix-parsing mapper is the de-facto AD-9 discriminator today; if the real
 * target_type/target_id columns ever land on notification_jobs, read them directly instead
 * of parsing.
 *
 * <p>Best-effort within dispatch's transaction: the write rides the SAME
 * {@code transactionTemplate} block as the attempt write when it succeeds, but the
 * caller ({@link NotificationDispatchService}) catches RuntimeException around every
 * upsert and only warns — an evidence hiccup (stale FK, length overflow) must never
 * roll back the attempt write + job status update after the WAHA send already
 * landed, which would re-poll the job and duplicate the message forever. An evidence
 * gap is tolerated; a duplicate send is not. A duplicate-dispatch race on the unique
 * job anchor is treated as an update (single-instance deployment assumed, mirroring
 * 22-1's deferred multi-instance posture).
 */
@Service
public class WhatsAppMessageLogService {

  static final String TARGET_TYPE_ALERT = "ALERT";
  static final String TARGET_TYPE_WORK_ORDER = "WORK_ORDER";
  static final String TARGET_TYPE_SPAREPART_REQUEST = "SPAREPART_REQUEST";

  private static final String PREFIX_WORKORDER = "WORKORDER:";
  private static final String PREFIX_WORKORDER_ACK = "WORKORDER_ACK:";
  private static final String PREFIX_SPAREPART_REQUEST = "SPAREPART_REQUEST:";

  private final WhatsAppMessageLogRepository messageLogs;

  public WhatsAppMessageLogService(WhatsAppMessageLogRepository messageLogs) {
    this.messageLogs = messageLogs;
  }

  /**
   * Records one dispatch outcome for the job: inserts the OUTBOUND row on first
   * dispatch, updates the same row on retries (attempt_count, status, sent_at).
   *
   * @param outcome      SENT when WAHA accepted the send, FAILED for failure/circuit-open/render-fail
   * @param renderedText the message text that was (or would have been) sent; null on render-fail
   * @param now          UTC instant from the injected Clock (never wall-clock local time)
   */
  public void upsertForDispatch(NotificationJobEntity job, WhatsAppMessageLogStatus outcome,
      String renderedText, Instant now) {
    var existing = messageLogs.findByNotificationJobId(job.getId());
    if (existing.isPresent()) {
      applyOutcome(existing.get(), outcome, renderedText, now);
      messageLogs.saveAndFlush(existing.get());
      return;
    }
    var row = WhatsAppMessageLogEntity.forOutbound(UUID.randomUUID(), job.getId(),
        job.getIdempotencyKey(), targetType(job), targetId(job), templateName(job),
        maskRecipient(job.getRecipientPhone()), workOrderId(job),
        job.getRecipientUserId(), job.getTraceId(), now);
    applyOutcome(row, outcome, renderedText, now);
    // A true concurrent-insert race on uq_whatsapp_message_logs_notification_job_id
    // surfaces as a DIV that aborts dispatch's transaction (Postgres semantics) and is
    // caught by NotificationWorker's per-job handler — the job is retried and the
    // pre-check above takes the update path. Single-instance today; multi-instance
    // row-claim deferred with 22-1's worker posture.
    messageLogs.saveAndFlush(row);
  }

  private static void applyOutcome(WhatsAppMessageLogEntity row, WhatsAppMessageLogStatus outcome,
      String renderedText, Instant now) {
    row.bumpAttempt();
    if (outcome == WhatsAppMessageLogStatus.SENT) {
      row.markSent(now, sha256Hex(renderedText));
    } else {
      row.markFailed(sha256Hex(renderedText));
    }
  }

  // -------------------------------------------------------------------------
  // Derivation from the job (AD-9 polymorphic identity lives in the idempotency key)
  // -------------------------------------------------------------------------

  /** Stable per-event/step template marker — never null, so distinct events never collide. */
  static String templateName(NotificationJobEntity job) {
    if (job.getAlertId() != null) {
      return WahaTemplate.DEFAULT_KEY;
    }
    var key = job.getIdempotencyKey();
    if (key == null) {
      return "unknown";
    }
    if (key.startsWith(PREFIX_WORKORDER_ACK)) {
      return WahaTemplate.WORKORDER_ACK_KEY;
    }
    if (key.startsWith(PREFIX_WORKORDER)) {
      var event = segment(key, 2);
      return WahaTemplate.WORKORDER_LIFECYCLE_KEY + ":" + (event != null ? event : "unknown");
    }
    if (key.startsWith(PREFIX_SPAREPART_REQUEST)) {
      var step = segment(key, 2);
      return "sparepart_request:" + (step != null ? step : "unknown");
    }
    return "unknown";
  }

  static String targetType(NotificationJobEntity job) {
    if (job.getAlertId() != null) {
      return TARGET_TYPE_ALERT;
    }
    var key = job.getIdempotencyKey();
    if (key == null) {
      return null;
    }
    if (key.startsWith(PREFIX_WORKORDER_ACK) || key.startsWith(PREFIX_WORKORDER)) {
      return TARGET_TYPE_WORK_ORDER;
    }
    if (key.startsWith(PREFIX_SPAREPART_REQUEST)) {
      return TARGET_TYPE_SPAREPART_REQUEST;
    }
    return null;
  }

  static String targetId(NotificationJobEntity job) {
    if (job.getAlertId() != null) {
      return job.getAlertId().toString();
    }
    var key = job.getIdempotencyKey();
    if (key == null) {
      return null;
    }
    // WORKORDER:{woId}:{event}:{userId} / WORKORDER_ACK:{woId}:{userId} /
    // SPAREPART_REQUEST:{requestId}:{step}:{userId} — the id is always segment 1.
    if (key.startsWith(PREFIX_WORKORDER_ACK) || key.startsWith(PREFIX_WORKORDER)
        || key.startsWith(PREFIX_SPAREPART_REQUEST)) {
      return segment(key, 1);
    }
    return null;
  }

  /**
   * Recipient masked for storage/display. {@link NotificationHistoryDtos#maskPhone}
   * returns inputs ≤6 chars UNMASKED (its documented display behavior) — for a stored
   * evidence column that would leak a short full phone, so such inputs collapse to a
   * bare {@code ***} (review 22-4 P2: no full phone numbers, ever).
   */
  static String maskRecipient(String phone) {
    var masked = NotificationHistoryDtos.maskPhone(phone);
    if (masked == null) {
      return null;
    }
    var trimmed = phone.trim();
    if (trimmed.length() <= 6 || masked.equals(trimmed)) {
      return "***";
    }
    return masked;
  }

  /** V1 work_order_id correlation: the woId for lifecycle/ack jobs, null otherwise. */
  static String workOrderId(NotificationJobEntity job) {
    return TARGET_TYPE_WORK_ORDER.equals(targetType(job)) ? targetId(job) : null;
  }

  private static String segment(String key, int index) {
    var parts = key.split(":");
    return parts.length > index ? parts[index] : null;
  }

  /** SHA-256 hex reference of the rendered text (22-3 precedent) — null text → null. */
  static String sha256Hex(String text) {
    if (text == null) {
      return null;
    }
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }
}
