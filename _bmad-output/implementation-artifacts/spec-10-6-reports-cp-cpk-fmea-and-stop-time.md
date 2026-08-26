---
title: 'Reports, CP/CPK, FMEA & Stop-Time'
type: 'feature'
created: '2026-08-26'
status: 'done'
baseline_revision: ce846c8
final_revision: ba91625
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-5-evidence-and-technical-drawings.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Workorders (10-1..10-5) have no report narrative, CP/CPK capability data, FMEA failure tagging, or stop-time reason capture — a closed workorder cannot carry full maintenance evidence, and future MTBF/FMEA analysis has no source data (FR-117, FR-118, FR-122).

**Approach:** Add report fields to `work_orders` (V51): a four-section narrative (chronological/analyze/corrective/preventive), optional CP/CPK values + an optional CP/CPK PDF (Garage object-key pattern from 10-5), an optional FMEA failure-type tag, and an optional stop-time reason code + detail. Expose `PUT /{id}/report`, `GET /{id}/report`, and a CP/CPK PDF upload endpoint, all gated on the same executor/leader access as 10-4/10-5. Stop-time reason becomes a hard DONE gate for breakdown-category workorders.

## Boundaries & Constraints

**Always:**
- **V51** (additive, on V50): `ALTER TABLE work_orders ADD COLUMN` — `report_chronological TEXT`, `report_analyze TEXT`, `report_corrective TEXT`, `report_preventive TEXT`, `cp_cp_lower NUMERIC(8,4)`, `cp_cp_upper NUMERIC(8,4)`, `cpk NUMERIC(8,4)`, `cpk_pdf_object_key VARCHAR(255)`, `fmea_failure_type VARCHAR(30)`, `stop_time_reason VARCHAR(30)`, `stop_time_detail VARCHAR(500)`. CHECK `ck_work_orders_stop_time_reason` — `stop_time_reason IN ('ELECTRIC','MECHANICAL','PNEUMATIC','HYDRAULIC','OTHER')` (NULL allowed). No new table. Audit `ck_audit_log_entity_type` unchanged — report writes audit under the existing `WORK_ORDER` entity type.
- **CP/CPK PDF** uses the 10-5 pattern: object-key-only, Garage bucket `GARAGE_BUCKET`, key prefix `workorders/{workOrderId}/cpk/{uuid}.pdf`. Replace deletes the old object first. Delete of the CPK PDF is `DELETE /{id}/report/cpk` (removes object + clears `cpk_pdf_object_key`).
- **Report write** `PUT /api/v1/workorders/{id}/report` — JSON body with all four narrative fields + optional CP/CPK values + FMEA + stop-time. Report is a **local operational field** (AD-3 "preserved"): allowed on both SYNCED and INTERNAL, never touches status or sync_version. No lock on the workorder row (same as 10.5 attachments — no status/sync mutation).
- **Report read** `GET /api/v1/workorders/{id}/report` — any authenticated user (workorder read posture). Returns the four narratives + CP/CPK values + `cpkPdfPresignedUrl` (fresh short-TTL) + FMEA + stop-time.
- **CP/CPK PDF upload** `PUT /api/v1/workorders/{id}/report/cpk` — multipart `@RequestPart("data") MultipartFile`, `@RequestParam("filename")`, `@RequestParam("contentType")`, `@Size` guards like 10-5. Size limit = `WorkorderEvidenceProperties.maxBytes()` (10 MB default). Content type restricted to `application/pdf` (PDF-only; the 10-5 "any IANA" latitude is for evidence attachments, not the capability sheet). Returns the report view with fresh presigned URL.
- **Access gate:** report write + CPK upload use the exact 10-4/10-5 gate (in-scope leader OR assigned executor, both sources). Report read is any-authenticated.
- **Stop-time DONE gate (FR-122):** when the workorder's category code is `01` (Breakdown) and `toStatus == DONE`, `stop_time_reason` must be non-null — else 400 `STOP_TIME_REASON_REQUIRED`. The gate reads the category via `workOrders.findByIdForUpdate` + category lookup; enforced in `WorkOrderService.transition` alongside the existing `enforceDoneGate`. Optional on every other category.
- **Rego:** `workorder_report_paths := {"/api/v1/workorders/*/report", "/api/v1/workorders/*/report/cpk"}` — five-role allow set `{MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE, TECHNICIAN}` (same parity as 10.4/10.5; service gate is authoritative). Reads (GET) flow through generic `read_allowed`. `.env.example` enforced-paths += both paths.
- **Errors:** unknown workorder → 404 `WORKORDER_NOT_FOUND`; access → 403 `FORBIDDEN`; validation (size/type/filename/narrative length/CPK range) → 400 `VALIDATION_ERROR` with fieldErrors; breakdown DONE without stop-time → 400 `STOP_TIME_REASON_REQUIRED`; `ObjectStorageException` → 502 `OBJECT_STORAGE_ERROR`; storage failure during CPK replace leaves old object orphaned → retry is idempotent no-op.
- **Audit:** report write → `WORK_ORDER` UPDATE with prev/new values; CPK PDF upload → `WORK_ORDER` UPDATE with prev/new `cpkPdfObjectKey`; CPK PDF delete → `WORK_ORDER` UPDATE clearing the key. entity_id = UUID-nameUUID (existing `auditEntityId`).

