---
baseline_commit: bdcb998280bce3519e008e0d50f6a0204cae923b
---

# Story 2.5: Manage Spareparts

Status: review

## Story

As a SUPER_ADMIN or MANAGE user,
I want to create and maintain spareparts using taxonomy dimensions,
so that installed spareparts can be tracked consistently.

## Acceptance Criteria

1. Given taxonomy entries exist for category, brand, kind, and type, when an authenticated SUPER_ADMIN or MANAGE user creates a sparepart referencing one entry from each dimension, then the sparepart is persisted and visible in the sparepart list.
2. Given required taxonomy references are missing, null, unknown, or point to the wrong taxonomy dimension, when a sparepart create or update request is submitted, then the API rejects the request with the existing safe validation or not-found error shape.
3. Given sparepart code or name is blank, over length, malformed JSON is submitted, or UUID path/body values are invalid, when a mutation request is submitted, then the API returns stable safe error codes/messages and field errors where applicable.
4. Given a sparepart with the same code or name already exists using different casing or surrounding whitespace, when a user creates or updates another sparepart with that duplicate code or name, then the request is rejected safely.
5. Given the same taxonomy combination is used by multiple distinct spareparts, when codes and names are unique, then each sparepart is allowed.
6. Given spareparts exist, when an authenticated permitted user lists spareparts, then the list supports dense table display, predictable ordering, and filters by category, brand, kind, type, and free-text code/name search.
7. Given a sparepart exists, when an authenticated permitted user updates bounded fields or taxonomy references, then the sparepart is updated and remains visible with updated taxonomy labels.
8. Given a sparepart has no dependent machine installations, when an authenticated permitted user deletes it, then the sparepart is removed.
9. Given a sparepart is referenced by future machine installation records, when an authenticated permitted user deletes it, then the API returns a safe `409` data integrity conflict.
10. Given a VIEWER user, when the user opens sparepart management or calls sparepart list/detail APIs, then spareparts can be viewed.
11. Given a VIEWER user, when the user attempts create, update, or delete, then the server denies mutation with a safe forbidden response.
12. Given an unauthenticated request, when any sparepart endpoint is called, then the API returns the existing safe authentication error shape.
13. Given a MANAGE or VIEWER user with plant assignment limitations, when spareparts are listed, then global sparepart master data remains viewable because sparepart master data is not plant-scoped in Epic 2.
14. Given the web user has SUPER_ADMIN or MANAGE role, when the user opens sparepart management, then the user can create, edit, and delete spareparts from a dense desktop-oriented management view using taxonomy selectors.
15. Given the web user has VIEWER role, when the user opens sparepart management, then the UI shows read-only state and hides or disables mutation actions.
16. Given sparepart data or taxonomy selector data is loading, empty, failed, read-only, or forbidden, when the web UI renders, then the user sees durable visible states for loading skeleton, empty setup prompt, API error with retry, read-only badge/state, and forbidden access.
17. Given API client generation is required, when backend OpenAPI changes are complete, then the generated Orval client is refreshed and the web feature uses generated hooks/types instead of handwritten fetch calls.

## Tasks / Subtasks

- [x] Create sparepart persistence model and migration (AC: 1, 2, 4, 5, 8, 9)
  - [x] Add Flyway migration `V7__create_spareparts.sql` after `V6__create_sparepart_taxonomy.sql`.
  - [x] Create `spareparts` table with UUID primary key, `code`, `name`, category/brand/kind/type taxonomy FK columns, optional bounded description/notes if needed, and timestamps.
  - [x] Add FK constraints from each taxonomy column to `sparepart_taxonomy(id)`.
  - [x] Add database constraints for non-blank and bounded `code`/`name`.
  - [x] Add case-insensitive unique indexes for sparepart `code` and `name`.
  - [x] Add lookup indexes for taxonomy FK filters.
  - [x] Use project constraint names: `uq_<table>_<columns>`, `idx_<table>_<columns>`, `ck_<table>_<rule>`.
