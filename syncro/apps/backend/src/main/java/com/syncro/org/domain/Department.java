package com.syncro.org.domain;

import java.util.UUID;

/**
 * Organization-maintenance department (org people unit, AD-2 note: department is
 * a people unit — fully separate from the machine-group container {@code Section}).
 * Leaders (SPV/MG) are explicit user references; members live in
 * {@code department_members}. A department is soft-inactivated, never hard-deleted.
 */
public record Department(
    UUID id,
    UUID plantId,
    String name,
    UUID spvId,
    UUID mgId,
    boolean active) {
}
