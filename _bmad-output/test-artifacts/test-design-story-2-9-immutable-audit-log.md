---
workflowStatus: 'draft'
runId: '20260807-214614-fc56'
story: '2-9'
storyKey: '2-9-implement-immutable-audit-log-for-master-data'
baselineRevision: '283d36b232058a90af50ba734e896ea85041a3f1'
lastSaved: '2026-08-07'
---

# Test Design: Story 2-9 - Immutable Audit Log for Master Data

**Date:** 2026-08-07
**Author:** Yusuf (TEA / bmad-loop story test-design run)
**Status:** Draft
**Mode:** Story-level (Epic 2 companion; epic-level design exists in `test-design-epic-2.md`)

---

## 1. Executive Summary

**Scope:** Story 2-9 implements the immutable audit trail across all seven master-data aggregates and exposes it via `GET /api/v1/audit-log` plus a responsive Audit Log page. This design is risk-driven: it validates the immutability guarantee end-to-end, the plant-scope enforcement, the same-transaction capture guarantee, and the seven wiring points — then addresses UI-state and operator-verification evidence gaps.

**Key findings from code review (drive this design):**

1. **DB immutability trigger has a column-list gap.** `V16` trigger `audit_log_immutable_before_update` is `BEFORE UPDATE OF actor_id, actor_name, action, entity_type, entity_id, entity_label, previous_value, new_value, created_at`. `plant_id` and `id` are **not** in the `OF` list, so `UPDATE audit_log SET plant_id = ...` (or `id = ...`) via direct SQL **succeeds without raising**. The omission of `plant_id` is *intentional* so the `ON DELETE SET NULL` FK action (plant deletion) is not blocked — but it is an immutability gap that must be tested and documented. Existing test `2.9-SVC-007` only exercises `actor_name`, missing the gap.
2. **Frontend does not reset page when filter value changes.** `audit-log-page.tsx` calls `setPage(0)` only on plant change, size change, and reset. Changing `entityType`, `actor`, `from`, or `to` while on page > 0 keeps the stale page index, which can produce an out-of-range empty state.
3. **Date filter uses UTC day boundaries.** `from`/`to` are sent as `<date>T00:00:00.000Z` / `<date>T23:59:59.999Z`, so a local-day filter is a UTC-day filter (edge cases for non-UTC operators).
4. **Immutable at API level is verified** (no write endpoints, repository has no update/delete), and the same-transaction capture (writer joins caller tx) is verified via `2.9-WIR` failed-mutation test.

**Coverage summary (story 2.9):**

- P0 scenarios: 9 (~14-20 h)
- P1 scenarios: 9 (~16-24 h)
- P2 scenarios: 6 (~6-12 h)
- P3 exploratory/ops: 3 (~2-6 h)
- Total effort: ~38-62 h (mostly backend integration + operator/runtime evidence)

---

## 2. Inputs Reviewed

- `_bmad-output/implementation-artifacts/spec-2-9-implement-immutable-audit-log-for-master-data.md`
- `_bmad-output/test-artifacts/test-design-epic-2.md` (epic-level register, incl. E2-R3)
- Backend: `AuditLogController`, `AuditLogService`, `AuditLogWriter`, `AuditLogRepository`, `V16__create_audit_log.sql`, `*AuditValues` builders, 7 wired mutation services
- Frontend: `audit-log-page.tsx`, `audit-log-table.tsx`, `(main)/dashboard/audit-log/page.tsx`
- Existing tests: `AuditLogControllerTest` (6), `AuditLogServiceIntegrationTest` (8), `AuditLogWiringIntegrationTest` (8)

---

## 3. Risk Assessment

Risk register is story-level. Score = Probability x Impact. Priority threshold from `_bmad/tea/config.yaml`: `risk_threshold: p1`.

