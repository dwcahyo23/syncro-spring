---
title: 'Evidence & Technical Drawings'
type: 'feature'
created: '2026-08-26'
status: 'done'
followup_review_recommended: true
review_loop_iteration: 1
baseline_revision: d8a8172
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-4-repair-sessions-and-mttr.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Workorders (10-1..10-4) lack file attachments — before/after photos and technical drawings are paper or local files, so evidence is not durable or auditable across the repair lifecycle (FR-116).

**Approach:** Add a `workorder_attachments` table (V50, object-key-only, Garage-backed), upload/replace/delete/list endpoints gated on session access (executor/leader, same as 10.4 repair sessions — attachments are local operational fields preserved on both sources), and a multipart upload pattern adapted from the sparepart-image precedent (8-4). Evidence is a local operational field on both SYNCED and INTERNAL sources (AD-3 "preserved"), never touches status or sync_version.

## Boundaries & Constraints

**Always:**
- **V50** (additive, on V49): `workorder_attachments(id, work_order_id, filename, content_type, object_key, size_bytes, uploaded_by, created_at, updated_at)`; FK to `work_orders(id)`; `NOT NULL` on all except `updated_at` (nullable on create, set on replace). Index on `work_order_id`. `object_key VARCHAR(255) NOT NULL`. `size_bytes BIGINT NOT NULL` (non-negative). `filename VARCHAR(255) NOT NULL`. `content_type VARCHAR(100) NOT NULL`. `uploaded_by UUID NOT NULL`. audit_log entity_type CHECK widened with `WORKORDER_ATTACHMENT` (drop/re-add pattern). No EXCLUDE constraint needed — multiple attachments per workorder are independent.
- **Attachment lifecycle:** one file per attachment record. `POST /{id}/attachments` creates a new record; `PUT /{id}/attachments/{attachmentId}` replaces an existing record's file (deletes the old Garage object first, then stores the new one — 8-4 pattern); `DELETE /{id}/attachments/{attachmentId}` deletes the Garage object then removes the row. Key format: `"workorders/{workOrderId}/{attachmentId}.{ext}"` (attachment UUID in key; fresh object per replacement defeats browser cache).
- **Upload/replace** `POST /api/v1/workorders/{id}/attachments` — multipart: `@RequestParam("filename")`, `@RequestParam("contentType")`, `@RequestPart("data") MultipartFile`. Loads workorder `findById` (no lock needed — attachments are local operational fields, no status/sync_version mutation). Access = `requireSessionAccess` (same as 10.4: in-scope leader OR assigned executor, both sources allowed). Limits: filename ≤255, contentType non-blank, data ≤ `WorkorderEvidenceProperties.maxBytes()` (default 10 MB, 100 MB ceiling). Creates row, stores object, returns `WorkorderAttachmentView`. Writes audit CREATE `WORKORDER_ATTACHMENT`.
- **Replace** `PUT /api/v1/workorders/{id}/attachments/{attachmentId}` — same multipart body + access gate. Loads attachment by `(id, workOrderId)` (404 `ATTACHMENT_NOT_FOUND` if missing/mismatched). Delete previous Garage object, store new one, update row (filename/contentType/size/objectKey, updatedAt). Writes audit UPDATE (prev/new objectKey). Returns `WorkorderAttachmentView`.
- **Delete** `DELETE /api/v1/workorders/{id}/attachments/{attachmentId}` — access gate same as upload. Loads attachment, verifies `work_order_id` matches. Deletes Garage object, deletes row. Writes audit DELETE `WORKORDER_ATTACHMENT`. 404 when attachment not found or wrong workorder.
- **List** `GET /api/v1/workorders/{id}/attachments` — any authenticated user (workorder read posture). Returns `WorkorderAttachmentsView(workOrderId, attachments[])` ordered by `created_at ASC`. Each attachment view includes `presignedUrl` (fresh short-TTL). No scope gate on reads.
- **Get single** `GET /api/v1/workorders/{id}/attachments/{attachmentId}` — same as list, returns single view. 404 when not found.
- **Config:** `WorkorderEvidenceProperties` — `@ConfigurationProperties(prefix = "syncro.workorder.evidence")` record `maxBytes` (default 10 MB, ceiling 100 MB). `application.yml` entry `syncro.workorder.evidence.max-bytes`, `.env.example` var `SYNCRO_WORKORDER_EVIDENCE_MAX_BYTES:10485760`. Multipart servlet limit stays 10 MB (already sufficient).
- **Rego:** `workorder_evidence_paths := {"/api/v1/workorders/*/attachments", "/api/v1/workorders/*/attachments/*"}` allowed for {STAFF_MAINTENANCE, TECHNICIAN, SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE} (same role set as 10.4 sessions — service gate is authoritative). Reads (GET) covered by generic `read_allowed` + parity test. `.env.example` enforced-paths += both paths (already covered by `/api/v1/workorders/**` but explicit for parity — same pattern as 10.4 sessions).
- **Errors:** unknown workorder → 404 `WORKORDER_NOT_FOUND`; attachment not found → 404 `ATTACHMENT_NOT_FOUND`; access → 403 `FORBIDDEN`; validation (size/type/filename) → 400 `VALIDATION_ERROR` with fieldErrors; `ObjectStorageException` → 502 `OBJECT_STORAGE_ERROR`; storage failure during delete leaves row orphaned → retry deletes the row on second attempt when Garage object is already gone (`delete` is idempotent no-op).
- **Audit entity:** `WORKORDER_ATTACHMENT` added to `AuditEntityType`. Audit action: CREATE on upload, UPDATE on replace (prev/new objectKey), DELETE on delete. Audit entity_id = attachment UUID, entity_label = workorder id, plantId = workorder's machine plant.