- [x] Add backend sparepart module classes under existing `com.syncro.sparepart` bounded context (AC: 1-13)
  - [x] Add `SparepartEntity` and `SparepartRepository` under `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/`.
  - [x] Add request/view/list DTO records under `SparepartDtos`; do not serialize JPA entities from controllers.
  - [x] Add `SparepartService` with `@Transactional` mutation methods and read-only list/get methods.
  - [x] Normalize `code` and `name` by trimming whitespace before duplicate checks and persistence.
  - [x] Reject duplicate sparepart `code`/`name` case-insensitively in service and map database unique violations as race fallback.
  - [x] Validate taxonomy references exist and match expected dimensions: category=`CATEGORY`, brand=`BRAND`, kind=`KIND`, type=`TYPE`.
  - [x] Keep spareparts global in Epic 2; do not inject or apply `PlantScopeService` for sparepart list/get/mutation unless later requirements make spareparts plant-scoped.
  - [x] Map delete FK/integrity failures to safe conflict exception for future Story 2.6 installation references.
- [x] Add backend API contracts and safe error handling (AC: 2, 3, 9, 11, 12)
  - [x] Add controller under `/api/v1/spareparts` with explicit OpenAPI `operationId`s.
  - [x] Support list filters: categoryId, brandId, kindId, typeId, and search.
  - [x] Add create, update, get, and delete endpoints using request/response DTO records.
  - [x] Add validation annotations for required taxonomy UUIDs, `code`, `name`, and bounded optional fields.
  - [x] Add OpenAPI response annotations for `200`, `201`, `204`, `400`, `401`, `403`, `404`, and `409` where applicable.
  - [x] Add scoped exception handler mapping duplicate, not found, invalid taxonomy dimension, forbidden mutation, validation, malformed JSON, and data integrity cases to safe stable error codes/messages.
- [x] Add backend tests with story IDs and priority markers (AC: 1-13)
  - [x] Add MockMvc controller tests for authN, VIEWER mutation denial, MANAGE/SUPER_ADMIN mutation success, validation field errors, malformed JSON, invalid path/query values, duplicate safe errors, taxonomy not found/dimension mismatch, sparepart not found, and delete conflict mapping.
  - [x] Add PostgreSQL Testcontainers service integration tests for persistence, taxonomy FK validation, taxonomy dimension validation, duplicate code/name rejection, cross-taxonomy-combination allowance, list filtering/ordering/search, VIEWER read access, VIEWER mutation denial, and delete without dependents.
  - [x] Add database-level unique index tests proving case-insensitive `code` and `name` conflicts.
  - [x] If no real machine installation table exists yet, document delete conflict as controller-level mocked mapping or use transactional ad-hoc FK table only if needed; Story 2.6 should replace this with real installation FK proof.
  - [x] Use display names like `2.5-API-001 P0 ...` and `2.5-SVC-001 P1 ...`.
- [x] Generate API client and use generated hooks/types (AC: 17)
  - [x] Run backend OpenAPI generation path already used by previous stories.
  - [x] Run Orval generation for web client.
  - [x] Use generated model types and TanStack Query hooks from `syncro/apps/web/src/lib/api/generated/`; do not hand edit generated files.
- [x] Build sparepart management UI (AC: 6, 10, 14, 15, 16, 17)
  - [x] Extend `syncro/apps/web/src/app/(main)/dashboard/master-data/spareparts/page.tsx` as thin route composition only.
  - [x] Keep feature logic under `syncro/apps/web/src/features/master-data/spareparts/`.
  - [x] Preserve existing taxonomy management UI and add sparepart CRUD without regressing taxonomy create/edit/delete.
  - [x] Use shadcn/Radix controls, especially non-native `Select`/dropdown components, for taxonomy selectors and filters.
  - [x] Render dense desktop table with code, name, category, brand, kind, type, created/updated metadata, filters, and actions.
  - [x] Show VIEWER read-only state with mutation controls hidden or disabled.
  - [x] Show loading skeleton, empty setup prompt, API error with retry, read-only badge, forbidden state, taxonomy-missing prompt, and duplicate validation message.
