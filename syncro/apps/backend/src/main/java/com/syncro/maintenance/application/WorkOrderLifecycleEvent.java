package com.syncro.maintenance.application;

import com.syncro.maintenance.domain.workorder.WorkOrderStatus;

/**
 * Published by {@link WorkOrderService} on every lifecycle-relevant transition
 * (story 14-4, FR-180). Consumed by {@code WorkOrderNotificationRoutingService}
 * with {@code @TransactionalEventListener(AFTER_COMMIT)}.
 *
 * @param workOrderId the workorder id (VARCHAR PK)
 * @param eventType   the lifecycle event type (BREAKDOWN, ON_PROCUREMENT, PART_READY, DONE, CLOSED)
 * @param status      the target status after the transition
 * @param traceId     correlation id for logging and notification_jobs
 */
public record WorkOrderLifecycleEvent(String workOrderId, String eventType, WorkOrderStatus status, String traceId) {

  public static final String EVENT_BREAKDOWN = "BREAKDOWN";
  public static final String EVENT_ON_PROCUREMENT = "ON_PROCUREMENT";
  public static final String EVENT_PART_READY = "PART_READY";
  public static final String EVENT_DONE = "DONE";
  public static final String EVENT_CLOSED = "CLOSED";
}