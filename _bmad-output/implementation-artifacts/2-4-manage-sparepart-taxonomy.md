---
baseline_commit: e2129017418f8058b7bfecacac530287aa3815a3
---

# Story 2.4: Manage Sparepart Taxonomy

Status: done

## Story

As a SUPER_ADMIN or MANAGE user,
I want to manage sparepart category, brand, kind, and type taxonomy,
so that spareparts are normalized and searchable.

## Acceptance Criteria

1. Given an authenticated SUPER_ADMIN or MANAGE user, when the user creates taxonomy entries for category, brand, kind, or type, then entries are persisted and available for later sparepart creation.
2. Given an existing taxonomy value in a dimension, when a user creates or updates another entry in the same dimension with the same value using different casing or surrounding whitespace, then the request is rejected with a safe duplicate error.
3. Given the same taxonomy value is needed in different dimensions, when the user creates entries with the same value under different dimensions, then both entries are allowed.
4. Given taxonomy entries exist, when an authenticated permitted user lists taxonomy entries, then entries are returned grouped or filterable by dimension and ordered predictably for UI selection.
5. Given an authenticated permitted user updates a taxonomy entry, when the request changes the name/code within valid bounds, then the entry is updated without changing its dimension identity.
6. Given a taxonomy entry has no dependent spareparts, when an authenticated permitted user deletes it, then the entry is removed.
7. Given a taxonomy entry is referenced by future sparepart records, when an authenticated permitted user deletes it, then the API returns a safe `409` data integrity conflict.
8. Given a VIEWER user, when the user opens taxonomy management or calls taxonomy list APIs, then taxonomy can be viewed.
9. Given a VIEWER user, when the user attempts create, update, or delete, then the server denies mutation with a safe forbidden response.
10. Given an unauthenticated request, when any taxonomy endpoint is called, then the API returns the existing safe authentication error shape.
11. Given malformed JSON, invalid enum/dimension values, blank names/codes, or over-length values, when a mutation request is submitted, then the API returns the existing safe validation or malformed JSON error shape with stable error codes and field errors where applicable.
12. Given a MANAGE or VIEWER user with plant assignment limitations, when taxonomy is listed, then global taxonomy remains viewable because taxonomy is not plant-scoped in Epic 2; plant-scoped authorization must not accidentally hide global taxonomy.
13. Given the web user has SUPER_ADMIN or MANAGE role, when the user opens the sparepart taxonomy management UI, then the user can create, edit, and delete category, brand, kind, and type entries from dense desktop-oriented management views.
14. Given the web user has VIEWER role, when the user opens the taxonomy UI, then the UI shows read-only state and hides or disables mutation actions.
15. Given taxonomy data is loading, empty, failed, read-only, or forbidden, when the web UI renders, then the user sees durable visible states for loading skeleton, empty setup prompt, API error with retry, read-only badge/state, and forbidden access.
16. Given API client generation is required, when backend OpenAPI changes are complete, then the generated Orval client is refreshed and the web feature uses generated hooks/types instead of handwritten fetch calls.

## Tasks / Subtasks

- [x] Create sparepart taxonomy persistence model and migration (AC: 1, 2, 3, 5, 6, 7)
  - [x] Add Flyway migration `V6__create_sparepart_taxonomy.sql` after `V5__create_machines.sql`.
  - [x] Create `sparepart_taxonomy` table with UUID primary key, dimension enum/string, normalized display value/code, timestamps, and database constraints.
  - [x] Add case-insensitive unique index on `(dimension, lower(name))` or equivalent chosen value field.
  - [x] Add check constraints for supported dimensions: `CATEGORY`, `BRAND`, `KIND`, `TYPE`.
  - [x] Add length and non-blank database checks mirroring DTO validation.
  - [x] Use constraint names following project style: `uq_<table>_<columns>`, `idx_<table>_<columns>`, `ck_<table>_<rule>`.
- [x] Add backend sparepart taxonomy module (AC: 1-12)
  - [x] Create package structure under `syncro/apps/backend/src/main/java/com/syncro/sparepart/` with `api`, `application`, `domain`, and `infrastructure` layers.
  - [x] Add domain enum for taxonomy dimension with uppercase values: `CATEGORY`, `BRAND`, `KIND`, `TYPE`.
  - [x] Add JPA entity and repository for taxonomy entries; do not serialize entities from controllers.
  - [x] Add application service with `@Transactional` mutation methods and read-only list/get methods.
  - [x] Normalize names/codes by trimming whitespace before duplicate checks and persistence.
  - [x] Reject same-dimension duplicates case-insensitively in service and map database unique violations as race fallback.
  - [x] Allow same visible value across different dimensions.
  - [x] Keep taxonomy global, not plant-scoped; do not require `PlantScopeService` for list/get/mutation beyond existing role authorization.
  - [x] Preserve dimension identity on update; reject attempts to move an entry between dimensions.
  - [x] Map delete FK/integrity failures to a safe conflict exception.
