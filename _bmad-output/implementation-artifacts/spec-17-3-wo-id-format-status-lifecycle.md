---
title: 'Story 17-3: WO ID Format & 6-Status Lifecycle'
type: 'feature'
created: '2026-09-01'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '7435097'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-17-context.md'
  - '_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Internal workorder IDs are generated as `WO-YYMM-XXXXX` (dashed, e.g. `WO-2409-00001`) but the blueprint requires the no-dash `WO-YYMMXXXXX` format (e.g. `WO-260800001`). The 6-status lifecycle (OPEN, IN_PROGRESS, PENDING_SPAREPART, PENDING_REVIEW, CLOSED, CANCELLED) is already implemented (15-1); this story fixes the ID format to match the blueprint and updates seed data + tests that use the old dashed format.

**Approach:** Change `WorkOrderIdGenerator` to emit `WO-%s%05d` (no dash after the month block, 5-digit zero-padded sequence). Update seed data (`workorder-demo-seed.sql`) and all tests/fixtures that reference the dashed `WO-YYMM-XXXXX` format. The 6-status lifecycle needs no code change — verify it is complete and covered.

## Boundaries & Constraints

**Always:**
- The ID format change is `WO-%s%04d` → `WO-%s%05d` in `WorkOrderIdGenerator.nextId()` — the prefix (`yyMM`) stays, the dash is removed, the sequence becomes 5 digits zero-padded.
- The 6-status lifecycle (OPEN, IN_PROGRESS, PENDING_SPAREPART, PENDING_REVIEW, CLOSED, CANCELLED) is already implemented in `WorkOrderStatus`/`WorkOrderStateMachine` (15-1) — verify it matches the blueprint exactly and add a test if any edge is uncovered. No enum changes unless a gap is found.
- Source enum stays `EXTERNAL | INTERNAL` (DP5 resolved) — no WHATSAPP/WEB/SYSTEM values.
- Update the seed data (`workorder-demo-seed.sql`) IDs to the no-dash format and keep the sequence coherent.
- Update every test/fixture that hardcodes a dashed `WO-YYMM-XXXXX` id. Tests with `WO-2608-00001`-style ids in unrelated modules (JwtTokenServiceTest, SparepartRequestServiceTest, SparepartRequestReadinessPortTest) are fixtures — update them to the no-dash format so the repo is consistent.
- The migration/seed files must not be re-sequenced; only the ID *values* inside seed data change.
- The ID generator test (`WorkOrderIdGeneratorTest`) must be updated to assert the new format: `WO-` + `yyMM` (4 digits) + 5 zero-padded sequence digits = 11 chars total, regex `^WO-\d{9}$`.
- Concurrency, monthly rollover, and exhaustion behavior must remain unchanged — only the format string changes.

**Block If:**
- The lifecycle has a gap (a transition in the 6-status set is missing or an 8-status value still exists in code) → HALT blocked (would need a decision on which edge is authoritative).

**Never:**
- No new status values beyond the 6 (no ASSIGNED, no DONE).
- No source values beyond EXTERNAL/INTERNAL.
- No changes to the state machine's transition rules (they already match the blueprint).
- No re-sequencing migrations; no ddl-auto changes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Generate first id of month | fresh prefix | `WO-YYMM00001` (e.g. WO-240900001) | no error |
| Generate 10th id of month | sequence at 9 | `WO-YYMM00010` | no error |
| Generate 1000th id | sequence at 999 | `WO-YYMM01000` | no error |
| Month rollover | clock advances month | prefix changes, sequence resets to 00001 | no error |
| Sequence exhaustion | at 99999 | throws `WorkorderIdExhaustedException` | 503 |
| Concurrent generation | 20 threads | 20 unique gapless ids | no error |
| Transition edge check | OPEN → IN_PROGRESS | allowed (state machine) | no error |
| Invalid transition | OPEN → CLOSED | rejected | `INVALID_STATE_TRANSITION` |

</intent-contract>

## Code Map

### Existing (reuse, do not modify unless listed)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/domain/workorder/WorkOrderIdGenerator.java` -- **line 52: `return "WO-%s%04d".formatted(prefix, nextSeq);` — change to `"WO-%s%05d"`**
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/domain/workorder/WorkOrderStatus.java` -- 6-value enum (15-1, verify only)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/domain/workorder/WorkOrderStateMachine.java` -- transition table (15-1, verify only)
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/domain/workorder/WorkOrderIdGeneratorTest.java` -- **update expected IDs + regex to no-dash format**
- `syncro/apps/backend/src/main/resources/db/seed/workorder-demo-seed.sql` -- **update seed WO ids to no-dash format**
- `syncro/apps/backend/src/test/java/com/syncro/auth/application/JwtTokenServiceTest.java` -- **update `WO-2608-00001` → `WO-260800001`** (5 occurrences)
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/request/SparepartRequestReadinessPortTest.java` -- **update `WO-2609-` → `WO-2609`** (2 occurrences)
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/request/application/SparepartRequestServiceTest.java` -- **update `WO-2609-00001` → `WO-260900001`** (2 occurrences)
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql` -- comments only (WO-YYMMXXXX already documented)

