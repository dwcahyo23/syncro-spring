---
title: 'Story 22-3: User Signatures & Signature Use (redesigned 2026-08-31)'
type: 'feature'
created: '2026-09-06'
status: 'done'
baseline_revision: 'dd835c4'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-22-context.md'
  - '_bmad-output/implementation-artifacts/spec-22-2-auth-login-audit-phone-verification.md'
warnings: []
deferred:
  - summary: >-
      Signature upload/replace audits as SIGNATURE_USE, conflating storage events with actual signature applications; a dedicated USER_SIGNATURE audit entity type would need a V17 CHECK migration.
    evidence: |-
      UserSignatureService.store writes AuditEntityType.SIGNATURE_USE for CREATE/UPDATE of the stored signature; no USER_SIGNATURE type exists and the V1 ck_audit_log_entity_type CHECK is frozen for this story (only V16 additive column allowed). Trade-off documented in Design Notes.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/auth/application/UserSignatureService.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** `user_signatures` and `signature_uses` exist in V1 with mapped entities, but nothing ever inserts a `user_signatures` row (no upload path), workorder approve writes `signature_uses` with null signature reference/hash/ip/user-agent, and PM approvals store signatures without any `signature_uses` row — so approvals are not evidenced by a reusable, tracked signature (FR-132/133/175).

**Approach:** Add a `UserSignatureService` + endpoints to store/retrieve a user's signature image (Garage object + SHA-256), a `SignatureUseService` that records every signature application, enrich the workorder approve flow to reference the stored signature with request metadata, and write `signature_uses` rows from PM checklist approve and PM execution verify. Print rendering already resolves FK→presigned URL (14-3/19-6) and needs no change.

## Boundaries & Constraints

**Always:**
- Forward-only change; `user_signatures`/`signature_uses` tables and `SIGNATURE_USE`/`WORKORDER_SIGNATURE` audit types already exist — only additive columns allowed (V16: `sha256 VARCHAR(64)` on `user_signatures`)
- One `user_signatures` row per user (`uq_user_signatures_user`): upload upserts and deletes the superseded Garage object; stores bucket, object_key, content_type, sha256 (computed from bytes at upload); the plain image is never returned by any endpoint (reads return reference + presigned URL only)
- Every signature application writes one `signature_uses` row (signer, signature reference + bucket/key/sha256 copied from `user_signatures`, module, subject_type/id, action, reason, ip, user-agent, signed_at server Clock) AND an immutable audit row (`SIGNATURE_USE` for new paths, keep `WORKORDER_SIGNATURE` for the existing WO flow)
- WO approve contract stays backward-compatible: `signatureId` optional; when absent, behavior identical to today (null reference columns)
- Owner-or-SUPER_ADMIN for signature writes; reads of another user's signature are SUPER_ADMIN-only; rego path sets + `.env.example` + parity-test regex updated in lockstep
- DTO records with Bean Validation; multipart upload mirrors `SparepartImageController` pattern; `Instant` UTC; error envelope via existing handlers

**Block If:**
- A change to the WO approve response shape, the `signature_uses` partial-unique semantics, or V1 columns seems required → HALT (all work is additive)