| ID | Category | Risk | P | I | Score | Priority |
|---|---|---:|---:|---:|---|---|
| R-2.9-1 | DATA | Immutability trigger `OF`-list omits `plant_id`/`id`; direct SQL can silently rewrite those columns (valid for `plant_id` via FK SET NULL, but also manually). | 3 | 3 | 9 | **P0** |
| R-2.9-2 | DATA | A mutation path not wired to `AuditLogWriter` silently drops accountability (7 aggregates x create/update/delete + threshold edit + assign/unassign). | 2 | 3 | 6 | **P0** |
| R-2.9-3 | DATA | Same-transaction guarantee broken if a future writer call uses a different propagation or is placed after a `flush()` that partially succeeds. | 2 | 3 | 6 | **P0** |
| R-2.9-4 | SEC | Plant-scope read leak: scoped user sees another plant's entries, or EMPTY scope sees non-global entries, or out-of-scope `plantId` filter not 403. | 2 | 3 | 6 | **P0** |
| R-2.9-5 | DATA | Wrong before/after JSON payloads (missing field, stale snapshot, leaked JPA entity, wrong `plantId` for sparepart/installation/responsibility). | 2 | 3 | 6 | **P0** |
| R-2.9-6 | BUS/UX | Frontend keeps stale page index when `entityType`/`actor`/`from`/`to` change, causing out-of-range empty state or wrong page. | 3 | 2 | 6 | **P1** |
| R-2.9-7 | SEC | Unauthenticated / wrong-role access to audit data; role matrix (SUPER_ADMIN/MANAGE/VIEWER) not enforced on the route. | 2 | 2 | 4 | P1 |
| R-2.9-8 | DATA | Invalid filter values (bad `entityType`, malformed `from`/`to`, bad `sort`) not mapped to `400 INVALID_QUERY_VALUE`. | 2 | 2 | 4 | P1 |
| R-2.9-9 | DATA | Date-range filter timezone boundary: local-day semantics vs UTC-day sent by the UI; boundary entries excluded/included incorrectly. | 2 | 2 | 4 | P1 |
| R-2.9-10 | TECH | Orval-generated client drift from backend contract (entity enum, response shape) breaks the page build. | 2 | 2 | 4 | P1 |
| R-2.9-11 | DATA | FK `ON DELETE SET NULL` on `plant_id` nulls the audit row when a plant is deleted, silently removing scope context of that entry (by design, but must be documented + tested). | 2 | 2 | 4 | P2 |
| R-2.9-12 | DATA | `readJson` throws `IllegalStateException` on corrupt stored JSON, surfacing as a 500 on a list read. | 1 | 2 | 2 | P2 |
| R-2.9-13 | PERF | `%actor%` LIKE + nullability means no index usage for actor filter; large audit volume degrades. Single-column indexes only; no composite for common filter combos. | 2 | 2 | 4 | P2 |
| R-2.9-14 | OPS | Immutability trigger gap (R-2.9-1) and lack of retention/cleanup story for an ever-growing append-only table. | 2 | 2 | 4 | P3 |

### Risk Testability Notes

- All R-2.9 risks are directly testable with existing harnesses (MockMvc slice + Testcontainers) except R-2.9-6/-9 (frontend/component-level, no frontend test harness yet) and R-2.9-14 (ops/manual).
- R-2.9-1 requires a dedicated Testcontainers test that attempts `UPDATE audit_log SET plant_id` / `SET id` and asserts the *actual* behavior (currently: succeeds), plus a test that `UPDATE ... SET actor_name` is rejected (already exists as `2.9-SVC-007`).

---

## 4. Risk-Based Coverage Strategy

Prioritization follows the epic-2 strategy: DB constraints + service auth + immutable audit before UI polish; backend integration first, then API contract, then frontend states, then operator/runtime proof.

### P0 - Critical (must pass before story is "done")

| ID | Scenario | Level | Existing evidence | Effort |
|---|---|---|---|---|
| T-2.9-P0-01 | DB immutability: UPDATE rejected for guarded columns (`actor_name`, `created_at`, `previous_value`) | Backend int (JdbcTemplate) | `2.9-SVC-007` (actor_name) | 0.5h (extend) |
| T-2.9-P0-02 | DB immutability **gap probe**: UPDATE `plant_id`/`id` — assert actual outcome (succeeds today); document as known gap or fix trigger to include `plant_id` except FK action | Backend int | none | 2-3h |
| T-2.9-P0-03 | DB immutability: DELETE rejected | Backend int | `2.9-SVC-008` (delete) | 0h |
| T-2.9-P0-04 | All 7 aggregates audited on CREATE/UPDATE/DELETE with actor/action/entityType/entityId/entityLabel/plantId + values | Backend int (wiring) | `2.9-WIR-001..008` | 0h |
| T-2.9-P0-05 | Failed mutation writes no entry (tx rollback, same-tx capture) | Backend int | `2.9-WIR` failed-mutation test | 0h |
| T-2.9-P0-06 | Scope matrix: SUPER_ADMIN all; MANAGE assigned+global; EMPTY global-only; out-of-scope `plantId` filter 403 | Backend int + API | `2.9-SVC-003..005`, `2.9-API-003/004/006` | 0-1h (verify EMPTY at API level) |
| T-2.9-P0-07 | Payload fidelity per aggregate: `plantId` correct for sparepart (machine.plant), taxonomy (null/global), installation, responsibility | Backend int | `2.9-WIR` asserts plantId | 0-1h |
| T-2.9-P0-08 | Repository exposes no update/delete mutation methods; no write endpoints | Static code review | reviewed | 0h |
| T-2.9-P0-09 | Fresh-DB Flyway V16 applies cleanly (blocking V9-gap condition absent) | Backend int | Testcontainers empty-DB run | 0h |

### P1 - High (agreed sprint scope)

