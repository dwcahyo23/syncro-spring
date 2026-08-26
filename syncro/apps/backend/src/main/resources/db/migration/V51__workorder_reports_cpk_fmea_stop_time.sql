-- Story 10-6: reports, CP/CPK, FMEA & stop-time (FR-117/FR-118/FR-122).
-- Additive on V50. 11 additive columns on work_orders: four-section report narrative,
-- optional CP/CPK capability values + PDF object key, optional FMEA failure-type tag
-- (CHECK-constrained for v1 tag-only, per architecture "Full FMEA module deferred"),
-- and optional stop-time reason + detail. No new table, no audit entity_type change.
-- CP/CPK is never mandatory on any category (FR-117).

ALTER TABLE work_orders ADD COLUMN report_chronological TEXT;
ALTER TABLE work_orders ADD COLUMN report_analyze TEXT;
ALTER TABLE work_orders ADD COLUMN report_corrective TEXT;
ALTER TABLE work_orders ADD COLUMN report_preventive TEXT;
ALTER TABLE work_orders ADD COLUMN cp_cp_lower NUMERIC(8,4);
ALTER TABLE work_orders ADD COLUMN cp_cp_upper NUMERIC(8,4);
ALTER TABLE work_orders ADD COLUMN cpk NUMERIC(8,4);
ALTER TABLE work_orders ADD COLUMN cpk_pdf_object_key VARCHAR(255);
ALTER TABLE work_orders ADD COLUMN fmea_failure_type VARCHAR(30);

ALTER TABLE work_orders ADD CONSTRAINT ck_work_orders_fmea_failure_type
  CHECK (fmea_failure_type IS NULL OR fmea_failure_type IN ('ELECTRIC','MECHANICAL','PNEUMATIC','HYDRAULIC','OTHER'));

ALTER TABLE work_orders ADD COLUMN stop_time_reason VARCHAR(30);
ALTER TABLE work_orders ADD COLUMN stop_time_detail VARCHAR(500);

ALTER TABLE work_orders ADD CONSTRAINT ck_work_orders_stop_time_reason
  CHECK (stop_time_reason IS NULL OR stop_time_reason IN ('ELECTRIC','MECHANICAL','PNEUMATIC','HYDRAULIC','OTHER'));