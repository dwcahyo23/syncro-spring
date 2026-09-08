package com.syncro.notification.infrastructure;

import com.syncro.notification.domain.WhatsAppMessageDirection;
import com.syncro.notification.domain.WhatsAppMessageLogStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted {@code whatsapp_message_logs} row (blueprint I5, story 15-2; outbound
 * evidence in story 22-4). The table is shared inbound+outbound: inbound rows
 * (WAHA ingest — mapped only, no writer yet) carry {@code waha_message_id} as the
 * idempotency key; OUTBOUND rows (story 22-4) record one logical notification send —
 * masked recipient, derived template, status, attempt count, traceId and a SHA-256
 * reference of the rendered text (never the raw text or phone). The physical dedupe
 * anchor is {@code notification_job_id} (unique): the job's own idempotency key
 * already encodes target+template+recipient+event, while the masked phone is lossy.
 * {@code waha_message_id} is nullable for outbound rows — the WAHA id is deliberately
 * not parsed from the 2xx body (per-attempt raw response lives in
 * {@code notification_attempts.response_detail}, which this log complements).
 */
@Entity
@Table(name = "whatsapp_message_logs")
public class WhatsAppMessageLogEntity {

  @Id
  private UUID id;

  @Column(name = "waha_message_id", length = 255)
  private String wahaMessageId;

  @Column(length = 100)
  private String session;

  @Column(name = "chat_id", length = 255)
  private String chatId;

  @Column(name = "from_phone", length = 32)
  private String fromPhone;

  @Column(columnDefinition = "text")
  private String text;

  @Column(name = "attachment_url", columnDefinition = "text")
  private String attachmentUrl;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> payload;

  @Column(name = "work_order_id", length = 50)
  private String workOrderId;

  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "received_at")
  private Instant receivedAt;

  // --- Story 22-4 outbound evidence columns (V20) ---

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 9)
  private WhatsAppMessageDirection direction;

  @Column(name = "notification_job_id")
  private UUID notificationJobId;

  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  @Column(name = "target_type", length = 32)
  private String targetType;

  @Column(name = "target_id", length = 64)
  private String targetId;

  @Column(name = "template_name", length = 100)
  private String templateName;

  @Column(name = "recipient_masked", length = 64)
  private String recipientMasked;

  @Enumerated(EnumType.STRING)
  @Column(length = 16)
  private WhatsAppMessageLogStatus status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "trace_id", length = 64)
  private String traceId;

  @Column(name = "text_sha256", length = 64)
  private String textSha256;

  @Column(name = "sent_at")
  private Instant sentAt;

  /** Row-creation instant (server Clock) — always set, never updated on retry. */
  @Column(name = "logged_at", nullable = false, updatable = false)
  private Instant loggedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected WhatsAppMessageLogEntity() {
  }

  /** Inbound-shaped constructor (story 15-2 mapping; direction fixed to INBOUND). */
  public WhatsAppMessageLogEntity(UUID id, String wahaMessageId, String session, String chatId,
      String fromPhone, String text, String attachmentUrl, Map<String, Object> payload,
      String workOrderId, UUID userId, Instant receivedAt) {
    this.id = id;
    this.wahaMessageId = wahaMessageId;
    this.session = session;
    this.chatId = chatId;
    this.fromPhone = fromPhone;
    this.text = text;
    this.attachmentUrl = attachmentUrl;
    this.payload = payload;
    this.workOrderId = workOrderId;
    this.userId = userId;
    this.receivedAt = receivedAt;
    this.direction = WhatsAppMessageDirection.INBOUND;
    // logged_at is NOT NULL (V20): for inbound rows the ingest time IS the row-creation
    // instant, so stamp it from receivedAt (the outbound factory takes its own value).
    this.loggedAt = receivedAt;
  }

  /**
   * Story 22-4: first OUTBOUND row for a dispatched job. The caller (service) has
   * already derived template/target/masked recipient from the job; the dispatch
   * outcome is applied through {@link #markSent}/{@link #markFailed} + {@link #bumpAttempt}.
   * {@code loggedAt} stamps the row-creation instant (never updated on retry) so
   * never-sent rows still carry a timestamp.
   */
  public static WhatsAppMessageLogEntity forOutbound(UUID id, UUID notificationJobId,
      String idempotencyKey, String targetType, String targetId, String templateName,
      String recipientMasked, String workOrderId, UUID userId, String traceId, Instant loggedAt) {
    var entity = new WhatsAppMessageLogEntity();
    entity.id = id;
    entity.direction = WhatsAppMessageDirection.OUTBOUND;
    entity.notificationJobId = notificationJobId;
    entity.idempotencyKey = idempotencyKey;
    entity.targetType = targetType;
    entity.targetId = targetId;
    entity.templateName = templateName;
    entity.recipientMasked = recipientMasked;
    entity.workOrderId = workOrderId;
    entity.userId = userId;
    entity.traceId = traceId;
    entity.loggedAt = loggedAt;
    return entity;
  }

  /** One more dispatch wrote evidence on this row (attempt_count = logged dispatches). */
  public void bumpAttempt() {
    this.attemptCount = this.attemptCount + 1;
  }

  /** WAHA accepted the send: terminal-for-now success state, latest sent_at wins. */
  public void markSent(Instant sentAt, String textSha256) {
    this.status = WhatsAppMessageLogStatus.SENT;
    this.sentAt = sentAt;
    this.textSha256 = textSha256;
  }

  /**
   * Dispatch did not land (failure, circuit-open, or render-fail). {@code textSha256}
   * is null when the message never rendered; the failure reason itself lives on the
   * companion {@code notification_attempts} row written in the same transaction.
   */
  public void markFailed(String textSha256) {
    this.status = WhatsAppMessageLogStatus.FAILED;
    this.textSha256 = textSha256;
  }

  public UUID getId() {
    return id;
  }

  public String getWahaMessageId() {
    return wahaMessageId;
  }

  public String getSession() {
    return session;
  }

  public String getChatId() {
    return chatId;
  }

  public String getFromPhone() {
    return fromPhone;
  }

  public String getText() {
    return text;
  }

  public String getAttachmentUrl() {
    return attachmentUrl;
  }

  public Map<String, Object> getPayload() {
    return payload;
  }

  public String getWorkOrderId() {
    return workOrderId;
  }

  public UUID getUserId() {
    return userId;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public WhatsAppMessageDirection getDirection() {
    return direction;
  }

  public UUID getNotificationJobId() {
    return notificationJobId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getTargetType() {
    return targetType;
  }

  public String getTargetId() {
    return targetId;
  }

  public String getTemplateName() {
    return templateName;
  }

  public String getRecipientMasked() {
    return recipientMasked;
  }

  public WhatsAppMessageLogStatus getStatus() {
    return status;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public String getTraceId() {
    return traceId;
  }

  public String getTextSha256() {
    return textSha256;
  }

  public Instant getSentAt() {
    return sentAt;
  }

  public Instant getLoggedAt() {
    return loggedAt;
  }

  public long getVersion() {
    return version;
  }
}
