---
title: 'Preventive Report & Auto-Workorder'
type: 'feature'
created: '2026-08-27'
baseline_commit: a5926d7fd4738367f2f5c008baa3916d244ad717
status: 'review'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-11-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-11-1-preventive-programs-and-schedules.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-11-2-preventive-checklist-assessment-signature.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** A completed preventive schedule (11.2) has no printable report — checklist results, assessment, and signature are stored but not assembled into a WYSIWYG-printable view — and a due schedule cannot produce the follow-up internal preventive workorder (category 02) that flows into the existing workorder pipeline, so preventive work is not evidenced on paper nor tracked as executable work (FR-133, FR-134).

**Approach:** Add a preventive report read endpoint that assembles the schedule's program/machine/checklist/items/evidence/signature into a single view for browser print (WYSIWYG tabular + logo + signature block; the template/editor mechanics are Epic 14 story 14.3 — 11.3 serves the data + a print page). Add an `auto_workorder` flag to `preventive_programs` (V56); when a program is flagged and a schedule reaches PERFORMED (approval flow from 11.2), create an internal preventive workorder (category 02 Preventive) linked back to the schedule via a nullable `preventive_schedule_id` column on `work_orders`, idempotently (one workorder per schedule period). The trigger fires from the existing `PreventiveChecklistService.approve`/`skip` PERFORMED path.

## Boundaries & Constraints

**Always:**
- **V56** (additive, on V55):
  - `ALTER TABLE preventive_programs ADD COLUMN auto_workorder BOOLEAN NOT NULL DEFAULT FALSE`.
  - `ALTER TABLE work_orders ADD COLUMN preventive_schedule_id UUID NULL REFERENCES preventive_schedules(id)` (nullable — only auto-generated workorders carry it; FK on delete SET NULL so deleting a schedule does not block workorder deletion). Index `idx_work_orders_preventive_schedule` on `preventive_schedule_id`.
  - Seed category 02: `INSERT INTO work_order_categories (id, code, label, created_by, created_at, updated_at) VALUES (gen_random_uuid(), '02', 'Preventive', NULL, NOW(), NOW()) ON CONFLICT (code) DO NOTHING`. The 11.1/11.2 category-code constants: `WorkOrderService.BREAKDOWN_CATEGORY_CODE = "01"` exists; add `PREVENTIVE_CATEGORY_CODE = "02"` in `WorkOrderService`.
- **Program flag:** `CreateProgramCommand`/`UpdateProgramCommand` + DTOs gain `autoWorkorder` (boolean, default false). `PUT`/`POST` validate nothing extra (flag is independent of category/scheduleType).
- **Auto-workorder trigger (FR-134, idempotent):** in `PreventiveChecklistService.approve` after `schedule.transition(PERFORMED)` + before/after `rollForwardNext`, when `program.autoWorkorder == true`, call a new `WorkOrderService.createSystem(machineId, PREVENTIVE_CATEGORY_CODE, "Preventive: <program title> due <dueDate>", null, null)` that creates the INTERNAL workorder with actor SYSTEM (audit via `recordSystem`, history `source=SYSTEM`, no user gate) and sets `preventive_schedule_id = schedule.id`. Idempotency: `preventive_schedule_id` unique per workorder — before creating, check `workOrders.existsByPreventiveScheduleId(scheduleId)`; if present, no-op (one workorder per schedule period). This is the hard backstop — the unique index `uq_work_orders_preventive_schedule ON work_orders(preventive_schedule_id)` is the DB-level guarantee (additive; NULLs allowed multiple).
- **Report read (FR-133):** `GET /api/v1/preventive-schedules/{id}/report` — any authenticated user (same read posture as checklist/evidence). Returns `PreventiveReportView`: program (title/category/scheduleType/autoWorkorder), machine (id/plant/group), schedule (dueDate/status/completedAt/performedBy), checklist result (notes/assessment/approvedAt/signerIdentity/leaderId + items with position/label/value/lsl/usl/note), evidence list (filename/contentType/presignedUrl — short-TTL, never persisted), signature presigned URL (derived from `signature_object_key` via `ObjectStorageService.presignGetUrl`), and the linked workorder id (if any). Server-clock timestamps only.
- **Frontend:** a preventive report print page `src/features/preventive/components/preventive-report.tsx` (client) rendered from the report endpoint — tabular checklist table (item/label/value/LSL/USL/note), evidence thumbnails/links, signature block (image via presigned URL + signer identity + approved timestamp), and a browser-print trigger (`window.print()`). Route: schedule detail gains a "Print Report" button that opens the report view (dialog or dedicated route). The full WYSIWYG variable-picker/template editor is 14.3 — 11.3 is the data-driven print page only. Loading/empty/error/forbidden states required; no new dependencies.
- **OPA:** `preventive_schedule_mutation_paths` already covers `.../checklist|evidence|approve|skip`. The report read `GET .../report` flows through generic `read_allowed` (any authenticated). No rego change needed for 11.3 — `.env.example` already lists `preventive-schedules`. (Verify: no new mutation path is introduced.)
- **Audit:** auto-workorder creation → `WORK_ORDER` CREATE via `recordSystem` (entityLabel = workorder id, plantId from machine). Program `autoWorkorder` flag change is covered by the existing `PREVENTIVE_PROGRAM` UPDATE audit (add `autoWorkorder` to `programValues`).
- **Errors:** unknown schedule → 404 `SCHEDULE_NOT_FOUND`; report on a schedule with no checklist → 200 with `checklist: null` (not an error — a not-yet-completed schedule has no report content); auto-workorder when category 02 missing → log + skip (do not fail the approval; category seeded by V56 so this is defensive only); workorder creation failure → does NOT roll back the schedule PERFORMED (11.2 rule: "WAHA provider failure must not roll back domain transactions" — same principle; wrap createSystem in try/catch, audit a PREVENTIVE_SCHEDULE UPDATE note on failure).

