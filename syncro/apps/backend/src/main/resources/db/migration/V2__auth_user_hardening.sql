-- ============================================================================
-- V2: Auth user hardening (story 16-4, blueprint A13)
-- Additive-only migration on top of the V1 baseline. Adds identity-hardening
-- columns to auth_users. plant scope stays on the auth_user_plant_assignments
-- pivot (AD-2) — no plant_id column is added here.
-- ============================================================================

ALTER TABLE auth_users
  ADD COLUMN phone_verified_at TIMESTAMPTZ,
  ADD COLUMN force_password_change BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN locked_at TIMESTAMPTZ,
  ADD COLUMN lock_reason VARCHAR(255);

CREATE INDEX idx_auth_users_locked_at ON auth_users(locked_at) WHERE locked_at IS NOT NULL;
