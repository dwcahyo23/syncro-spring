---
title: 'Story 19-6: PM Preventive Print Report'
type: 'feature'
created: '2026-09-04'
status: 'done'
review_loop_iteration: 0
baseline_revision: '192b729897b28ee32593d39feca76b5f22aba4e3'
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: [derived-story, oversized]
deferred:
  - summary: >-
      technician_signature_id / technician_signed_at still never set by any
      mutation path (19-5 deferral re-confirmed): the report's technician
      signature block is production-null until a technician-sign endpoint
      exists; INT-001 stamps it directly (synthetic, commented).
    evidence: >-
      Repo-wide src/main search: only entity/view/report-read touch the
      columns. Carried from 19-5 deferred list — product decision, not 19-6
      scope.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmExecutionService.java
    severity: low
  - summary: >-
      openapi.json snapshot not regenerated for the report operation.
    evidence: >-
      DW-141 pattern repeats; the committed snapshot already omits all PM
      surfaces (repo-wide staleness since story 9-5).
    location: syncro/apps/web/openapi.json
    severity: low
  - summary: >-
      short-TTL presigned URLs can expire between report load and the actual
      print action (delayed print breaks images).
    evidence: >-
      Inherent to the 14-3/11-3 presign-at-read pattern (mint at render time);
      a refresh endpoint is a frontend-print-flow decision.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmExecutionReportService.java
    severity: low
---

