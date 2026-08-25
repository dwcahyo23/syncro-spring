package com.syncro.maintenance.domain.workorder;

/**
 * Work-order category value (story 10-1): a stable unique {@code code} plus a
 * display {@code label}. Global config — not scoped to plant or machine group.
 */
public record WorkOrderCategory(String code, String label) {
}