**Block If:** nothing.

**Never:**
- Never touch V47-V50 or add V52 — V51 is the only migration for 10.6.
- Never implement workorder GET list/detail (10.6 does NOT include the list/detail views — that stays deferred), MTBF/dashboards (14.2), ratings (10.8), todos/kanban (10.7), or preventive reports (Epic 13).
- Never build a full FMEA module (RPN worksheets, severity/occurrence/detection) — v1 tags only (architecture Deferred: "Full FMEA module — v1 only tags workorders with an FMEA failure type").
- Never store file bytes in PostgreSQL — only object keys (AD-10).
- Never mutate status or sync_version for report operations.
- Never accept client timestamps — all timestamps server-side `Instant.now(clock)`.
- Never persist presigned URLs.
- Never add a new Garage bucket — reuse `GARAGE_BUCKET`; `workorders/{id}/cpk/` prefix keeps CPK PDFs separate from attachments.
- Never require a lock on the workorder row for report ops (same rationale as 10.5).
- Never make CP/CPK mandatory on any category — FR-117 is explicit: optional, and a workorder can close without it.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| REPORT_WRITE_OK | executor/leader, valid JSON | 200 `WorkOrderReportView`; 4 narratives + optional fields persisted; audit UPDATE | — |
| REPORT_WRITE_FORBIDDEN | not executor nor leader | 403 FORBIDDEN | — |
| REPORT_WRITE_NOT_FOUND | unknown workorder | 404 WORKORDER_NOT_FOUND | — |
| REPORT_WRITE_TOO_LONG | narrative >4000 chars | 400 VALIDATION_ERROR fieldErrors | — |
| REPORT_WRITE_INVALID_CPK | cpCkLower/cpCkUpper/cpk negative | 400 VALIDATION_ERROR fieldErrors | — |
| REPORT_WRITE_INVALID_FMEA | fmeaFailureType not in enum | 400 VALIDATION_ERROR fieldErrors | — |
| REPORT_WRITE_INVALID_STOP | stopTimeReason not in enum | 400 VALIDATION_ERROR fieldErrors | — |
| REPORT_READ_OK | any authenticated user | 200 view with cpkPdfPresignedUrl (fresh TTL) | — |
| REPORT_READ_NOT_FOUND | unknown workorder | 404 WORKORDER_NOT_FOUND | — |
| CPK_UPLOAD_OK | executor/leader, PDF ≤10MB | 200 view; Garage object stored; `cpk_pdf_object_key` set; audit UPDATE | — |
| CPK_UPLOAD_NON_PDF | content-type not application/pdf | 400 VALIDATION_ERROR fieldErrors | — |
| CPK_UPLOAD_TOO_LARGE | >10MB | 400 VALIDATION_ERROR fieldErrors | — |
| CPK_UPLOAD_FORBIDDEN | not executor nor leader | 403 FORBIDDEN | — |
| CPK_REPLACE_OK | existing key, new PDF | 200 view; old object deleted then new stored; audit prev/new key | — |
| CPK_DELETE_OK | existing key | 200 view (cleared); object deleted; audit UPDATE | — |
| CPK_DELETE_IDEMPOTENT | no key stored | 200 view unchanged (no-op) | — |
| STOP_TIME_GATE_OK | breakdown DONE with stopTimeReason | DONE proceeds; audit reason | — |
| STOP_TIME_GATE_BLOCKED | breakdown DONE, no stopTimeReason | 400 STOP_TIME_REASON_REQUIRED | — |
| STOP_TIME_GATE_NON_BREAKDOWN | non-01 category DONE, no reason | DONE proceeds (reason optional) | — |
| OBJECT_STORAGE_DOWN | Garage unreachable on CPK ops | 502 OBJECT_STORAGE_ERROR | Rollback transaction (no row persisted) |

