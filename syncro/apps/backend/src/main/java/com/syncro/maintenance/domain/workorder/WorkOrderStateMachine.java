package com.syncro.maintenance.domain.workorder;

import java.util.EnumSet;
import java.util.Set;

/**
 * Explicit workorder transition table (AD-4 redesigned for the 6-value status
 * set, story 15-1). Legacy 8-value edges are remapped onto the new enum:
 * {@code DRAFT→OPEN} collapses into create-materialises-OPEN,
 * {@code ASSIGNED→IN_PROGRESS} becomes the assign transition
 * (OPEN → IN_PROGRESS), {@code ON_PROCUREMENT} becomes PENDING_SPAREPART, and
 * {@code DONE} becomes PENDING_REVIEW (completion review before CLOSED). Valid
 * edges:
 * {@code OPEN → IN_PROGRESS → PENDING_SPAREPART → IN_PROGRESS → PENDING_REVIEW → CLOSED}
 * plus {@code OPEN → CANCELLED} and {@code IN_PROGRESS → CANCELLED}. Terminal
 * states (CLOSED/CANCELLED) have no outgoing edges — nothing may leave them.
 *
 * <p>{@code OPEN → IN_PROGRESS} lives here because it is part of the AD-4
 * lifecycle, but it is exclusive to {@code POST /{id}/assign} (10.2): the
 * transition endpoint rejects that target before consulting this table (the
 * service owns that rule).
 */
public final class WorkOrderStateMachine {

  private WorkOrderStateMachine() {
  }

  private record Transition(WorkOrderStatus from, WorkOrderStatus to) {
  }

  private static final Set<Transition> VALID_TRANSITIONS = Set.of(
      new Transition(WorkOrderStatus.OPEN, WorkOrderStatus.IN_PROGRESS),
      new Transition(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.PENDING_SPAREPART),
      new Transition(WorkOrderStatus.PENDING_SPAREPART, WorkOrderStatus.IN_PROGRESS),
      new Transition(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.PENDING_REVIEW),
      new Transition(WorkOrderStatus.PENDING_REVIEW, WorkOrderStatus.CLOSED),
      new Transition(WorkOrderStatus.OPEN, WorkOrderStatus.CANCELLED),
      new Transition(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.CANCELLED));

  private static final Set<WorkOrderStatus> TERMINAL_STATES = EnumSet.of(
      WorkOrderStatus.CLOSED,
      WorkOrderStatus.CANCELLED);

  public static boolean can(WorkOrderStatus from, WorkOrderStatus to) {
    return VALID_TRANSITIONS.contains(new Transition(from, to));
  }

  /** CLOSED/CANCELLED are terminal: nothing may leave them (NFR-P2-9 machine side). */
  public static boolean isTerminal(WorkOrderStatus status) {
    return TERMINAL_STATES.contains(status);
  }
}
