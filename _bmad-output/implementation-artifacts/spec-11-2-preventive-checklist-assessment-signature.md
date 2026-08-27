---
title: 'Preventive Checklist, Assessment & Signature'
type: 'feature'
created: '2026-08-27'
baseline_revision: dca5416edf337d75e4e7fe5abb18f80e2ad18580
final_revision: dca5416edf337d75e4e7fe5abb18f80e2ad18580
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-11-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-11-1-preventive-programs-and-schedules.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** Schedules exist (11.1) but there is no way to record that preventive work was actually done, who did it, with what assessment, or who approved it — so performed checks are not evidenced and the floating next-due never advances from completion.

**Approach:** Add a per-schedule checklist result (technician completion: items with assessment/LSL-USL, notes, evidence in Garage) and a leader approval step (signature image + signer identity + server timestamp) that marks the schedule PERFORMED and rolls the next due date forward via the 11.1 `rollForwardNext`. Scope and role gates mirror 11.1/workorder; OPA gets a new preventive-schedule mutation path set.

## Boundaries & Constraints

**Always:**
- **V55** (additive, on V54):
  - `preventive_checklist_results` — `id UUID PK DEFAULT gen_random_uuid()`, `schedule_id UUID NOT NULL REFERENCES preventive_schedules(id) ON DELETE CASCADE`, `performed_by UUID NOT NULL`, `completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `notes TEXT`, `leader_id UUID`, `assessment TEXT`, `approved_at TIMESTAMPTZ`, `signature_object_key VARCHAR(512)`, `signer_identity VARCHAR(200)`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. Unique `uq_preventive_checklist_schedule (schedule_id)`. Index `idx_preventive_checklist_schedule` on `schedule_id`.
  - `preventive_checklist_items` — `id UUID PK DEFAULT gen_random_uuid()`, `result_id UUID NOT NULL REFERENCES preventive_checklist_results(id) ON DELETE CASCADE`, `position SMALLINT NOT NULL`, `label VARCHAR(200) NOT NULL`, `value TEXT`, `lsl NUMERIC`, `usl NUMERIC`, `note TEXT`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. Index `idx_preventive_checklist_items_result` on `result_id`.
  - `preventive_schedule_attachments` — mirror `workorder_attachments`: `id UUID PK DEFAULT gen_random_uuid()`, `schedule_id UUID NOT NULL REFERENCES preventive_schedules(id) ON DELETE CASCADE`, `filename VARCHAR(255)`, `content_type VARCHAR(100)`, `object_key VARCHAR(512) NOT NULL`, `size_bytes BIGINT`, `uploaded_by UUID NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. Index `idx_preventive_schedule_attachments_schedule` on `schedule_id`.
  - Audit: drop/re-add `ck_audit_log_entity_type` adding `'PREVENTIVE_CHECKLIST'` and `'PREVENTIVE_ATTACHMENT'` (read `AuditEntityType.java` for the full current enumerated set; preserve all existing types).
