package com.syncro.maintenance.preventive.api;

/**
 * Superseded by {@link com.syncro.authz.AuthzEnforcementParityTest} (DW-156 rescope).
 *
 * <p>The original test pinned only 14 hand-listed rego sets and checked one direction, so
 * it could not see the sets it excluded (inventory reservations, departments, user
 * bindings) or a typo'd {@code SYNCRO_AUTHZ_ENFORCED_PATHS} entry. The replacement parses
 * every {@code *_paths} set and asserts both directions.
 *
 * <p>This file is intentionally test-free; delete it once file removal is available in the
 * working environment (it was blocked when the rescope landed).
 */
class PmAuthzEnforcementParityTest {
}