**Never:**
- No frontend UI (capture/consumption surfaces are later stories)
- No signature verification/login throttle (`signature_failed_attempts`/`signature_blocked_until` stay unused — no consumer defines their semantics yet)
- No new dependencies; no Lombok/MapStruct; no download method on ObjectStorageService (hash at upload, not at use)

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Upload signature | owner, multipart image | 201 + view (bucket/key/contentType/sha256); upserts single row; old Garage object deleted | 415 non-image / 400 empty |
| Re-upload | user already has signature | 200/201 replaces row content, prior object deleted | — |
| Get own signature | authenticated owner | 200 + view + short-TTL presigned URL | 404 SIGNATURE_NOT_FOUND |
| Get other user's | SUPER_ADMIN | 200 | 403 for non-admin |
| WO approve with signatureId | valid stored signature | 200; use row enriched (signatureId, bucket, key, sha256, ip, ua) | 404 SIGNATURE_NOT_FOUND for unknown id |
| WO approve without signatureId | existing client | unchanged behavior (null reference columns) | existing 409 dup |
| PM checklist approve | signed result | + one signature_uses row (module=preventive) | existing validation |
| PM execution verify | spv signature id | + one signature_uses row | existing validation |
| Unauthenticated | no JWT | 401 AUTHENTICATION_REQUIRED | SecurityConfig |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql:1721-1762` -- table DDL + indexes + partial unique (read-only reference)
- `auth/infrastructure/UserSignatureEntity.java` -- immutable-style entity -- add sha256 field + mutation support
- `auth/infrastructure/UserSignatureRepository.java:8` -- findByUserId exists -- upsert path uses it
- `auth/infrastructure/SignatureUseEntity.java` / `SignatureUseRepository.java` -- mapped -- reuse for inserts
- `storage/application/ObjectStorageService.java:12-43` -- store/presignGetUrl/delete -- upload + supersede
- `maintenance/application/WorkorderSignatureService.java:82-122` -- approve() writes use row with nulls -- enrich with signatureId resolution + ip/ua
- `maintenance/api/WorkOrderController.java:970` -- POST /{id}/approve -- extend request DTO with optional signatureId
- `maintenance/preventive/application/PreventiveChecklistService.java:147-171` + `PmExecutionService.java:323-336` -- PM signature stores -- add signature_uses writes
- `maintenance/application/WorkorderPrintReportService.java:171-182` + `preventive/application/PmExecutionReportService.java:165-183` -- FK→presign resolvers -- already render, no change
- `audit/application/AuditLogWriter.java` + `AuditRecord.java` -- audit rows -- SIGNATURE_USE type exists, no migration
- `auth/api/AuthController.java` / `AuthDtos.java` / `AuthExceptionHandler.java` -- 22-2 envelope pattern to mirror for new UserSignature endpoints
- `sparepart/api/SparepartImageController.java:56` -- multipart upload precedent
- `syncro/authz/policy/authz.rego` + `authz_test.rego` + `syncro/.env.example:83` + `PmAuthzEnforcementParityTest.java:26` -- path sets, rules, env, parity regex
- Tests: `WorkorderSignatureServiceTest.java` (Mockito), `AuthAuditControllerTest.java` (@WebMvcTest), `AbstractPostgresIntegrationTest` (Testcontainers)

## Tasks & Acceptance

**Execution:**
- `db/migration/V16__user_signature_sha256.sql` -- create -- add `sha256 VARCHAR(64)` to `user_signatures` (V13/V15 pattern); update `V1BaseSchemaMigrationTest` count
- `auth/infrastructure/UserSignatureEntity.java` -- extend -- sha256 field + setters or rewrite-constructor for upsert
- `auth/application/UserSignatureService.java` -- create -- store (bytes→sha256→Garage→upsert→delete superseded key), get, presigned view; owner-or-SUPER_ADMIN gates
- `auth/application/SignatureUseService.java` -- create -- record(signer, signatureId?, module, subjectType, subjectId, action, reason, ip, ua) → use row + SIGNATURE_USE audit
- `auth/api/UserSignatureController.java` + `AuthDtos.java` + `AuthExceptionHandler.java` -- extend -- POST /api/v1/auth/user-signatures (multipart), GET /me, GET /{userId}; stable codes SIGNATURE_NOT_FOUND, FORBIDDEN
- `maintenance/application/WorkorderSignatureService.java` + `WorkOrderController.java` + `WorkOrderDtos` -- extend -- optional signatureId on approve; resolve user_signatures → copy bucket/key/sha256; capture ip/user-agent; keep null path backward-compatible
- `PreventiveChecklistService.java` + `PmExecutionService.java` -- extend -- write signature_uses rows via SignatureUseService on checklist approve and execution verify
- `authz.rego` + `authz_test.rego` + `.env.example` + `PmAuthzEnforcementParityTest` -- extend -- `user_signature_paths` set (owner+admin), `workorder_approve_paths` set (leader/SPV roles per service gate), env entries, regex + parsed-set guards
- Tests: `UserSignatureServiceTest` (Mockito: upload/upsert/supersede-delete/sha256/gates), `SignatureUseServiceTest` (record + audit), `UserSignatureControllerTest` (@WebMvcTest envelope incl. multipart), `SignatureUseIntegrationTest` (Testcontainers: upload→approve→enriched use row→audit; PM flows write uses; WO approve without signatureId unchanged)

**Acceptance Criteria:**
- Given a user uploads a signature, when stored, then exactly one `user_signatures` row holds bucket/key/contentType/sha256 and the prior object is gone; no endpoint returns image bytes (AC1)
- Given WO approve with a stored signature, when applied, then the `signature_uses` row carries signature reference + hash + ip + user-agent and an audit row exists; without signatureId the old behavior is byte-identical (AC2)
- Given PM checklist approve or execution verify, when signed, then a `signature_uses` row (module=preventive) is written and audit-logged; print reports render the signature via existing resolvers (AC3)

## Spec Change Log

## Review Triage Log

### 2026-09-06 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 18: (high 3, medium 7, low 8)
- defer: 1
- reject: 3
- addressed_findings:
  - `[high]` `[patch]` WO approve / SignatureUseService.record never check the referenced signature belongs to the signer — cross-user signature attribution possible — ownership gate + tests.
  - `[high]` `[patch]` `Map.copyOf` NPE when stored pre-V16 row has null sha256 — guard nulls in the enrichment values map.
  - `[high]` `[patch]` Controller wiring (signatureId/ip/UA extraction) untested — enrichment can ship inert with green build — ArgumentCaptor tests in all three controller tests incl. XFF header.
  - `[medium]` `[patch]` Concurrent first-upload race → raw 500 + orphaned Garage object — catch DataIntegrityViolationException, delete orphan, replace.
  - `[medium]` `[patch]` Presign failure after store rolls back tx and orphans object — try/catch presign, url=null (14-3 precedent).
  - `[medium]` `[patch]` PM execution verify writes a use row even with null spvSignatureId — gate on presence per matrix.
  - `[medium]` `[patch]` ValidationException.fieldErrors discarded by handler — include details per house pattern.
  - `[medium]` `[patch]` Multipart failure modes (missing part, oversized, non-multipart) escape the envelope — mirror SparepartImageExceptionHandler.
  - `[medium]` `[patch]` signatureObjectKey stays @NotBlank even when signatureId present (dummy required) — relax to service-level either-or validation.
  - `[medium]` `[patch]` No size cap / content sniffing on uploads — MAX_SIGNATURE_BYTES + magic-byte check (trust boundary).
  - `[low]` `[patch]` Inconsistent unknown-signatureId semantics (WO 404 vs PM null) — document in Design Notes.
  - `[low]` `[patch]` Upload audits as SIGNATURE_USE (store vs use collision) — document trade-off in Design Notes.
  - `[low]` `[patch]` PM checklist reference-less rows are matrix-literal — document reading in Design Notes.
  - `[low]` `[patch]` Import style inconsistency across the three controllers — normalize.
  - `[low]` `[patch]` authz_test.rego missing trailing newline — add.
  - `[low]` `[patch]` GET /{userId} 200 + presign-502 missing from controller test/@ApiResponses — add.
  - `[low]` `[patch]` UserSignatureEntity javadoc overstates sha256 invariant — scope to enriched path.
  - `[low]` `[patch]` SignatureUseService.record uses save() not saveAndFlush() — align with house pattern.

## Design Notes

- sha256 is computed at upload from the in-memory bytes (MessageDigest, hex) — ObjectStorageService has no download method and adding one for hashing is the wrong layer; the hash rides on `user_signatures` and is copied into each use row as the signature reference at time of signing.
- Supersede-on-upload deletes the old Garage key after the new row commits (best-effort; a failed delete leaves an orphan object, never a dangling reference).
- WO approve keeps its `WORKORDER_SIGNATURE` audit type (existing contract); new PM paths audit as `SIGNATURE_USE`.
- `subject_id` is VARCHAR(50) by design (dual-source WO ids) — use rows store it as string, not UUID.
- Unknown-signatureId semantics differ by path (review 22-3): WO approve 404s hard (`SIGNATURE_NOT_FOUND`) because the client explicitly referenced a stored row; PM execution verify degrades to null reference columns because `pm_executions.spv_signature_id` carries no FK (the 19-5 contract accepts any UUID) — a dangling id there is legacy data, not a client error. Both paths reject a signature owned by ANOTHER user with 403 (review 22-3 P1).
- Signature upload audits as `SIGNATURE_USE` (store-vs-use collision): the table has no dedicated store type and the CHECK is frozen; a distinct `USER_SIGNATURE` audit type would need its own V17 migration — deferred.
- PM checklist approve records a reference-less use row (signatureId null, object key only) by matrix-literal reading: `ApproveScheduleRequest` has no signatureId field, so the checklist flow cannot reference a stored signature without a contract change — deliberate scope (review 22-3).

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=*Signature*,*UserSignature*,*WorkorderSignature*,*Pm*,PmAuthzEnforcementParityTest,V1BaseSchemaMigrationTest"` -- expected: green incl. Testcontainers
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=WorkorderPrintReportServiceIntegrationTest,PmExecutionReportService*"` -- expected: print rendering untouched, green
- `opa test syncro/authz/policy` via docker opa:1.19.1 -- expected: all policy tests green

