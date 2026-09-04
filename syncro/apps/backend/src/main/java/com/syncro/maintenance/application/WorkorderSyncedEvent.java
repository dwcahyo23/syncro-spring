package com.syncro.maintenance.application;

import java.util.UUID;

/**
 * Published by {@link WorkorderImportService} when a sync upsert creates or updates a
 * workorder (story 20-1, AD-6/AD-20: "sync mutations invalidate analytics"). Consumed
 * by the kpi module's refresh scheduler with
 * {@code @TransactionalEventListener(AFTER_COMMIT)} — the refresh never runs inside the
 * sync batch transaction and never sees pre-commit state.
 *
 * @param workOrderId the synced workorder id (VARCHAR PK)
 * @param machineId   the machine the workorder belongs to
 * @param traceId     correlation id for logging
 */
public record WorkorderSyncedEvent(String workOrderId, UUID machineId, String traceId) {
}