<!-- Derived-story note: Epic 19 planning defines Stories 19.1-19.5 only. FR-133
     (print preventive report, administratively covered by Story 19.5's AC) was
     never built there — 19.5 shipped execution recording only. This spec closes
     that documented-but-unbuilt requirement as Story 19-6. -->

<intent-contract>

## Intent

**Problem:** FR-133 requires the completed PM report to print via a WYSIWYG template — tabular view, configurable logo, signature block, rendering checklist, results, signer identity, and timestamps. Story 19-5 records executions but exposes only the raw `GET /api/v1/pm-executions/{id}` view (bare UUIDs, no names, no presigned images, no machine header) — a print page cannot be built on it. The legacy 11-3 `PreventiveReportService` reads the superseded `preventive_checklist_result` tables, so the new F7/F8 model has no report surface.

**Approach:** Add `GET /api/v1/pm-executions/{id}/report` — an aggregate JSON DTO assembled by a new PmExecutionReportService, mirroring the 14-3 `WorkorderPrintReportService` pattern exactly (JSON payload for the frontend browser-print page; no server-side PDF/HTML). It composes: machine/plant header, WO period info, per-item tabular rows (snapshotted checklist + results), presigned NG photo URLs, technician + SPV signature blocks (presigned from `user_signatures`), and the finding WO reference. The company logo stays a separate `GET /api/v1/settings/logo` read (14-3 precedent — not embedded here).

## Boundaries & Constraints

**Always:**
- No migration: pm_executions / pm_execution_items / user_signatures / settings all exist since V1; no new audit entity types (read-only surface, no audit — 14-3/11-3 precedent).
- `GET /api/v1/pm-executions/{id}/report` returns `PmExecutionReportView`: header {executionId, pmWoId, machineId/Code/Name, plantCode, scheduledDate, frequencyCode/Name, checksheetRevision, startedAt, completedAt, status (execution lifecycle: NOT_STARTED/RUNNING/COMPLETED/VERIFIED derived from timestamps), hasNgItems, ngCount, findingWoId}; items[] ordered by sequence {sequence, categoryName, parameterText, checkMethod, inputType, criticalFlag, unit, lsl, nominal, usl, actualValue, ok, ng, ngNotes, ngPhotoPresignedUrl (null when no photo), blocked, blockedWoCode}; signatures {technician {userId, displayName, signaturePresignedUrl, signedAt}, spv {userId, displayName, signaturePresignedUrl, signedAt}} — each null when unsigned; camelCase.
- Read gate: reuse the exact 19-5 `get()` read posture (assigned users / machine-scope readers / SUPER_ADMIN; out-of-scope → 403, unknown id → 404). Same load-then-gate ordering.
- Signature resolution: `technician_signature_id`/`spv_signature_id` are UUID FKs to `user_signatures` (one per user, uq_user_signatures_user). Resolve via UserSignatureRepository; presign the object key with ObjectStorageService.presignGetUrl (short-TTL, never persisted). A presign failure on a deleted object must not break the report — skip the URL (null), mirror 14-3's buildSignature comment. displayName falls back to login_identifier (14-3 pattern).
- ng_photo_url is free-text (19-5 stores as-is): presign only when it looks like a storage object key (no scheme, non-blank); otherwise echo it verbatim as the URL field. Document this in the service javadoc.
- Unknown execution id → 404 PM_EXECUTION_NOT_FOUND (existing handler); malformed UUID → 400 INVALID_PATH_VALUE (existing controller idiom).
- OPA: add "/api/v1/pm-executions/*/report" to pm_execution_paths (single extra depth — `*` matches one segment) + mirror tests in authz_test.rego + append to SYNCRO_AUTHZ_ENFORCED_PATHS in .env.example.
- Tests: integration (happy full report with both signatures + NG photo, unsigned execution, out-of-scope 403, unknown 404, presign-failure resilience) + controller (200 shape, 403, 404, 400).

**Block If:**
- The 19-5 read posture turns out to reject COMPLETED-execution reads in a way that makes a report of a completed execution impossible → HALT blocked with the analysis (do not invent a second gate).

**Never:**
- No migration; no frontend; no server-side PDF/HTML rendering (no new deps); no logo embedding (separate settings read); no audit writes; no edits to 19-5 service mutation logic; no legacy preventive_checklist_* reads.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Full report | completed+verified execution, NG item with photo, both signature ids set | 200: header + items ordered by sequence + presigned photo + both signature blocks with names | — |
| Unsigned execution | started, no signatures | 200: signature blocks null, status RUNNING | — |
| Deleted signature object | spv_signature_id set but Garage object gone | 200: spv block present with null signaturePresignedUrl (report not broken) | — |
| ng_photo_url is external URL | value starts with http(s):// | echoed verbatim in ngPhotoPresignedUrl, no presign attempt | — |
| Out-of-scope reader | machine in other plant | 403 FORBIDDEN | — |
| Unknown id | random UUID | 404 PM_EXECUTION_NOT_FOUND | — |
| Malformed id | "not-a-uuid" | 400 INVALID_PATH_VALUE | — |

</intent-contract>

## Code Map

### Existing (reuse)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkorderPrintReportService.java` -- THE pattern to mirror: aggregate assembly, presign helper + failure-skip comment (176-183), technician name fallback (94-102), readOnly @Transactional.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkorderPrintReportDtos.java` -- DTO record style (nested records, nullable sections, logo-not-in-aggregate note at 92-96).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmExecutionService.java` -- `get()` at :374 (read posture to reuse verbatim), ExecutionView :641-654, loadMachine/loadWorkOrder helpers, INVALID_PATH_VALUE idiom in controller.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/api/PmExecutionController.java` -- add the report endpoint here; existing 404/403/400 handler wiring (PreventiveExceptionHandler).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmExecutionEntity.java` + `PmExecutionItemEntity.java` -- all report fields (F7/F8); items already ordered via findByExecutionIdOrderBySequenceAsc.
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/UserSignatureRepository.java` -- findByUserId (uq_user_signatures_user); UserSignatureEntity carries bucket/objectKey/contentType.
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserRepository.java` -- displayName/login_identifier for signer identity.
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineRepository.java` -- findByIdWithPlantAndGroup (used by 19-5 loadMachine).
- `syncro/apps/backend/src/main/java/com/syncro/storage/application/ObjectStorageService.java` -- presignGetUrl(key), throws ObjectStorageException.
- `syncro/authz/policy/authz.rego:419-426` -- pm_execution_paths set; `authz_test.rego` mirror tests; `syncro/.env.example` SYNCRO_AUTHZ_ENFORCED_PATHS.
- Tests: `PmExecutionServiceIntegrationTest` (fixture chain to a completed+verified execution — reuse), `PmExecutionControllerTest` (MockMvc patterns), `WorkorderPrintReportService`-style expectations.

### New
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmExecutionReportService.java` (+ view records nested or in PreventiveDtos).
- Report endpoint on `PmExecutionController.java` + DTO records in `PreventiveDtos.java`.
- Tests: `PmExecutionReportServiceIntegrationTest.java`, report cases in `PmExecutionControllerTest.java`.

## Tasks & Acceptance

**Execution:**
- `PmExecutionReportService.java` -- aggregate assembly (header/items/signatures) reusing 19-5 read posture -- core
- `PmExecutionController.java` + `PreventiveDtos.java` -- GET /{id}/report + DTO records -- API surface
- `authz.rego` + `authz_test.rego` + `.env.example` -- one path + mirror tests + rollout -- OPA parity
- `PmExecutionReportServiceIntegrationTest.java` -- every matrix row -- AC evidence
- `PmExecutionControllerTest.java` -- 200/403/404/400 -- API evidence

**Acceptance Criteria:**
- Given a completed+verified execution with an NG photo item and both signature ids, when GET report runs, then the response carries machine/plant header, sequence-ordered item rows with bounds+actual+ok/ng, a presigned photo URL, and both signature blocks with resolved display names.
- Given an execution whose signature object was deleted from storage, then the report still returns 200 with that signature URL null.
- Given an out-of-scope caller, then 403; unknown id 404; malformed id 400.
- Given the OPA suite, then the new path is allowed for the same role set as the rest of pm-executions and denied otherwise.
- Given a full per-class test run + OPA suite, then all green.

## Spec Change Log

### 2026-09-04 — review pass: no loopback; two clarifications recorded
- The "Block If" precondition (19-5 read posture must not reject completed/verified executions) was verified empirically: INT-001/INT-005 read completed+verified executions through the reused gate without error. Recorded here so the empty-check concern is evidenced.
- Header field list is the spec's contract shape; review suggestions to add findingWoCode/filledAt/scheduleDateId/generatedAt/plantName were rejected as scope expansion beyond the specced aggregate (the frontend print page can resolve WO codes via existing endpoints).

## Review Triage Log

### 2026-09-04 — /bmad-code-review pass (fresh context, 4 layers, post-patch diff)
- decision_needed: 0
- patch: 7 (high 0, medium 3, low 4) — all applied (R1-R7 below)
- defer: 0 new (technician-sign gap + openapi.json already in frontmatter deferred)
- dismiss: 12 (deriveStatus unreachable-state, status-as-enum, duplicate WO/machine load, findingWoCode additions, POST-on-rego-path, external-URL allowlist, headObject pre-check, INT-001 constructor style, frontend print page = out of intent "backend-only", magic-string enum refactor, displayName trim asymmetry, generatedAt/plantName)
- addressed_findings:
  - `[medium]` `[patch]` R1: ngPhotoUrl dot-rule misclassified flat storage keys ("photo.jpg") as external (3 layers confirmed; regression introduced by P2) — classification now: trim + strip leading "/", external iff `://` or leading `//` or (has "/" AND first segment has "."); slash-less keys always presign
  - `[medium]` `[patch]` R2: `//` echo branch had no test (deleting it kept suite green) — protocol-relative + flat-dotted-key cases added to externalNgPhotoUrl
  - `[medium]` `[patch]` R3: unasserted mapping slots (unit/nominal/checkMethod/criticalFlag/ok/blocked/blockedWoCode/frequencyName/startedAt/completedAt) — fullReport pins them + a blocked fill asserts both fields (field-order bug class already hit once via P1)
  - `[low]` `[patch]` R4: parity test was self-referential (hand-copied REGO_PM_PATHS) — now parses pm_execution_paths + pm_work_order_paths from authz.rego and resolves both files via walk-up (CWD-fragile fixed ".." removed)
  - `[low]` `[patch]` R5: rego mirror tests missing MANAGER_MAINTENANCE/MAINTENANCE_LEADER report-path allow cases — added
  - `[low]` `[patch]` R6: ngPhotoPresignedUrl name/behavior undocumented at schema level + 404 @ApiResponse omitted orphan codes — @Schema description + description extension
  - `[low]` `[patch]` R7: signedAt-set-but-signatureId-null block (production-reachable via verify(...,null)) unobserved — asserted in completedUnverified/gateParity test

### 2026-09-04 — Review pass (edge-case hunter + verification-gap + blind hunter + intent-alignment)
- intent_gap: 0
- bad_spec: 0
- patch: 6 (high 0, medium 1, low 5)
- defer: 3
- reject: 14
- addressed_findings:
  - `[medium]` `[patch]` P1: controller-test fixture passed TECH_ID in the machineId slot (field-order bug would pass) — distinct MACHINE_ID constant + jsonPath assertion on it
  - `[low]` `[patch]` P2: `contains("://")` heuristic lost scheme-less domain text (presigned → null) — now external when `://` or leading `//` or domain-like first segment; javadoc updated
  - `[low]` `[patch]` P3: derived status was a magic string — allowableValues/javadoc pinned RUNNING/COMPLETED/VERIFIED
  - `[low]` `[patch]` P4: .env.example per-story comment convention ended at 19-5 — 19-6 annotation added
  - `[low]` `[patch]` P5: checksheetRevision vs templateRevision naming — javadoc line on the record field
  - `[low]` `[patch]` P6: .env.example rollout untested (deleting the path broke nothing) — PmAuthzEnforcementParityTest (plain unit test, 1/1) asserts SYNCRO_AUTHZ_ENFORCED_PATHS contains all 7 pm-executions + 7 pm-work-orders rego paths, pinning 19-4+19-6 rollout parity
  - deferred: technician-sign production gap (19-5 deferral re-confirmed; INT-001 synthetic stamp commented); openapi.json snapshot (DW-141 pattern repeats); presign TTL vs delayed print (14-3/11-3 inherent)
  - rejected: technician-sign endpoint in this story (19-5 product decision, deferred above); header signer identity when unsigned (signer identity is the signature block's purpose; unsigned execution has no signer — RUNNING badge + technicianId already in ExecutionView consumers' reach); findingWoCode/filledAt/blockingWoId/scheduleDateId/WO-status/generatedAt/plantName header additions (spec-defined shape; frontend resolves via existing endpoints); POST-on-report allowed in rego (intended coarse-fence posture — framework 405s unmapped methods, same as every other path set); external-URL echo host allowlist (19-5 store-as-is precedent; print page is same-origin browser print); deleted-object headObject pre-check (S3 presign is offline — 14-3 accepts the same live-URL-then-404 ceiling); RUNNING-with-signed-timestamps guard (unreachable state — no mutation path writes signedAt without completedAt except the synthetic test); displayName trim asymmetry (cosmetic); seam between integration and controller surfaces (repo-wide test idiom inherited from spec's own plan — intent auditor confirmed faithful)

## Auto Run Result

Status: done

Summary: PM preventive print report (FR-133, derived story closing Epic 19's documented-but-unbuilt requirement) — GET /api/v1/pm-executions/{id}/report returns the PmExecutionReportView aggregate (machine/plant header + WO period + derived RUNNING/COMPLETED/VERIFIED status + sequence-ordered snapshotted item rows with bounds/actual/NG fields + presigned NG photo + technician/SPV signature blocks with resolved display names). Mirrors 14-3 WorkorderPrintReportService exactly: JSON payload for the frontend browser-print page, no server-side PDF/HTML, logo stays a separate settings read. Read gate reuses PmExecutionService.get() verbatim (no second gate). No migration, no audit writes (read-only posture precedent). OPA: report path added to pm_execution_paths + 6 mirror tests + .env.example rollout + NEW parity test pinning env-list ↔ rego-set consistency for 19-4/19-6.

Files changed: PmExecutionReportService.java (new), PreventiveDtos.java (5 report records), PmExecutionController.java (GET /{id}/report), authz.rego, authz_test.rego, .env.example. Tests: PmExecutionReportServiceIntegrationTest (6, new), PmExecutionControllerTest (26 = 22 + 4 report cases), PmAuthzEnforcementParityTest (1, new).

Review findings: pass 1 (build-auto): 6 patched (high 0, medium 1, low 5), 3 defer, 14 reject, 0 intent_gap, 0 bad_spec. Pass 2 (/bmad-code-review, fresh context): 7 patched (high 0, medium 3, low 4 — R1-R7), 12 dismiss. Notable: R1 fixed a regression introduced by pass-1 P2 (flat dotted storage keys misclassified as external URLs).

Follow-up review recommendation: patched high 0, medium 1+3, low 5+4 → score 3×4+1×9 = 21 ≥ 5 → true.

Verification performed (each class run alone — shared-container contention; final state after both passes):
- PmExecutionReportServiceIntegrationTest 6/6, PmExecutionControllerTest 26/26, PmAuthzEnforcementParityTest 1/1, PmExecutionServiceIntegrationTest 18/18 (19-5 regression)
- OPA: 442/442
- git diff --stat -- db/migration: empty (no migration, as specced)

Residual risks: full-suite green asserted at the epic gate (DW-142); technician signature block production-null until a technician-sign endpoint exists (deferred, 19-5 lineage); openapi.json snapshot stale (DW-141 pattern).

## Design Notes

- Status is derived, not stored: NOT_STARTED impossible (execution exists once started), RUNNING = completedAt null, COMPLETED = completedAt set + spvSignedAt null, VERIFIED = both set. The frontend print page keys its header badge off this.
- One service, zero writes: everything is read + presign. Keeping it separate from PmExecutionService avoids bloating the mutation service and mirrors 14-3 (WorkorderPrintReportService is its own class).
- ponytail: no report template registry — the "WYSIWYG template" is the frontend print page consuming this JSON (14-3 precedent); backend only guarantees the payload is print-ready.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=PmExecutionReportServiceIntegrationTest` (alone) -- expected: pass
- `cd syncro/apps/backend && mvn -q test -Dtest=PmExecutionControllerTest` (alone) -- expected: pass incl. new report cases
- `cd syncro/apps/backend && mvn -q test -Dtest=PmExecutionServiceIntegrationTest` (alone) -- expected: 19-5 regression green
- OPA via `docker run --rm -v <authz>/policy:/policy openpolicyagent/opa:1.19.1-debug test /policy` -- expected: pass with the new path

**Manual checks:**
- `git diff --stat -- syncro/apps/backend/src/main/resources/db/migration` shows nothing (no migration).