**Block If:** nothing.

**Never:**
- Never build the full WYSIWYG template editor / variable picker / logo-config UI in 11.3 — that is 14.3 (FR-175). 11.3 serves report data + a print page.
- Never store report HTML, presigned URLs, or image bytes in PostgreSQL.
- Never generate a workorder for a SKIPPED schedule or a program with `autoWorkorder=false`.
- Never create duplicate workorders for the same schedule period — `preventive_schedule_id` unique is the backstop.
- Never call the user-gated `WorkOrderService.create` for auto-workorders — use the system path (`createSystem`), actor SYSTEM, audit `recordSystem`.
- Never let a workorder-creation failure roll back the schedule PERFORMED state.
- Never touch V47-V55 or add V57 — V56 is the only migration for 11.3.
- Never add a new Garage bucket or a new frontend table/print library.
- Never invent a new status for the schedule — PERFORMED stays the terminal-complete marker.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| REPORT_OK | PERFORMED schedule with checklist + evidence + signature | 200 `PreventiveReportView` with full data + presigned URLs | — |
| REPORT_NO_CHECKLIST | SCHEDULED/IN_PROGRESS schedule | 200 with `checklist: null`, empty evidence | — |
| REPORT_NOT_FOUND | bad schedule id | 404 SCHEDULE_NOT_FOUND | — |
| AUTO_WO_OK | approve on PERFORMED, program.autoWorkorder=true | INTERNAL WO (02 Preventive) created, `preventive_schedule_id` set, audit SYSTEM | — |
| AUTO_WO_IDEMPOTENT | approve fires twice on same schedule | second is a no-op (existsByPreventiveScheduleId / unique index) | — |
| AUTO_WO_DISABLED | program.autoWorkorder=false | no workorder created | — |
| AUTO_WO_FAILURE | category 02 missing or create throws | schedule stays PERFORMED; workorder skipped; audit UPDATE note logged | — |
| FLAG_UPDATE_OK | PUT program with autoWorkorder true | 200 view; flag persisted; audit UPDATE | — |

</intent-contract>

## Code Map

**Migration:**
- `resources/db/migration/V56__preventive_auto_workorder.sql` -- NEW -- program flag + work_orders preventive_schedule_id + 02 category seed.

**Domain:**
- `com/syncro/maintenance/preventive/domain/PreventiveProgram.java` -- MODIFY -- + `autoWorkorder` field.
- `com/syncro/maintenance/preventive/domain/PreventiveReport.java` -- NEW -- assembled report record (program/machine/schedule/checklist/evidence/signature/workorderId).

**Persistence:**
- `com/syncro/maintenance/preventive/infrastructure/db/PreventiveProgramEntity.java` -- MODIFY -- + autoWorkorder column/getter/constructor.
- `com/syncro/maintenance/preventive/infrastructure/db/PreventiveProgramRepository.java` -- MODIFY -- (unchanged queries).
- `com/syncro/maintenance/infrastructure/db/WorkOrderEntity.java` -- MODIFY -- + preventiveScheduleId column/getter.
- `com/syncro/maintenance/infrastructure/db/WorkOrderRepository.java` -- MODIFY -- + `existsByPreventiveScheduleId(UUID)`.
- `com/syncro/maintenance/infrastructure/db/WorkOrderCategoryRepository.java` -- READ -- findByCode (exists).

