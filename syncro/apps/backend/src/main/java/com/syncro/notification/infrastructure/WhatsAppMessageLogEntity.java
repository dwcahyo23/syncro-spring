package com.syncro.notification.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted {@code whatsapp_message_logs} row (blueprint I5, story 15-2). One inbound
 * WhatsApp message as received via WAHA: raw payload JSONB for audit, the optional
 * workorder/user correlation ({@code work_order_id} is the VARCHAR(50) work_orders
 * reference). {@code waha_message_id} is unique — the ingest idempotency key.
 */
@Entity
@Table(name = "whatsapp_message_logs")
public class WhatsAppMessageLogEntity {

  @Id
  private UUID id;

  @Column(name = "waha_message_id", nullable = false, length = 255)
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

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  protected WhatsAppMessageLogEntity() {
  }

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
}
