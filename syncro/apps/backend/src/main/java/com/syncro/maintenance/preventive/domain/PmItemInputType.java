package com.syncro.maintenance.preventive.domain;

/**
 * PM checklist item input type (blueprint F4, story 15-2): values match the
 * {@code pm_checklist_items.input_type} and {@code pm_execution_items.input_type}
 * CHECK constraints in V1 exactly. MEASUREMENT items carry lsl/nominal/usl bounds;
 * OK_NG items carry a boolean outcome.
 */
public enum PmItemInputType {
  MEASUREMENT,
  OK_NG
}