- [x] Add backend API contracts and safe error handling (AC: 4, 7, 9, 10, 11)
  - [x] Add controller under `/api/v1/sparepart-taxonomy` or `/api/v1/sparepart-taxonomies`; keep resource path plural and aligned with generated operation names.
  - [x] Support list by optional dimension filter or grouped response; choose shape that keeps frontend simple and OpenAPI stable.
  - [x] Add create, update, get, and delete endpoints using request/response DTO records.
  - [x] Add validation annotations for required dimension/name/code and bounded lengths.
  - [x] Add OpenAPI response annotations for `200`, `201`, `204`, `400`, `401`, `403`, `404`, and `409` where applicable.
  - [x] Add exception handler mapping duplicate, not found, forbidden mutation, invalid dimension, and data integrity cases to safe stable error codes/messages.
- [x] Add backend tests with story IDs and priority markers (AC: 1-12)
  - [x] Add MockMvc controller tests for authN, VIEWER mutation denial, MANAGE/SUPER_ADMIN mutation success, validation field errors, malformed JSON, invalid path/query values, duplicate safe errors, not found, and delete conflict mapping.
  - [x] Add PostgreSQL Testcontainers service integration tests for persistence, case-insensitive duplicate rejection, cross-dimension duplicate allowance, update preserving dimension, list ordering/filtering, VIEWER read access, VIEWER mutation denial, and delete without dependents.
  - [x] Add database-level unique index test proving `Electric` and `electric` conflict within the same dimension.
  - [x] If no real dependent sparepart table exists yet, document delete conflict as controller-level mocked mapping or use a transactional ad-hoc FK table only if needed; prefer delaying real FK proof until Story 2.5 introduces spareparts.
  - [x] Use display names like `2.4-API-001 P0 ...` and `2.4-SVC-001 P1 ...`.
- [x] Generate API client and use generated hooks/types (AC: 16)
  - [x] Run backend OpenAPI generation path already used by previous stories.
  - [x] Run Orval generation for web client.
  - [x] Use generated model types and TanStack Query hooks from `syncro/apps/web/src/lib/api/generated/`; do not hand edit generated files.
- [x] Build taxonomy management UI (AC: 13, 14, 15, 16)
  - [x] Replace spareparts placeholder or add a taxonomy-focused feature under `syncro/apps/web/src/features/master-data/spareparts/` or a clearly named taxonomy subfeature.
  - [x] Keep route composition thin under `syncro/apps/web/src/app/(main)/dashboard/master-data/spareparts/page.tsx`.
  - [x] Use shadcn/Radix controls, especially non-native select/tabs/dropdowns, for dimension selection and actions.
  - [x] Render four dimensions: category, brand, kind, type; support create/edit/delete per dimension for mutating roles.
  - [x] Show VIEWER read-only state with mutation controls hidden or disabled.
  - [x] Show loading skeleton, empty setup prompt, API error with retry, forbidden state, and duplicate validation message.
  - [x] Keep dense desktop table/card layout aligned with existing master-data screens.
- [x] Verify and document evidence (AC: all)
  - [x] Run targeted backend tests for taxonomy controller/service.
  - [x] Run web lint/type checks used by recent stories.
  - [x] If UI changes are made during implementation, start the dev server and test golden path plus edge states in browser before marking done.
  - [x] Record commands and results in Dev Agent Record.

### Review Findings

- [x] [Review][Patch] Taxonomy `code` contract missing end-to-end — AC5/AC11 and story subtasks mention name/code, normalized display value/code, and required dimension/name/code. Decision: add `code` now as first-class taxonomy field.
- [x] [Review][Patch] Update silently ignores requested dimension changes instead of rejecting them [syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartTaxonomyService.java:55]
- [x] [Review][Patch] Delete conflict closes dialog and loses retry/error context [syncro/apps/web/src/features/master-data/spareparts/sparepart-taxonomy-management.tsx:232]
- [x] [Review][Patch] Generated client export names shifted and can break existing callers [syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartTaxonomyController.java:39]

