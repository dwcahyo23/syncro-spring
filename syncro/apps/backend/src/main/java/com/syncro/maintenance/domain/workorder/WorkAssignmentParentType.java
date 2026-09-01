package com.syncro.maintenance.domain.workorder;

/**
 * Work assignment parent type (blueprint B3, story 15-2): the only assignable parent
 * is a corrective workorder. Value matches the {@code work_assignments.parent_type}
 * CHECK constraint in V1 exactly.
 */
public enum WorkAssignmentParentType {
  CORRECTIVE_WO
}