**Application:**
- `com/syncro/maintenance/application/WorkOrderService.java` -- MODIFY -- + `PREVENTIVE_CATEGORY_CODE = "02"` constant + `createSystem(UUID machineId, String categoryCode, String description)` (SYSTEM actor path, reuses the internal create core, audit recordSystem, history source SYSTEM) + expose `existsByPreventiveScheduleId`.
- `com/syncro/maintenance/preventive/application/PreventiveChecklistService.java` -- MODIFY -- after PERFORMED in `approve`, fire auto-workorder (idempotent, guarded, non-fatal).
- `com/syncro/maintenance/preventive/application/PreventiveReportService.java` -- NEW -- assemble report view (program/machine/checklist/items/evidence/signature presigned URLs + linked workorder id).
- `com/syncro/maintenance/preventive/application/PreventiveMapper.java` -- MODIFY -- + autoWorkorder mapping + report mapping.
- `com/syncro/maintenance/preventive/application/PreventiveProgramService.java` -- MODIFY -- pass autoWorkorder through create/update + programValues.

**API:**
- `com/syncro/maintenance/preventive/api/PreventiveScheduleController.java` -- MODIFY -- + `GET /{id}/report`.
- `com/syncro/maintenance/preventive/api/PreventiveDtos.java` -- MODIFY -- + autoWorkorder in program request/view + `PreventiveReportView` DTOs.
- `com/syncro/maintenance/preventive/api/PreventiveProgramController.java` -- MODIFY -- wire autoWorkorder in request bodies.

**Frontend:**
- `src/features/preventive/types.ts` -- MODIFY -- + autoWorkorder + report view types.
- `src/features/preventive/hooks/use-preventive.ts` -- MODIFY -- + report fetch hook.
- `src/features/preventive/components/preventive-report.tsx` -- NEW -- tabular report print view + `window.print()`.
- `src/features/preventive/components/preventive-schedule-detail.tsx` -- MODIFY -- + "Print Report" button (leader/approved only).
- `src/features/preventive/components/preventive-programs-panel.tsx` -- MODIFY -- + autoWorkorder toggle in create/edit form.

**Tests:**
- `com/syncro/maintenance/preventive/application/PreventiveReportServiceTest.java` -- NEW -- report assembly, no-checklist, not-found, presigned URLs.
- `com/syncro/maintenance/preventive/application/PreventiveChecklistServiceTest.java` -- MODIFY -- auto-workorder fired on approve when flagged, no-op idempotent, disabled flag, failure non-fatal.
- `com/syncro/maintenance/preventive/api/PreventiveChecklistControllerTest.java` -- MODIFY -- report endpoint shapes.
- `com/syncro/db/PreventiveMigrationTest.java` -- MODIFY -- V56 columns/seed/unique.

## Tasks & Acceptance

**Execution:**
- [x] `resources/db/migration/V56__preventive_auto_workorder.sql` -- program flag + WO FK/unique + 02 category seed.
- [x] Domain + entity changes -- autoWorkorder on program, preventiveScheduleId on work_orders, report record.
- [x] `WorkOrderService.createSystem` + category constant + `existsByPreventiveScheduleId`.
- [x] `PreventiveChecklistService.approve` -- auto-workorder trigger (idempotent, non-fatal).
- [x] `PreventiveReportService` -- assemble report view.
- [x] Controller + DTOs -- `GET /{id}/report` + autoWorkorder wiring.
- [x] Frontend -- report print page + Print button + autoWorkorder toggle.
- [x] Tests (report + checklist trigger + controller + migration).

**Acceptance Criteria:**
- Given a completed preventive schedule, when the report is read, then a `PreventiveReportView` renders checklist results, assessment, evidence (presigned), and the signature block (image + signer identity + timestamp); printing is browser-print of the data page. [FR-133]
- Given a schedule with no checklist, when the report is read, then 200 with `checklist: null` (not an error). [FR-133]
- Given a program configured with autoWorkorder and a schedule that is approved to PERFORMED, then an internal preventive workorder (category 02 Preventive) is created, linked back to the schedule. [FR-134]
- Given the same schedule is approved twice, then only one workorder is created (idempotent, unique `preventive_schedule_id` backstop). [FR-134]
- Given a program without autoWorkorder, or a SKIPPED schedule, then no workorder is created. [FR-134]
- Given a workorder-creation failure, then the schedule remains PERFORMED and the failure is audit-logged, not rolled back. [FR-134, AD-9 principle]

## Design Notes

