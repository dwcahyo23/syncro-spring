package com.syncro.auth.domain;

/**
 * PRD role taxonomy (AD-15). Replaces the Phase 1 trio (SUPER_ADMIN/MANAGE/VIEWER):
 * {@code MANAGE -> MANAGER_MAINTENANCE} and {@code VIEWER -> AUDITOR} (V45 migrated
 * stored values in the same change set; no alias coexistence is allowed afterwards).
 *
 * <p>Caveat: MANAGER_MAINTENANCE does NOT imply global scope — like every non-admin
 * role it still requires plant assignments (AD-2) for any scoped access.
 *
 * <p>MAINTENANCE_LEADER, INVENTORY_MAINTENANCE, STOREKEEPER and PRODUCTION_LEADER
 * exist as identity values from day one; their behavioral gates arrive with later
 * epics (workorders, inventory, procurement).
 */
public enum ApplicationRole {
  SUPER_ADMIN,
  MANAGER_MAINTENANCE,
  MAINTENANCE_LEADER,
  SECTION_LEADER,
  STAFF_MAINTENANCE,
  TECHNICIAN,
  INVENTORY_MAINTENANCE,
  STOREKEEPER,
  PRODUCTION_LEADER,
  AUDITOR
}
