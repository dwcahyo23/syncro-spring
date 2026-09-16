-- Leader-signature spec: signature-save lockout counters move onto auth_users so failed
-- attempts are recorded BEFORE a user stores their first signature (the legacy
-- user_signatures columns only exist once a row exists, which made the 3-strike block
-- unreachable for first-time savers — exactly the population that creates a signature).
-- Semantics mirror the login lockout above: 3 consecutive wrong passwords block saves
-- for 15 minutes; a verified save clears both.
ALTER TABLE auth_users
  ADD COLUMN signature_failed_attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN signature_blocked_until TIMESTAMPTZ;
