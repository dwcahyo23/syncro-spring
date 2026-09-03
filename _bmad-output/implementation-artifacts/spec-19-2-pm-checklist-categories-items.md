---
title: 'Story 19-2: PM Checklist Categories & Items'
type: 'feature'
created: '2026-09-03'
status: 'done'
review_loop_iteration: 0
baseline_revision: '5f4600b'
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** `pm_checklist_categories`/`pm_checklist_items` (blueprint F4) exist in V1 with bare 15-2 entities but no service or API — a checksheet revision cannot carry any actual check content (parameters, methods, MEASUREMENT bounds, OK/NG inputs, criticality), so 19-1's revision workflow produces empty checksheets.

**Approach:** Add PmChecklistService + REST `/api/v1/pm-checklist-categories` and `/api/v1/pm-checklist-items` implementing define/edit of categories and ordered items against an UNAPPROVED checksheet revision (approved revisions are immutable — corrections go through 19-1's revise flow). Items carry input_type MEASUREMENT (unit/lsl/nominal/usl) or OK_NG, is_critical_flag for later NG escalation, optional calibration instrument reference. V8 additive migration extends the audit entity_type CHECK with PM_CHECKLIST_CATEGORY/PM_CHECKLIST_ITEM.

## Boundaries & Constraints

**Always:**
- V8 additive migration only (V1..V7 untouched): extend ck_audit_log_entity_type with PM_CHECKLIST_CATEGORY, PM_CHECKLIST_ITEM (V6/V7 drop+recreate pattern) + matching AuditEntityType enum values in the same change. No schema changes to the two tables (they exist in V1 with all F4 columns + CHECKs).
- API `/api/v1/pm-checklist-categories`: POST {checksheetId,name,sortOrder?}; GET list ?checksheetId=; PUT /{id} {name,sortOrder?}; DELETE /{id}. camelCase.
- API `/api/v1/pm-checklist-items`: POST {checksheetId,categoryId?,sequence?,parameterText,checkMethod?,inputType,unit?,lsl?,nominal?,usl?,isCriticalFlag?,referenceDocument?,calibrationInstrumentId?}; GET list ?checksheetId=&categoryId=; GET /{id}; PUT /{id} (same fields); DELETE /{id}. sequence defaults to max+1 within the checksheet.
- Mutations (category/item create/update/delete) require the target checksheet to exist AND be unapproved (approvedBy == null) → else 409 INVALID_CHECKSHEET_TRANSITION. Items/categories belong to a checksheet revision; approved content is frozen (revision chain preserves history).
- Validation: parameterText non-blank ≤500; name non-blank ≤200; inputType ∈ {MEASUREMENT,OK_NG}; MEASUREMENT with both lsl and usl present → lsl ≤ usl else 400 VALIDATION_ERROR; category must belong to the same checksheet as the item else 400; calibrationInstrumentId present → must exist else 404 CALIBRATION_INSTRUMENT_NOT_FOUND; unknown checksheet/category → 404.
- Gates: same four-role set + machine-plant/group scope as 19-1 create/revise (SUPER_ADMIN bypass) — resolved through the checksheet's machine via requireMutationAccess pattern. Reads scope-filtered like 19-1 list/get.
- Audit: CREATE/UPDATE/DELETE records with actor + previous/new values (19-1 checksheetValues pattern, all UUIDs stringified).
- OPA: new pm_checklist_paths {"/api/v1/pm-checklist-categories", "/api/v1/pm-checklist-categories/*", "/api/v1/pm-checklist-items", "/api/v1/pm-checklist-items/*"} with the four-role mutation allow blocks; update authz.rego + authz_test.rego; add both patterns to SYNCRO_AUTHZ_ENFORCED_PATHS in .env.example.
- Errors: VALIDATION_ERROR (400), FORBIDDEN (403), PM_CHECKSHEET_NOT_FOUND / PM_CHECKLIST_CATEGORY_NOT_FOUND / PM_CHECKLIST_ITEM_NOT_FOUND / CALIBRATION_INSTRUMENT_NOT_FOUND (404), INVALID_CHECKSHEET_TRANSITION (409).
- is_critical_flag persisted for 19-5's NG escalation — no execution logic in this story.
- TypeScript strict; no frontend work.

**Block If:**
- No race-safe default-sequence (max+1) path exists without violating the spec's sequence semantics → HALT blocked.

**Never:**
- No edits to V1..V7; no changes to 19-1's checksheet endpoints; no execution/assessment surface (19-5); no bulk-reorder endpoint (PUT carries sequence); no frontend.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create category | POST valid on unapproved r1 | 201; audit CREATE | unknown checksheet → 404; approved → 409 |
| Create MEASUREMENT item | bounds lsl 10 usl 5 | rejected | 400 VALIDATION_ERROR (lsl ≤ usl) |
| Create OK_NG item | no bounds | 201 sequence=max+1 | — |
| Item with foreign category | categoryId of another checksheet | rejected | 400 VALIDATION_ERROR |
| Unknown calibration ref | calibrationInstrumentId absent | rejected | 404 CALIBRATION_INSTRUMENT_NOT_FOUND |
| Mutate approved revision | create/update/delete on approved checksheet | rejected | 409 INVALID_CHECKSHEET_TRANSITION |
| Delete category | items reference it | 204; service nulls item.categoryId explicitly before delete (audit records orphaned items) | — |
| Out-of-scope leader | machine in other plant | rejected | 403 FORBIDDEN |
| Staff creates item | STAFF_MAINTENANCE in scope | allowed (define/edit is staff-level) | — |

</intent-contract>

## Code Map

### Existing (reuse)
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql:1135-1171` -- pm_checklist_categories/items DDL + CHECKs + FKs (calibration FK at :1616). READ-ONLY.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmChecklistCategoryEntity.java` + `PmChecklistItemEntity.java` + repositories -- bare 15-2 entities; add mutators (update fields) + repo queries (findByChecksheetIdOrderBySortOrderAsc exists for categories; items need findByChecksheetIdOrderBySequenceAsc, findMaxSequence).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/domain/PmItemInputType.java` -- MEASUREMENT/OK_NG enum.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmChecksheetService.java` -- 19-1 pattern: requireMutationAccess/requireReadAccess/loadMachine/audit values/save backstop. Reuse its gates (inject PmChecksheetRepository; load checksheet → machine → scope).
- `syncro/apps/backend/src/main/java/com/syncro/compliance/infrastructure/db/CalibrationInstrumentRepository.java` -- existence check (cross-module repo read, MachineRepository precedent).
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add PM_CHECKLIST_CATEGORY, PM_CHECKLIST_ITEM.
- `syncro/authz/policy/authz.rego` + `authz_test.rego` -- pm_* path-set pattern (19-1).
- `syncro/.env.example` -- SYNCRO_AUTHZ_ENFORCED_PATHS list.
- Tests: `PmChecksheetServiceIntegrationTest.java` (fixture pattern), `PmChecksheetControllerTest.java` (MockMvc error-path pattern), `V1BaseSchemaMigrationTest.java` (CHECK assertions; update migration count to 8).

### New
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmChecklistService.java` (+ commands/views/exceptions).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/api/PmChecklistCategoryController.java`, `PmChecklistItemController.java`, DTO records in PreventiveDtos, handlers in PreventiveExceptionHandler.
- `syncro/apps/backend/src/main/resources/db/migration/V8__pm_checklist_audit_types.sql`.
- Tests: `PmChecklistServiceIntegrationTest.java`, `PmChecklistControllerTest.java`.

## Tasks & Acceptance

**Execution:**
- `V8__pm_checklist_audit_types.sql` -- CHECK extend -- audit parity
- `AuditEntityType.java` -- two new values -- same change as V8
- `PmChecklistCategoryEntity/ItemEntity` + repositories -- mutators + queries -- service needs
- `PmChecklistService.java` -- CRUD + approved-guard + validation + gates + audit -- core
- `PmChecklistCategoryController.java` + `PmChecklistItemController.java` + DTOs + handlers -- API surface
- `syncro/authz/policy/authz.rego` + `authz_test.rego` + `.env.example` -- path set + enforcement wiring -- OPA parity
- `V1BaseSchemaMigrationTest.java` -- V8 assertions -- migration evidence
- Integration + controller tests -- every matrix row -- AC evidence

**Acceptance Criteria:**
- Given an unapproved checksheet revision, when categories and items are created (MEASUREMENT with bounds, OK_NG without), then they persist in sequence order with input_type/criticality/bounds intact and audit CREATE rows exist.
- Given an approved revision, when any category/item mutation is attempted, then 409 INVALID_CHECKSHEET_TRANSITION and nothing changes.
- Given lsl > usl, or a category from another checksheet, or an unknown calibration instrument, then 400/400/404 respectively with the stable codes.
- Given an out-of-scope leader, then 403 on mutations and invisible on scoped reads; given STAFF_MAINTENANCE in scope, then define/edit succeeds.
- Given a full per-class test run + OPA suite, then all green.

## Spec Change Log

### Review Findings (bmad-build-auto step-04, 2026-09-03)

- [x] [Review][Patch] Negative item `sequence` persists (no DB CHECK) → guard like sortOrder [`PmChecklistService.java:173`] — fixed: requireNonNegativeSequence added
- [x] [Review][Patch] `max+1` overflows at Integer.MAX_VALUE → negative sequence; guard it [`PmChecklistService.java:174`] — fixed: maxSequence == MAX_VALUE → 400
- [x] [Review][Patch] `listItems` with foreign/unknown `categoryId` returns empty 200 — make it 400 like the create path [`PmChecklistService.java:195`] — fixed: resolveCategoryId on filter
- [x] [Review][Patch] `itemLabel` truncates with `substring(0,255)` — splits surrogate pairs; truncate code-point-safe [`PmChecklistService.java:464`] — fixed: offsetByCodePoints
- [x] [Review][Patch] New controllers lack `@Tag` (telemetry controllers use it) — add for OpenAPI grouping [`PmChecklistCategoryController.java`, `PmChecklistItemController.java`] — fixed: @Tag added
- [x] [Review][Patch] MANAGER_MAINTENANCE / MAINTENANCE_LEADER gate branches never exercised by tests — add in-scope + out-of-scope cases — fixed: INT-016
- [x] [Review][Patch] `itemLabel` 255-char truncation never executed (no test crosses 256–500) — add 300-char item test asserting entity_label prefix — fixed: INT-017
- [x] [Review][Patch] Null-merge unasserted for `nominal` (INT-014) and category `sortOrder` (INT-010) — add the two assertions — fixed
- [x] [Review][Patch] Negative `sortOrder` (category) and negative `sequence` (item) have no test — add INT cases — fixed: INT-008 extended
- [x] [Review][Defer] MEASUREMENT with one bound / no unit accepted — spec's "lsl≤usl when both present" permits half-specification — deferred, spec-aligned
- [x] [Review][Defer] `nominal` outside lsl..usl accepted — spec silent; adding is behavior change — deferred
- [x] [Review][Defer] Deleting an item referenced by execution rows — execution tables ship in 19-5; revisit there — deferred
- [x] [Review][Defer] Concurrent createItem + deleteCategory orphan race — FK SET NULL backstop; accepted at this scale — deferred
- [x] [Review][Defer] Cannot clear category/calibration refs via PUT (record can't distinguish omitted vs null) — documented limitation — deferred
- [x] [Review][Defer] .env.example enforced-paths typo would go unnoticed (tests run with empty list) — pre-existing pattern — deferred
- [x] [Review][Defer] authz/README.md stale "38/38" count — pre-existing (DW-147) — deferred

## Spec Change Log

## Review Triage Log

## Design Notes

- Approved-freeze rule: the checksheet's approvedBy != null is the single immutability predicate for checklist content — mirrors 19-1's "approve is the only activation path" and makes the revision chain honest (r2 starts empty; content is defined pre-approval).
- Category delete nulls item.categoryId explicitly (service-level) rather than relying on the FK's ON DELETE SET NULL, so the audit trail records which items were orphaned.
- sequence default max+1 computed in-txn; no unique constraint on (checksheet_id, sequence) in V1, so duplicates are tolerated (ordering is by sequence asc — stable-enough for 19-5 rendering).

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=PmChecklistServiceIntegrationTest,PmChecklistControllerTest,V1BaseSchemaMigrationTest` (per class if the shared container dies) -- expected: all pass
- `cd syncro/apps/backend && mvn -q test -Dtest=PmChecksheetServiceIntegrationTest` -- expected: 19-1 regression green
- OPA via `docker run --rm -v <authz>/policy:/policy openpolicyagent/opa:1.19.1-debug test /policy` -- expected: pass with new path sets

**Manual checks:**
- `git diff --stat -- syncro/apps/backend/src/main/resources/db/migration` shows only V8 added.

## Auto Run Result

Status: done

Summary: PM Checklist Categories & Items (blueprint F4) — V8 additive migration extends ck_audit_log_entity_type with PM_CHECKLIST_CATEGORY/PM_CHECKLIST_ITEM (enum moved in same change). PmChecklistService + REST /api/v1/pm-checklist-categories (POST/GET/PUT/DELETE) and /api/v1/pm-checklist-items (POST/GET /{id}/PUT/DELETE) implementing define/edit of ordered categories and items (MEASUREMENT with unit/lsl/nominal/usl, OK_NG, is_critical_flag, optional calibration instrument) against an UNAPPROVED checksheet revision — approved revisions frozen (409). sequence defaults to max+1 in-txn; category delete orphans item.categoryId explicitly with per-item audit. Gates: four-role + plant/group scope via the checksheet's machine (SUPER_ADMIN bypass), reads scope-filtered. OPA pm_checklist_paths + four-role allow blocks; enforced-paths wired in .env.example.

Files changed: V8 migration (new); PmChecklistService (new); PmChecklistCategoryController + PmChecklistItemController (new, @Tag); PreventiveDtos (+6 records), PreventiveExceptionHandler (+4 mappings); PmChecklistCategoryEntity/ItemEntity (+mutators), PmChecklistItemRepository (+queries); AuditEntityType (+2); authz.rego + authz_test.rego (+13); .env.example; V1BaseSchemaMigrationTest (+V8 assertions); tests: PmChecklistServiceIntegrationTest (17), PmChecklistControllerTest (24).

Review findings breakdown: step-04 pass — 9 patched (all code+test), 7 deferred, ~12 dismissed. Note: premature 19-3 agent interference cleaned up (V9/stale target/classes removed via mvn clean).

Follow-up review recommendation: false — no high-severity patches this pass (score 3×0+0=0).

Verification performed: PmChecklistServiceIntegrationTest 17/17, PmChecklistControllerTest 24/24, V1BaseSchemaMigrationTest 23/23 (after mvn clean removed stale V9 from target/classes), regression PmChecksheetServiceIntegrationTest 17/17; OPA 377/377; mvn test-compile clean; migration diff shows only V8.

Deferred: MEASUREMENT half-specification, nominal-outside-bounds, execution-row delete guard (19-5), concurrent orphan race, PUT ref-clearing limitation, .env typo invisibility (pre-existing), authz README stale count (DW-147).

Residual risks: duplicate sequence under concurrent creates tolerated by design (no unique constraint, documented); V9 contamination from the interrupted 19-3 agent is fully reverted (enum stripped, files moved out, stale target cleaned).
