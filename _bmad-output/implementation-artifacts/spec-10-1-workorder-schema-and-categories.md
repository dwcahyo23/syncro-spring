---
title: 'Workorder Schema & Categories'
type: 'feature'
created: '2026-08-26'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: false
final_revision: 52f1e00
baseline_revision: db0aadb
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-9-3-opa-infrastructure.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-9-4-role-taxonomy-migration.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-9-5-opa-enforcement-on-maintenance-endpoints.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** Workorders have no schema, categories have no management surface, and there is no concurrency-safe internal ID generator — the entire Epic 10 execution line depends on these foundations.

**Approach:** V47 migration creates four tables: `work_orders` (dual-source ID, lifecycle, parent), `work_order_categories` (code+label, unique codes), `work_order_status_history` (transitions with source/traceId), and `workorder_id_sequences` (per-prefix FOR UPDATE locking). A WorkorderIdGenerator produces WO-YYMM-XXXXX (monthly reset, no duplicates under concurrency). Category CRUD endpoints (GET list, POST/PUT by section-leader+) with role gate, audit-log, and OPA enforcement. JPA entities for ddl-auto=validate pass-through.

## Boundaries & Constraints

**Always:**
- V47 is additive: id VARCHAR(50) UTF-8, source VARCHAR(8) CHECK (SYNCED/INTERNAL), parent_id self-FK, status VARCHAR(20) CHECK (DRAFT/OPEN/ASSIGNED/IN_PROGRESS/ON_PROCUREMENT/DONE/CLOSED/CANCELLED), sync_version BIGINT, machine_id FK, description TEXT, timestamps. No FK to users (created_by stores UUID string).
- Categories: id UUID PK, code VARCHAR(16) UNIQUE NOT NULL, label VARCHAR(100) NOT NULL, created_by, timestamps.
- Status history: work_order_id FK, from_status, to_status, source VARCHAR(8) CHECK (MANUAL/DERIVED/SYNC), actor VARCHAR(50), trace_id, transitioned_at.
- ID sequences: prefix VARCHAR(6) PK, last_seq INTEGER NOT NULL, updated_at.
- WorkorderIdGenerator.nextId(): SELECT FOR UPDATE on prefix row; if missing → INSERT 0; increment; if > 99999 → throw WorkorderIdExhaustedException; COMMIT; return "WO-YYMM-XXXXX". Monthly reset = prefix YYMM (new month → new row → start at 1).
- Category mutation gate: role in {SUPER_ADMIN, MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER}. Creates/updates are audit-logged (AuditEntryType.WORK_ORDER_CATEGORY). View list by any authenticated user (workorder read scope).
- Error codes: mutation below gate → `FORBIDDEN` (403, repo-wide gate shape). Category code duplicate → `DUPLICATE_CATEGORY_CODE` (409). ID exhausted → `WORKORDER_ID_EXHAUSTED` (500).
- OPA enforcement: add `/api/v1/work-order-categories/**` to SYNCRO_AUTHZ_ENFORCED_PATHS (.env.example). Rego: category mutation (POST/PUT/PATCH/DELETE) allowed if role in SECTION_LEADER+ set; category read (GET) allowed for any authenticated. SUPER_ADMIN bypass. Update authz_test.rego parity matrix.
- In-service gate stays as defense-in-depth (same pattern as 9-5: keep requireCategoryRole + OPA evaluate).

**Block If:** nothing.

