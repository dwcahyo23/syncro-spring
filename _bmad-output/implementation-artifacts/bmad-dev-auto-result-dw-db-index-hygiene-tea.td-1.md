---
status: done
---

TEA test-design workflow (`bmad-testarch-test-design`) completed for `dw-db-index-hygiene`.

## What was produced

- **Risk assessment + risk-based coverage strategy**: `_bmad-output/test-artifacts/test-design-story-db-index-hygiene.md`
- **Workflow progress log** (story-level run appended): `_bmad-output/test-artifacts/test-design-progress.md`

## Scope covered

Working-tree changes for the `dw-db-index-hygiene` bundle (baseline `a30e7f6` → `d55d6d1`):
- V17 migration dropping 6 redundant indexes / constraints, adding 3 ORDER BY-supporting indexes
- `DbIndexHygieneMigrationTest` (6 tests) — catalog + behavioral verification
- DW-5/DW-6 deferred-work ledger close-out

## Key findings

- 8 risks scored (1 high at score 6: non-deterministic `ORDER BY code` OFFSET pagination, pre-existing but surfaced by `idx_machines_code`).
- P0 coverage already shipped and green (3 scenarios, 0 incremental effort). P1 gaps: upgrade-path probe (V16→V17 on existing data), pagination-determinism probe, EXPLAIN plan validation.
- No production code was modified. Artifacts written only under TEA's configured `test_artifacts` directory.

## Recommended follow-ups (recorded in the design)

1. Add the V16→V17 upgrade-path probe (uncovered project rule "from previous schema state").
2. Decide pagination tiebreaker for `ORDER BY code`.
3. EXPLAIN evidence on bare `(code)` index usefulness; record composite `(plant_id, code)` tuning item.
4. Track `JwtTokenService` gitignore clean-checkout compile blocker separately.
5. Document V17 `SHARE`-lock maintenance window for production apply.
