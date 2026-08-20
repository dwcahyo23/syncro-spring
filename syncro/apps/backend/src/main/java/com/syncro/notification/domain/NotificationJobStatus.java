package com.syncro.notification.domain;

public enum NotificationJobStatus {
  PENDING,
  ROUTING_FAILED,
  SENT,
  EXHAUSTED,
  ESCALATED,
  CANCELLED,
  /**
   * The dispatch worker checked the Redis rate-limit key for this alert+recipient and found it
   * active. No WAHA call was made. The job's {@code nextAttemptAt} is set to the key expiry so
   * the worker retries after the window expires. Rate limiting does not permanently suppress
   * a notification — it will be retried.
   */
  RATE_LIMITED
}
