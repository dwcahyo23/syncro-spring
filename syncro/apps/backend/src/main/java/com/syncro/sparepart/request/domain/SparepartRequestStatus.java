package com.syncro.sparepart.request.domain;

/**
 * Full sparepart request status set (FR-141, story 12-2). The state machine edges are
 * owned by {@link SparepartRequestStateMachine}: REQUESTED → ACKED → PROCESSING →
 * [READY | PURCHASE_REQUESTED → PART_RECEIVED → READY] → PICKED_UP → CLOSED.
 * PENDING_COMPLETION → ACKED is also allowed (new-item requests from 12-4).
 */
public enum SparepartRequestStatus {
  REQUESTED,
  PENDING_COMPLETION,
  ACKED,
  PROCESSING,
  READY,
  PURCHASE_REQUESTED,
  PART_RECEIVED,
  PICKED_UP,
  CLOSED
}
