package com.syncro.org.application;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Port implemented by the machine module: resolves a collection of machine ids to
 * their distinct machine-group ids (AD-13 "cross-plant team machine IDs are merged
 * into the machineGroupIds scope"). Used at derive time to populate the operational
 * scope's {@code activeTeamIds} — the additive cross-plant branch.
 */
public interface TeamMachineGroupReader {
  Set<UUID> resolveMachineGroupIds(Collection<UUID> machineIds);
}