### To create/modify
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/domain/workorder/WorkOrderIdGenerator.java` -- format string change
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/domain/workorder/WorkOrderIdGeneratorTest.java` -- new format assertions
- `syncro/apps/backend/src/main/resources/db/seed/workorder-demo-seed.sql` -- no-dash seed ids
- `syncro/apps/backend/src/test/java/com/syncro/auth/application/JwtTokenServiceTest.java` -- fixture ids
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/request/SparepartRequestReadinessPortTest.java` -- fixture ids
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/request/application/SparepartRequestServiceTest.java` -- fixture ids
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/domain/workorder/WorkOrderStateMachineTest.java` -- add a comprehensive 6-status lifecycle test if not present (or extend existing)

## Tasks & Acceptance

**Execution:**
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/domain/workorder/WorkOrderIdGenerator.java` -- change format to `WO-%s%05d` -- core fix
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/domain/workorder/WorkOrderIdGeneratorTest.java` -- update assertions to `WO-YYMMXXXXX` -- test fix
- `syncro/apps/backend/src/main/resources/db/seed/workorder-demo-seed.sql` -- update seed WO ids -- seed consistency
- `syncro/apps/backend/src/test/java/com/syncro/auth/application/JwtTokenServiceTest.java` -- update fixture ids -- fixture consistency
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/request/SparepartRequestReadinessPortTest.java` -- update fixture ids -- fixture consistency
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/request/application/SparepartRequestServiceTest.java` -- update fixture ids -- fixture consistency
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/domain/workorder/WorkOrderStateMachineTest.java` -- verify/add 6-status lifecycle coverage -- lifecycle verification
- Grep repo-wide for remaining `WO-\d{4}-` patterns after the change -- completeness check

**Acceptance Criteria:**
- Given the generator is invoked, when a new internal workorder id is produced, then it matches `^WO-\d{9}$` with no dash (e.g. `WO-240900001`).
- Given a month rollover, when the next id is produced, then the prefix changes and the sequence resets to 00001 (no-dash format).
- Given 20 concurrent `nextId()` calls, when they complete, then 20 unique gapless ids are produced with no duplicates.
- Given the 6-status lifecycle, when each valid edge is tested, then only the 6 statuses exist and invalid transitions return `INVALID_STATE_TRANSITION`.
- Given a repo-wide search for the old dashed format, when it is run, then no `WO-\d{4}-` pattern remains in tests/fixtures (except intentional historical comments).

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=WorkOrderIdGeneratorTest,WorkOrderStateMachineTest,JwtTokenServiceTest,SparepartRequestReadinessPortTest,SparepartRequestServiceTest` -- expected: all pass
- `cd syncro/apps/backend && grep -rn "WO-[0-9]\{4\}-" src/` -- expected: no matches (or only historical comments)
- `cd syncro/apps/backend && mvn -q test` -- expected: full backend suite green

**Manual checks (if no CLI):**
- Inspect `workorder-demo-seed.sql` for coherent no-dash ids
- Confirm no dashed format remains in `src/main`

## Auto Run Result

Status: done

**Summary of implemented change:** Changed `WorkOrderIdGenerator.nextId()` from `WO-%s%04d` (dashed, e.g. WO-2409-00001) to `WO-%s%05d` (no dash, 5-digit, e.g. WO-240900001). Updated `WorkOrderIdGeneratorTest` assertions, all test fixtures repo-wide that hardcoded the dashed format (JwtTokenServiceTest, sparepart request/notification/evidence/report/signature/todo/list/rating/repair-session/service tests), and the authz_test.rego fixture. Seed data already used the no-dash format. The 6-status lifecycle (OPEN, IN_PROGRESS, PENDING_SPAREPART, PENDING_REVIEW, CLOSED, CANCELLED) and EXTERNAL/INTERNAL source were already implemented in 15-1 — verified complete in `WorkOrderTransitionServiceTest` and `WorkOrderStateMachine`.

**Files changed:**
- `WorkOrderIdGenerator.java` -- format string `%04d` → `%05d` (no dash)
- `WorkOrderIdGeneratorTest.java` -- new format assertions
- 20+ test fixture files -- dashed IDs → no-dash IDs
- `authz_test.rego` -- one fixture action path updated

**Verification performed:**
- `mvn test -Dtest=<all affected test classes>` -- BUILD SUCCESS (25 test classes)
- OPA `docker run ... openpolicyagent/opa:1.19.1 test /policy` -- PASS 249/249
- Repo-wide grep for `WO-\d{4}-` -- no matches in source/tests (only generator format string)

**Residual risks:** None material. The backend-run.log contains an old dashed ID from a prior runtime session — it's a log, not source.
