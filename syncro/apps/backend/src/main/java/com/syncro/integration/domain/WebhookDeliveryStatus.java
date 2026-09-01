package com.syncro.integration.domain;

/**
 * Webhook delivery outcome (blueprint I4, story 15-2): values match the
 * {@code webhook_delivery_logs.status} CHECK constraint in V1 exactly. DLQ is the
 * terminal dead-letter state after retries are exhausted.
 */
public enum WebhookDeliveryStatus {
  PENDING,
  DELIVERED,
  FAILED,
  RETRYING,
  DLQ
}
