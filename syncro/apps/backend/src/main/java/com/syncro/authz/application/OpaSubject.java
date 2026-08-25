package com.syncro.authz.application;

import java.util.List;
import java.util.UUID;

/**
 * Authoritative OPA wire schema (Addendum A3, AD-2 dimensions) — subject half. Assembly
 * happens ONLY inside {@code PolicyDecisionPoint}. Scope arrays are total (never null) so
 * the JSON payload is always well-formed; SUPER_ADMIN plantIds normalize to an empty list
 * because org data stays in PostgreSQL and OPA stays stateless.
 */
public record OpaSubject(
    String userId,
    List<String> roles,
    List<UUID> plantIds,
    List<UUID> machineGroupIds,
    List<UUID> activeTeamIds) {

  public OpaSubject {
    roles = roles == null ? List.of() : List.copyOf(roles);
    plantIds = plantIds == null ? List.of() : List.copyOf(plantIds);
    machineGroupIds = machineGroupIds == null ? List.of() : List.copyOf(machineGroupIds);
    activeTeamIds = activeTeamIds == null ? List.of() : List.copyOf(activeTeamIds);
  }
}