## Auto Run Result

**Summary:** Users can store one reusable signature (multipart upload → SHA-256 → Garage object → single `user_signatures` row, superseded object deleted after commit); every signature application writes an enriched `signature_uses` row (reference + bucket/key/sha256 + ip + user-agent + server-clock signed_at) with an immutable audit row. WO approve gained an optional `signatureId` (backward-compatible null path), PM checklist approve and PM execution verify now write `signature_uses` rows, and both write paths enforce signature ownership (403 on cross-user reference). V16 adds the `sha256` column; rego gains `user_signature_paths` + `workorder_approve_paths` with env + parity guards.

**Files changed:**
- `auth/application/UserSignatureService.java` (new) — upload upsert, gates, presigned reads, race/presign hardening
- `auth/application/SignatureUseService.java` (new) — record use + SIGNATURE_USE audit, ownership gate
- `auth/api/UserSignatureController.java` (new) — POST multipart, GET /me, GET /{userId}
- `auth/infrastructure/UserSignatureEntity.java` — sha256 + replaceContent
- `auth/api/AuthDtos.java` / `AuthExceptionHandler.java` — view record, fieldErrors, multipart handlers
- `maintenance/application/WorkorderSignatureService.java` + `WorkOrderController.java` + `WorkOrderDtos.java` + `WorkOrderExceptionHandler.java` — optional signatureId enrichment, ip/UA capture, SIGNATURE_NOT_FOUND
- `maintenance/preventive/application/PreventiveChecklistService.java` + `PmExecutionService.java` + controllers — signature_uses writes on PM approve/verify
- `db/migration/V16__user_signature_sha256.sql` (new) + `V1BaseSchemaMigrationTest.java`
- `authz.rego` + `authz_test.rego` + `.env.example` + `PmAuthzEnforcementParityTest.java` — path sets, rules, parity guards
- Tests: `UserSignatureServiceTest` (11), `SignatureUseServiceTest` (6), `UserSignatureControllerTest` (12), `SignatureUseIntegrationTest` (10, Testcontainers), widened controller tests with ArgumentCaptor

**Review findings breakdown:** 18 patches applied (3 high, 7 medium, 8 low); 1 deferred (dedicated USER_SIGNATURE audit type needs V17); 3 rejected (clientIp duplication = house pattern, README count, seq-scan perf).

**Follow-up review recommended:** true — patched counts: high 3, medium 7, low 8; score 3×7+8=29 ≥ 5.

**Verification performed:** independent re-run `mvn test -Dtest=*Signature*,*UserSignature*,*WorkorderSignature*,PmExecutionService*,PmChecklist*,PmAuthzEnforcementParityTest,V1BaseSchemaMigrationTest,WorkOrderControllerTest` → **269/269 green** incl. Testcontainers; print-rendering suites 5/5 + 6/6 green; `run-opa-test.ps1` → **488/488 PASS**.

**Residual risks:** shared-fork Testcontainers port flake (pre-existing, documented since 21-1) can fail unrelated suites in large combined runs; upload events audit under SIGNATURE_USE (store-vs-use collision, deferred); PM checklist rows are reference-less by matrix-literal scope; openapi.json not regenerated (no frontend consumption this story).