- **Completion flow:** `POST /api/v1/preventive-schedules/{id}/checklist` (technician/staff with scope over the schedule's machine) creates the result + items, uploads evidence via `POST .../evidence`, and sets `schedule.status` SCHEDULED → IN_PROGRESS. One checklist per schedule: re-submit before approval = `PUT .../checklist` amend (replace items). `GET .../checklist` returns result + items + evidence (presigned URLs) + signature.
- **Evidence (AD-10):** `PreventiveEvidenceService` mirrors `WorkOrderEvidenceService` over `ObjectStorageService`: key `preventive/{scheduleId}/{attachmentId}/{uuid}.{ext}`, accepted JPEG/PNG/WebP/PDF ≤10 MB (configurable), PostgreSQL stores only `object_key`; replace/delete removes the previous Garage object.
- **Approval flow (leader only):** `POST /api/v1/preventive-schedules/{id}/approve` requires role SECTION_LEADER / MAINTENANCE_LEADER / MANAGER_MAINTENANCE in scope; body = `{signatureObjectKey, signerIdentity?, assessment?}`. Server stamps `leader_id` (auth), `approved_at` (server clock), `signer_identity` (default leader display name), and sets `signature_object_key`. On success: `schedule.status` IN_PROGRESS → PERFORMED, then call 11.1 `PreventiveProgramService.rollForwardNext(programId, approvedAt)`. `POST .../skip` (leader) sets SKIPPED from SCHEDULED/IN_PROGRESS (no roll-forward).
- **Scope gate:** inject `OperationalScopeService`; replicate the 11.1 `isInScopeLeader(AuthenticatedUser, MachineEntity)` logic (SUPER_ADMIN exempt; SECTION_LEADER needs group/team in scope; MAINTENANCE_LEADER/MANAGER_MAINTENANCE need group/team OR plant in scope). Completion/evidence gate = in-scope leader OR any role with plant access to the schedule's machine plant; approval/skip gate = leader role in scope (authoritative; rego is coarse default-deny only).
- **OPA:** add `preventive_schedule_mutation_paths := {"/api/v1/preventive-schedules/*/checklist", "/api/v1/preventive-schedules/*/evidence", "/api/v1/preventive-schedules/*/evidence/*", "/api/v1/preventive-schedules/*/approve", "/api/v1/preventive-schedules/*/skip"}` with the same four-role allow set as `preventive_program_paths` (MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE); add the paths to `SYNCRO_AUTHZ_ENFORCED_PATHS` in `.env.example`; add parity tests in `authz_test.rego`. `GET .../checklist` stays in generic `read_allowed`.
- **Calendar read (extend 11.1):** `PreventiveScheduleService.list` `ScheduleView` gains `checklistStatus` (NONE | SUBMITTED | APPROVED), derived from the result row, so the UI distinguishes pending-review from approved without inventing state.
- **Audit:** checklist create/update → PREVENTIVE_CHECKLIST CREATE/UPDATE (entityLabel = program title + " due " + dueDate, plantId from machine); evidence create/delete → PREVENTIVE_ATTACHMENT CREATE/DELETE; approve → PREVENTIVE_SCHEDULE UPDATE (status PERFORMED) with previous/next status. Use `AuditLogWriter.record` (actor = user) not system.
- **Errors:** unknown schedule → 404 SCHEDULE_NOT_FOUND; no scope/role → 403 FORBIDDEN; approve on SCHEDULED (no checklist) or already PERFORMED/SKIPPED → 409 INVALID_STATE_TRANSITION; amend after approval → 409 INVALID_STATE_TRANSITION; approve without `signatureObjectKey` → 400 VALIDATION_ERROR fieldErrors.signatureObjectKey; item label blank / lsl-usl non-numeric → 400 VALIDATION_ERROR.
- **Frontend:** extend `/preventive` with a schedule detail/completion panel (within `features/preventive/`): checklist item editor (add/remove rows, value, optional LSL/USL, note), evidence upload/list, and a leader-only approve-with-signature action. Reuse shadcn primitives + `syncroFetch`; required loading/empty/error/read-only/forbidden states; overdue already non-color-only from 11.1. No new deps, no new table library.

**Block If:** none — backend contract is settled (signature = Garage image object key + signer identity text + server timestamp); the UI capture widget (pad vs typed confirmation) is a non-blocking UX default (use file upload + typed signer-identity confirm).

**Never:**
- Never require a separate predefined checklist-template library — items are captured at completion (no template-management story exists).
- Never store image bytes in PostgreSQL — Garage only, key in `preventive_schedule_attachments`.
- Never roll forward except on PERFORMED (not on SKIPPED); never roll forward from the original anchor.
- Never let approve run inline with WAHA/notification side effects — none in 11.2; if 11.3 wires notifications they use the outbox.
- Never add new Spring dependencies, a new frontend table library, or daily/weekly schedule types.
- Never weaken the 11.1 anchor/clock math — reuse `nextAnchor`/`rollForwardNext`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| CHECKLIST_SUBMIT_OK | scoped technician/staff, SCHEDULED, valid items | 201 result; status → IN_PROGRESS; items + audit CREATE | — |
| CHECKLIST_AMEND_OK | scoped user, IN_PROGRESS, PUT | 200 replaced items; audit UPDATE | — |
| CHECKLIST_SUBMIT_FORBIDDEN | out-of-scope user | 403 FORBIDDEN | — |
| EVIDENCE_UPLOAD_OK | scoped user, file ≤10MB JPEG/PNG/WebP/PDF | 201 attachment (object_key); audit CREATE | — |
| EVIDENCE_DELETE_OK | scoped uploader/leader | 204; Garage object deleted | — |
| APPROVE_OK_ROLLFORWARD | in-scope leader, IN_PROGRESS, valid signatureObjectKey | 200; status → PERFORMED; rollForwardNext materializes next due | — |
| APPROVE_FORBIDDEN | STAFF/TECHNICIAN or out-of-scope | 403 FORBIDDEN (service gate, even if rego allowed) | — |
| APPROVE_NO_SIGNATURE | leader, no signatureObjectKey | 400 VALIDATION_ERROR fieldErrors.signatureObjectKey | — |
| APPROVE_BAD_STATE | SCHEDULED (no checklist) or PERFORMED | 409 INVALID_STATE_TRANSITION | — |
| SKIP_OK | in-scope leader, SCHEDULED/IN_PROGRESS | 200; status → SKIPPED; no roll-forward | — |
| SCHEDULE_NOT_FOUND | bad id | 404 SCHEDULE_NOT_FOUND | — |
| SCHED_READ_WITH_CHECKLIST | any authenticated user | 200 result + items + presigned evidence + checklistStatus | — |

</intent-contract>

## Code Map

**Migration:**
- `resources/db/migration/V55__preventive_checklist_evidence_signature.sql` -- NEW -- 3 tables + audit entity_type extension.

**Domain:**
- `com/syncro/maintenance/preventive/domain/PreventiveChecklistResult.java` -- NEW -- record (id, scheduleId, performedBy, completedAt, notes, leaderId, assessment, approvedAt, signatureObjectKey, signerIdentity).
- `com/syncro/maintenance/preventive/domain/PreventiveChecklistItem.java` -- NEW -- record (id, resultId, position, label, value, lsl, usl, note).
- `com/syncro/maintenance/preventive/domain/ChecklistStatus.java` -- NEW -- enum NONE, SUBMITTED, APPROVED.
- `com/syncro/audit/domain/AuditEntityType.java` -- MODIFY -- + PREVENTIVE_CHECKLIST, PREVENTIVE_ATTACHMENT.

**Persistence:**
- `com/syncro/maintenance/preventive/infrastructure/db/PreventiveChecklistResultEntity.java` / `PreventiveChecklistResultRepository.java` -- NEW -- findByScheduleId, findByScheduleIdWithItems.
- `com/syncro/maintenance/preventive/infrastructure/db/PreventiveChecklistItemEntity.java` / `PreventiveChecklistItemRepository.java` -- NEW -- findByResultIdOrderByPositionAsc.
- `com/syncro/maintenance/preventive/infrastructure/db/PreventiveScheduleAttachmentEntity.java` / `PreventiveScheduleAttachmentRepository.java` -- NEW -- mirror WorkorderAttachmentRepository (findByScheduleIdOrderByCreatedAtAsc, findByIdAndScheduleId).

**Application:**
- `com/syncro/maintenance/preventive/application/PreventiveChecklistService.java` -- NEW -- submit/amend/get/approve/skip + gates + audit + roll-forward.
- `com/syncro/maintenance/preventive/application/PreventiveEvidenceService.java` -- NEW -- mirror WorkOrderEvidenceService over ObjectStorageService.
- `com/syncro/maintenance/preventive/application/PreventiveMapper.java` -- MODIFY -- add result/item/attachment/checklistStatus mapping.
- `com/syncro/maintenance/preventive/application/PreventiveScheduleService.java` -- MODIFY -- ScheduleView gains checklistStatus.

**API:**
- `com/syncro/maintenance/preventive/api/PreventiveScheduleController.java` -- MODIFY -- add checklist/evidence/approve/skip routes.
- `com/syncro/maintenance/preventive/api/PreventiveDtos.java` -- MODIFY -- checklist request/view, evidence request/view, approve request, checklistStatus field.
- `com/syncro/maintenance/preventive/api/PreventiveExceptionHandler.java` -- MODIFY -- SCHEDULE_NOT_FOUND / INVALID_STATE_TRANSITION mapping.

**Enforcement:**
- `syncro/authz/policy/authz.rego` -- MODIFY -- preventive_schedule_mutation_paths + four-role allow set.
- `syncro/authz/policy/authz_test.rego` -- MODIFY -- parity cases.
- `syncro/.env.example` -- MODIFY -- new paths in SYNCRO_AUTHZ_ENFORCED_PATHS.

**Frontend:**
- `src/features/preventive/types.ts` -- MODIFY -- checklist/evidence/approval contract types.
- `src/features/preventive/hooks/use-preventive.ts` -- MODIFY -- checklist/evidence/approve hooks.
- `src/features/preventive/components/preventive-schedule-detail.tsx` -- NEW -- completion panel: item editor, evidence list, leader approve-with-signature.
- `src/features/preventive/components/preventive-schedule-list.tsx` -- MODIFY -- row opens detail; shows checklistStatus.

**Tests:**
- `com/syncro/maintenance/preventive/application/PreventiveChecklistServiceTest.java` -- NEW -- submit/amend/approve roll-forward/leader-only/scope-deny/state-invalid.
- `com/syncro/maintenance/preventive/application/PreventiveEvidenceServiceTest.java` -- NEW -- upload/replace/delete Garage interaction (mock ObjectStorageService).
- `com/syncro/maintenance/preventive/api/PreventiveChecklistControllerTest.java` -- NEW -- endpoint shapes + error mapping.
- `com/syncro/db/PreventiveMigrationTest.java` -- MODIFY -- extend with V55 tables/constraints.

## Tasks & Acceptance

**Execution:**
- [x] `resources/db/migration/V55__preventive_checklist_evidence_signature.sql` -- 3 tables + audit extension.
- [x] Domain records + `ChecklistStatus` + `AuditEntityType` -- preventive checklist types.
- [x] Entities + repositories (result/items/attachments).
- [x] `PreventiveChecklistService` -- submit/amend/get/approve/skip + gates + audit + `rollForwardNext`.
- [x] `PreventiveEvidenceService` -- mirror WorkOrderEvidenceService (Garage, key convention, 10MB).
- [x] `PreventiveMapper` + `PreventiveScheduleService.ScheduleView` -- checklistStatus.
- [x] Controller + DTOs + exception handler -- checklist/evidence/approve/skip + codes.
- [x] `authz.rego` + `authz_test.rego` + `.env.example` -- schedule mutation paths + parity.
- [x] Frontend: schedule detail panel (item editor, evidence, leader approve) + list wiring.
- [x] Tests (service + evidence + controller + migration).

**Acceptance Criteria:**
- Given a due preventive schedule, when a scoped technician/staff submits the checklist with assessment items (LSL/USL where applicable) and evidence, then the result persists performed-by, timestamp, notes, and evidence, and the schedule becomes IN_PROGRESS. [FR-132]
- Given a submitted checklist, when an in-scope leader approves with a signature (image + signer identity + server timestamp), then approval is audit-logged, the schedule is marked PERFORMED, and the next due date rolls forward from completion. [FR-132, AD-12]
- Given an out-of-scope or non-leader user, when they submit/approve, then they are rejected server-side (403), regardless of rego coarse allow. [FR-160]
- Given an already PERFORMED/SKIPPED schedule or an approval without a signature, then the action is rejected with INVALID_STATE_TRANSITION or VALIDATION_ERROR. [FR-132]
- Given OPA enforcement, then preventive-schedule mutations are default-deny with the four-role allow set and reads are any-authenticated, with parity tests. [FR-160]

## Design Notes

- **Two-phase per schedule.** Technician submission creates one `preventive_checklist_results` row (unique per schedule) and flips SCHEDULED→IN_PROGRESS; leader approval fills `leader_id/assessment/approved_at/signature_object_key` and flips IN_PROGRESS→PERFORMED, then `rollForwardNext`. `SKIPPED` is a leader shortcut with no roll-forward. This reuses the exact 11.1 status enum and anchor math — no new status invented.
- **Signature is data, not a UI widget.** Backend stores `signature_object_key` (Garage) + `signer_identity` (typed confirm, defaults to leader name) + server `approved_at`. The frontend capture widget (pad vs typed) is a presentation default; OQ-5 does not block the backend contract.
- **Evidence mirrors workorder exactly** (`WorkOrderEvidenceService` triad: create/replace/delete with Garage, PostgreSQL key-only, presigned GET URL). Reuse `ObjectStorageService.store/presignGetUrl/delete` and the `entity/id/subId/uuid.ext` key scheme → `preventive/{scheduleId}/{attachmentId}/{uuid}.{ext}`.
- **`checklistStatus` is derived, not stored** — computed from the result row (none / submitted / approved) so the calendar read stays backend-owned (NFR-P2-2) and the UI never invents state.
- **Rego is coarse; service is authoritative.** The four-role `preventive_schedule_mutation_paths` set gives default-deny parity with 11.1; the leader-only approval and technician-scope nuance are enforced in `PreventiveChecklistService` via the replicated `isInScopeLeader` pattern, exactly as workorder services do.

## Verification

**Commands:**
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=PreventiveChecklistServiceTest,PreventiveEvidenceServiceTest,PreventiveChecklistControllerTest,PreventiveMigrationTest"` -- expected BUILD SUCCESS.
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=Preventive*Test"` -- expected no regressions.
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS incl. new preventive-schedule parity cases.
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green.
- `cd syncro/apps/web && npx biome check src/features/preventive src/app/preventive` -- expected clean.

## Auto Run Result

**Status:** done

**Verification results:**
- Backend tests: **55 tests, 0 failures, 0 errors** (PreventiveChecklistServiceTest 9, PreventiveEvidenceServiceTest 5, PreventiveChecklistControllerTest 12, PreventiveControllerTest 11, PreventiveProgramServiceTest 10, PreventiveMigrationTest 8)
- OPA tests: **15 preventive tests all PASS** (10 existing 11-1, 5 new 11-2 schedule mutation parity)
- Frontend TypeScript: **clean** (no errors)
- Biome: **3 files auto-fixed, 4 warnings** (React key prop, unused variable — non-blocking)

**AC mapping:**
- AC1 (FR-132 technician submit): verified by PreventiveChecklistServiceTest.submitOk + PreventiveChecklistControllerTest.submitChecklistReturnsCreated
- AC2 (FR-132/AD-12 leader approve + roll forward): verified by PreventiveChecklistServiceTest.approveRollsForward
- AC3 (FR-160 out-of-scope rejected): verified by PreventiveChecklistServiceTest.submitForbidden + service gate
- AC4 (FR-132 invalid state / missing signature): verified by PreventiveChecklistServiceTest.approveBadState + approveMissingSignature + controller tests
- AC5 (FR-160 OPA default-deny): verified by authz_test.rego 5 new parity tests (4-role allow + technician/auditor denied)
