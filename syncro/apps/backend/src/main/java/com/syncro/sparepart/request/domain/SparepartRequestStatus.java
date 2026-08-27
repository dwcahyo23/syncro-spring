package com.syncro.sparepart.request.domain;

/**
 * Sparepart request status (story 12-1). 12-1 only creates requests — REQUESTED for a
 * known part, PENDING_COMPLETION for a new/unknown part awaiting inventory to finish
 * (FR-144). The full state machine (ACKED/PROCESSING/READY/PURCHASE_REQUESTED/
 * PART_RECEIVED/PICKED_UP/CLOSED) is owned by story 12-2.
 */
public enum SparepartRequestStatus {
  REQUESTED,
  PENDING_COMPLETION
}