</intent-contract>

## Code Map

**Migration:**
- `resources/db/migration/V51__workorder_reports_cpk_fmea_stop_time.sql` -- NEW -- 10 additive columns + stop_time_reason CHECK.

**Domain:**
- `com/syncro/maintenance/domain/workorder/WorkOrder.java` -- MODIFY -- add report/cpCk/cpk/fmea/stopTime fields to the record.
- `com/syncro/maintenance/domain/workorder/WorkOrderStatus.java` -- UNCHANGED (no new status).

**Persistence:**
- `com/syncro/maintenance/infrastructure/db/WorkOrderEntity.java` -- MODIFY -- 10 new mapped columns + getters/setters.
- `com/syncro/maintenance/infrastructure/db/WorkOrderRepository.java` -- UNCHANGED (findById suffices; no lock).

**Application:**
- `com/syncro/maintenance/application/WorkOrderService.java` -- MODIFY -- `saveReport`, `getReport`, `uploadCpkPdf`, `deleteCpkPdf` (or a sibling `WorkOrderReportService` following the 10-5 evidence-service precedent); inline executor/leader gate; breakdown stop-time gate added to `transition` (V51 columns on entity); new exceptions `StopTimeReasonRequiredException`, `WorkOrderReportValidationException`.
- `com/syncro/maintenance/application/WorkOrderMapper.java` -- MODIFY -- map new domain fields.

