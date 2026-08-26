package com.syncro.maintenance.domain.workorder;

/**
 * FMEA failure-type tag (story 10-6, FR-118). v1 tags only — the architecture defers a
 * full FMEA module (RPN worksheets, severity/occurrence/detection). The values are
 * CHECK-constrained on {@code work_orders.fmea_failure_type} (V51) so the report and
 * machine-history surfaces stay stable; a future full-FMEA module can migrate the
 * column to an FK.
 */
public enum FmeaFailureType {
  ELECTRIC,
  MECHANICAL,
  PNEUMATIC,
  HYDRAULIC,
  OTHER
}
