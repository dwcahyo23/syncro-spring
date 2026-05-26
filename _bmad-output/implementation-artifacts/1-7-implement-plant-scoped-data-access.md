# Story 1.7: Implement Plant-Scoped Data Access

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a MANAGE or VIEWER user,
I want to see only telemetry, alerts, and operational data for plants I am assigned to,
so that multi-plant deployments maintain data privacy between plants.

## Acceptance Criteria

1. Given users have plant assignments stored in their profile, when authenticated users request protected operational data, then backend exposes the user's effective plant scope and enforces plant-scope decisions server-side instead of trusting frontend cookies or query parameters.
2. Given `SUPER_ADMIN` is authenticated, when they request plant-scoped operational data or available plant scopes, then backend treats them as unrestricted and returns all available plants or an all-plants scope where no plant rows exist yet.
3. Given `MANAGE` or `VIEWER` is authenticated with one or more assigned plants, when they access Operations Overview, Telemetry Dashboard, Alerts, or Machine lists, then only assigned-plant data is returned by backend APIs.
4. Given `MANAGE` or `VIEWER` has no plant assignments, when they access plant-scoped operational screens, then backend returns an empty permitted scope and frontend shows the empty-state copy `No plants assigned. Contact your administrator.`
5. Given a user opens the authenticated app shell, when the header renders, then the existing header placeholder is replaced by `PlantScopeSelector` using the WDS hardening spec: single/no-plant state is non-interactive, multi-plant state opens a dropdown, and the component is accessible with label `Select plant scope`.
6. Given `SUPER_ADMIN` has more than one plant available, when they use `PlantScopeSelector`, then `All Plants` is available; given non-SUPER_ADMIN has multiple assigned plants, then only assigned plants plus `All my plants` are available.
7. Given user changes active plant scope in `PlantScopeSelector`, when they navigate between protected routes, then selected scope persists across navigation in client UI state and resets on login/session expiration/logout.
8. Given a user navigates directly to a resource outside their plant scope, when backend evaluates the resource plant, then API returns `403` with safe standard error shape and frontend direct-resource state uses copy `You don't have access to this plant's data.` where such direct-resource pages exist.
9. Given frontend requests plant-scoped data, when a plant filter is sent, then frontend treats it as display/query preference only; backend validates it against effective scope and ignores/rejects out-of-scope filters.
10. Given application roles and job scopes are inspected, then plant-scope access remains separate from application roles (`SUPER_ADMIN`, `MANAGE`, `VIEWER`) and job scopes (`TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, `MANAGER`); do not implement ABAC/job-scope permission rules in this story.
11. Given code and tests are inspected, then no Next.js code accesses PostgreSQL/Redis/InfluxDB/EMQX/WAHA directly; all plant-scope data comes through Spring Boot `/api/v1/**` APIs or existing frontend API boundary.
12. Given this story is verified, when evidence is mapped, then every AC has automated test or explicit browser/manual evidence, including empty scope, multi-scope selector, `SUPER_ADMIN` unrestricted scope, and `403` out-of-scope denial.

## Tasks / Subtasks

- [x] Task 1: Add backend plant-scope foundation model and migration (AC: #1, #2, #3, #4, #8, #10)
  - [x] Add forward-only Flyway migration for minimal plant-scope persistence without editing `V1__create_auth_baseline.sql`.
  - [x] Create `plants` table only if no existing plant master table exists; keep columns minimal for scope selection: `id`, `code`, `name`, `created_at`, `updated_at`.
  - [x] Create `auth_user_plant_assignments` join table linking `auth_users.id` to `plants.id` with unique constraint and indexes.
  - [x] Do not implement full plant CRUD, machine CRUD, telemetry, alert, or master-data workflows; later Epic 2 owns CRUD.
  - [x] Use PostgreSQL constraints, `snake_case` names, plural tables, `idx_*` indexes, and `uq_*` unique constraints.

- [x] Task 2: Add backend effective plant-scope service and API contract (AC: #1, #2, #3, #4, #8, #9, #10, #11)
  - [x] Add application service that derives effective plant scope from authenticated backend principal and database assignments.
  - [x] `SUPER_ADMIN` must be unrestricted and may select all plants; non-SUPER_ADMIN must be limited to assigned plants.
  - [x] Add `/api/v1/auth/plant-scope` or nearest auth/session endpoint returning current user's available plant scopes and default active scope.
  - [x] Response DTO should include stable fields such as `mode`, `availablePlants`, `defaultPlantId`, and `emptyReason` when no assignments exist.
  - [x] Use Java records for DTOs; never serialize JPA entities directly.
  - [x] Add reusable backend guard/helper for future plant-scoped queries to validate requested `plantId` against effective scope.
  - [x] Keep `/api/v1/auth/me` behavior compatible unless intentionally extending its response in a tested way.

- [x] Task 3: Add backend tests for plant-scope decisions (AC: #1, #2, #3, #4, #8, #9, #10, #12)
  - [x] Add MockMvc/API tests for `SUPER_ADMIN`, assigned `MANAGE`, assigned `VIEWER`, and no-assignment non-SUPER_ADMIN.
  - [x] Assert out-of-scope plant filter/resource checks return `403` with `code`, `message`, `timestamp`, and `traceId`.
  - [x] Assert unauthenticated plant-scope requests return `401`, not `403`.
  - [x] Assert application roles remain distinct from job scopes; do not add job-scope authorities/claims.
  - [x] Use real DB/Testcontainers where migration, repository, or constraints are under test; do not mock persistence for schema behavior.

- [x] Task 4: Build `PlantScopeSelector` from WDS hardening spec (AC: #5, #6, #7, #11)
  - [x] Create `syncro/apps/web/src/components/syncro/plant-scope-selector.tsx` using existing shadcn/Radix/Tailwind primitives; add no UI dependency.
  - [x] Props must align with hardening spec: assigned/available plants, active plant id or all value, select handler, and SUPER_ADMIN behavior.
  - [x] Use accessible name `Select plant scope`, visible text labels, keyboard-operable dropdown, and no color-only state.
  - [x] For no plants: show disabled `All Plants` chip with tooltip/help text `Add plants in Master Data` for SUPER_ADMIN or empty assignment copy for non-SUPER_ADMIN context.
  - [x] For single plant: show non-interactive plant chip.
  - [x] For multiple plants: show dropdown with active indicator and `All Plants`/`All my plants` option according to role.

- [x] Task 5: Wire frontend plant-scope API/client state into app shell (AC: #4, #5, #6, #7, #9, #11)
  - [x] Extend existing `syncro-api.ts` typed API boundary; do not parse plant-scope API responses inside components.
  - [x] Treat API and cookie data as external `unknown` until narrowed; avoid `any` and broad assertions.
  - [x] Replace header placeholder in `syncro/apps/web/src/app/(main)/dashboard/layout.tsx` with client boundary/component for `PlantScopeSelector`.
  - [x] Keep `dashboard/layout.tsx` as route composition; put client state/fetching logic in a component or feature/lib boundary.
  - [x] Persist active plant scope across navigation using existing client state pattern or URL/search param if chosen, but reset on login/logout/session expiration via existing auth session clear events.
  - [x] Do not re-add sidebar footer plant placeholder removed in Story 1.6.

- [x] Task 6: Apply scope to current placeholder operational screens without inventing domain data (AC: #3, #4, #8, #9, #12)
  - [x] Show no-assignment empty state on current operational placeholder screens where plant-scoped data will later appear: Operations Overview, Telemetry, Alerts, and Master Data machine lists if present.
  - [x] If no real operational data endpoints exist yet, document and test scope contract through plant-scope endpoint and placeholder UI state rather than fabricating telemetry/alert data.
  - [x] Preserve existing role guards and permission-denied copy from Story 1.6 for role-hidden routes.
  - [x] Use out-of-scope direct-resource copy only for resource-detail pages that can identify a plant in this story; do not create fake detail routes solely for this AC.

- [x] Task 7: Verify automated checks and browser evidence (AC: #1-#12)
  - [x] Run `mvn -f syncro/apps/backend/pom.xml test`.
  - [x] Run `npm --prefix syncro/apps/web run check`.
  - [x] Run `npm --prefix syncro/apps/web run build`.
  - [x] Run `npm --prefix syncro/apps/web run lint`.
  - [x] Run `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1`.
  - [x] Start backend and frontend locally; manually verify header selector and empty/multi/super-admin behavior in browser.
  - [x] Map each acceptance criterion to exact command, test, or browser evidence before moving story to review/done.

## Dev Notes

### Scope Boundary

This story creates the plant-scoped access foundation and WDS `PlantScopeSelector`. It must not implement full plant CRUD, machine CRUD, telemetry dashboards, alert data, ABAC/job-scope policy, WAHA, audit log, or system health. Epic 2 owns master-data CRUD. Epic 3+ owns telemetry/alerts. This story may add minimal plant rows/assignment storage and scope API so later data queries can enforce scope.

### WDS Hardening Requirements

Use `_bmad-output/planning-artifacts/frontend-hardening-specification.md` as implementation source for `PlantScopeSelector`:

- Component path: `syncro/apps/web/src/components/syncro/plant-scope-selector.tsx`.
- Build from existing dropdown/sidebar/header primitives; no new npm dependencies.
- Placement: header right zone, replacing existing `Plant scope selector placeholder` in `dashboard/layout.tsx`.
- States: no plants, single plant, multiple plants, SUPER_ADMIN all-plants, non-SUPER_ADMIN assigned-plants only.
- Accessibility: `aria-label="Select plant scope"`, visible label/chip text, keyboard dropdown, no color-only signal.

### Current Code State To Preserve

- `syncro/apps/backend/src/main/resources/db/migration/V1__create_auth_baseline.sql` creates `auth_users` with `application_role` limited to `SUPER_ADMIN`, `MANAGE`, `VIEWER`. Do not edit this applied migration; add a new migration.
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserEntity.java` maps only auth identity, password hash, application role, enabled, and timestamps. Extend through a dedicated assignment entity/repository rather than overloading application role.
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthDtos.java` currently returns `AuthUserView(id, loginIdentifier, applicationRole)`. Keep login/session compatibility unless deliberately adding tested plant-scope DTOs.
- `syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java` keeps health/login public and `/api/v1/**` authenticated. Preserve `401` vs `403` behavior and safe error shape.
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/JwtAuthenticationFilter.java` maps only `ROLE_<applicationRole>` authorities. Do not add job-scope or plant IDs as authorities in this story.
- `syncro/apps/web/src/lib/auth/auth-session.ts` defines `AuthUser` with `id`, `loginIdentifier`, and `applicationRole` only. Do not trust cookie role or any browser state for server authorization.
- `syncro/apps/web/src/lib/auth/use-auth-user.ts` defensively parses `syncro_auth_user` and emits session-change events. Reuse/reset through existing auth session behavior.
- `syncro/apps/web/src/lib/api/syncro-api.ts` owns current auth API calls and centralized `401` expiration behavior. Extend this boundary for plant-scope API; do not scatter ad-hoc fetches.
- `syncro/apps/web/src/app/(main)/dashboard/layout.tsx` currently renders the header placeholder. Replace placeholder with selector while preserving `LayoutControls`, `ThemeSwitcher`, `SearchDialog`, `SidebarTrigger`, sticky header styling, and shell rewrites.
- Story 1.6 added role-aware navigation/search and direct-route forbidden states. Preserve role visibility matrix and existing copy `You don't have permission to access this page.`

### Backend Implementation Guardrails

- Backend remains source of truth for plant-scope enforcement. Frontend selected plant is only query preference.
- Use bounded-context/layering rules: controllers validate/delegate, application service owns scope decisions, repositories remain module-local.
- Suggested modules: plant-scope foundation may live under `auth` if it is user-session scope, or under `masterdata` if plant entity ownership is introduced there. Keep dependencies one-directional and explicit.
- REST API stays under `/api/v1`; use camelCase JSON fields and ISO-8601 UTC timestamps if timestamps appear.
- Standard errors must include stable `code`, safe `message`, `timestamp`, and `traceId`; never expose SQL/Java details.
- If adding a reusable guard, expose methods such as `canAccessPlant(user, plantId)` / `requirePlantAccess(user, plantId)` for later services; do not wire fake telemetry/alert endpoints just to demonstrate scope.
- If adding seed/sample assignments for local testing, keep deterministic, non-secret, and aligned with existing local auth bootstrap.

### Frontend Implementation Guardrails

- `app/` route files should remain composition only. Client fetching/state should live under `features`, `components`, `lib`, or a small shell client component.
- Add `"use client"` only where hooks, browser state, dropdown interaction, or cookies require it.
- Use existing shadcn/ui, Radix, Tailwind v4, and boilerplate patterns. No Prettier/ESLint, no new UI kit, no new package manager.
- Do not hardcode backend URL beyond existing `NEXT_PUBLIC_API_URL` pattern in `syncro-api.ts`.
- Unknown API responses must be narrowed before use. Avoid duplicate role/plant response shapes scattered across components.
- Do not make dashboards look fresher or broader than data is. Empty/no-assignment/forbidden states are product behavior, not polish.

### Data Model Notes

Minimum acceptable schema for this story:

- `plants`: plant identity used for scope selection; full plant CRUD fields may be deferred.
- `auth_user_plant_assignments`: user-to-plant assignments.

Recommended constraints:

- `plants.code` unique and non-empty.
- `auth_user_plant_assignments(auth_user_id, plant_id)` unique.
- Foreign keys to `auth_users(id)` and `plants(id)`.
- Index assignment lookup by `auth_user_id` and `plant_id`.

Do not store plant scope in JWT only. JWT may identify user and role, but effective plant scope must be checked from backend-owned state so assignment changes can take effect without trusting stale frontend state.

### Testing Requirements

- Backend tests must prove server-side enforcement, not frontend hiding.
- Include negative tests: unauthenticated `401`, out-of-scope `403`, non-SUPER_ADMIN no assignment empty scope.
- Include `SUPER_ADMIN` unrestricted behavior.
- Include frontend/browser evidence for selector states and no-assignment empty copy.
- If actual plant-scoped operational endpoints do not exist yet, test the reusable backend plant-scope guard/service and plant-scope session endpoint; list later endpoint adoption as deferred to domain stories.
- For UI change, run the app and verify in browser before marking done. If browser verification is blocked, record blocker and risk explicitly.

### Previous Story Intelligence

Story 1.6 completed application-role enforcement and role-aware app shell UX:

- Backend method security and safe `403 FORBIDDEN` JSON handling are in place.
- Frontend role filter helper is used by sidebar and search; keep hidden routes out of command palette.
- `useAuthUser()` parses `syncro_auth_user` defensively; reuse pattern.
- `NavUser` logout clears auth state and redirects. Plant-scope UI state must reset on logout/session expiration.
- Header plant placeholder was intentionally left for Story 1.7. Sidebar plant placeholder was removed and must not return.
- Browser verification was required and completed for role navigation/search/forbidden/logout. Same level of browser evidence is required here for selector behavior.

### Project Structure Notes

Expected changed areas:

- Backend migrations/entities/repositories/services/tests:
  - `syncro/apps/backend/src/main/resources/db/migration/V2__*.sql` or next migration number
  - `syncro/apps/backend/src/main/java/com/syncro/auth/**` and/or `syncro/apps/backend/src/main/java/com/syncro/masterdata/**`
  - `syncro/apps/backend/src/test/java/com/syncro/**`
- Frontend API/auth/shell/component:
  - `syncro/apps/web/src/lib/api/syncro-api.ts`
  - `syncro/apps/web/src/components/syncro/plant-scope-selector.tsx`
  - possible `syncro/apps/web/src/features/plant-scope/**` or shell client component
  - `syncro/apps/web/src/app/(main)/dashboard/layout.tsx`
  - current placeholder pages under `syncro/apps/web/src/app/(main)/dashboard/{operations-overview,telemetry,alerts,master-data/**}` only as needed for empty state
- Story/status artifacts:
  - `_bmad-output/implementation-artifacts/1-7-implement-plant-scoped-data-access.md`
  - `_bmad-output/implementation-artifacts/sprint-status.yaml`

No structure conflict detected. Do not create worktree or branch unless user explicitly asks.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.7: Implement Plant-Scoped Data Access]
- [Source: _bmad-output/planning-artifacts/epics.md#Research Reconciliation Stories]
- [Source: _bmad-output/planning-artifacts/architecture.md#Authentication & Security]
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Architecture]
- [Source: _bmad-output/planning-artifacts/architecture.md#Project Structure & Boundaries]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Plant-Scoped Access: Data Filtering by User Assignment]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#PlantScopeSelector]
- [Source: _bmad-output/planning-artifacts/frontend-hardening-specification.md#4.11 PlantScopeSelector]
- [Source: _bmad-output/planning-artifacts/frontend-hardening-specification.md#8. Implementation Sequence]
- [Source: _bmad-output/planning-artifacts/app-shell-navigation-specification.md#5. Plant Scope Selector]
- [Source: _bmad-output/project-context.md#Framework-Specific Rules]
- [Source: _bmad-output/project-context.md#Testing Rules]
- [Source: _bmad-output/project-context.md#Critical Don't-Miss Rules]
- [Source: _bmad-output/implementation-artifacts/1-6-enforce-application-role-access.md#Previous Story Intelligence]
- [Source: syncro/apps/backend/src/main/resources/db/migration/V1__create_auth_baseline.sql]
- [Source: syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserEntity.java]
- [Source: syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthDtos.java]
- [Source: syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java]
- [Source: syncro/apps/web/src/lib/auth/auth-session.ts]
- [Source: syncro/apps/web/src/lib/auth/use-auth-user.ts]
- [Source: syncro/apps/web/src/lib/api/syncro-api.ts]
- [Source: syncro/apps/web/src/app/(main)/dashboard/layout.tsx]

## Dev Agent Record

### Agent Model Used

cx/gpt-5.5

### Debug Log References

- `mvn -f syncro/apps/backend/pom.xml -Dtest=PlantScopeRepositoryIntegrationTest test` — passed
- `mvn -f syncro/apps/backend/pom.xml "-Dtest=PlantScopeControllerTest,PlantScopeServiceTest" test` — passed
- `mvn -f syncro/apps/backend/pom.xml "-Dtest=PlantScopeControllerTest,PlantScopeServiceTest,PlantScopeRepositoryIntegrationTest" test` — passed
- `mvn -f syncro/apps/backend/pom.xml "-Dtest=PlantScopeControllerTest,SyncroBackendApplicationTests" test` — passed
- `mvn -f syncro/apps/backend/pom.xml test` — passed
- `npm --prefix syncro/apps/web run check` — passed
- `npm --prefix syncro/apps/web run build` — passed
- `npm --prefix syncro/apps/web run lint` — passed
- `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1` — passed
- Browser: `http://localhost:3001/dashboard/operations-overview` with mocked `SUPER_ADMIN` unrestricted scope showed `All Plants`, `Plant One`, and `Plant Two` in `PlantScopeSelector`.
- Browser: `http://localhost:3001/dashboard/telemetry` with mocked `MANAGE` assigned scope showed `All my plants`, `Plant One`, and `Plant Two` only.
- Browser: `http://localhost:3001/dashboard/alerts` with mocked `VIEWER` empty scope showed `No plants assigned. Contact your administrator.` empty state.

### Completion Notes List

- Added forward-only `V2__create_plant_scope_foundation.sql` migration for `plants` and `auth_user_plant_assignments` with PostgreSQL constraints and indexes.
- Added minimal JPA entities/repositories for plant identity and user plant assignments without plant CRUD or role/job-scope changes.
- Added Testcontainers-backed repository integration coverage for migration, uniqueness constraints, and assignment lookup.
- Added `/api/v1/auth/plant-scope` contract with Java record DTOs, server-derived scope decisions, and reusable plant access guard helpers.
- Preserved `/api/v1/auth/me` response shape while keeping plant scope separate from application roles and job scopes.
- Added MockMvc/API coverage for SUPER_ADMIN, assigned MANAGE, assigned VIEWER, empty scope, unauthenticated 401, and out-of-scope 403 safe error shape.
- Built WDS-hardened `PlantScopeSelector` with no-plant, single-plant, multi-plant, `All Plants`, and `All my plants` states using existing UI primitives only.
- Wired typed frontend plant-scope API parsing, app-shell client state, header selector replacement, and auth-session reset behavior.
- Added plant-scoped placeholder empty-state wrapper for Operations Overview, Telemetry, Alerts, and Machines without fabricating domain data.
- Fixed context-load and MVC slice tests after adding `PlantScopeService` to `AuthController`.
- Verified AC #1-#4, #8-#10, and #12 through backend unit/API/integration tests; verified AC #5-#7 and #11 through frontend checks and browser evidence.
- AC #3 and #9 are enforced through backend scope service/guard contracts because real telemetry, alert, operation, and machine data APIs are deferred to later domain stories.

### Acceptance Criteria Evidence

- AC #1: `PlantScopeControllerTest`, `PlantScopeServiceTest`, and `/api/v1/auth/plant-scope` derive scope server-side from authenticated principal and assignment repositories.
- AC #2: `PlantScopeControllerTest.superAdminReceivesUnrestrictedPlantScope` and `PlantScopeServiceTest.superAdminIsUnrestrictedAndDefaultsToAllPlants` cover unrestricted `SUPER_ADMIN`; browser evidence shows `All Plants`.
- AC #3: `PlantScopeServiceTest.nonSuperAdminReceivesOnlyAssignedPlants` and `canAccessPlantReturnsTrueOnlyForEffectiveScope` cover assigned-plant enforcement contract for future operational APIs.
- AC #4: `PlantScopeControllerTest.unassignedUserReceivesEmptyScopeReason`, `PlantScopeServiceTest.nonSuperAdminWithoutAssignmentsReceivesEmptyScope`, and browser empty-state evidence cover no-assignment behavior.
- AC #5: Browser evidence confirms header placeholder replaced by accessible `PlantScopeSelector` with `Select plant scope`; `npm --prefix syncro/apps/web run check` passed.
- AC #6: Browser evidence confirms `SUPER_ADMIN` sees `All Plants`; `MANAGE` sees `All my plants` plus assigned plants only.
- AC #7: `plant-scope-store.ts` keeps active scope across route navigation and `plant-scope-shell.tsx` resets on auth-user/session changes; browser route checks confirmed selector state in app shell.
- AC #8: `PlantScopeControllerTest.outOfScopeResourceCheckReturnsSafeForbiddenError` verifies `403` safe error shape and copy.
- AC #9: Frontend sends scope only through typed API/client state; `PlantScopeService.requirePlantAccess` validates requested plant server-side for future filters.
- AC #10: JWT/application role code unchanged; tests use only `ROLE_<applicationRole>` and plant scope remains service/database driven.
- AC #11: `syncro-api.ts` owns plant-scope backend call; no frontend direct dependency access added; baseline validation passed.
- AC #12: Evidence above maps every AC to automated commands or browser checks.

### File List

- `syncro/apps/backend/src/main/resources/db/migration/V2__create_plant_scope_foundation.sql`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/PlantEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/PlantRepository.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserPlantAssignmentEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserPlantAssignmentId.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserPlantAssignmentRepository.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthController.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthDtos.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthExceptionHandler.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/application/PlantScopeService.java`
- `syncro/apps/backend/src/test/java/com/syncro/auth/infrastructure/PlantScopeRepositoryIntegrationTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/auth/api/PlantScopeControllerTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/auth/application/PlantScopeServiceTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/SyncroBackendApplicationTests.java`
- `syncro/apps/web/src/components/syncro/plant-scope-selector.tsx`
- `syncro/apps/web/src/features/plant-scope/plant-scope-shell.tsx`
- `syncro/apps/web/src/features/plant-scope/plant-scope-store.ts`
- `syncro/apps/web/src/features/plant-scope/plant-scoped-module-placeholder.tsx`
- `syncro/apps/web/src/lib/api/syncro-api.ts`
- `syncro/apps/web/src/app/(main)/dashboard/layout.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/operations-overview/page.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/telemetry/page.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/alerts/page.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/machines/page.tsx`

### Change Log

- 2026-05-26 — Implemented plant-scoped data access foundation, WDS selector, tests, and validation evidence.

## Story Completion Status

Implementation complete. All story tasks and acceptance criteria have automated or browser evidence. Ready for code review.
