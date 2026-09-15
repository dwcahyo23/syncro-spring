-- ============================================================================
-- V21: global print-report layout templates (spec: Template Laporan Cetak).
-- One row per report type — the leader edits the layout ONCE per report kind
-- (WORK_ORDER / PREVENTIVE), and every print page of that kind renders it.
-- The UNIQUE report_type is the ON CONFLICT anchor for the atomic upsert
-- (same DW-77 recipe as waha_templates), so two concurrent PUTs cannot
-- duplicate rows. body_html stores the TipTap HTML verbatim; XSS is fought
-- at render time (client DOMPurify), not by mangling the stored text.
-- updated_by references auth_users for the audit trail of who last changed
-- the official layout.
-- ============================================================================

CREATE TABLE report_print_templates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  report_type VARCHAR(32) NOT NULL,
  body_html TEXT NOT NULL,
  updated_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_report_print_templates_report_type UNIQUE (report_type),
  CONSTRAINT ck_report_print_templates_report_type
    CHECK (report_type IN ('WORK_ORDER', 'PREVENTIVE')),
  CONSTRAINT fk_report_print_templates_updated_by
    FOREIGN KEY (updated_by) REFERENCES auth_users(id) ON DELETE SET NULL
);
