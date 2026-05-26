# Story 1.5: Choose and Implement Auth Mode Baseline

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a SUPER_ADMIN,
I want to log in to Syncro with the selected Phase 1 auth mode,
so that only authenticated users can access protected screens and APIs.

## Acceptance Criteria

1. Given Story 0 auth decision is not yet implemented in code, when this story starts, then auth mode is recorded as **Spring Security JWT auth baseline** unless product owner explicitly changes it before implementation.
2. Given backend auth baseline is implemented, when a valid SUPER_ADMIN credential signs in through `/api/v1/auth/login`, then backend returns authenticated session data for the selected auth mode without exposing password hash or secret material.
3. Given invalid login credentials, when `/api/v1/auth/login` is called, then backend returns a generic safe failure using standard error shape and does not reveal whether username, email, password, or user state was wrong.
4. Given unauthenticated request targets protected `/api/v1` endpoints, when request is sent without valid auth, then backend rejects it with `401` while `/api/v1/health` and `/actuator/health` remain public.
5. Given frontend login screen is used, when SUPER_ADMIN submits valid credentials, then it authenticates against backend and routes into `/operations-overview` app shell.
6. Given frontend login fails, when backend returns authentication error, then login page shows safe persistent error feedback and does not expose backend exception details or sensitive values.
7. Given authentication succeeds, when frontend app shell loads or refreshes, then route protection prevents unauthenticated access to dashboard URLs and preserves existing shell navigation/theme behavior.
8. Given selected auth mode is implemented, when docs are inspected, then `syncro/docs/local-development.md` or dedicated auth docs record selected mode, local credential setup/seed behavior, required env values, and security caveats without committing secrets.
9. Given role access is a later story, when Story 1.5 completes, then only authenticated-vs-unauthenticated access is enforced; role menu/API permissions for `SUPER_ADMIN`, `MANAGE`, and `VIEWER` remain Story 1.6 scope.

## Tasks / Subtasks