| ID | Scenario | Level | Existing evidence | Effort |
|---|---|---|---|---|
| T-2.9-P1-01 | API: unauth 401, full response shape, filter params bind (`entityType`, `actor`, `plantId`, `from`, `to`, `size`, `sort`) | API | `2.9-API-001/002` | 0h |
| T-2.9-P1-02 | API: unknown `entityType` → `400 INVALID_QUERY_VALUE` | API | `2.9-API-005` | 0h |
| T-2.9-P1-03 | API: invalid `sort` property → `400` (not silently defaulted) | API | none | 1-2h |
| T-2.9-P1-04 | API: malformed `from`/`to` ISO → `400` | API | none | 1-2h |
| T-2.9-P1-05 | Sort allowlist + default `createdAt,desc`; size default 100 clamp 200; page>=0 | Service int | `2.9-SVC-006` (order), API-002 (sort) | 0-1h |
| T-2.9-P1-06 | Frontend: reset page to 0 on every filter change (bug R-2.9-6) — fix + component test | Frontend | none (no harness) | 3-6h incl. harness |
| T-2.9-P1-07 | Frontend states: loading, error+retry, empty, filtered-empty+reset | Frontend | none | 2-4h |
| T-2.9-P1-08 | Frontend: desktop dense table + mobile stacked date-grouped cards + expandable before/after | Frontend (E2E/visual) | none | 3-6h |
| T-2.9-P1-09 | Client-contract parity: regenerate Orval, page builds with enum-derived filters | Build | `npm run check`/`build` | 0-1h |

### P2 - Medium (deferred unless evidence cheap)

| ID | Scenario | Level | Existing evidence | Effort |
|---|---|---|---|---|
| T-2.9-P2-01 | Date-range UTC vs local boundary behavior documented + tested (edge rows at 00:00 UTC / 23:59 UTC) | API/Service | none | 1-2h |
| T-2.9-P2-02 | `plant_id` NULLed on plant deletion (FK SET NULL) — scoped visibility of that entry afterwards | Backend int | none | 1-2h |
| T-2.9-P2-03 | Corrupt JSON in `previous_value`/`new_value` → graceful handling vs 500 | Backend int | none | 1-2h |
| T-2.9-P2-04 | Actor filter `%`/`_`/`\` escaping (LIKE injection) | Backend int | none | 1-2h |
| T-2.9-P2-05 | Pagination beyond first page; totalElements accuracy; page bounds | Service int | partial (`listPaginates`) | 1-2h |
| T-2.9-P2-06 | Composite index check for common filter combos (entityType+created_at, plant+created_at) | NFR/exploratory | none | 1-2h |

### P3 - Exploratory / Ops (operator evidence, not automated)

| ID | Scenario | Level | Existing evidence |
|---|---|---|---|
| T-2.9-P3-01 | psql `UPDATE`/`DELETE` on `audit_log` raises (operator action #6) | Manual | awaiting-operator |
| T-2.9-P3-02 | Boot with `SPRING_PROFILES_ACTIVE=local`, V16 applied, `GET /api/v1/audit-log` 200/403/401 smokes (operator actions #1-#4) | Manual | awaiting-operator |
| T-2.9-P3-03 | Browser verification of `/dashboard/audit-log` (operator action #5) | Manual | awaiting-operator |

---

## 5. Traceability to Acceptance Criteria

| AC (from spec) | Covered by |
|---|---|
| Every master-data mutation → exactly one immutable entry with full fields | T-P0-04, T-P0-07 |
| Filter by entityType/actor/plant/date-range, paginated newest-first | T-P1-01..05, T-P0-06 |
| Edit/delete rejected (no API + DB trigger) | T-P0-01..03, T-P0-08 |
| Plant-scope on reads; out-of-scope filter 403 | T-P0-06 |
| Desktop dense table / mobile cards / expandable / all UI states | T-P1-06..08 |

---

## 6. Recommended Follow-Up Work (gaps discovered)

1. **Decide & test R-2.9-1**: either add `plant_id` to the trigger `OF` list (requires excluding the FK `ON DELETE SET NULL` action — not possible for a plain trigger) or accept and document that `plant_id` is mutable via SQL; add a Testcontainers probe asserting actual behavior and a regression test for the guarded columns.
2. **Frontend page-reset fix** (`audit-log-page.tsx`): reset `page` to 0 in the `entityType`, `actor`, `from`, `to`, and `sort` handlers (currently only plant/size/reset do).
3. **Add frontend test harness** (component-level) to lock the filter/page/state behavior — the only layer with zero coverage.
4. **API 400 tests** for bad `sort` and malformed `from`/`to` (currently untested).
5. **Retention/cleanup decision** for the append-only table (P3, ops).
6. **Operator actions** in the spec remain the runtime/UI proof gate (awaiting-operator status).

## 7. Verification Commands

- `$env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"; mvn -q -f syncro/apps/backend/pom.xml test -Dtest="AuditLogControllerTest,AuditLogServiceIntegrationTest,AuditLogWiringIntegrationTest"` — all pass
- `npm --prefix syncro/apps/web run check` — Biome + typecheck pass (CRLF format baseline is environmental)
- `npm --prefix syncro/apps/web run build` — production build succeeds
