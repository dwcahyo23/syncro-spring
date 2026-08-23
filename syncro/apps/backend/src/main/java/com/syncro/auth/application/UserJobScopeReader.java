package com.syncro.auth.application;

import java.util.Set;
import java.util.UUID;

/**
 * Port to the machine bounded context: does the user hold any responsibility assignment at one
 * of the given job-scope levels? Implemented by {@code MachineResponsibilityJobScopeAdapter}.
 * Levels are the persisted uppercase enum-string contract values (e.g. "LEADER"), keeping this
 * auth-side port free of machine-module types.
 */
public interface UserJobScopeReader {

  boolean hasAnyLevel(UUID userId, Set<String> responsibilityLevels);
}