**Block If:** nothing.

**Never:**
- Never touch V47/V48/V49 or add V51 — V50 is the only migration for 10.5.
- Never implement workorder GET list/detail (10.6), MTBF/dashboards (14.2), ratings (10.8), todos/kanban (10.7).
- Never store file bytes in PostgreSQL; only object keys (AD-10).
- Never mutate status or sync_version for attachments (attachments are local operational fields on both sources).
- Never accept client timestamps — all timestamps server-side `Instant.now(clock)`.
- Never persist presigned URLs; only object keys (AD-10).
- Never use base64; multipart upload only.
- Never add a new Garage bucket — reuse the existing `syncro-spareparts` bucket (or whatever `GARAGE_BUCKET` resolves to). Object key prefix `workorders/` keeps them separate.
- Never require a lock on the workorder row for attachment operations — no status/sync_version mutation, no serialization needed.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| ATTACHMENT_UPLOAD_OK | executor/leader, valid file ≤10MB | 200 `WorkorderAttachmentView`; Garage object stored; row persisted; audit CREATE | — |
| ATTACHMENT_UPLOAD_FORBIDDEN | not executor nor leader | 403 FORBIDDEN | — |
| ATTACHMENT_UPLOAD_TOO_LARGE | file >10MB | 400 VALIDATION_ERROR with fieldErrors | — |
| ATTACHMENT_UPLOAD_MISSING_FIELD | missing filename/contentType/part | 400 VALIDATION_ERROR (missing field) | — |
| ATTACHMENT_UPLOAD_NOT_FOUND | unknown workorder id | 404 WORKORDER_NOT_FOUND | — |
| ATTACHMENT_REPLACE_OK | PUT on existing attachment | 200 view; new Garage object stored; old object deleted; audit UPDATE (prev/new objectKey) | — |
| ATTACHMENT_REPLACE_NOT_FOUND | PUT on unknown/mismatched attachment | 404 ATTACHMENT_NOT_FOUND | — |
| ATTACHMENT_DELETE_OK | existing attachment | 204; Garage object deleted; row deleted; audit DELETE | — |
| ATTACHMENT_DELETE_NOT_FOUND | unknown attachment id | 404 ATTACHMENT_NOT_FOUND | — |
| ATTACHMENT_DELETE_WRONG_WORKORDER | attachment belongs to other WO | 404 ATTACHMENT_NOT_FOUND | — |
| ATTACHMENT_LIST | workorder with attachments | 200 {workOrderId, attachments[]} ordered by createdAt ASC | — |
| ATTACHMENT_LIST_EMPTY | workorder with no attachments | 200 {workOrderId, attachments: []} | — |
| ATTACHMENT_GET | existing attachment | 200 view with presignedUrl | — |
| ATTACHMENT_GET_NOT_FOUND | unknown | 404 ATTACHMENT_NOT_FOUND | — |
| OBJECT_STORAGE_DOWN | Garage unreachable | 502 OBJECT_STORAGE_ERROR | Rollback transaction (no row persisted) |
| STORAGE_FAIL_AFTER_DELETE | delete Garage OK, save row fails | 500; Garage object orphaned; retry succeeds (delete idempotent) | — |

</intent-contract>

## Code Map