- **System actor path.** `WorkOrderService.createSystem` mirrors `create` but skips `requireCreateRole`/`requireCreateAccess` (the schedule's PERFORMED state is the authorization), records audit via `recordSystem` (zero UUID + "SYSTEM"), and writes history `source=SYSTEM`. This is the same SYSTEM actor pattern used by the DERIVED transitions (`SYSTEM_ACTOR`) — consistent, not new.
- **Idempotency is a DB constraint, not a check-then-act.** `uq_work_orders_preventive_schedule` on `preventive_schedule_id` makes duplicate generation impossible even under concurrency; the `existsByPreventiveScheduleId` pre-check avoids the noisy path. NULLs are allowed (all non-auto workorders) so the unique index doesn't reject normal inserts.
- **Approval is the single trigger.** The auto-workorder fires inside `PreventiveChecklistService.approve` (IN_PROGRESS → PERFORMED), the exact point 11.2 already calls `rollForwardNext`. SKIPPED never reaches it. A schedule that was already PERFORMED before 11.3 ships simply has no workorder — backfill is out of scope (deferred note).
- **Report is data, not markup.** The backend returns structured data + short-TTL presigned URLs; the frontend `preventive-report.tsx` lays out the tabular print view and calls `window.print()`. The WYSIWYG variable-picker template editor (logo config, template save) is explicitly 14.3 — 11.3 is the data-driven print page so 11.3 doesn't build a half-editor that 14.3 replaces.
- **Failure isolation.** `createSystem` is wrapped in try/catch; a workorder failure (missing category, DB error) logs + audits a PREVENTIVE_SCHEDULE UPDATE note and does NOT roll back PERFORMED — mirroring the "provider failure must not roll back domain transactions" rule.
- **02 category seeding** belongs in V56 because the auto-workorder depends on it at runtime; `ON CONFLICT DO NOTHING` keeps it idempotent across environments.

## Verification

**Commands:**
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=PreventiveReportServiceTest,PreventiveChecklistServiceTest,PreventiveChecklistControllerTest,PreventiveMigrationTest"` -- expected BUILD SUCCESS.
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrder*Test"` -- expected no regressions (WorkOrderService.createSystem).
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS (no rego change; report read is generic).
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green.
- `cd syncro/apps/web && npx biome check src/features/preventive src/app/preventive` -- expected clean.

## Dev Agent Record

**Implementation notes:**
- V56 migration: `auto_workorder` flag on preventive_programs, `preventive_schedule_id` (nullable FK, ON DELETE SET NULL) + unique partial index on work_orders, and idempotent seed of category 02 (Preventive).
- `WorkOrderService.createSystem` — system-actor internal workorder creation (no user/role/scope gate; audit via `recordSystem`; history source DERIVED, actor SYSTEM); `PREVENTIVE_CATEGORY_CODE="02"` constant; `existsByPreventiveScheduleId` wrapper.
- `PreventiveChecklistService.approve` fires the auto-workorder after PERFORMED + rollForwardNext; idempotent via `existsByPreventiveScheduleId` (unique index backstop); failure is logged (audit note) and does not roll back PERFORMED.
- `PreventiveReportService` — assembles program/machine/schedule/checklist/items/evidence (fresh presigned URLs)/signature/linked-workorder into `PreventiveReport`; any-authenticated read.
- Controller: `GET /preventive-schedules/{id}/report`; `autoWorkorder` wired through program create/update request + view.
- Frontend: `preventive-report.tsx` print page (tabular checklist, evidence links, signature block, `window.print()`), Report button in schedule detail, autoWorkorder toggle in programs panel.
- No rego change needed (report read flows through generic read_allowed).

**AC mapping:**
- AC1 (FR-133 report): PreventiveReportServiceTest.reportOk + reportNoChecklist + PreventiveChecklistControllerTest.reportReturnsOk.
- AC2 (FR-133 no-checklist): PreventiveReportServiceTest.reportNoChecklist.
- AC3 (FR-134 auto-workorder): PreventiveChecklistServiceTest.approveAutoWorkorder.
- AC4 (FR-134 idempotent): PreventiveChecklistServiceTest.approveAutoWorkorderIdempotent + PreventiveMigrationTest.v56UniquePreventiveSchedule.
- AC5 (FR-134 disabled/skipped): PreventiveChecklistServiceTest.approveNoAutoWorkorder (skip never reaches approve).
- AC6 (FR-134 failure non-fatal): PreventiveChecklistServiceTest.approveAutoWorkorderFailureNonFatal.

## Spec Change Log

<!-- Empty until review loop. -->

## Review Triage Log

<!-- Empty until first review pass. -->
