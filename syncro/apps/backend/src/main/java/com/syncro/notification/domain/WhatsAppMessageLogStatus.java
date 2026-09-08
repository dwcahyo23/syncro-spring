package com.syncro.notification.domain;

/**
 * Delivery outcome recorded on an outbound {@code whatsapp_message_logs} row
 * (story 22-4). Per-message evidence state — the job lifecycle stays in
 * {@link NotificationJobStatus}; this only distinguishes "WAHA accepted the send"
 * from "this dispatch did not land" (failure, circuit-open, or render-fail).
 */
public enum WhatsAppMessageLogStatus {
  SENT,
  FAILED
}
