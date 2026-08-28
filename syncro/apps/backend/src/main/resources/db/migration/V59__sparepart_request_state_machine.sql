-- Story 12-2: Sparepart Request State Machine (FR-141/FR-145/AD-5). Additive on V58.
--
-- 1. Drop/re-add ck_sparepart_requests_status with the full FR-141 set (REQUESTED,
--    PENDING_COMPLETION, ACKED, PROCESSING, READY, PURCHASE_REQUESTED,
--    PART_RECEIVED, PICKED_UP, CLOSED).
-- 2. Create sparepart_request_timeline for per-transition operational evidence.

-- ---------------------------------------------------------------------------
-- 1. Status CHECK re-add (drop/re-add pattern, additive on V57's two-value set)
-- ---------------------------------------------------------------------------
ALTER TABLE sparepart_requests DROP CONSTRAINT ck_sparepart_requests_status;
ALTER TABLE sparepart_requests ADD CONSTRAINT ck_sparepart_requests_status
  CHECK (status IN ('REQUESTED','PENDING_COMPLETION','ACKED','PROCESSING','READY',
                    'PURCHASE_REQUESTED','PART_RECEIVED','PICKED_UP','CLOSED'));

-- ---------------------------------------------------------------------------
-- 2. sparepart_request_timeline — per-request operational evidence
-- ---------------------------------------------------------------------------
CREATE TABLE sparepart_request_timeline (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  request_id UUID NOT NULL REFERENCES sparepart_requests(id) ON DELETE CASCADE,
  from_status VARCHAR(20),
  to_status VARCHAR(20) NOT NULL,
  actor UUID NOT NULL,
  action VARCHAR(20) NOT NULL CHECK (action IN ('TRANSITION','MRE_RECORDED')),
  mre_code VARCHAR(64),
  note VARCHAR(500),
  trace_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sparepart_request_timeline_request ON sparepart_request_timeline(request_id);