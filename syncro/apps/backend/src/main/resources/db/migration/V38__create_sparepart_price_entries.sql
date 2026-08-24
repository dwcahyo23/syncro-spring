-- Story 8-3: append-only estimated price history per sparepart.
-- amount is the original-currency price; currency defaults to IDR; kurs_to_idr snapshots the
-- exchange rate at entry time (forced 1 for IDR by the service); idr_amount is the
-- backend-computed normalized value (amount x kurs, scale 2 HALF_UP) and never client-supplied.
CREATE TABLE sparepart_price_entries (
  id UUID PRIMARY KEY,
  sparepart_id UUID NOT NULL,
  amount NUMERIC(18, 2) NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'IDR',
  kurs_to_idr NUMERIC(18, 6),
  idr_amount NUMERIC(18, 2) NOT NULL,
  entered_by UUID NOT NULL,
  entered_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_sparepart_price_entries_sparepart_id FOREIGN KEY (sparepart_id) REFERENCES spareparts(id) ON DELETE RESTRICT,
  CONSTRAINT fk_sparepart_price_entries_entered_by FOREIGN KEY (entered_by) REFERENCES auth_users(id) ON DELETE RESTRICT,
  CONSTRAINT ck_sparepart_price_entries_amount_positive CHECK (amount > 0),
  CONSTRAINT ck_sparepart_price_entries_kurs_positive CHECK (kurs_to_idr IS NULL OR kurs_to_idr > 0),
  CONSTRAINT ck_sparepart_price_entries_idr_amount_positive CHECK (idr_amount > 0),
  CONSTRAINT ck_sparepart_price_entries_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_sparepart_price_entries_sparepart_entered_at
  ON sparepart_price_entries (sparepart_id, entered_at DESC);

ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY'));
