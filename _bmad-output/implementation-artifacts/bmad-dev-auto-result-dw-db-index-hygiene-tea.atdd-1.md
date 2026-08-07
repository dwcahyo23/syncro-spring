---
status: done
---

TEA ATDD workflow (`bmad-testarch-atdd`, RED phase) completed for `dw-db-index-hygiene`.

## What was produced

- **ATDD checklist** (scaffolds + implementation checklist): `_bmad-output/test-artifacts/atdd-checklist-dw-db-index-hygiene.md`
- **RED scaffold (flagship, upgrade path):** `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddUpgradePathScaffoldTest.java` (1 `@Disabled`) — standalone Testcontainers + programmatic Flyway migrated only to V16, seeds every touched table, then applies V17 and asserts drops/creates/kept-objects/data survival.
- **RED scaffolds (gaps):** `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java` (3 `@Disabled`) — pagination-determinism probe (DH-04), EXPLAIN plan evidence (DH-02), exact-list assertion (DH-06).
- **Workflow progress log** (ATDD gap-closing run appended): `_bmad-output/test-artifacts/test-design-progress.md`

## Scope covered

Working-tree changes for the `dw-db-index-hygiene` bundle (baseline `a30e7f6` → `d55d6d1`):
- V17 migration dropping 6 redundant indexes / constraints, adding 3 ORDER BY-supporting indexes
- `DbIndexHygieneMigrationTest` (6 tests) — catalog + behavioral verification (P0, already green)
- DW-5/DW-6 deferred-work ledger close-out

## Key findings

- Flagship RED is `DH-P1-01` (DH-07): the shipped test only proves V17 on a fresh DB; the V16→V17 production upgrade path is not directly exercised. The scaffold covers it.
- Three decision/evidence/activation locks: `DH-P1-02` (pagination tiebreaker decision), `DH-P1-03` (EXPLAIN plan evidence for composite `(plant_id, code)` tuning), `DH-P2-02` (exact-list assertion strengthening).
- Verified backend scaffolds compile (`mvn test-compile`, JDK 25). No production code modified.

## Recommended follow-ups (recorded in the checklist)

1. Activate `DH-P1-01` upgrade-path probe and confirm V17 applies cleanly over seeded V16 data.
2. Decide the pagination tiebreaker (secondary sort key vs. documented acceptance) for `ORDER BY code`.
3. Record EXPLAIN evidence on bare `(code)` index usefulness; file composite `(plant_id, code)` tuning item.
4. Run static grep guard (T-DH-P2-01) proving dropped objects are unreferenced.
5. Document V17 `SHARE`-lock maintenance window (DH-03) and track `JwtTokenService` gitignore compile blocker (DH-05) separately.
