---
title: 'Manage Sparepart Image via Garage'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: false
baseline_commit: 1a73686
context:
  - '{project-root}/_bmad-output/project-context.md'
warnings:
  - oversized
---

<intent-contract>

## Intent

**Problem:** FR-082 (Epic 8) requires one global image per sparepart stored in Garage S3-compatible object storage with PostgreSQL holding only an object reference, but no upload/replace/remove path exists. Story 8.1 delivered the storage plumbing (`ObjectStorageService.store` + `presignGetUrl`); Story 8.4 must expose the operator-facing image API and UI without persisting image bytes in PostgreSQL and without letting stale objects orphan in the bucket.

**Approach:** A V39 migration adds nullable `image_object_key VARCHAR(255)` to `spareparts`. The `ObjectStorageService` contract gains `delete(key)` (Garage-backed impl uses `S3Client.deleteObject`). A new `SparepartImageService` (sparepart module) enforces the pinned gate order (app-role → `JobScopeService.requireLevelOrAbove(user, LEADER)` → plant-masked 404), validates content type `image/*` and a bounded size, mints a bucket-relative key `spareparts/{sparepartId}/{uuid}.{ext}`, and provides: `replace(user, sparepartId, command)` — deletes the prior object key (no orphans) then stores the new bytes, updates the entity, audits `CREATE`/`UPDATE`; `delete(user, sparepartId)` — removes the object, clears the reference, audits `DELETE`; `get(user, sparepartId)` — read-scoped presigned GET URL, 404 when no image. API under `/api/v1`: `POST|GET|DELETE /spareparts/{sparepartId}/image`. Frontend regenerates Orval clients and adds a `SparepartImageUpload` shared component (upload/preview via short-TTL presigned URL/remove) integrated into the sparepart edit dialog.

## Boundaries & Constraints

**Always:**
- Only the object key persists in PostgreSQL (`spareparts.image_object_key`); image bytes live only in Garage. Never store the presigned URL — it is short-TTL and host-bound (8-1 residual-risk contract).
- Gate order on mutations (replace/delete): `requireMutationRole` (SUPER_ADMIN or MANAGE, else 403 `FORBIDDEN`) → `jobScopes.requireLevelOrAbove(user, "LEADER")` (else 403 `JOB_SCOPE_REQUIRED`, message naming LEADER-or-above) → sparepart load, non-SUPER_ADMIN must pass sparepart→machine→plant membership (out-of-plant masked 404 `SPAREPART_NOT_FOUND`). Denials: no mutation, no storage call, no audit row.
- GET requires authentication + plant access (same masking) but NOT job scope; returns `{ sparepartId, objectKey, presignedUrl }` from `ObjectStorageService.presignGetUrl`; 404 `SPAREPART_IMAGE_NOT_FOUND` when `image_object_key` is null.
- Upload validation (service-owned, house style): content type must match `^image/` and a safe subset (jpeg/png/webp/gif), filename non-blank (max 255), decoded bytes > 0 and ≤ `SparepartImageProperties.max-bytes` (default 5 MB). Violations → 400 `VALIDATION_ERROR` with fieldErrors; file bytes never logged.
- Key grammar: `spareparts/{sparepartId}/{uuid}.{ext}` where ext derived from validated content type (jpeg→jpg, png→png, webp→webp, gif→gif). Replace always deletes the previous `image_object_key` object BEFORE storing the new one, so no orphan accumulates and a failed upload never leaves a dangling new object (store after successful delete; if store throws after old delete, old reference already cleared by design — entity updated only on store success).
- Mutations update `updated_at` and write immutable audit rows via `AuditLogWriter.record(user, …)`: action `CREATE` on first upload / `UPDATE` on replace, `DELETE` on removal; `AuditEntityType.SPAREPART`, entityId = sparepart id, label = sparepart code, plantId = sparepart's machine plant, previous/new `image_object_key` snapshots (values not URLs).
- `SparepartAuditValues` includes `imageObjectKey` (nullable) so sparepart create/update/patch audits stay complete.
- Frontend follows existing idioms: `SparepartImageUpload` controlled shared component in `components/syncro/` (props per material-code-field precedent), edit dialog section visible only in edit mode, static "Requires job scope LEADER or above." hint, backend denial surfaced verbatim via `errorResponse()`, plain controlled state, generated files touched only via regen scripts.
- Sparepart image properties bound through typed config `syncro.sparepart.image.*` (max-bytes) with a safe default; no hardcoded size/content limits in source.