- [x] Verify and document evidence (AC: all)
  - [x] Run targeted backend tests for sparepart controller/service.
  - [x] Run API generation and web Orval generation.
  - [x] Run web lint/type checks used by recent stories.
  - [x] If UI changes are made, start the dev server and test golden path plus edge states in browser before marking done.
  - [x] Record commands and results in Dev Agent Record.

## Dev Notes

### Source Context

- Epic 2 objective is to let SUPER_ADMIN and MANAGE configure plant, machine group, machine, sparepart taxonomy, spareparts, installed spareparts, and responsibility so Syncro knows what exists and who owns response. [Source: _bmad-output/planning-artifacts/epics.md#Epic 2]
- Story 2.5 covers sparepart CRUD using taxonomy dimensions and prepares the master data needed by Story 2.6 machine sparepart installation. [Source: _bmad-output/planning-artifacts/epics.md#Story 2.5]
- FR-015 requires authorized users to create, read, update, and delete spareparts referencing taxonomy dimensions. FR-016 and later stories depend on these spareparts for machine installation and lifetime calculation. [Source: _bmad-output/planning-artifacts/epics.md#Functional Requirements]
- The setup journey expects users to create Sparepart Taxonomy and Sparepart before installing spareparts on machines. [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Setup Flow]

### Architecture Constraints

- Backend source belongs under `syncro/apps/backend/src/main/java/com/syncro/`; modules use `api`, `application`, `domain`, and `infrastructure` layers. [Source: _bmad-output/planning-artifacts/architecture.md#Structure Patterns]
- Keep sparepart work inside existing `com.syncro.sparepart` bounded context created by Story 2.4.
- Controllers bind/validate DTOs and delegate to services. Application services own transaction boundaries. JPA entities must not be serialized through controllers. [Source: _bmad-output/planning-artifacts/architecture.md#Architectural Boundaries]
- PostgreSQL is the master-data source of truth and Flyway owns schema evolution; do not use `ddl-auto=update`. [Source: _bmad-output/project-context.md]
- REST APIs use `/api/v1` and plural nouns. Table names use plural snake_case. JSON fields use camelCase. Enum values are uppercase. [Source: _bmad-output/planning-artifacts/architecture.md#Naming Patterns]
- Error responses must use stable `code`, safe `message`, optional `fieldErrors`, `timestamp`, and `traceId`; do not leak SQL or stack details. [Source: _bmad-output/planning-artifacts/architecture.md#Format Patterns]
- Frontend route files under `app/` should compose only; feature logic belongs under `src/features/*`. [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Boundaries]
- Starting Epic 2, frontend data APIs use Orval-generated TypeScript clients and TanStack Query hooks from backend OpenAPI. [Source: _bmad-output/planning-artifacts/architecture.md#API & Communication Patterns]

### Existing Code Context to Reuse

- Story 2.4 added taxonomy domain under `syncro/apps/backend/src/main/java/com/syncro/sparepart/`; reuse this bounded context rather than creating a new package.
- `SparepartTaxonomyEntity` persists taxonomy rows in `sparepart_taxonomy` with `dimension`, `code`, `name`, and timestamps. [Source: syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartTaxonomyEntity.java]
- `SparepartTaxonomyRepository` already exposes dimension-specific lookup methods such as `findByDimensionAndCodeIgnoreCase` and `findByDimensionAndNameIgnoreCase`; use repository lookups to validate category/brand/kind/type IDs point to correct dimensions. [Source: syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartTaxonomyRepository.java]
- `SparepartTaxonomyDtos` uses Jakarta validation records with `@NotNull`, `@NotBlank`, and `@Size(max = ...)`; follow same DTO style for sparepart request records. [Source: syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartTaxonomyDtos.java]
- `SparepartTaxonomyService` trims values, validates dimensions, checks duplicate code/name, catches database unique violations, maps integrity failures safely, and restricts mutation to SUPER_ADMIN/MANAGE; mirror this pattern for spareparts. [Source: syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartTaxonomyService.java]
- `MachineGroupService` is still the reference for plant-scope enforcement where data is plant-scoped, but sparepart master data in this story is global, like taxonomy. Do not copy plant-scope filtering into sparepart list/get/mutation. [Source: syncro/apps/backend/src/main/java/com/syncro/masterdata/application/MachineGroupService.java]
- `SparepartTaxonomyController` uses explicit OpenAPI `operationId`s to stabilize generated Orval names; add explicit `operationId`s for sparepart endpoints. [Source: syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartTaxonomyController.java]
- Current migration sequence ends at `V6__create_sparepart_taxonomy.sql`; next migration should be `V7__create_spareparts.sql`. [Source: syncro/apps/backend/src/main/resources/db/migration]
- `V6__create_sparepart_taxonomy.sql` defines taxonomy constraints and FK target table for this story. [Source: syncro/apps/backend/src/main/resources/db/migration/V6__create_sparepart_taxonomy.sql]

### Frontend Context to Preserve

- Current spareparts route is thin composition and renders `SparepartTaxonomyManagement` inside `RoleGuard` for SUPER_ADMIN/MANAGE/VIEWER. Preserve this route-shell pattern. [Source: syncro/apps/web/src/app/(main)/dashboard/master-data/spareparts/page.tsx]
- Existing taxonomy management UI already imports generated hooks/types, uses TanStack Query invalidation, shadcn/Radix `Select`, dialogs, table, skeleton, retry error state, read-only badges, and toast error handling. Reuse these patterns for sparepart management. [Source: syncro/apps/web/src/features/master-data/spareparts/sparepart-taxonomy-management.tsx]
- User preference: use non-native shadcn/Radix selects, dropdowns, and pickers over native controls.
- UX requires dense desktop admin/table patterns for spareparts and durable loading, empty, error, read-only, and forbidden states. [Source: _bmad-output/planning-artifacts/ux-design-specification.md#UX Consistency Patterns]

### Security and Authorization Notes

- All mutation authorization must be enforced backend-side. UI hiding buttons is not security. [Source: _bmad-output/project-context.md]
- SUPER_ADMIN and MANAGE can mutate spareparts; VIEWER can list/get spareparts but cannot mutate.
- Sparepart master data is global in Epic 2. Do not hide spareparts by plant assignment unless later stories make spareparts plant-scoped.
- Unauthenticated requests must keep existing `AUTHENTICATION_REQUIRED` shape through Spring Security.
- Safe errors must not expose SQL, stack traces, FK names, or implementation detail.

### Testing Standards

- Every acceptance criterion needs automated or clearly documented manual evidence. [Source: _bmad-output/project-context.md]
- Use PostgreSQL Testcontainers for uniqueness, constraints, FK behavior, and transactional behavior; do not mock persistence for database uniqueness. [Source: _bmad-output/project-context.md]
- Tests must include story IDs and priority markers in display names, following Stories 2.2-2.4 patterns.
- Avoid hard waits/sleeps. Use fixed `Instant`/`Clock` and generated UUID isolation.
- Keep test files focused; if controller or service integration tests exceed roughly 300 lines, split by API/auth/validation or service/constraint/CRUD concerns.

### Previous Story Intelligence

- Story 2.4 review found generated client export names can shift when operation IDs are not explicit; Story 2.5 must use explicit OpenAPI operation IDs from the start.
- Story 2.4 delete conflict proof used an ad-hoc FK because no sparepart table existed. Story 2.5 should introduce real FKs from `spareparts` to taxonomy and should make taxonomy delete conflict proof stronger.
- Story 2.4 browser-level evidence was limited to dev-server HTTP smoke because no browser automation tool was available. If UI is changed, dev server and browser/manual smoke evidence still must be recorded honestly.
- Story 2.4 OpenAPI generation hit local DB Flyway checksum mismatch; avoid repairing/resetting local DB without explicit user approval. Prefer the temporary PostgreSQL generation path that succeeded previously if needed.

### Project Structure Notes

Expected new or updated files:

```text
syncro/apps/backend/src/main/resources/db/migration/V7__create_spareparts.sql
syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartEntity.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartRepository.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartService.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartDtos.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartController.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartExceptionHandler.java
syncro/apps/backend/src/test/java/com/syncro/sparepart/api/SparepartControllerTest.java
syncro/apps/backend/src/test/java/com/syncro/sparepart/application/SparepartServiceIntegrationTest.java
syncro/apps/web/src/app/(main)/dashboard/master-data/spareparts/page.tsx
syncro/apps/web/src/features/master-data/spareparts/sparepart-management.tsx
syncro/apps/web/src/features/master-data/spareparts/sparepart-taxonomy-management.tsx
syncro/apps/web/src/lib/api/generated/*
```

The existing spareparts page currently shows taxonomy management only. This story should add sparepart CRUD while preserving taxonomy management because taxonomy remains setup prerequisite for spareparts.

### Dependencies and Sequencing

- Story 2.5 depends on Story 2.4 taxonomy being complete.
- Story 2.6 will install spareparts on machines and should create real dependent rows referencing `spareparts`. Design this story so Story 2.6 can add foreign keys without reworking sparepart identity.
- Avoid adding machine sparepart installation, expected lifetime, baseline counter, threshold, setup completeness, responsibility assignment, telemetry, or alert behavior in this story.
- Avoid implementing audit log in this story except where unavoidable in existing shared patterns; immutable master-data audit belongs to Story 2.9.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.5]
- [Source: _bmad-output/planning-artifacts/architecture.md#Naming Patterns]
- [Source: _bmad-output/planning-artifacts/architecture.md#Architectural Boundaries]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Setup Flow]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#UX Consistency Patterns]
- [Source: _bmad-output/project-context.md]
- [Source: _bmad-output/implementation-artifacts/2-4-manage-sparepart-taxonomy.md]
- [Source: syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartTaxonomyService.java]
- [Source: syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartTaxonomyController.java]
- [Source: syncro/apps/web/src/features/master-data/spareparts/sparepart-taxonomy-management.tsx]

## Dev Agent Record

### Agent Model Used

cx/gpt-5.5

### Debug Log References

- `mvn -f "syncro/apps/backend/pom.xml" "-Dtest=SparepartControllerTest,SparepartServiceIntegrationTest" test` — PASS, 36 tests.
- `npm --prefix "syncro/apps/web" run generate:api` — PASS.
- `npm --prefix "syncro/apps/web" run check` — PASS, 89 files.
- `npm --prefix "syncro/apps/web" run build` — PASS.
- `npm --prefix "syncro/apps/web" run dev -- --port 3001` plus HTTP smoke `http://localhost:3001/dashboard/master-data/spareparts` — PASS. Browser automation was not available in this session, so UI verification is limited to route compilation/HTTP smoke plus build checks.

### Completion Notes List

- Added global `spareparts` table with required taxonomy references, case-insensitive unique code/name indexes, lookup indexes, and safe delete-conflict behavior.
- Added `/api/v1/spareparts` CRUD/list API with explicit OpenAPI operation IDs, generated DTO contracts, safe exception mapping, and SUPER_ADMIN/MANAGE mutation authorization.
- Added backend controller and PostgreSQL integration tests covering auth, validation, duplicate handling, taxonomy reference/dimension validation, global list/filter/search, and delete behavior.
- Regenerated Orval client and built sparepart management UI using generated hooks/types, shadcn/Radix selectors, dense table, filters, dialogs, loading/empty/error/read-only/taxonomy-missing states.
- Preserved taxonomy management UI on same spareparts route.

### File List

- `syncro/apps/backend/src/main/resources/db/migration/V7__create_spareparts.sql`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartRepository.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartService.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartDtos.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartController.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartExceptionHandler.java`
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/api/SparepartControllerTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/application/SparepartServiceIntegrationTest.java`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/spareparts/page.tsx`
- `syncro/apps/web/src/features/master-data/spareparts/sparepart-management.tsx`
- `syncro/apps/web/src/lib/api/generated/syncro.ts`
- `syncro/apps/web/src/lib/api/generated/model/*`
- `_bmad-output/implementation-artifacts/2-5-manage-spareparts.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Change Log

- 2026-05-28: Created ready-for-dev Story 2.5 context for sparepart CRUD.
- 2026-05-28: Implemented Story 2.5 sparepart persistence, API, tests, generated client, frontend UI, and validation evidence.