**Migration:**
- `resources/db/migration/V50__workorder_attachments.sql` -- NEW -- workorder_attachments table + FK + audit CHECK widen.

**Config:**
- `com/syncro/config/WorkorderEvidenceProperties.java` -- NEW -- maxBytes, @ConfigurationProperties(prefix="syncro.workorder.evidence").

**Service:**
- `com/syncro/maintenance/application/WorkOrderEvidenceService.java` -- NEW -- create/replace/delete/list/get + validation + access gate (reuse `requireSessionAccess` via delegating to WorkOrderService or inline gate) + Garage integration + audit.
- `com/syncro/maintenance/application/WorkOrderService.java` -- MODIFY -- make `requireSessionAccess` package-visible or add EvidenceService as a separate class that calls WorkOrderService for the workorder lookup + access gate. Prefer a separate service that calls `workOrders.findById()` + `machines.findByIdWithPlantAndGroup()` + inline `isInScopeLeader`/`isExecutor` (same logic as WorkOrderService, extracted or duplicated as a small gate helper). Since the access gate is already private in WorkOrderService, simplest: extract the gate logic into a package-private `WorkOrderAccessHelper` or just duplicate the ~10 lines in EvidenceService — the gate is small and stable.

Alternative (simpler): let `WorkOrderEvidenceService` call `workOrders.findById()` + `machines.findByIdWithPlantAndGroup()` + inline the exact same `isInScopeLeader`/`isExecutor`/`groupInScope` logic. This duplicates ~15 lines across 2 services but avoids refactoring the existing 10.4 code. The gate is stable (no expected changes), and the scope helpers (`scopes.derive`, `plantScopes`) are already injected.

**Domain:**
- `com/syncro/maintenance/domain/workorder/WorkorderAttachment.java` -- NEW -- domain record (id, workOrderId, filename, contentType, objectKey, sizeBytes, uploadedBy, createdAt, updatedAt).

**Persistence:**
- `com/syncro/maintenance/infrastructure/db/WorkorderAttachmentEntity.java` -- NEW -- JPA entity.
- `com/syncro/maintenance/infrastructure/db/WorkorderAttachmentRepository.java` -- NEW -- findByWorkOrderIdOrderByCreatedAtAsc, findByIdAndWorkOrderId.