**Block If:** Nothing requires human input. Pinned: content-type whitelist (jpeg/png/webp/gif), key grammar with uuid segment (avoids browser-cache collisions on replace), 5 MB default max, DELETE returns 204, GET returns 404 when absent, `ObjectStorageService.delete` is added to the contract (not a sparepart-specific S3 call) so storage ownership stays in the storage module.

**Never:**
- Never persist image bytes or presigned URLs in PostgreSQL; never store `image_*` beyond `image_object_key`.
- Never add GET of raw bytes through the backend — the browser opens the presigned URL directly (backend never proxies image bytes).
- Never allow VIEWER or MANAGE-below-LEADER to mutate; never treat MANAGE app role alone as LEADER scope; SUPER_ADMIN bypasses job scope (documented, matches 8-2/8-3).
- Never skip deleting the previous object on replace/remove (orphan accumulation is a contract violation).
- Never call `S3Client.deleteObject` outside `storage` module (sparepart module must only see the `ObjectStorageService` contract).
- Never log file bytes, presigned URLs in error logs, or content-type secrets; never hand-edit generated Orval files.
- Never add React Hook Form/Zod or a new upload/multipart framework — use the existing fetch mutator with `FormData` (Orval multipart support) or documented plain approach.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_UPLOAD | LEADER+ MANAGE uploads `image/png`, 1 MB | 201/200; `image_object_key` persisted; presigned URL returned; Garage object exists; audit CREATE with new key, previous null | No error |
| REPLACE | Sparepart already has image; upload new `image/jpeg` | Old object deleted from Garage; new key persisted; audit UPDATE with previous/new keys | Old-delete failure → 502 `OBJECT_STORAGE_ERROR`, entity untouched |
| UPLOAD_DELETE_FAILS | Store throws after old delete | Entity still has old key cleared? No — see Always: store throws → object not stored, old object already deleted, entity NOT updated (still old key) → residual: callers retry; audit none | 502 `OBJECT_STORAGE_ERROR`; no partial entity state |
| REMOVE | Delete image → 204; `image_object_key` null; object removed; audit DELETE previous=old key, new=null | No error | Garage delete failure → 502, entity untouched |
| REMOVE_WHEN_ABSENT | No `image_object_key` | 204 no-op (idempotent delete; nothing to remove, no audit) | No error |
| GET_WITH_IMAGE | Image present | 200 `{sparepartId, objectKey, presignedUrl}` short-TTL | No error |
| GET_WITHOUT_IMAGE | `image_object_key` null | 404 `SPAREPART_IMAGE_NOT_FOUND` | Standard |
| BAD_CONTENT_TYPE | Upload `application/pdf` or `text/plain` | No storage call | 400 `VALIDATION_ERROR` fieldErrors.contentType |
| OVERSIZE | Upload 20 MB > max-bytes | No storage call | 400 `VALIDATION_ERROR` fieldErrors.data |
| EMPTY_FILE | 0 bytes | No storage call | 400 `VALIDATION_ERROR` fieldErrors.data |
| BELOW_LEADER | MANAGE with no ≥LEADER row uploads/deletes | No mutation/storage/audit | 403 `JOB_SCOPE_REQUIRED` naming LEADER+ |
| VIEWER_ROLE | Any mutation | No mutation | 403 `FORBIDDEN` (app-role gate first) |
| SUPER_ADMIN_NO_ROWS | SUPER_ADMIN without responsibility rows | Allowed (documented bypass) | No error |
| WRONG_PLANT | MANAGE+LEADER on sparepart outside assigned plants | Masked | 404 `SPAREPART_NOT_FOUND` |
| UNKNOWN_SPAREPART | POST/GET/DELETE nonexistent sparepartId | Standard | 404 `SPAREPART_NOT_FOUND` |
| STORAGE_DOWN | Garage unreachable on upload/get/delete | Sanitized | 502 `OBJECT_STORAGE_ERROR`; never leaks SDK detail |

</intent-contract>

## Code Map