## Dev Notes

### Source Context

- Epic 2 objective is to let SUPER_ADMIN and MANAGE configure plant, machine group, machine, sparepart taxonomy, spareparts, installed spareparts, and responsibility so Syncro knows what exists and who owns response. [Source: _bmad-output/planning-artifacts/epics.md#Epic 2]
- Story 2.4 specifically covers sparepart category, brand, kind, and type taxonomy with duplicate rejection, safe validation/malformed JSON errors, UI loading/empty/error/read-only/forbidden states, and VIEWER read-only behavior. [Source: _bmad-output/planning-artifacts/epics.md#Story 2.4]
- FR-014 requires normalized sparepart taxonomy for category, brand, kind, and type. FR-015 depends on this taxonomy for sparepart CRUD in the next story. [Source: _bmad-output/planning-artifacts/epics.md#Functional Requirements]

### Architecture Constraints

- Backend source belongs under `syncro/apps/backend/src/main/java/com/syncro/`; modules use `api`, `application`, `domain`, and `infrastructure` layers. [Source: _bmad-output/planning-artifacts/architecture.md#Unified Project Structure]
- Use new `com.syncro.sparepart` bounded context for this story; no existing `sparepart` backend package exists at story creation time.
- Controllers must bind/validate DTOs and delegate to services. Application services own transaction boundaries. JPA entities must not be serialized through controllers. [Source: _bmad-output/planning-artifacts/architecture.md#Backend Architecture]
- PostgreSQL is the master-data source of truth and Flyway owns schema evolution; do not use `ddl-auto=update`. [Source: _bmad-output/project-context.md]
- Resource paths use plural nouns under `/api/v1`; table names use plural snake_case; indexes use `idx_<table>_<columns>`; unique constraints use `uq_<table>_<columns>`; enum values are uppercase. [Source: _bmad-output/planning-artifacts/architecture.md#API Design and Data Models]
- Error responses must use stable `code`, safe `message`, optional `fieldErrors`, `timestamp`, and `traceId`; do not leak SQL or stack details. [Source: _bmad-output/project-context.md]

### Existing Patterns to Reuse

- `MachineGroupController` uses `/api/v1/machine-groups`, `@AuthenticationPrincipal AuthenticatedUser`, DTO records, `@Valid @RequestBody`, OpenAPI annotations, and `ResponseEntity.created(...)`. [Source: syncro/apps/backend/src/main/java/com/syncro/masterdata/api/MachineGroupController.java]
- `MachineGroupDtos` uses Jakarta validation records with `@NotNull`, `@NotBlank`, and `@Size(max = 255)`. [Source: syncro/apps/backend/src/main/java/com/syncro/masterdata/api/MachineGroupDtos.java]
- `MachineGroupService` trims names, checks duplicates before save, catches database unique violations as race fallback, maps integrity failures safely, and restricts mutation to SUPER_ADMIN/MANAGE. [Source: syncro/apps/backend/src/main/java/com/syncro/masterdata/application/MachineGroupService.java]
- `MachineGroupControllerTest` is the closest controller test pattern for authN/authZ, validation, malformed JSON, duplicate safe errors, invalid path/query values, and delete conflict mapping. [Source: syncro/apps/backend/src/test/java/com/syncro/masterdata/api/MachineGroupControllerTest.java]
- `MachineGroupServiceIntegrationTest` is the closest service/DB pattern for Testcontainers, transactional isolation, case-insensitive uniqueness, role behavior, and generated UUID fixtures. [Source: syncro/apps/backend/src/test/java/com/syncro/masterdata/application/MachineGroupServiceIntegrationTest.java]
- `V5__create_machines.sql` shows current migration style for check constraints, foreign keys, and unique indexes. Next migration should be `V6__create_sparepart_taxonomy.sql`. [Source: syncro/apps/backend/src/main/resources/db/migration/V5__create_machines.sql]

### Frontend Constraints

- Frontend route files under `app/` should compose only; feature logic belongs under `src/features/*`. [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Architecture]
- Existing placeholder route is `syncro/apps/web/src/app/(main)/dashboard/master-data/spareparts/page.tsx`; it currently renders `ModulePlaceholder` with taxonomy references, sparepart list, and dense table placeholder. Replace or extend this route carefully. [Source: syncro/apps/web/src/app/(main)/dashboard/master-data/spareparts/page.tsx]
- Existing master-data feature pattern is `syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx`: shadcn/Radix `Select`, dialogs, table, skeleton, retry error state, read-only badges, generated Orval hooks/types, and toast error handling. [Source: syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx]
- User preference: use non-native shadcn/Radix selects, dropdowns, and pickers over native controls.
- UX requires setup flow from Plant → Machine Group → Machine → Sparepart Taxonomy and Sparepart → Install Sparepart, with clear validation and visible loading/empty/error/read-only/forbidden states. [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Setup Flow]

### Security and Authorization Notes

- All mutation authorization must be enforced backend-side. UI hiding buttons is not enough. [Source: _bmad-output/project-context.md]
- VIEWER can view taxonomy but cannot mutate it. Mutating endpoints must deny VIEWER with safe `403` response.
- Taxonomy is global master data for future spareparts, not plant-scoped. Do not copy plant-scope filtering from machine groups unless a later product requirement explicitly makes taxonomy plant-scoped.
- Unauthenticated requests must keep existing `AUTHENTICATION_REQUIRED` shape through Spring Security.

### Testing Standards

- Every acceptance criterion needs automated or clearly documented manual evidence. [Source: _bmad-output/project-context.md]
- Use PostgreSQL Testcontainers for uniqueness, constraints, and transactional behavior; do not mock persistence for database uniqueness. [Source: _bmad-output/project-context.md]
- Tests must include story IDs and priority markers in display names, following Story 2.2 and 2.3 patterns.
- Avoid hard waits/sleeps. Use fixed `Instant`/`Clock` and generated UUID isolation.
- Keep test files focused; if controller or service integration tests exceed roughly 300 lines, split by API/auth/validation or service/constraint/CRUD concerns.

### Project Structure Notes

Expected new or updated files:

```text
syncro/apps/backend/src/main/resources/db/migration/V6__create_sparepart_taxonomy.sql
syncro/apps/backend/src/main/java/com/syncro/sparepart/domain/SparepartTaxonomyDimension.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartTaxonomyEntity.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartTaxonomyRepository.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartTaxonomyService.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartTaxonomyDtos.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartTaxonomyController.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartTaxonomyExceptionHandler.java
syncro/apps/backend/src/test/java/com/syncro/sparepart/api/SparepartTaxonomyControllerTest.java
syncro/apps/backend/src/test/java/com/syncro/sparepart/application/SparepartTaxonomyServiceIntegrationTest.java
syncro/apps/web/src/app/(main)/dashboard/master-data/spareparts/page.tsx
syncro/apps/web/src/features/master-data/spareparts/sparepart-taxonomy-management.tsx
syncro/apps/web/src/lib/api/generated/*
```

No backend sparepart package existed when this story was created, so most backend files are new. The spareparts route exists as a placeholder and should become the route composition entry point for taxonomy management.

### Dependencies and Sequencing

- Story 2.4 depends on Story 2.1-2.3 platform/master-data patterns being complete.
- Story 2.5 will create spareparts referencing taxonomy. Design this story so Story 2.5 can add foreign keys from spareparts to taxonomy without reworking taxonomy identity.
- Avoid adding sparepart CRUD in this story. Scope is taxonomy only.
- Avoid implementing setup completeness, installation, or responsibility behavior; those belong to later stories.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.4]
- [Source: _bmad-output/planning-artifacts/architecture.md#Unified Project Structure]
- [Source: _bmad-output/planning-artifacts/architecture.md#API Design and Data Models]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Setup Flow]
- [Source: _bmad-output/planning-artifacts/page-specifications.md#State Patterns]
- [Source: _bmad-output/project-context.md]
- [Source: _bmad-output/implementation-artifacts/2-3-manage-machines-with-manual-active-state.md]
- [Source: syncro/apps/backend/src/main/java/com/syncro/masterdata/application/MachineGroupService.java]
- [Source: syncro/apps/backend/src/test/java/com/syncro/masterdata/api/MachineGroupControllerTest.java]
- [Source: syncro/apps/backend/src/test/java/com/syncro/masterdata/application/MachineGroupServiceIntegrationTest.java]
- [Source: syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx]

## Dev Agent Record

### Agent Model Used

cx/gpt-5.5

### Debug Log References

- RED backend tests failed as expected before implementation: missing `com.syncro.sparepart` service/domain/API classes.
- Targeted backend taxonomy tests passed: `mvn -f "syncro/apps/backend/pom.xml" "-Dtest=SparepartTaxonomyControllerTest,SparepartTaxonomyServiceIntegrationTest" test` — 29 tests, 0 failures, 0 errors.
- First OpenAPI generation run failed without `SERVER_PORT`; local profile run then failed because local database Flyway migration v5 checksum differed. Local DB was not repaired or reset.
- OpenAPI generation succeeded using temporary PostgreSQL on port `65432`, then `npm --prefix "syncro/apps/web" run generate:api` refreshed Orval client.
- Web checks passed: `npm --prefix "syncro/apps/web" run check`.
- Web production build passed: `npm --prefix "syncro/apps/web" run build`.
- Review patch backend tests passed: `mvn -f "syncro/apps/backend/pom.xml" "-Dtest=SparepartTaxonomyControllerTest,SparepartTaxonomyServiceIntegrationTest" test` — 32 tests, 0 failures, 0 errors.
- Review patch web checks passed: `npm --prefix "syncro/apps/web" run check`.
- Review patch web production build passed: `npm --prefix "syncro/apps/web" run build`.
- UI smoke passed: Next dev server served `/dashboard/master-data/spareparts` with HTTP 200 on port `3001`.

### Completion Notes List

- Added global sparepart taxonomy persistence with database dimension, non-blank, length, and case-insensitive same-dimension uniqueness constraints.
- Added backend taxonomy service, API DTOs, controller, and scoped exception handler with safe duplicate, validation, malformed JSON, not-found, forbidden, and conflict responses.
- Enforced mutation authorization server-side for SUPER_ADMIN/MANAGE while allowing VIEWER read access.
- Preserved taxonomy dimension identity on update by applying only name changes to existing entry dimension.
- Added MockMvc and PostgreSQL Testcontainers coverage for API/auth/error mapping and service/database constraints.
- Refreshed generated Orval client and wired taxonomy UI through generated hooks/types.
- Replaced spareparts placeholder with taxonomy management UI showing four dimensions, read-only state, loading skeleton, empty state, retryable API error state, duplicate validation messages, and mutation actions for permitted roles.
- Adjusted existing plant, machine, and machine-group feature imports/usages after OpenAPI operation order renumbered generated hook names.
- Browser-level interaction was limited to dev-server HTTP smoke because no browser automation tool is available in this session.
- Review patches added first-class taxonomy code contract, rejected dimension moves, preserved delete dialog errors, and stabilized generated API client names with explicit operation IDs.

### File List

- `_bmad-output/implementation-artifacts/2-4-manage-sparepart-taxonomy.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `syncro/apps/backend/src/main/resources/db/migration/V6__create_sparepart_taxonomy.sql`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/domain/SparepartTaxonomyDimension.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartTaxonomyEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartTaxonomyRepository.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartTaxonomyService.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartTaxonomyDtos.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartTaxonomyController.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartTaxonomyExceptionHandler.java`
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/api/PlantController.java`
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/api/MachineGroupController.java`
- `syncro/apps/backend/src/main/java/com/syncro/machine/api/MachineController.java`
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/api/SparepartTaxonomyControllerTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/application/SparepartTaxonomyServiceIntegrationTest.java`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/spareparts/page.tsx`
- `syncro/apps/web/src/features/master-data/spareparts/sparepart-taxonomy-management.tsx`
- `syncro/apps/web/src/features/master-data/plants/plant-management.tsx`
- `syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx`
- `syncro/apps/web/src/features/master-data/machines/machine-management.tsx`
- `syncro/apps/web/src/lib/api/generated/model/index.ts`
- `syncro/apps/web/src/lib/api/generated/model/sparepartTaxonomyListResponse.ts`
- `syncro/apps/web/src/lib/api/generated/model/sparepartTaxonomyRequest.ts`
- `syncro/apps/web/src/lib/api/generated/model/sparepartTaxonomyRequestDimension.ts`
- `syncro/apps/web/src/lib/api/generated/model/sparepartTaxonomyView.ts`
- `syncro/apps/web/src/lib/api/generated/model/sparepartTaxonomyViewDimension.ts`
- `syncro/apps/web/src/lib/api/generated/syncro.ts`

### Change Log

- 2026-05-28: Implemented Story 2.4 sparepart taxonomy backend, generated API client, web UI, tests, validations, and moved story to review.
- 2026-05-28: Resolved code review patches for taxonomy code contract, dimension-change rejection, delete conflict UX, and stable generated operation names; moved story to done.