**API:**
- `com/syncro/maintenance/api/WorkOrderController.java` -- MODIFY -- POST /{id}/attachments, PUT /{id}/attachments/{attachmentId}, GET /{id}/attachments, GET /{id}/attachments/{attachmentId}, DELETE /{id}/attachments/{attachmentId}.
- `com/syncro/maintenance/api/WorkOrderDtos.java` -- MODIFY -- WorkorderAttachmentView, WorkorderAttachmentsView.
- `com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- MODIFY -- ATTACHMENT_NOT_FOUND + OBJECT_STORAGE_ERROR mappings; DataIntegrityViolationException attachment FK backstop (optional, generic 500 is fine).

**Audit:**
- `com/syncro/audit/domain/AuditEntityType.java` -- MODIFY -- WORKORDER_ATTACHMENT.

**Enforcement:**
- `syncro/authz/policy/authz.rego` -- MODIFY -- workorder_evidence_paths + role rule.
- `syncro/authz/policy/authz_test.rego` -- MODIFY -- parity cases (technician/staff allowed; auditor denied; read allowed).
- `syncro/.env.example` -- MODIFY -- evidence paths in enforced-paths (already covered by `/api/v1/workorders/**` but add explicit for parity).

**Tests:**
- `com/syncro/maintenance/application/WorkOrderEvidenceServiceTest.java` -- NEW -- upload/replace/delete/list/get matrix, size/type validation, access gates, audit, storage failure.
- `com/syncro/maintenance/api/WorkOrderControllerTest.java` -- MODIFY -- evidence endpoint 200/403/404/400/502 shapes.
- `com/syncro/db/WorkorderAttachmentsMigrationTest.java` -- NEW -- V50 table/columns/FK/audit CHECK (Testcontainers).

## Tasks & Acceptance

**Execution:**
- [x] `resources/db/migration/V50__workorder_attachments.sql` -- create workorder_attachments table + FK + index + audit CHECK widen.
- [x] `WorkorderEvidenceProperties.java` -- maxBytes config record.
- [x] `WorkorderAttachment.java` + `WorkorderAttachmentEntity.java` + `WorkorderAttachmentRepository.java` -- domain record, JPA entity, repo (findByWorkOrderIdOrderByCreatedAtAsc, findByIdAndWorkOrderId).
- [x] `WorkOrderEvidenceService.java` -- create/replace/delete/list/get + validation + access gate (inline isInScopeLeader/isExecutor) + Garage + audit.
- [x] `WorkOrderController.java` + DTOs + exception handler -- evidence endpoints + ATTACHMENT_NOT_FOUND + OBJECT_STORAGE_ERROR.
- [x] `AuditEntityType.WORKORDER_ATTACHMENT` -- enum value.
- [x] `authz.rego` + `authz_test.rego` + `.env.example` -- workorder_evidence_paths + parity.
- [x] Tests (service + controller + migration).

**Acceptance Criteria:**
- Given an authorized user (executor or in-scope leader) uploads a file to a workorder, when the upload is submitted, then the file is stored in Garage via ObjectStorageService, the attachment record is persisted with object key only, and the presigned URL is returned in the view. [FR-116/AD-10]
- Given an attachment exists on a workorder, when the same attachment record is replaced, then the previous Garage object is deleted before the new one is stored (replace, not append).
- Given an out-of-scope or over-limit upload, when it is submitted, then a standard validation/permission error is returned and nothing is persisted.
- Given OPA enforcement, then the evidence mutation paths are default-deny with the same executor/leader role set as 10.4 sessions, with parity tests. [FR-160]
- Given a workorder with attachments, when the attachment list is requested, then all attachments are returned ordered by createdAt ASC with presigned URLs, and any authenticated user may read.

## Spec Change Log

<!-- Append-only. Populated by step-04 during review loops. -->

## Review Triage Log

### 2026-08-26 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 7 (high 1, medium 3, low 3)
- defer: 1 (medium 1)
- reject: 9
- addressed_findings:
  - `[high]` `[patch]` contentType length unchecked against VARCHAR(100) column — added `else if (contentType.length() > 100)` in `validate()`; added `@Size(max=100)` on controller params; added SVC-020 test.
  - `[medium]` `[patch]` Replace key lacked UUID segment — 8-4 pattern adds UUID for cache-busting and to avoid a download window during delete→store. Added `UUID.randomUUID()` to `buildKey()`.
  - `[medium]` `[patch]` Dead `PlantScopeService` injection — removed field, constructor param, and test mock.
  - `[medium]` `[patch]` Equal servlet and domain limits (10MB=10MB) — `max-request-size` overhead truncates the domain range; raising the property past 10MB is silently dead. Raised servlet limit to 25MB above the 10MB domain limit, updated comment.
  - `[low]` `[patch]` Missing exception handlers for `MultipartException`, `HttpMediaTypeNotSupportedException`, `MethodArgumentTypeMismatchException` — added three handlers returning 400 `VALIDATION_ERROR` with fieldErrors.
  - `[low]` `[patch]` Missing `@Size(max=255)` on filename param in controller — added to both POST and PUT endpoints.
  - `[low]` `[patch]` Test gaps: added SVC-020 (contentType too long), SVC-021 (replace store failure), SVC-014b (empty list).

## Design Notes

- **Access gate for attachments:** Same as repair sessions (10.4): in-scope leader OR assigned executor. Attachments are local operational fields (AD-3 "preserved"), allowed on both SYNCED and INTERNAL sources. No lock needed — no status/sync_version mutation.
- **Replace via PUT, not POST:** `PUT /{id}/attachments/{attachmentId}` replaces the file on an existing attachment record (delete-before-store, 8-4 pattern). This is a separate endpoint from POST (create). The client must know the attachment ID to replace it, which they get from the list response.
- **No lock on upload:** Unlike session start/stop, attachment operations don't need `findByIdForUpdate` — they never read or write workorder status, sync_version, or any other field that could race with transitions. A plain `findById` is sufficient.
- **Key format:** `"workorders/{workOrderId}/{attachmentId}/{uuid}.{ext}"` — the attachment UUID groups objects per attachment; the random UUID segment (8-4 pattern) makes every replacement a fresh object URL so browsers never serve a stale cached file and the old object is only deleted after the new one is stored (no download window on a reused key).
- **Garage bucket:** Reuses the existing `GARAGE_BUCKET` (default `syncro-spareparts`). The `workorders/` prefix keeps them separate from sparepart images (`spareparts/`). No new bucket needed.
- **Content type:** Accept any non-blank IANA media type (contrast with sparepart images which restrict to `image/*`). The epic context lists JPEG/PNG/WebP/PDF as the canonical set, but a hard whitelist would reject legitimate drawing CAD/PDF variants; the 10 MB size limit is the enforcement boundary. Frontend (later story) may restrict its picker to the canonical set.
- **Storage failure on delete:** If Garage delete succeeds but the DB row delete fails (transaction rollback), the Garage object is orphaned. This is acceptable — retrying the delete succeeds (Garage delete is idempotent). The object key format is deterministic so an orphaned object doesn't leak sensitive data.
- **Audit entity_id:** The attachment UUID is the real UUID (not hashed), unlike workorder VARCHAR PKs.

## Verification

**Commands:**
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrderEvidenceServiceTest,WorkOrderControllerTest,WorkorderAttachmentsMigrationTest,WorkOrderServiceTest"` -- expected BUILD SUCCESS (per-class JVM).
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS incl. new evidence parity cases.
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green (no frontend changes).

## Auto Run Result

| Step | Outcome | Notes |
|------|---------|-------|
| 01 route | pass | epic 10, story 5; epic-10-context valid; spec-10-4 continuity loaded; clean tree on main |
| 02 plan | pass | spec-10-5 written (V50, evidence endpoints, Garage/replace/delete, access gate, rego) |
| 03 implement | pass | full backend impl + tests; 4 test defects fixed in first run (unmocked presign, replace assertion); verified independently |
| 04 review | pass | Blind Hunter (6 findings) + Edge Case Hunter (12 findings); deduped to 7 patches (high 1, medium 3, low 3), 1 defer (DW-140), 9 rejects |
| finalize | this commit | status done; followup_review_recommended: true |

**Summary:** Evidence uploads (before/after photos, technical drawings) for workorders with V50 workorder_attachments table (object-key-only, Garage-backed), upload/replace/delete/list/get endpoints gated on executor/leader access (same as 10.4 sessions), multipart upload pattern adapted from 8-4 sparepart images. Evidence is a local operational field on both SYNCED and INTERNAL sources, never touching status or sync_version.

**Files changed (7 new, 8 modified):**
- NEW `V50__workorder_attachments.sql` -- table + FK + index + audit CHECK.
- NEW `WorkorderEvidenceProperties.java` -- maxBytes config (10 MB default, 100 MB ceiling).
- NEW `WorkorderAttachment.java` / `WorkorderAttachmentEntity.java` / `WorkorderAttachmentRepository.java` -- domain + JPA + repo.
- NEW `WorkOrderEvidenceService.java` -- create/replace/delete/list/get + validation + access gate + Garage + audit.
- NEW `WorkOrderEvidenceServiceTest.java` (22 tests) + `WorkorderAttachmentsMigrationTest.java` (6 tests).
- MOD `WorkOrderController.java` -- POST/PUT/GET/DELETE evidence + @Size params.
- MOD `WorkOrderDtos.java` -- WorkorderAttachmentView, WorkorderAttachmentsView.
- MOD `WorkOrderExceptionHandler.java` -- ATTACHMENT_NOT_FOUND, OBJECT_STORAGE_ERROR, multipart/type-mismatch handlers.
- MOD `AuditEntityType.java` -- WORKORDER_ATTACHMENT.
- MOD `application.yml` -- servlet limit 25MB, comment.
- MOD `authz.rego` / `authz_test.rego` -- evidence paths + 10 parity cases.
- MOD `.env.example` -- enforced-paths.
- MOD `WorkOrderControllerTest.java` -- 12 evidence endpoint tests.

**Review findings:** patches applied 7 (high 1: contentType length check; medium 3: UUID segment in key, dead PlantScopeService, servlet limit above domain; low 3: exception handlers, @Size params, test gaps). Deferred 1 (DW-140: no Garage integration test). Rejected 9 (dangling-row by-design, no-lock spec-sanctioned, ON DELETE CASCADE, status gate intentional, memory, list N+1, trailing-slash OPA 403, rego coarse-gate, concurrent-replace last-write-wins).

**Verification:**
- `mvnd -o -f pom.xml test "-Dtest=WorkOrderEvidenceServiceTest,WorkOrderControllerTest,WorkorderAttachmentsMigrationTest,WorkOrderServiceTest,WorkOrderRepairSessionServiceTest,WorkOrderTransitionServiceTest"` -- BUILD SUCCESS, 149/149.
- `cd syncro/authz && ./run-opa-test.ps1` -- PASS 91/91.
- `cd syncro/apps/web && npx tsc --noEmit` -- green.

**Residual risks:** dangling-row/orphan on storage failure mid-replace (8-4 parity, retry contract); no Garage integration test (DW-140).