**Backend (`syncro/apps/backend/src/main/java/com/syncro` + resources):**
- `resources/db/migration/V39__add_sparepart_image_object_key.sql` -- NEW -- `ALTER TABLE spareparts ADD COLUMN image_object_key VARCHAR(255);` (nullable).
- `storage/application/ObjectStorageService.java` -- MODIFY -- add `void delete(String key)` contract (javadoc: idempotent; unknown key is a no-op).
- `storage/infrastructure/GarageObjectStorageService.java` -- MODIFY -- implement `delete` via `S3Client.deleteObject` (bucket from properties; wrap in ObjectStorageException).
- `sparepart/infrastructure/SparepartEntity.java` -- MODIFY -- add `@Column(name = "image_object_key", length = 255) String imageObjectKey` + getter + `updateImageObjectKey(String key, Instant updatedAt)` mutator.
- `sparepart/infrastructure/SparepartRepository.java` -- MODIFY -- no new query needed (findById + update suffice).
- `sparepart/application/SparepartAuditValues.java` -- MODIFY -- include `imageObjectKey`.
- `sparepart/application/SparepartImageService.java` -- NEW -- deps: `SparepartRepository`, `AuthUserPlantAssignmentRepository`, `AuditLogWriter`, `Clock`, `JobScopeService`, `ObjectStorageService`, `SparepartImageProperties`; methods `replace`, `delete`, `get` per matrix; nested exceptions (Validation/MutationForbidden/NotFound/Storage) mirroring 8-3 service style; private `findScopedSparepart` (404 masking), `requireMutationRole`, key builder, content-type/size validation.
- `sparepart/application/SparepartImageProperties.java` -- NEW -- `@Validated @ConfigurationProperties(prefix = "syncro.sparepart.image")` record with `@Positive @NotNull Long maxBytes` (default 5*1024*1024).
- `sparepart/api/SparepartImageDtos.java` -- NEW -- `SparepartImageUploadRequest(@NotBlank @Size(max=255) String filename, @NotBlank @Pattern(regexp="^image/(jpeg|png|webp|gif)$") String contentType, @NotNull @Size(min=1) byte[] data)`, `SparepartImageView(UUID sparepartId, String objectKey, String presignedUrl)`.
- `sparepart/api/SparepartImageController.java` -- NEW -- `@RequestMapping("/api/v1/spareparts/{sparepartId}/image")`; POST (multipart `file` part + explicit filename/content-type or `@RequestPart`) → 200 view; GET → 200 view / 404; DELETE → 204; full `@Operation`/`@ApiResponses` with `content = @Content` on every non-2xx (8-2/8-3 lesson).
- `sparepart/api/SparepartImageExceptionHandler.java` -- NEW -- 400 `VALIDATION_ERROR`, 403 `FORBIDDEN`/`JOB_SCOPE_REQUIRED`, 404 `SPAREPART_NOT_FOUND`/`SPAREPART_IMAGE_NOT_FOUND`, 502 `OBJECT_STORAGE_ERROR`; stable error shape via `ErrorResponse` idiom.
- `resources/application.yml` + `application-local.yml` -- MODIFY -- `syncro.sparepart.image.max-bytes` bound to env with default; `spring.servlet.multipart.max-file-size`/`max-request-size` set above the default max (e.g. 10 MB) so the controller can enforce the stricter domain limit.

**Backend tests:**
- `src/test/java/com/syncro/storage/infrastructure/GarageObjectStorageServiceTest.java` -- MODIFY -- add `delete` cases (happy + wraps failure).
- `src/test/java/com/syncro/sparepart/application/SparepartImageServiceIntegrationTest.java` -- NEW -- Testcontainers PostgreSQL; reuse `persistedUser`/`assign`/`assignJobScope`/`latestAuditEntryFor` patterns; mock `ObjectStorageService` via `@MockitoBean`; cover every matrix row (upload persist key + audit, replace deletes old + UPDATE audit, remove, idempotent remove, GET with/without, bad content type, oversize, empty, below-leader, viewer, super-admin bypass, wrong-plant, unknown, storage-down).
- `src/test/java/com/syncro/sparepart/api/SparepartImageControllerTest.java` -- NEW -- `@WebMvcTest` + `@Import(SecurityConfig, …Handler, JwtAuthenticationFilter, TimeConfig, TestJsonConfig)`; status codes + `$.code` per matrix.

**Frontend (`syncro/apps/web/src`):**
- `lib/api/generated/**` -- REGENERATE via `npm run generate:snapshot` (backend running) + `npm run generate:api` -- typed `useCreateSparepartImage`/`useDeleteSparepartImage`/`useGetSparepartImage` hooks + models; force-add orphaned new model files.
- `components/syncro/sparepart-image-upload.tsx` -- NEW -- controlled shared component (props: `value` presignedUrl | null, `onUpload(file: File)`, `onRemove()`, `isUploading`, `isRemoving`, `error?`, `readOnly?`): hidden `<input type="file" accept="image/*">` triggered by a button; preview `<img src={presignedUrl}>` with alt text; Remove button; loading states; per house style (Label/aria-invalid/role=alert).
- `features/master-data/spareparts/sparepart-management.tsx` -- MODIFY -- edit dialog gains Image section (edit mode only, below Price History): `SparepartImageUpload` bound to generated GET hook (enabled in edit mode), upload via POST hook (FormData), remove via DELETE hook, invalidate query key on success, static LEADER+ hint, denial/error surfaced verbatim via `errorResponse()` + toast; disabled/readOnly when `!canMutate` or pending (double-submit prevention).
- Tests: `components/syncro/sparepart-image-upload.test.tsx` -- NEW; `features/master-data/spareparts/sparepart-management.test.tsx` -- EXTEND using module-level mutable mock + `importOriginal` clone pattern (section hidden in create / visible in edit, upload calls hook once, remove calls hook, denial message verbatim).

