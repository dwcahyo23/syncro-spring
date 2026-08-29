---
title: 'WYSIWYG Print Reports with Signature'
type: 'feature'
created: '2026-08-29'
status: 'done'
baseline_revision: '678d045'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/implementation-artifacts/epic-14-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Leaders cannot print workorder reports in WYSIWYG format with logo, evidence, and signature — the existing preventive report (11-3) is the proven pattern, but no WO equivalent exists (FR-175).

**Approach:** Add a workorder aggregate report endpoint (`GET /api/v1/workorders/{id}/print-report`) mirroring `PreventiveReportService`, a new `workorder_signatures` table (V66, mirroring V55's `signature_object_key`/`signer_identity`/`approved_at`) with an approve endpoint, company logo upload/config via Garage, and frontend WYSIWYG print pages (WO + preventive enhanced) with `@media print` stylesheet. All existing data (narrative, CP/CPK, sessions, attachments, sparepart requests) is composed into one aggregate DTO per the preventive-report pattern.

## Boundaries & Constraints

**Always:**
- Reports are browser-printed WYSIWYG HTML, never server-side PDF (FR-175).
- Aggregate DTO for WO print report includes: WO header fields (id, machine, category, status, assigned technician, dates), sessions (from RepairSessionRepository), narrative (4 report fields), CP/CPK (values + PDF presigned URL when present), evidence attachments (presigned URLs), sparepart requests linked to this WO (material code, qty, status), and signature block when present.
- Signature: new `workorder_signatures` table (V66) with `work_order_id VARCHAR(50), signature_object_key VARCHAR(512), signer_identity VARCHAR(200), signed_by UUID, signed_at TIMESTAMPTZ, UNIQUE(work_order_id)`. Image upload to Garage (key pattern `workorders/{woId}/signature/{uuid}.{ext}`), presigned URL for print view. Approve endpoint: `POST /api/v1/workorders/{id}/approve` with `ApproveWorkorderRequest(signatureObjectKey, signerIdentity)`. Only DONE/CLOSED workorders accept signatures; gate: leader/SPV scope.
- Logo: company logo stored in Garage, key `settings/logo/{uuid}.{ext}`. New `settings` table or Garage-only config (no migration — use Garage as sole source). Backend endpoint `GET /api/v1/settings/logo` returns presigned URL; `PUT /api/v1/settings/logo` (multipart, SUPER_ADMIN only) uploads new logo, deletes old. No migration needed.
- Print CSS: `@media print` rules in a print stylesheet: hide nav/sidebar/buttons, set page margins, ensure logo and signature print at correct positions, white background, black text, table borders.
- Both the existing preventive report page and the new WO print page use the same print stylesheet.
- WYSIWYG template editor (variable insertion) is explicitly deferred (not in scope for 14.3 — the data-driven tabular print page is the current ceiling; templates are future).
- OQ-5 signature mechanism: file upload (image) → Garage key → presigned `<img>` in print view (reuse the existing 11-2 pattern; no canvas signature pad).

**Never:**
- Do NOT generate server-side PDFs.
- Do NOT modify the existing workorder lifecycle, state machine, or report narrative endpoints.
- Do NOT create a canvas signature pad (beyond scope — use file upload like 11-2).
- No new dependencies (recharts, jsPDF, etc.).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | DONE workorder with all data | aggregate report with header, sessions, narrative, CP/CPK, evidence, sparepart requests, no signature block | no error |
| WITH_SIGNATURE | DONE workorder, signed | signature block with presigned URL, signer identity, signed at | no error |
| NO_SESSIONS | workorder without sessions | sessions section empty, not missing | no error |
| NO_NARRATIVE | workorder without report text | narrative fields null/empty, rendered as blank | no error |
| NO_CPK | workorder without CP/CPK | CP/CPK section absent | no error |
| NO_EVIDENCE | workorder without attachments | evidence section empty | no error |
| NO_PARTS | workorder without sparepart requests | parts section empty | no error |
| NO_LOGO | logo not configured | report renders without logo, no broken image | graceful fallback |
| PRINT_PREVENTIVE | preventive schedule report | same print layout with checklist, evidence, signature | no error |
| UNAUTHORIZED | non-leader/SPV tries to approve | 403 forbidden | FORBIDDEN error |
| DUPLICATE_SIGNATURE | already signed WO | 409 CONFLICT = already signed | error code |

</intent-contract>

## Code Map

Backend:
- `maintenance/infrastructure/db/WorkOrderEntity.java` — existing fields (header, narrative, CP/CPK, status, etc.)
- `maintenance/infrastructure/db/WorkOrderStatusHistoryEntity.java` + `RepairSessionEntity.java` — sessions via `RepairSessionRepository.sumCompletedDuration` + session list
- `maintenance/application/WorkOrderReportService.java` — existing report narrative service; extend with aggregate print report method
- `sparepart/request/infrastructure/db/SparepartRequestEntity.java` — `workOrderId` column; repository query `findByWorkOrderId(workOrderId)`
- `maintenance/application/WorkOrderEvidenceService.java` — evidence attachments + presigned URLs
- `storage/application/ObjectStorageService.java` — Garage store/presign
- `preventive/application/PreventiveReportService.java` + `preventive/api/PreventiveDtos.PreventiveReportView` — the pattern to copy
- NEW: `db/migration/V66__workorder_signatures.sql` — `workorder_signatures` table
- NEW: `maintenance/infrastructure/db/WorkorderSignatureEntity.java` — JPA entity
- NEW: `maintenance/application/WorkorderSignatureService.java` — approve + read
- NEW: `maintenance/api/WorkorderPrintReportDtos.java` — aggregate DTO (WO header + sessions + narrative + CP/CPK + evidence + parts + signature)
- NEW: `maintenance/api/WorkorderSignatureController.java` — POST /api/v1/workorders/{id}/approve
- MODIFY: `WorkOrderController.java` — add GET /{id}/print-report
- NEW: `settings/api/SettingsController.java` — GET/PUT /api/v1/settings/logo
- NEW: `settings/application/LogoService.java` — Garage-based logo CRUD

Frontend:
- `features/preventive/components/preventive-report.tsx` — existing page; enhance with shared print stylesheet + logo
- NEW: `features/workorders/components/workorder-print-report.tsx` — WO print page (aggregate DTO → tabular print layout)
- NEW: `features/workorders/hooks/use-workorder-print-report.ts` — hand-written TanStack Query hook
- NEW: `features/workorders/hooks/use-workorder-approve.ts` — approve mutation
- MODIFY: `app/(main)/dashboard/workorders/[id]/print/page.tsx` — route for WO print (or a modal like preventive)
- NEW: `app/(main)/dashboard/settings/logo/page.tsx` — logo upload UI
- NEW: `styles/print.css` — `@media print` rules
- MODIFY: `app/layout.tsx` — import print.css

## Tasks & Acceptance

**Execution:**
- `backend V66 migration` — `workorder_signatures` table (unique per WO)
- `backend WorkorderSignatureEntity` + `WorkorderSignatureRepository` — JPA entity + repository
- `backend WorkorderSignatureService` — approve (`POST /{id}/approve`), read signature for print report
- `backend WorkorderPrintReportDtos` + `WorkOrderController.GET /{id}/print-report` — aggregate endpoint composing WO header + status history + sessions + narrative + CP/CPK + evidence + sparepart requests + signature
- `backend LogoService` + `SettingsController` — GET/PUT /api/v1/settings/logo (Garage, no migration)
- `backend tests` — integration for print report aggregate, approve, duplicate-signature reject, logo upload/replace, unauthorized approve
- `web styles/print.css` — `@media print` rules
- `web workorder-print-report.tsx` — WYSIWYG print page (tab, logo, sessions, narrative, CP/CPK, evidence, parts, signature block)
- `web enhance preventive-report.tsx` — reuse shared print stylesheet + logo
- `web logo upload page` — settings/logo route
- `web print route` for WO (`/dashboard/workorders/[id]/print`)

**Acceptance Criteria:**
- Given a DONE/CLOSED workorder, when the print report is opened, then it shows WO header, sessions, narrative, CP/CPK, evidence, sparepart requests, and signature block (if present) in a tabular layout suitable for browser printing.
- Given a leader/SPV opens a DONE/CLOSED workorder, when they upload a signature image and enter identity, then the signature is stored in Garage + Postgres and the report shows the signed block.
- Given a signed workorder, when someone tries to approve again, then a 409 conflict is returned.
- Given a non-leader/SPV tries to approve, then a 403 FORBIDDEN is returned.
- Given a logo is uploaded (SUPER_ADMIN), when the print report is rendered, then the logo appears at the top.
- Given no logo is configured, when the print report is rendered, then no broken image appears.
- Given the print button is clicked, when `@media print` applies, then the print layout hides nav, shows white background, black text, table borders, and logo + signature print correctly.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest="WorkorderPrintReportServiceIntegrationTest,WorkorderSignatureServiceTest,SettingsControllerTest"` -- expected: green
- `cd syncro/apps/web && npm run lint` -- expected: no new violations
- `cd syncro/apps/web && npm run build` -- expected: production build succeeds

## Auto Run Result

**Summary:** WYSIWYG print reports with signature (FR-175): `GET /api/v1/workorders/{id}/print-report` aggregate endpoint (WO header, sessions, narrative, CP/CPK, evidence, sparepart requests, signature block), `POST /{id}/approve` signature capture (V66 `workorder_signatures` table, unique per WO, Garage-stored image, audit-logged), `GET/PUT /api/v1/settings/logo` (Garage-based company logo, SUPER_ADMIN upload), shared `styles/print.css` (`@media print`, A4, scoped chrome-hiding via `html.print-mode`), WO print route + inline signature upload, and the preventive report page enhanced with the logo + shared stylesheet. No schema changes beyond V66; no server-side PDFs.

**Files changed:** V66 migration, `WorkorderSignatureEntity/Repository/Service`, `WorkorderPrintReportService` + DTOs, `SettingsEntity/Repository`, `LogoService`, `SettingsController` + DTOs + exception handler, `WorkOrderController`/`WorkOrderDtos`/`WorkOrderExceptionHandler` (print-report + approve), `AuditEntityType.WORKORDER_SIGNATURE`; frontend `print.css`, `workorder-print-report.tsx`, `use-workorder-print-report.ts`/`use-workorder-approve.ts`/`use-workorder-signature-upload.ts`/`use-company-logo.ts` (shared in `features/settings/hooks`), print route page, logo settings page, `preventive-report.tsx` enhancement, `workorder-actions-cell.tsx` Print button.

**Review findings breakdown:** 0 intent_gap, 0 bad_spec, 17 patches (all applied: audit-log verify, machine-not-found 404, redundant index removal, duplicate-approve → 409, input length validation, missing-object null check, MissingServletRequestParameter handler, print-types null type, logo hook relocation, LogoPage loading, inline signature upload, IOException wrap, validation messages, print.css .print-mode scoping, MANAGER_MAINTENANCE test, ObjectStorageException path tests, jsonPath robustness), 8 rejected (defensible readings: evidence-as-links, preventive parity via 11-3, no status change on approve, SUPER_ADMIN-only logo, etc.).

**Follow-up review recommended:** false (score 0; all patches applied).

**Verification performed:**
- Backend: 26 tests green (integration 5, signature 15, settings 6) + 113 green (controller 102, print-report 5, logo 6)
- `npm run lint` — only pre-existing violations
- `npm run build` — compiled successfully in 14.0s

**Residual risks:** OQ-5 signature mechanism remains file-upload based (canvas pad deferred, per spec). Evidence/CPK render as links (browser print cannot embed presigned URLs inline). Full CI suite hits pre-existing Testcontainers Hikari pool exhaustion under parallel execution.