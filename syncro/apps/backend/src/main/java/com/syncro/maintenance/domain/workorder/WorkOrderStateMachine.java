package com.syncro.maintenance.domain.workorder;

import java.util.EnumSet;
import java.util.Set;

/**
 * Explicit workorder transition table (AD-4, story 10.3). Valid edges:
 * {@code DRAFT → OPEN → ASSIGNED → IN_PROGRESS → ON_PROCUREMENT → IN_PROGRESS → DONE → CLOSED}
 * plus {@code OPEN → CANCELLED} and {@code ASSIGNED → CANCELLED}. Terminal states
 * (DONE/CLOSED/CANCELLED) have no outgoing edges — nothing may leave them.
 *
 * <p>{@code OPEN → ASSIGNED} lives here because it is part of the AD-4 lifecycle, but it
 * is exclusive to {@code POST /{id}/assign} (10.2): the transition endpoint rejects
 * {@code toStatus=ASSIGNED} before consulting this table (the service owns that rule).
 */
public final class WorkOrderStateMachine {

  private WorkOrderStateMachine() {
  }

  private record Transition(WorkOrderStatus from, WorkOrderStatus to) {
  }

  private static final Set<Transition> VALID_TRANSITIONS = Set.of(
      new Transition(WorkOrderStatus.DRAFT, WorkOrderStatus.OPEN),
      new Transition(WorkOrderStatus.OPEN, WorkOrderStatus.ASSIGNED),
      new Transition(WorkOrderStatus.ASSIGNED, WorkOrderStatus.IN_PROGRESS),
      new Transition(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.ON_PROCUREMENT),
      new Transition(WorkOrderStatus.ON_PROCUREMENT, WorkOrderStatus.IN_PROGRESS),
      new Transition(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.DONE),
      new Transition(WorkOrderStatus.DONE, WorkOrderStatus.CLOSED),
      new Transition(WorkOrderStatus.OPEN, WorkOrderStatus.CANCELLED),
      new Transition(WorkOrderStatus.ASSIGNED, WorkOrderStatus.CANCELLED));

  private static final Set<WorkOrderStatus> TERMINAL_STATES = EnumSet.of(
      WorkOrderStatus.DONE,
      WorkOrderStatus.CLOSED,
      WorkOrderStatus.CANCELLED);

  public static boolean can(WorkOrderStatus from, WorkOrderStatus to) {
    return VALID_TRANSITIONS.contains(new Transition(from, to));
  }

  /** DONE/CLOSED/CANCELLED are terminal: nothing may leave them (NFR-P2-9 machine side). */
  public static boolean isTerminal(WorkOrderStatus status) {
    return TERMINAL_STATES.contains(status);
  }
}