**Never:**
- Never create workorder CRUD endpoints (that's 10.2). The schema entities exist for ddl-auto=validate only; no service or repository for work_orders/work_order_status_history beyond the migration.
- Never implement status transitions (10.3). Status column exists, but no state machine logic.
- Never change the 9-5 rego enforcement pattern (only add new paths + rules).
- Never hand-edit generated client files.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| LIST_CATEGORIES | GET /api/v1/work-order-categories as any authenticated | 200 [{code:"01",label:"Breakdown"},...] | — |
| CREATE_CATEGORY | POST /api/v1/work-order-categories as SECTION_LEADER (code, label) | 201, persisted, audit-logged | 403 if role below SECTION_LEADER; 409 if code exists |
| UPDATE_CATEGORY | PUT /api/v1/work-order-categories/{code} as MAINTENANCE_LEADER | 200, updated, audit-logged | 403 below; 409 duplicate code |
| DUPLICATE_CODE | POST with existing code "01" | 409 DUPLICATE_CATEGORY_CODE | — |
| ID_GENERATE | nextId() on new month prefix | "WO-2409-00001" | — |
| ID_CONCURRENT | 20 threads nextId() same prefix | 20 unique IDs, no gaps; last_seq = 20 | — |
| ID_EXHAUSTED | last_seq = 99999, nextId() called | WorkorderIdExhaustedException | Rethrown as 500 |
| ID_MONTH_ROLL | prefix changes from "2409" to "2410" | Seq resets to 1 | — |
| OPA_MUTATION | POST /api/v1/work-order-categories as TECHNICIAN | 403 FORBIDDEN (OPA + in-service gate, both deny) | — |
| OPA_READ | GET as TECHNICIAN | 200 (OPA allow + in-service allow) | — |

</intent-contract>

## Code Map

**Migration:**
- `resources/db/migration/V47__workorder_schema.sql` -- NEW -- four tables per Always block.

**Domain + entities:**
- `com/syncro/maintenance/domain/workorder/WorkOrderIdGenerator.java` -- NEW -- nextId() with FOR UPDATE + monthly reset.
- `com/syncro/maintenance/domain/workorder/WorkOrderCategory.java` -- NEW -- domain record {code, label}.
- `com/syncro/maintenance/infrastructure/db/WorkOrderCategoryEntity.java` -- NEW -- JPA entity.
- `com/syncro/maintenance/infrastructure/db/WorkOrderCategoryRepository.java` -- NEW -- JPA repo.
- `com/syncro/maintenance/infrastructure/db/WorkOrderEntity.java` -- NEW -- JPA entity (schema-only for validate).
- `com/syncro/maintenance/infrastructure/db/WorkOrderStatusHistoryEntity.java` -- NEW -- JPA entity (schema-only).
- `com/syncro/maintenance/infrastructure/db/WorkOrderIdSequenceEntity.java` -- NEW -- JPA entity.
- `com/syncro/maintenance/infrastructure/db/WorkOrderIdSequenceRepository.java` -- NEW -- JPA repo.

**Service:**
- `com/syncro/maintenance/application/WorkOrderCategoryService.java` -- NEW -- list, create, update; gate `requireCategoryRole`; unique code check; audit.
- `com/syncro/maintenance/application/WorkOrderCategoryMapper.java` -- NEW -- entity ↔ view.

**API:**
- `com/syncro/maintenance/api/WorkOrderCategoryController.java` -- NEW -- `@RequestMapping("/api/v1/work-order-categories")` GET list, POST create, PUT update.
- `com/syncro/maintenance/api/WorkOrderCategoryDtos.java` -- NEW -- request/response records.

**Audit:**
- `com/syncro/audit/domain/AuditEntityType.java` -- MODIFY -- add `WORK_ORDER_CATEGORY`.

**Enforcement:**
- `syncro/authz/policy/authz.rego` -- MODIFY -- add `category_mutation_paths` (POST/PUT/PATCH/DELETE on /api/v1/work-order-categories/**) allowed if role in {SECTION_LEADER,MAINTENANCE_LEADER,MANAGER_MAINTENANCE} (SUPER_ADMIN bypass); category read (GET) allowed for any authenticated.
- `syncro/authz/policy/authz_test.rego` -- MODIFY -- add parity cases for category mutation/read per role.
- `syncro/.env.example` -- MODIFY -- add `/api/v1/work-order-categories/**` to enforced-paths.

**Tests:**
- `com/syncro/db/WorkorderSchemaMigrationTest.java` -- NEW -- V47 tables/columns/constraints, all FK/indexes.
- `com/syncro/maintenance/application/WorkOrderCategoryServiceTest.java` -- NEW -- gate (below SECTION_LEADER → 403), create/update, unique code, audit.
- `com/syncro/maintenance/api/WorkOrderCategoryControllerTest.java` -- NEW -- HTTP 200/201/403/409.
- `com/syncro/maintenance/domain/workorder/WorkOrderIdGeneratorTest.java` -- NEW -- format, concurrent 20-thread uniqueness, monthly reset, exhaustion.

## Tasks & Acceptance

**Execution:**
- [x] V47 migration: four tables with CHECK constraints, FK, indexes.
- [x] JPA entities + repos for all four tables (matching DDL exactly).
- [x] WorkorderIdGenerator: FOR UPDATE prefix row, monthly reset, overflow guard (WorkorderIdExhaustedException).
- [x] Category CRUD service + controller + DTOs; requireCategoryRole gate (SUPER_ADMIN/MANAGER_MAINTENANCE/MAINTENANCE_LEADER/SECTION_LEADER); audit; unique code check.
- [x] AuditEntityType.WORK_ORDER_CATEGORY added.
- [x] Rego: category mutation/read rules + authz_test.rego parity cases; .env.example enforced-paths.
- [x] Tests: migration, category service, category controller, ID generator concurrency.

**Acceptance Criteria:**
- Given V47 migration applied, then work_orders/work_order_categories/work_order_status_history/workorder_id_sequences exist with the correct columns, constraints, and indexes. [FR-112/NFR-P2-3]
- Given a section leader (or above) creates a category, then the category is persisted with a unique code, audit-logged, and viewable by any authenticated user. [FR-112]
- Given a user below section leader attempts a category mutation, then a 403 CATEGORY_MUTATION_FORBIDDEN is returned server-side. [FR-112]
- Given an internal workorder ID is generated, then the format is WO-YYMM-XXXXX, concurrent nextId() calls produce unique IDs, and a new month resets the sequence. [FR-110/AD-3]
- Given OPA enforcement, then category mutations are evaluated by the rego (SECTION_LEADER+ allow; TECHNICIAN deny) and category reads are allowed for any authenticated user. [FR-160/FR-112]

## Review Triage Log

### 2026-08-26 — Review pass (step-04)
- intent_gap: 0
- bad_spec: 1 (low 1)
- patch: 6 (medium 3, low 3)
- defer: 2 (low 2)
- reject: 3 (low 3)
- addressed_findings:
  - `[low]` `[bad_spec]` Spec asked for bespoke `CATEGORY_MUTATION_FORBIDDEN`; repo convention is the uniform `FORBIDDEN` gate shape. Amended the spec Always-block to `FORBIDDEN` (implementation already correct; no code change).
  - `[medium]` `[patch]` Category code charset unvalidated → space in code made `URI.create` throw 500 after commit. Added `@Pattern` (`^[A-Za-z0-9._-]{1,16}$`) to both category DTOs (Blind Hunter P2 + Edge Case Hunter P2 converged).
  - `[medium]` `[patch]` `saveWithIntegrityCheck` mapped ANY DataIntegrityViolation to duplicate-code 409. Now checks `uq_work_order_categories_code` constraint name; other violations rethrow.
  - `[medium]` `[patch]` PUT `@PathVariable` not normalized → lowercase `ab` 404 for stored `AB`. Update now normalizes the lookup code.
  - `[low]` `[patch]` Missing FK indexes on `work_orders.machine_id` + `category_id` (repo convention indexes every FK). Added two CREATE INDEX to V47.
  - `[low]` `[patch]` rego `actions` under-reported `*.write` for SECTION_LEADER/MAINTENANCE_LEADER. Added the two rules.
  - `[low]` `[patch]` Concurrency test pre-seeded the prefix row, never exercising the insertIfAbsent first-call race. Removed the pre-seed; 20 fresh threads prove gapless bootstrap.
  - defer: DW-134 (month prefix UTC vs plant-local zone), DW-135 (WorkorderIdExhaustedException lacks an @ExceptionHandler until 10-2).
  - reject: `@Size(max=16)` pre-trim length (covered by the @Pattern patch); yyMM century wrap (WO-YYMM is the spec'd AD-3 format; 100-year horizon); nextId REQUIRED vs REQUIRES_NEW (reuse-after-full-rollback is correct, not a duplicate).

## Spec Change Log

- (step-04) Error code `CATEGORY_MUTATION_FORBIDDEN` → `FORBIDDEN` to align with the repo-wide gate shape. bad_spec (amended spec Always-block; implementation was already correct).

## Design Notes

- **ID format**: WO-2409-00001 (WO-YYMM-XXXXX). 5-digit = 00001-99999. If exceeded within a month, WorkorderIdExhaustedException (unlikely at 100k/month for a single site).
- **Category gate scope**: unlike section/machine mutations (which are scoped to plant/machine-group), category mutation is a global config gate — any SECTION_LEADER+ can mutate; no further plant/machine-group scoping. This matches FR-112 ("Leaders can create WO categories" without scope qualifier).
- **Status lifecycle**: the eight statuses are defined in the DDL CHECK; the state machine logic (transition validation, ON_PROCUREMENT derivation) arrives in 10.3. For now the schema stores whatever status is set.
- **OPA continuity**: the enforcement pattern from 9-5 is extended: add new paths + new rego rules. The in-service gate stays (defense-in-depth). The `category_mutation_paths` set mirrors the in-service `requireCategoryRole` set exactly.

## Verification

**Commands:**
- `mvn -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrderIdGeneratorTest,WorkOrderCategoryServiceTest,WorkOrderCategoryControllerTest,WorkorderSchemaMigrationTest"` -- per-class JVM (DW-127): ID gen 4/4, service 7/7, controller 7/7, migration 8/8. All BUILD SUCCESS.
- `cd syncro/authz && ./run-opa-test.ps1` -- PASS 46/46.
- `cd syncro/apps/web && npx tsc --noEmit` -- green (no frontend changes).

## Auto Run Result

| Step | Outcome | Notes |
|------|---------|-------|
| 01 route | pass | epic 10, story 1; epic-10-context compiled via subagent |
| 02 plan | pass | spec-10-1 written; schema + categories + ID generator |
| 03 implement | pass | V47 migration, WorkOrderIdGenerator, category CRUD, rego; subagent returned clean; verified independently |
| 04 review | pass | Blind Hunter (3 medium/2 low) + Edge Case Hunter (3 P2/4 P3); 6 patches applied, 1 bad_spec aligned, 2 defers (DW-134, DW-135), 3 rejects |
| commit | 52f1e00 | `feat(workorder): schema, categories and WO id generator (story 10-1)` |
| finalize | 52f1e00~1 | status done; followup_review_recommended: false |

**Defers appended:** DW-134 (UTC month prefix vs plant-local zone), DW-135 (WorkorderIdExhaustedException handler for 10-2).

**Residual risks:** ID format WO-YYMM is UTC-based (DW-134); the known multi-integration-class Testcontainers quirk (DW-127) persists; no frontend changes in this story.