## Tasks & Acceptance

**Execution:**
- [ ] `V39__add_sparepart_image_object_key.sql` + entity field/mutator + audit-values -- persistence carries the reference.
- [ ] `ObjectStorageService.delete` contract + Garage impl + unit tests -- storage module owns deletion.
- [ ] `SparepartImageProperties` + yml/env wiring (incl. multipart limits) -- config owns limits.
- [ ] `SparepartImageService` replace/delete/get with pinned gates, validation, key grammar, audit -- the use case.
- [ ] DTOs + controller + exception handler -- API surface with correct OpenAPI error schemas.
- [ ] Backend tests (integration + WebMvc) per matrix -- prove every row incl. denials, no-orphan replace, idempotent remove, audit.
- [ ] Orval regeneration (boot backend, run scripts; force-add orphaned models) -- typed hooks exist.
- [ ] `SparepartImageUpload` + dialog integration + frontend tests -- UI delivery.
- [ ] Verify: Maven suite green; `npm run test:unit` + Biome green; live-stack evidence (upload→presigned GET→replace→delete + psql reference + Garage object lifecycle).

**Acceptance Criteria:**

- Given a sparepart exists and Garage is reachable, when a LEADER+-scoped MANAGE user uploads an image within limits, then the file is stored in Garage, only the object key persists in `spareparts.image_object_key`, and the response returns a short-TTL presigned URL. [AC 8.4-1]
- Given a sparepart already has an image, when the user uploads a replacement, then the previous Garage object is removed (no orphan accumulates) and the new key is persisted with an audit UPDATE. [AC 8.4-2]
- Given an image exists, when the user removes it, then the Garage object is deleted, `image_object_key` clears, and an audit DELETE records the previous key; removing again is a 204 no-op. [AC 8.4-3]
- Given the UI previews a sparepart image, when the dialog is open, then the image is loaded through the backend-generated presigned URL that expires (never from PostgreSQL bytes). [AC 8.4-4]
- Given a user below LEADER job scope, when uploading, replacing, or removing, then the server responds 403 `JOB_SCOPE_REQUIRED` explaining LEADER-or-above, with no storage call, no mutation, and no audit row; reads remain available to plant-scoped users without job scope. [AC 8.4-5]
- Given an invalid upload (non-image content type, empty, or over the size limit), when submitted, then no storage call occurs and the API returns 400 `VALIDATION_ERROR` with fieldErrors. [AC 8.4-6]
- Given any successful upload/replace/remove, when inspected, then the immutable audit log contains actor, action, entity type SPAREPART, sparepart label, plant, and previous/new `image_object_key` snapshots (never URLs). [AC 8.4-7]

## Spec Change Log

- 2026-08-24: Implementation round 1 complete + review. Subagent delivered all Code Map items; 215 backend + 234 frontend tests green, tsc exit 0, biome no new diagnostics, V39 applied, Orval hooks/models regenerated, mutator FormData guard added.
- 2026-08-24: LIVE-EVIDENCE DEFECT FOUND & FIXED — live upload to real Garage failed with 502 OBJECT_STORAGE_ERROR. Root cause: AWS SDK v2.46.7 sends `Content-Encoding: aws-chunked` + `x-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER` + CRC32 trailer by default; Garage v2.3 rejects it as "Invalid payload signature" (boto3 plain-SigV4 PUT verified working, isolating the Java SDK). Fix: `GarageS3Config.s3ClientBuilder` now sets `.chunkedEncodingEnabled(false)`, sending non-chunked payloads with a real content hash. Regression test added (`GarageS3ConfigTest.s3Client_putObject_doesNotSendAwsChunkedOrTrailer`) that stubs a local HTTP server and asserts the wire request carries no aws-chunked/trailer/STREAMING headers. Full suite re-run: 221 backend tests green. Full live round-trip re-run on docker stack: upload 200 + key persisted + presigned GET fetches bytes + replace 200 (old object removed, bucket bytes 92=70+11+11) + delete 204 (bucket back to 2 objects) + audit CREATE/UPDATE/DELETE rows with imageObjectKey snapshots.
- 2026-08-24: Spec created (draft → ready-for-dev). Epic 8 context loaded (valid cache); continuity from spec-8-3 (done) and spec-8-1 (Garage) — ObjectStorageService contract read, GarageObjectStorageService/Properties/exception inspected, current SparepartEntity/DTOs/Service/Controller/audit-values state confirmed, orval-mutator checked for FormData/multipart implications. Decisions pinned: content-type whitelist jpeg/png/webp/gif (SVG XSS guard); key grammar `spareparts/{id}/{uuid}.{ext}` (browser-cache-collision avoidance); 5 MB default via typed `syncro.sparepart.image.max-bytes`; `ObjectStorageService.delete` added to contract (storage module owns S3); delete-when-absent = 204 idempotent no-op; GET-without-image = 404 `SPAREPART_IMAGE_NOT_FOUND`; audit entity type SPAREPART with key snapshots only; multipart via Orval FormData.