- [x] Task 1: Record auth mode decision and config contract (AC: #1, #8)
  - [x] Use Spring Security JWT auth baseline for Phase 1 unless user explicitly changes auth mode before coding.
  - [x] Document why JWT was selected: current `/api/v1/**` CSRF ignore path exists, frontend already calls backend by API URL, and role/plant authorization comes later through backend APIs.
  - [x] Define env/config names for JWT secret, issuer, token TTL, and local bootstrap credentials without hardcoded production values.
  - [x] Do not introduce OAuth/OIDC, Keycloak, NextAuth, external identity provider, refresh-token rotation, MFA, or password reset in this story.

- [x] Task 2: Add backend auth persistence and bootstrap baseline (AC: #2, #3, #8)
  - [x] Add Flyway migration for user/auth baseline under `syncro/apps/backend/src/main/resources/db/migration/`; first migration file should follow current empty migration folder state.
  - [x] Store auth users in PostgreSQL with immutable IDs, unique login identifier, password hash, enabled state, timestamps, and application role value ready for Story 1.6.
  - [x] Use `SUPER_ADMIN`, `MANAGE`, `VIEWER` as application role enum/string values; do not mix with job scopes `TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, `MANAGER`.
  - [x] Hash passwords with Spring Security password encoder; never store plaintext password or log submitted credentials.
  - [x] Provide local/dev bootstrap path for one SUPER_ADMIN account through env/profile-safe seed, migration-safe seed, or application initializer; document exact behavior.

- [x] Task 3: Implement backend auth API and security filter chain (AC: #2, #3, #4, #9)
  - [x] Add `com.syncro.auth` bounded context packages using `api`, `application`, `domain`, and `infrastructure` layers.
  - [x] Add `POST /api/v1/auth/login` with validated request DTO and response DTO; return token/session data plus user identity/role only as needed for frontend shell.
  - [x] Add `GET /api/v1/auth/me` so frontend can verify current auth state after refresh.
  - [x] Add `POST /api/v1/auth/logout` only if selected token storage needs explicit client cleanup; no server session invalidation expected for pure stateless JWT.
  - [x] Update `SecurityConfig` to permit health and auth login endpoints, authenticate all other `/api/v1/**`, and avoid protecting static/frontend-unrelated paths by accident.
  - [x] Keep CSRF decision consistent with selected auth mode: stateless JWT can keep `/api/v1/**` CSRF ignored; cookie/session mode would require CSRF, SameSite, credentials, and CORS review.
  - [x] Return standard safe error shape with stable `code`, safe `message`, optional field errors/details, timestamp, and traceId/correlationId where current baseline supports it.

- [x] Task 4: Wire frontend login to backend auth (AC: #5, #6, #7)
  - [x] Replace placeholder login behavior in `syncro/apps/web/src/app/(main)/auth/v1/login/page.tsx` and/or `v2/login/page.tsx` according to current route used by starter.
  - [x] Add typed API/auth boundary under `syncro/apps/web/src/lib/api/` and `syncro/apps/web/src/lib/auth/`; do not scatter ad-hoc `fetch` parsing in components.
  - [x] Read backend base URL from browser-safe env (`NEXT_PUBLIC_API_URL` or documented equivalent); do not expose secrets or backend private credentials to browser.
  - [x] On login success, route to `/operations-overview` and preserve existing Next rewrites from `syncro/apps/web/next.config.mjs`.
  - [x] On login failure, show safe page-level error text; do not rely on toast-only feedback for failed login.
  - [x] Implement dashboard route protection through middleware/layout/client guard appropriate to token storage; ensure direct visits to `/operations-overview`, `/dashboard/*`, and rewritten module routes cannot show protected shell unauthenticated.
  - [x] Preserve theme preferences, sidebar behavior, search dialog, plant-scope placeholder, and responsive shell behavior from Story 1.4.

- [x] Task 5: Add tests and validation evidence (AC: #2, #3, #4, #5, #6, #7, #8)
  - [x] Backend tests cover login success, generic failed-login response, `/api/v1/auth/me` with/without auth, protected `/api/v1/**` unauthenticated rejection, and health endpoints public access.
  - [x] Persistence/migration tests use real PostgreSQL/Testcontainers when proving schema, constraints, password hash storage, or repository behavior; do not mock database for those checks.
  - [x] Frontend checks cover login form success/failure behavior with typed API boundary mocks or runtime manual evidence; do not duplicate backend auth decisions in UI tests.
  - [x] Run relevant backend Maven tests, frontend `npm --prefix syncro/apps/web run check`, `build`, `lint` where changed, and baseline validation script.
  - [x] Start frontend and backend locally and manually verify login success, failed login, protected dashboard direct URL when signed out, and refresh behavior before marking story complete.

## Dev Notes

### Scope Boundary

This story establishes authenticated access only. It must not implement role-based authorization, plant scoping, menu permission hiding, user management UI, password reset, email flows, external IdP, MFA, audit-log product flow, master-data CRUD, telemetry, alerts, WAHA, or System Health authorization. Story 1.6 owns role enforcement. Story 1.7 owns plant-scoped data filtering.

### Selected Auth Mode

Use **Spring Security JWT auth baseline** unless Yusuf/product owner explicitly changes direction before implementation. This matches current backend `SecurityConfig` state where `/api/v1/**` has CSRF ignored, and current frontend shell is API-client oriented. If implementer chooses cookie/session instead, they must first revise story docs and account for CSRF token flow, SameSite cookies, credentialed CORS, cookie encoding, and frontend fetch credentials.

### Current Code State To Preserve

- `syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java` currently permits `/api/v1/health` and `/actuator/health`, ignores CSRF for `/api/v1/**`, and authenticates any other request. Replace this default gate with explicit auth endpoints and JWT validation while keeping health public.
- `syncro/apps/backend/pom.xml` already includes Spring Security, WebMVC, Validation, JPA, Flyway, PostgreSQL runtime, Redis, Actuator, MQTT, and Spring Security test dependencies. Do not add auth frameworks unless existing Spring Security cannot satisfy JWT baseline.
- `syncro/apps/backend/src/main/resources/application.yml` uses env placeholders and `ddl-auto: validate`. Add auth properties as typed config/env placeholders; do not hardcode local secrets, ports, or credentials.
- `syncro/apps/backend/src/main/resources/db/migration/` currently has no SQL migrations. First auth schema migration must create the baseline required by this story and pass from empty database.
- `syncro/apps/web/src/app/(main)/auth/v1/login/page.tsx` and `v2/login/page.tsx` are static placeholders with a link into `/operations-overview`; remove placeholder bypass and wire real backend login.
- `syncro/apps/web/src/app/(main)/dashboard/layout.tsx` renders protected shell, sidebar, header controls, theme switcher, and plant-scope placeholder. Add auth protection without breaking layout, preferences, keyboard access, or responsive behavior.
- `syncro/apps/web/next.config.mjs` rewrites public app URLs such as `/operations-overview` to `/dashboard/operations-overview`; route protection must handle both public rewritten URLs and underlying dashboard paths.

### Backend Implementation Guardrails

- Java packages stay under `com.syncro.<context>.<layer>`; use `com.syncro.auth.api`, `.application`, `.domain`, `.infrastructure`.
- Controllers validate DTOs, delegate to application services, and never access repositories directly.
- `@Transactional` belongs at application service/use-case boundary.
- Use Java records for immutable request/response DTOs. Do not return JPA entities from controllers.
- Use `jakarta.*` imports only.
- PostgreSQL is source of truth for users/auth state. Redis/browser storage must not become permission source of truth.
- Flyway owns schema. Do not use `ddl-auto=update`, manual DB edits, or edited applied migrations.
- Password submitted to login is boundary input. Validate request shape, authenticate safely, hash stored secrets, and redact credentials from logs/errors.
- JWT secret must come from typed properties/env. Local dev may document sample values in `.env.example`; production secret must not be committed.
- JWT claims should be minimal: subject/user ID, role, issuer, issued/expiry times. Do not put plant scope or future job-scope permissions in token in this story.
- If `traceId` infrastructure is not yet implemented, keep standard error shape as close as current baseline allows and record follow-up only if outside scope.

### Frontend Implementation Guardrails

- All backend calls go through one typed API/auth boundary. Treat network responses as `unknown` until validated/narrowed.
- Do not store secrets, credentials, or password in localStorage/sessionStorage/cookies. Token storage choice must be explicit and documented; if using browser storage for Phase 1 JWT, state security limitation in docs and do not treat it as authorization source of truth.
- Frontend may use auth state for redirect/display only. Backend remains authority for authentication and future authorization.
- Login failure UX must be safe, persistent in page context, keyboard accessible, and not color-only.
- Preserve existing shadcn/Tailwind tokens and Story 1.4 shell behavior. Do not add another UI kit, form library, package manager, or router pattern.
- Do not implement role-specific navigation hiding yet; Story 1.6 will consume backend-provided role/permissions.

### Data Model Guidance

Minimum backend auth model should support later stories without premature user-management UI:

- User identifier: opaque ID (`uuid` recommended).
- Login identifier: email or username with unique constraint; pick one and document it.
- Password hash: non-null, never exposed.
- Application role: one of `SUPER_ADMIN`, `MANAGE`, `VIEWER`.
- Enabled/disabled state.
- Created/updated timestamps in UTC.

Avoid plant assignments in this story unless needed for schema forward compatibility only; Story 1.7 owns plant-scoped access.

### Testing Requirements

- Map every AC to evidence before moving story to review.
- Backend security tests must prove actual HTTP status and response shape for success/failure/unauthenticated paths.
- Migration/repository tests that prove constraints/password storage use PostgreSQL/Testcontainers, not mocks.
- Frontend runtime verification is required because login changes user-visible behavior. Start backend and frontend; test success, failure, signed-out protected URL, and refresh.
- Expected commands, adjusted to actual scripts:

```powershell
mvn -f syncro/apps/backend/pom.xml test
npm --prefix syncro/apps/web run check
npm --prefix syncro/apps/web run build
npm --prefix syncro/apps/web run lint
pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1
npm --prefix syncro/apps/web run dev
```

### Previous Story Intelligence

- Story 1.3 established Spring Boot 4.0.6 / Java 25, env-driven config, `/api/v1/health`, Flyway folder, Testcontainers baseline, and Spring Security config with `/api/v1/**` CSRF ignored instead of global CSRF disable.
- Story 1.4 intentionally removed demo auth form behavior and left login as placeholder; real auth belongs here.
- Story 1.4 fixed cookie encoding and SameSite behavior. If cookie/session auth is selected instead of JWT, reuse that pattern and do not introduce raw cookie value handling.
- Story 1.4 verified shell with browser; this story must re-verify shell because auth gating can break rewrites, layout, or refresh.
- Baseline validation script must remain cwd-independent; do not weaken backend/infra/frontend checks to make auth pass.

### Project Structure Notes

No conflict detected. New backend source belongs under `syncro/apps/backend/src/main/java/com/syncro/auth/**`, migrations under `syncro/apps/backend/src/main/resources/db/migration/`, backend tests under matching `src/test/java/com/syncro/auth/**`, frontend auth/API helpers under `syncro/apps/web/src/lib/auth/` and `syncro/apps/web/src/lib/api/`, and login UI under existing auth route files. Docs may update `syncro/docs/local-development.md` or add a small dedicated auth doc if needed.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.5: Choose and Implement Auth Mode Baseline]
- [Source: _bmad-output/planning-artifacts/epics.md#Requirements Inventory]
- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.6: Enforce Application Role Access]
- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.7: Implement Plant-Scoped Data Access]
- [Source: _bmad-output/project-context.md#Technology Stack & Versions]
- [Source: _bmad-output/project-context.md#Framework-Specific Rules]
- [Source: _bmad-output/project-context.md#Testing Rules]
- [Source: _bmad-output/project-context.md#Critical Don't-Miss Rules]
- [Source: _bmad-output/implementation-artifacts/1-4-initialize-nextjs-admin-frontend-shell.md]
- [Source: syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java]
- [Source: syncro/apps/backend/pom.xml]
- [Source: syncro/apps/backend/src/main/resources/application.yml]
- [Source: syncro/apps/web/src/app/(main)/auth/v1/login/page.tsx]
- [Source: syncro/apps/web/src/app/(main)/auth/v2/login/page.tsx]
- [Source: syncro/apps/web/src/app/(main)/dashboard/layout.tsx]
- [Source: syncro/apps/web/next.config.mjs]

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 via Claude Code

### Debug Log References

- `mvn -f syncro/apps/backend/pom.xml test` passed: 6 tests, 0 failures, 0 errors.
- `npm --prefix syncro/apps/web run check` passed.
- `npm --prefix syncro/apps/web run lint` passed.
- `npm --prefix syncro/apps/web run build` passed.
- `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1` passed.
- Manual runtime verification passed with backend local profile and frontend dev server: public health, invalid login safe error, valid SUPER_ADMIN login, protected route redirect, and refresh on protected page.

### Completion Notes List

- Selected Spring Security stateless JWT baseline and documented local auth configuration/security caveats.
- Added PostgreSQL `auth_users` Flyway baseline with application roles `SUPER_ADMIN`, `MANAGE`, and `VIEWER`.
- Added backend auth bounded context with login, current-user, logout, JWT service/filter, safe auth errors, local SUPER_ADMIN bootstrap, and local CORS for frontend dev ports.
- Updated backend security so health and login remain public while `/api/v1/**` requires JWT auth.
- Replaced placeholder frontend login with typed backend auth flow, browser-managed Phase 1 JWT cookies, persistent safe error feedback, and route guard for protected app URLs.
- Extended backend/web tests, docs, env example, and baseline validation for auth mode and required local configuration.

### File List

- `_bmad-output/implementation-artifacts/1-5-choose-and-implement-auth-mode-baseline.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `syncro/.env.example`
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthController.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthDtos.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthExceptionHandler.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/application/AuthService.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/application/JwtTokenService.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/domain/ApplicationRole.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserRepository.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/JwtAuthenticationFilter.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/LocalAdminBootstrap.java`
- `syncro/apps/backend/src/main/java/com/syncro/config/JsonConfig.java`
- `syncro/apps/backend/src/main/java/com/syncro/config/JwtProperties.java`
- `syncro/apps/backend/src/main/java/com/syncro/config/LocalAdminProperties.java`
- `syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java`
- `syncro/apps/backend/src/main/java/com/syncro/config/TimeConfig.java`
- `syncro/apps/backend/src/main/resources/application-local.yml`
- `syncro/apps/backend/src/main/resources/application.yml`
- `syncro/apps/backend/src/main/resources/db/migration/V1__create_auth_baseline.sql`
- `syncro/apps/backend/src/test/java/com/syncro/SyncroBackendApplicationTests.java`
- `syncro/apps/backend/src/test/java/com/syncro/TestJsonConfig.java`
- `syncro/apps/backend/src/test/java/com/syncro/api/HealthControllerTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/auth/api/AuthControllerTest.java`
- `syncro/apps/web/src/app/(main)/auth/v1/login/page.tsx`
- `syncro/apps/web/src/app/(main)/auth/v2/login/page.tsx`
- `syncro/apps/web/src/features/auth/login-form.tsx`
- `syncro/apps/web/src/lib/api/syncro-api.ts`
- `syncro/apps/web/src/lib/auth/auth-client.ts`
- `syncro/apps/web/src/lib/auth/auth-session.ts`
- `syncro/apps/web/src/middleware.ts`
- `syncro/docs/local-development.md`
- `syncro/scripts/validate-syncro-baseline.ps1`

### Change Log

- 2026-05-26: Implemented Story 1.5 Spring Security JWT auth baseline.

## Story Completion Status

Story 1.5 implementation complete and ready for review.
