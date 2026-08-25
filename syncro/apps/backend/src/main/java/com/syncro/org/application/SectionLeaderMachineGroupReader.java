package com.syncro.org.application;

import java.util.Set;
import java.util.UUID;

/**
 * Port implemented by the machine module: the set of machine-group ids where the
 * user holds responsibility level LEADER or above (AD-2). Section leadership is
 * derived from machine responsibilities — no separate section-leader assignment.
 */
public interface SectionLeaderMachineGroupReader {
  Set<UUID> findMachineGroupIdsWhereUserIsLeader(UUID userId);
}
