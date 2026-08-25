package com.syncro.org.application;

import java.util.Set;
import java.util.UUID;

/**
 * Derived operational scope (AD-2): the exact scope set used for both OPA input
 * and SQL row filtering. Sections are containers, never a scoping dimension.
 *
 * @param plantIds        plant ids from plant assignments; null for SUPER_ADMIN
 *                        (consumers keep their own unscoped path)
 * @param machineGroupIds machine groups where the user is LEADER or above;
 *                        empty = no group restriction (Phase 1 plant-scope behavior)
 * @param activeTeamIds   cross-plant team machine-group ids; empty until story 9.2
 */
public record OperationalScope(Set<UUID> plantIds, Set<UUID> machineGroupIds, Set<UUID> activeTeamIds) {
}