**API:**
- `com/syncro/maintenance/api/WorkOrderController.java` -- MODIFY -- `PUT /{id}/report`, `GET /{id}/report`, `PUT /{id}/report/cpk`, `DELETE /{id}/report/cpk`.
- `com/syncro/maintenance/api/WorkOrderDtos.java` -- MODIFY -- `SaveWorkOrderReportRequest`, `WorkOrderReportView`, CPK multipart params.
- `com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- MODIFY -- `STOP_TIME_REASON_REQUIRED` + report validation handler.

**Enforcement:**
- `syncro/authz/policy/authz.rego` -- MODIFY -- `workorder_report_paths` + five-role rule (mirror evidence block).
- `syncro/authz/policy/authz_test.rego` -- MODIFY -- parity cases (technician/staff allowed; auditor denied; GET allowed).
- `syncro/.env.example` -- MODIFY -- report paths in enforced-paths.

**Config:**
- `com/syncro/config/WorkorderEvidenceProperties.java` -- UNCHANGED (CPK PDF reuses `maxBytes`).

**Tests:**
- `com/syncro/maintenance/application/WorkOrderServiceTest.java` -- MODIFY -- report save/get/CPK/stop-time gate tests.
- `com/syncro/maintenance/api/WorkOrderControllerTest.java` -- MODIFY -- report endpoint shapes 200/400/403/404/502.
- `com/syncro/db/WorkorderReportsMigrationTest.java` -- NEW -- V51 columns/CHECK (Testcontainers).

## Tasks & Acceptance

**Execution:**
- [x] `resources/db/migration/V51__workorder_reports_cpk_fmea_stop_time.sql` -- add 10 columns + CHECK.
- [x] `WorkOrder.java` / `WorkOrderEntity.java` / `WorkOrderMapper.java` -- new fields mapped.
- [x] `WorkOrderService.java` -- saveReport/getReport/uploadCpkPdf/deleteCpkPdf + gate + stop-time DONE gate.
- [x] `WorkOrderController.java` + DTOs + exception handler -- report endpoints + STOP_TIME_REASON_REQUIRED.
- [x] `authz.rego` + `authz_test.rego` + `.env.example` -- workorder_report_paths + parity.
- [x] Tests (service + controller + migration).

**Acceptance Criteria:**
- Given an authorized user (executor or in-scope leader) writes a workorder report, when the report is submitted, then the four-section narrative (chronological/analyze/corrective/preventive) is persisted and appears in the report view; optional CP/CPK values, FMEA failure type, and stop-time reason are stored when provided. [FR-117/FR-118/FR-122]
- Given a workorder report exists without CP/CPK values or PDF, when the workorder is completed, then DONE is not blocked — CP/CPK is never mandatory on any category. [FR-117]
- Given a user uploads a CP/CPK PDF to a workorder, when the upload is submitted, then the PDF is stored in Garage via ObjectStorageService (key `workorders/{id}/cpk/{uuid}.pdf`), the object key is persisted, and the report view exposes a fresh presigned URL. [FR-117/AD-10]
- Given a breakdown workorder (category code `01`) is being completed without a stop-time reason code, when DONE is submitted, then DONE is blocked with `STOP_TIME_REASON_REQUIRED`; the reason (electric/mechanical/pneumatic/hydraulic/other) is required. [FR-122]
- Given a non-breakdown workorder is being completed without a stop-time reason, when DONE is submitted, then DONE proceeds — stop-time reason is optional outside breakdown.
- Given a report or CPK mutation is attempted by an out-of-scope user or over-limit file, when submitted, then a standard validation/permission error is returned and nothing is persisted.
- Given OPA enforcement, then the report mutation paths are default-deny with the same executor/leader role set as 10.4/10.5 sessions, with parity tests. [FR-160]

## Spec Change Log

<!-- Append-only. Populated by step-04 during review loops. -->

### 2026-08-26 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 5 (high 2, low 3)
- defer: 3 (low 3)
- reject: 8
- addressed_findings:
  - `[high]` `[patch]` Missing FMEA `failure_type` CHECK constraint in V51 — added `ck_work_orders_fmea_failure_type` with same enum values as stop_time_reason.
  - `[high]` `[patch]` NUMERIC(8,4) overflow causes 500 instead of 400 — added `precision()-scale() > 4 || scale() > 4` guard in `validateNonNegative` with new SVC-007/SVC-008 tests.
  - `[low]` `[patch]` `cpk` column with no explicit name — added `@Column(name = "cpk")` in entity.
  - `[low]` `[patch]` No test for PUT-as-overwrite clearing report fields — added SVC-009 verifying all sections nulled except CPK key.
  - `[low]` `[patch]` Missing controller test for GET /report 404 — added API-005b.
  - `[low]` `[patch]` Migration test/doc says "10 columns" but V51 adds 11 — corrected comments in V51.sql and test class.
  - `[low]` `[defer]` Concurrent full-row UPDATE clobbers report fields (pre-existing 10-5 pattern) — no lock on report ops by design.
  - `[low]` `[defer]` CPK upload race on concurrent replace (pre-existing 10-5 pattern) — last-writer-wins.
  - `[low]` `[defer]` No PDF magic byte check (defense-in-depth, not in contract).
## Auto Run Result

<!-- Append-only. Populated by step-04 on EVERY review pass. -->

## Design Notes

- **No new table.** Unlike 10-4/10-5, the report is a set of nullable columns on `work_orders` — the report is a single aggregate field written atomically with the workorder, and the 14.2 print view reads it alongside the workorder row. A separate `workorder_reports` table would add a join for no isolation benefit (report is not lifecycle-independent).
- **CP/CPK PDF reuses the evidence key pattern but with a fixed `{uuid}.pdf` suffix and the 10-5 replace semantics** (delete old before store new). No `contentType` param needed — PDF-only; the multipart handler validates `application/pdf` from the part's declared content type.
- **Stop-time gate placement:** `WorkOrderService.transition` reads the category via `categories.findByCode(BREAKDOWN_CATEGORY_CODE)` to determine breakdown; a workorder with no category is treated as non-breakdown (gate never blocks). The gate runs after the state-machine edge check, before persisting the transition — same point as the existing `enforceDoneGate`.
- **FMEA tag is a plain column, not an FK.** v1 tags only (architecture Deferred); the enum values (ELECTRIC/MECHANICAL/PNEUMATIC/HYDRAULIC/OTHER) are CHECK-constrained so the report and machine-history surfaces stay stable. A future full-FMEA module (Epic 14) can migrate the column to an FK.
- **CP/CPK values are NUMERIC(8,4)** — capability indices are small decimals; 4 fractional digits is precision-safe, and the application layer validates ≥0 (indices are non-negative).
- **Report read is any-authenticated** (workorder read posture), same as attachments.

## Verification

**Commands:**
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrderServiceTest,WorkOrderControllerTest,WorkorderReportsMigrationTest"` -- expected BUILD SUCCESS.
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS incl. new report parity cases.
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green (no frontend changes).

## Auto Run Result

