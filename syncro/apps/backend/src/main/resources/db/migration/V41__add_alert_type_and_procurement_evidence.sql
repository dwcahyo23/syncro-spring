-- Story 8-7: alert type discriminator, procurement-risk evidence columns, nullable threshold.
-- Existing THRESHOLD_PERCENTAGE rows keep their values; snapshot columns made nullable
-- for PROCUREMENT_RISK rows (nulls instead of fabricated zeros).

ALTER TABLE sparepart_alerts
  ADD COLUMN alert_type VARCHAR(24) NOT NULL DEFAULT 'THRESHOLD_PERCENTAGE';

ALTER TABLE sparepart_alerts
  ADD COLUMN lead_time_hours NUMERIC(12, 2);

ALTER TABLE sparepart_alerts
  ADD COLUMN rate_per_operating_hour NUMERIC(18, 2);

ALTER TABLE sparepart_alerts
  ADD COLUMN calculation_basis VARCHAR(24);

ALTER TABLE sparepart_alerts
  ADD COLUMN projected_depletion_at TIMESTAMPTZ;

ALTER TABLE sparepart_alerts
  ALTER COLUMN threshold_percentage DROP NOT NULL;

ALTER TABLE sparepart_alerts
  ALTER COLUMN current_counter_snapshot DROP NOT NULL;

ALTER TABLE sparepart_alerts
  ALTER COLUMN consumed_production_count_snapshot DROP NOT NULL;

ALTER TABLE sparepart_alerts
  ALTER COLUMN consumed_percentage_snapshot DROP NOT NULL;

-- Procurement-risk dedupe: one non-RESOLVED PROCUREMENT_RISK per installation.
CREATE UNIQUE INDEX sparepart_alerts_proc_risk_dedup_idx
  ON sparepart_alerts (machine_sparepart_installation_id)
  WHERE status != 'RESOLVED' AND alert_type = 'PROCUREMENT_RISK';

-- CHECK constraints
ALTER TABLE sparepart_alerts
  ADD CONSTRAINT chk_sparepart_alerts_alert_type
  CHECK (alert_type IN ('THRESHOLD_PERCENTAGE', 'PROCUREMENT_RISK'));

ALTER TABLE sparepart_alerts
  ADD CONSTRAINT chk_sparepart_alerts_threshold_required
  CHECK (alert_type = 'PROCUREMENT_RISK' OR threshold_percentage IS NOT NULL);

ALTER TABLE sparepart_alerts
  ADD CONSTRAINT chk_sparepart_alerts_procurement_evidence
  CHECK (alert_type <> 'PROCUREMENT_RISK' OR (
    lead_time_hours IS NOT NULL
    AND rate_per_operating_hour IS NOT NULL
    AND calculation_basis IS NOT NULL
    AND projected_depletion_at IS NOT NULL
  ));

ALTER TABLE sparepart_alerts
  ADD CONSTRAINT chk_sparepart_alerts_calc_basis
  CHECK (calculation_basis IS NULL OR calculation_basis IN ('ROLLING_30_DAY', 'FULL_HISTORY'));

-- Discriminator contract: THRESHOLD rows always carry their snapshots; PROCUREMENT_RISK rows
-- carry neither a threshold nor threshold snapshots (nulls instead of fabricated zeros).
ALTER TABLE sparepart_alerts
  ADD CONSTRAINT chk_sparepart_alerts_threshold_snapshots
  CHECK (alert_type = 'PROCUREMENT_RISK' OR (
    current_counter_snapshot IS NOT NULL
    AND consumed_production_count_snapshot IS NOT NULL
    AND consumed_percentage_snapshot IS NOT NULL
  ));

ALTER TABLE sparepart_alerts
  ADD CONSTRAINT chk_sparepart_alerts_proc_threshold_null
  CHECK (alert_type <> 'PROCUREMENT_RISK' OR threshold_percentage IS NULL);

ALTER TABLE sparepart_alerts
  ADD CONSTRAINT chk_sparepart_alerts_proc_snapshots_null
  CHECK (alert_type <> 'PROCUREMENT_RISK' OR (
    current_counter_snapshot IS NULL
    AND consumed_production_count_snapshot IS NULL
    AND consumed_percentage_snapshot IS NULL
  ));