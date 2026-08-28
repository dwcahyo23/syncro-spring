package com.syncro.sparepart.request.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Explicit sparepart-request transition table (FR-141, story 12-2). Mirrors
 * {@code WorkOrderStateMachine}'s shape: a {@code Set} of Transition records plus
 * {@code can}/{@code isTerminal}. Valid edges:
 *
 * <pre>
 * REQUESTED → ACKED
 * PENDING_COMPLETION → ACKED
 * ACKED → PROCESSING
 * PROCESSING → READY
 * PROCESSING → PURCHASE_REQUESTED
 * PURCHASE_REQUESTED → PART_RECEIVED
 * PART_RECEIVED → READY
 * READY → PICKED_UP
 * PICKED_UP → CLOSED
 * </pre>
 *
 * <p>CLOSED is terminal — nothing may leave it. Everything else → INVALID_STATE_TRANSITION.
 */
public final class SparepartRequestStateMachine {

  private SparepartRequestStateMachine() {
  }

  private record Transition(SparepartRequestStatus from, SparepartRequestStatus to) {
  }

  private static final Set<Transition> VALID_TRANSITIONS = Set.of(
      new Transition(SparepartRequestStatus.REQUESTED, SparepartRequestStatus.ACKED),
      new Transition(SparepartRequestStatus.PENDING_COMPLETION, SparepartRequestStatus.ACKED),
      new Transition(SparepartRequestStatus.ACKED, SparepartRequestStatus.PROCESSING),
      new Transition(SparepartRequestStatus.PROCESSING, SparepartRequestStatus.READY),
      new Transition(SparepartRequestStatus.PROCESSING, SparepartRequestStatus.PURCHASE_REQUESTED),
      new Transition(SparepartRequestStatus.PURCHASE_REQUESTED, SparepartRequestStatus.PART_RECEIVED),
      new Transition(SparepartRequestStatus.PART_RECEIVED, SparepartRequestStatus.READY),
      new Transition(SparepartRequestStatus.READY, SparepartRequestStatus.PICKED_UP),
      new Transition(SparepartRequestStatus.PICKED_UP, SparepartRequestStatus.CLOSED));

  private static final Set<SparepartRequestStatus> TERMINAL_STATES = EnumSet.of(
      SparepartRequestStatus.CLOSED);

  public static boolean can(SparepartRequestStatus from, SparepartRequestStatus to) {
    return VALID_TRANSITIONS.contains(new Transition(from, to));
  }

  /** CLOSED is terminal: nothing may leave it (FR-141). */
  public static boolean isTerminal(SparepartRequestStatus status) {
    return TERMINAL_STATES.contains(status);
  }
}