| Step | Outcome | Notes |
|------|---------|-------|
| 01 route | pass | epic 10, story 6; epic-10-context valid; spec-10-5 continuity loaded; clean tree on main |
| 02 plan | pass | spec-10-6 written (V51 columns, report endpoints, CPK PDF Garage pattern, stop-time gate, rego) |
| 03 implement | pass | full backend impl + tests; 188/188 verified independently |
| 04 review | pass | Blind Hunter (16 findings) + Edge Case Hunter (6 findings); deduped to 5 patches (high 2, low 3), 3 defers (DW-123/124/125), 8 rejects |
| finalize | this commit | status done; followup_review_recommended: false |

**Summary:** Workorder reports with four-section narrative (chronological/analyze/corrective/preventive), optional CP/CPK values + an optional CP/CPK PDF (Garage object-key-only, `workorders/{id}/cpk/{uuid}.pdf`), an optional FMEA failure-type tag, and an optional stop-time reason + detail. V51 adds 11 additive nullable columns plus `stop_time_reason` and `fmea_failure_type` CHECK constraints. Four endpoints (PUT/GET `/{id}/report`, PUT/DELETE `/{id}/report/cpk`) gated on the same executor/leader access as 10-4/10-5; reads any-authenticated. Breakdown (category `01`) workorders require a stop-time reason before DONE (FR-122). Report/CPK are local operational fields — never touching status or sync_version (AD-3 preserved).

**Files changed (7 new, 13 modified):**
- NEW `V51__workorder_reports_cpk_fmea_stop_time.sql` -- 11 columns + 2 CHECK constraints.
- NEW `FmeaFailureType.java` / `StopTimeReason.java` -- domain enums.
- NEW `WorkOrderReportService.java` -- saveReport/getReport/uploadCpkPdf/deleteCpkPdf + gate + Garage + audit.
- NEW `WorkOrderReportServiceTest.java` (22 tests) + `WorkorderReportsMigrationTest.java` (7 tests).
- MOD `WorkOrder.java` / `WorkOrderEntity.java` / `WorkOrderMapper.java` -- 11 new fields mapped.
- MOD `WorkOrderService.java` -- enforceBreakdownStopTimeGate in transition + stop-time audit values.
- MOD `WorkOrderController.java` / `WorkOrderDtos.java` / `WorkOrderExceptionHandler.java` -- 4 endpoints + STOP_TIME_REASON_REQUIRED + report validation.
- MOD `authz.rego` / `authz_test.rego` -- workorder_report_paths + 12 parity cases.
- MOD `.env.example` -- report paths in enforced-paths.
- MOD `WorkOrderControllerTest.java` (62 tests) / `WorkOrderServiceTest.java` / `WorkOrderTransitionServiceTest.java` -- stop-time gate + endpoint shapes.

**Review findings:** patches applied 5 (high 2: FMEA CHECK constraint, NUMERIC(8,4) overflow guard; low 3: explicit cpk column name, PUT-as-overwrite clear test, GET /report 404 test). Deferred 3 (DW-123: report full-row UPDATE race, DW-124: CPK replace race, DW-125: PDF magic-byte check). Rejected 8 (SUPER_ADMIN OPA false alarm — the `allow if super_admin` rule covers all paths, CPK/evidence key-namespace collision, presigned-URL-on-save cost, orphaned-object-window on replace, audit-failure rollback, stopTimeDetail gate asymmetry, .env.example glob redundancy, @Valid double-validation).

**Verification:**
- `mvnd -o test "-Dtest=WorkOrderReportServiceTest,WorkOrderControllerTest,WorkorderReportsMigrationTest,WorkOrderServiceTest,WorkOrderTransitionServiceTest"` -- BUILD SUCCESS, 156/156.
- `mvnd -o test "-Dtest=WorkOrderReportServiceTest,WorkOrderControllerTest,WorkorderReportsMigrationTest,WorkOrderServiceTest,WorkOrderTransitionServiceTest,WorkOrderEvidenceServiceTest,WorkOrderRepairSessionServiceTest"` (pre-patch) -- BUILD SUCCESS, 188/188.
- `cd syncro/authz && ./run-opa-test.ps1` -- PASS 103/103.
- `cd syncro/apps/web && npx tsc --noEmit` -- green (no frontend changes).

**Residual risks:** no Garage integration test for CPK PDF (same gap as 10-5, DW-140); report full-row UPDATE race + CPK replace race (DW-123/124); PDF magic-byte check deferred (DW-125); stop-time gate is a hard behavior change for in-flight Breakdown workorders that were opened before this story and carry no reason — they must edit their report before DONE (deployment note).
