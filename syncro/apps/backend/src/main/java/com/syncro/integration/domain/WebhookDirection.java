package com.syncro.integration.domain;

/**
 * Webhook config direction (blueprint I4, story 15-2): values match the
 * {@code webhook_configs.direction} CHECK constraint in V1 exactly. INBOUND
 * endpoints receive provider callbacks; OUTBOUND configs dispatch app events.
 */
public enum WebhookDirection {
  INBOUND,
  OUTBOUND
}
