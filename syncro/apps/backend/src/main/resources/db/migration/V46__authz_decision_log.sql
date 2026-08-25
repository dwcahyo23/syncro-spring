-- Story 9-5: persisted OPA enforcement decisions (FR-164 / NFR-P2-7).
-- One row per PolicyDecisionPoint.evaluate() call: decision_id (OPA envelope),
-- policy revision, outcome, degraded flag, subject user id, action and resource type.
-- Masked by construction: the OPA input schema carries only user + derived scope +
-- action (no request bodies, WAHA secrets, or phone numbers), and this table persists
-- only the structural fields listed below.
CREATE TABLE authz_decisions (
  id UUID PRIMARY KEY,
  decision_id UUID,
  policy_revision TEXT,
  allowed BOOLEAN NOT NULL,
  degraded BOOLEAN NOT NULL,
  subject_user_id UUID,
  action VARCHAR(255) NOT NULL,
  resource_type VARCHAR(64),
  decided_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_authz_decisions_decided_at ON authz_decisions(decided_at);
