-- ============================================================================
-- V15: phone_verification_challenges optimistic lock (story 22-2, review P3).
-- Additive-only migration: the challenge row gains the version column so two
-- concurrent verifies can no longer both read attempt_count=4 and both
-- increment (a lost update silently enlarges the brute-force budget).
-- V13 non_conformances/eight_d_reports precedent (InventoryStockBalanceEntity/
-- SectionEntity pattern).
-- ============================================================================

ALTER TABLE phone_verification_challenges
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