## Review Triage Log

- 2026-08-24 (final): BLOCKER found in live verification → fixed (Garage chunked/trailer incompatibility; `GarageS3Config.chunkedEncodingEnabled(false)` + wire-level regression test). All other findings triaged:
  - `SparepartLifetimeEvaluatorTest` stale Mockito stubs removed (harmless test cleanup, unrelated to 8-4, needed to keep suite green).
  - DTO deviation accepted: multipart binds `filename`/`contentType` params + `data` part instead of a single JSON DTO — API contract names preserved; documented in subagent report.
  - Pre-existing `sparepart-management.tsx` biome warnings (useSortedClasses/noUnusedVariables/noUnnecessaryConditions) at lines outside new diff hunks — out of scope, unchanged.
  - No orphans: replace deletes old object before storing new (proven live: bucket bytes 92 after replace = 70 new + 11 + 11 diagnostics; old 70-byte object gone).
  - Residual risk accepted: `SparepartLifetimeEvaluatorTest` change is unrelated; flagged for review.

## Design Notes

- **Why key contains a uuid segment:** browser caches images by URL; a stable key like `spareparts/{id}/image.png` would make a replaced image show stale bytes even though the presigned URL is new. A fresh uuid per upload forces a new URL → no cache poisoning.
- **Why `ObjectStorageService.delete` is added to the contract:** 8-1 deliberately left delete/replace to the image story; the sparepart module must not depend on `S3Client` (bounded context), so storage ownership stays in `storage` module. Garage `deleteObject` on a missing key is idempotent (no error), which is why remove-when-absent is a 204 no-op at the service level by simply not calling storage when key is null.
- **Why validate content type with a whitelist, not just `image/*`:** Garage serves objects with the stored Content-Type; a browser would execute `image/svg+xml` scripts. Whitelisting jpeg/png/webp/gif avoids XSS through an uploaded SVG while keeping the DTO pattern tight.
- **Continuity from 8-1/8-3:** reuses `ObjectStorageService` (with its host-bound presign contract), `JobScopeService`, plant masking, `errorResponse()`/dialog patterns, and the audit/`AuditEntityType.SPAREPART` convention. The 8-1 residual risk "mint presigned URLs on the host clients use" applies — the frontend already uses `NEXT_PUBLIC_API_URL` for API calls; the image preview must use the URL returned by the backend (minted for that host).
- **Multipart vs JSON:** the existing `syncroFetch` mutator sends `Content-Type: application/json` unless overridden; Orval multipart generates `FormData`, which works with the mutator's existing `if (!headers.has("Content-Type"))` guard only if the generated hook sends FormData without a JSON header — the implementation must verify the generated request body is `FormData` (Orval `multipart/form-data` schema) and, if the mutator forces JSON, extend it to skip setting Content-Type for `FormData` bodies (documented, tested).

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=SparepartImage*,Sparepart*,GarageObjectStorage*,JobScope*,AuditLogWiringIntegrationTest"` -- expected: BUILD SUCCESS (V39 applies from empty and prior state; gates/audit/no-orphan proven on Testcontainers PostgreSQL + mocked storage).
- `cd syncro/apps/web && npm run generate:snapshot && npm run generate:api && npm run test:unit` -- expected: image hooks generated; unit tests green.
- `npx biome check src/components/syncro/sparepart-image-upload.tsx src/features/master-data/spareparts/sparepart-management.tsx` -- expected: no new diagnostics beyond known pre-existing ones.

**Manual checks:**
- Boot stack (incl. garage); upload a small png via curl with JWT → 200 with presignedUrl; open the URL in browser → image renders; replace → old object gone from `garage bucket list`; delete → 204 and reference null in psql; `\d spareparts` shows `image_object_key`; audit_log rows show CREATE/UPDATE/DELETE with key snapshots.
