package com.syncro.notification.domain;

/**
 * Message direction on {@code whatsapp_message_logs} (blueprint I5). V1 shaped the
 * table for inbound ingest; story 22-4 adds OUTBOUND rows for every WAHA send.
 * Inbound handling stays unimplemented — the enum value exists for the shared table.
 */
public enum WhatsAppMessageDirection {
  INBOUND,
  OUTBOUND
}
