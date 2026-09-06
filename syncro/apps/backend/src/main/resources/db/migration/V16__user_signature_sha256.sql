-- ============================================================================
-- V16: user_signatures sha256 (story 22-3, FR-132/133/175).
-- Additive-only migration: the stored signature row gains the SHA-256 hex of
-- the image bytes (computed at upload from the in-memory payload —
-- ObjectStorageService has no download method, so the hash must ride on the
-- reference). Every signature_uses row copies this value as the signature
-- reference at time of signing. V13/V15 additive-column precedent.
-- ============================================================================

ALTER TABLE user_signatures
  ADD COLUMN sha256 VARCHAR(64);
