package com.syncro.maintenance.application;

/**
 * Sparepart-request readiness port (AD-5, story 10.3). Answers whether the workorder has
 * at least one live sparepart request that is not READY, which drives the derived
 * ON_PROCUREMENT state. The {@code NoopSparepartRequestReadinessPort} reports "no live
 * request" until Epic 12 ships the real request module and calls
 * {@link WorkOrderService#recomputeProcurementState(String)} on request transition events.
 */
public interface SparepartRequestReadinessPort {

  boolean hasLiveNonReadyRequest(String workOrderId);
}
