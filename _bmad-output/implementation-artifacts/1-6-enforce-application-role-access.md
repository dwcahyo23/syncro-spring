# Story 1.6: Enforce Application Role Access

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a SUPER_ADMIN,
I want Syncro to enforce `SUPER_ADMIN`, `MANAGE`, and `VIEWER` role access in API and navigation,
so that users only see and perform actions allowed by their platform role.

## Acceptance Criteria

1. Given authenticated users have `SUPER_ADMIN`, `MANAGE`, or `VIEWER` application roles, when they call protected `/api/v1/**` endpoints, then backend enforces application-role authorization server-side using Spring Security authorities derived from the authenticated JWT principal.
2. Given a user lacks the required role for a protected API, when the request is authenticated but forbidden, then backend returns `403` with standard safe error shape containing `code`, `message`, `timestamp`, and `traceId`, without Java exception names or sensitive values.
3. Given `VIEWER` is authenticated, when they attempt any mutation endpoint introduced in this story or covered by role tests, then backend rejects the request with `403` while permitted read/current-user endpoints remain accessible.
4. Given `MANAGE` is authenticated, when they access role-gated foundation routes/actions, then they can access `MANAGE`-permitted areas but cannot access `SUPER_ADMIN`-only capabilities such as System Health or WAHA Templates.
5. Given `SUPER_ADMIN` is authenticated, when they access role-gated foundation routes/actions, then all application-role-gated capabilities in this story are accessible.
6. Given the app shell renders for a signed-in user, when sidebar navigation and command search build their items, then unavailable items are hidden using the user's `applicationRole`: `SUPER_ADMIN` sees all items; `MANAGE` sees Operations Overview, Telemetry, Alerts, Master Data, Audit Log, and Settings; `VIEWER` sees Operations Overview, Telemetry, Alerts, and Settings.
7. Given a user navigates directly to a frontend route hidden from their role, when the page loads, then the shell remains visible and content shows a permission-denied state with copy `You don't have permission to access this page.` and a link to `/operations-overview`.
8. Given the sidebar footer renders, when a user is signed in, then it shows Syncro user identity from the existing auth session cookie (`loginIdentifier`, `applicationRole`) and provides Settings and Log out actions; boilerplate Account, Billing, and Notifications items are removed.
9. Given the user selects Log out from the sidebar user menu, when logout completes, then frontend calls `POST /api/v1/auth/logout` best-effort, clears `syncro_auth_token` and `syncro_auth_user`, clears client auth state, and redirects to `/auth/v2/login`.
10. Given the app shell renders, when navigation content is displayed, then generic boilerplate Quick Create and Inbox controls are removed.
11. Given the app shell header and sidebar render, when plant scope placeholder is evaluated, then sidebar footer plant placeholder is removed; header placeholder remains until Story 1.7 implements `PlantScopeSelector`.
12. Given an API call returns `401` after the shell is loaded, when the frontend API/auth boundary observes the response, then it clears auth cookies and redirects to `/auth/v2/login?next=<current-path>` with safe session-expired feedback where current UI infrastructure supports it.
13. Given the role model is implemented, when code and tests are inspected, then application roles remain separate from job scopes `TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, and `MANAGER`; do not add job-scope/ABAC or plant-assignment logic in this story.

## Tasks / Subtasks

- [x] Task 1: Add backend role authorization foundation (AC: #1, #2, #3, #4, #5, #13)
  - [x] Reuse `ApplicationRole` and existing JWT authorities (`ROLE_SUPER_ADMIN`, `ROLE_MANAGE`, `ROLE_VIEWER`) from `JwtAuthenticationFilter`; do not create duplicate role enum or permission source.
  - [x] Add method/path authorization in `SecurityConfig` or controller/service annotations for foundation endpoints currently available in code.
  - [x] Keep `/api/v1/health`, `/actuator/health`, and `/api/v1/auth/login` public; keep `/api/v1/auth/me` and `/api/v1/auth/logout` authenticated for all roles.
  - [x] Add a standard access-denied handler returning safe `403` JSON with `FORBIDDEN`, safe message, UTC timestamp, and traceId.
  - [x] Do not implement plant-scope checks, job-scope checks, user management, or ABAC.

- [x] Task 2: Expand backend role tests (AC: #1, #2, #3, #4, #5, #13)
  - [x] Add MockMvc tests proving `SUPER_ADMIN`, `MANAGE`, and `VIEWER` access/denial behavior for the role-gated endpoints selected in Task 1.
  - [x] Assert forbidden responses use stable error shape and `403`.
  - [x] Assert unauthenticated requests still return `401`, not `403`.
  - [x] Assert application roles remain distinct from job scopes by not adding job-scope authorities/claims.

- [x] Task 3: Add frontend auth-user access helper (AC: #6, #8, #9, #12)
  - [x] Add `useAuthUser()` under `syncro/apps/web/src/lib/auth/` or nearest existing auth boundary to parse `syncro_auth_user` using the existing `AuthUser` type.
  - [x] Treat cookie value as external data: parse defensively, accept only `SUPER_ADMIN | MANAGE | VIEWER`, and return `null` if invalid.
  - [x] Reuse `clearAuthSession()`, `getAuthToken()`, and typed auth/API boundary; do not scatter ad-hoc cookie parsing/fetch parsing across components.

- [x] Task 4: Apply role-aware navigation and search (AC: #6, #10)
  - [x] Update `sidebar-items.ts` roles: Master Data = `SUPER_ADMIN`, `MANAGE`; WAHA Templates = `SUPER_ADMIN`; Audit Log = `SUPER_ADMIN`, `MANAGE`; System Health = `SUPER_ADMIN`; Operations Overview/Telemetry/Alerts/Settings visible to all authenticated users.
  - [x] Add shared helper to filter `NavGroup[]` by `applicationRole`; `roles` absent/empty means all authenticated users.
  - [x] Use the helper in `NavMain` before rendering groups/items.
  - [x] Use the same helper in `SearchDialog` so command palette cannot show hidden items.
  - [x] Remove Quick Create and Inbox block from `NavMain`.
  - [x] Preserve active-state behavior with public URLs and existing rewrites.

- [x] Task 5: Replace boilerplate sidebar user menu (AC: #8, #9, #11)
  - [x] Adapt `nav-user.tsx` for Syncro: avatar initials from `loginIdentifier`, visible role badge/label, Settings action, Log out action.
  - [x] Remove Account, Billing, and Notifications items.
  - [x] Wire `NavUser` into `AppSidebar` footer using `useAuthUser()`.
  - [x] Remove sidebar plant-scope placeholder; leave header placeholder in `dashboard/layout.tsx` unchanged.
  - [x] Implement logout best-effort API call to `/api/v1/auth/logout`, then clear cookies and redirect.

- [x] Task 6: Add frontend forbidden route handling (AC: #7)
  - [x] Add route-level guard or shared page wrapper for role-hidden shell routes so direct URL access shows a shell-wrapped forbidden state, not a blank page or redirect loop.
  - [x] Cover at least `/waha-templates`, `/system-health`, `/master-data/*`, and `/audit-log` according to role matrix.
  - [x] Keep middleware as presence-only auth check; do not put role authorization in Next middleware because cookie role is not security boundary.

- [x] Task 7: Add session-expired handling in frontend API/auth boundary (AC: #12)
  - [x] Centralize `401` handling in the existing API/auth boundary where current fetch calls are made.
  - [x] Clear `syncro_auth_token` and `syncro_auth_user`.
  - [x] Redirect to `/auth/v2/login?next=<current-path>` from browser context.
  - [x] Use safe feedback if Sonner/toast infrastructure is already available; do not add new notification library.

- [x] Task 8: Verify with automated checks and browser evidence (AC: #1-#13)
  - [x] Run `mvn -f syncro/apps/backend/pom.xml test`.
  - [x] Run `npm --prefix syncro/apps/web run check`.
  - [x] Run `npm --prefix syncro/apps/web run build`.
  - [x] Run `npm --prefix syncro/apps/web run lint`.
  - [x] Run `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1`.
  - [x] Start backend and frontend locally; manually verify navigation/search/menu/forbidden/logout for `SUPER_ADMIN`, `MANAGE`, and `VIEWER` using seeded or test users.
  - [x] Map every AC to evidence before moving story to review.

## Dev Notes

### Scope Boundary

This story implements application-role enforcement and app-shell role UX only. It must not implement plant-scoped data access, plant assignments, `PlantScopeSelector`, job-scope/ABAC rules, master-data CRUD, telemetry, alerts, WAHA template functionality, audit log data, or System Health data. Story 1.7 owns plant-scoped data access. Later epics own domain endpoints and data.

### Current Code State To Preserve

- `syncro/apps/backend/src/main/java/com/syncro/auth/domain/ApplicationRole.java` already defines `SUPER_ADMIN`, `MANAGE`, and `VIEWER`. Reuse it.
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/JwtAuthenticationFilter.java` already maps authenticated users to `SimpleGrantedAuthority("ROLE_" + user.applicationRole().name())`. Role checks should use these authorities.
- `syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java` currently permits health and login, authenticates `/api/v1/**`, sets stateless JWT, ignores CSRF for `/api/v1/**`, and has CORS for frontend dev origins. Preserve public health/login behavior.
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthController.java` exposes login, current user, and logout under `/api/v1/auth`. Keep `me`/`logout` authenticated for all roles.
- `syncro/apps/web/src/lib/auth/auth-session.ts` already owns cookie names and `AuthUser` type. Use it as frontend role shape.
- `syncro/apps/web/src/lib/auth/auth-client.ts` already saves/clears token and user cookies. Extend/reuse it; do not introduce localStorage/sessionStorage auth.
- `syncro/apps/web/src/middleware.ts` currently checks only `syncro_auth_token` presence and redirects unauthenticated protected routes to `/auth/v2/login?next=<pathname>`. Keep it presence-only; backend remains security authority.
- `syncro/apps/web/next.config.mjs` rewrites clean public URLs to internal dashboard routes. Preserve URL strategy and active-state behavior.
- `syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/nav-main.tsx` currently includes Quick Create and Inbox boilerplate. Remove them.
- `syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/nav-user.tsx` is boilerplate Account/Billing/Notifications. Adapt it, do not keep irrelevant menu items.
- `syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/app-sidebar.tsx` currently has a sidebar plant-scope placeholder. Remove it after wiring user menu.
- `syncro/apps/web/src/app/(main)/dashboard/layout.tsx` currently has header plant-scope placeholder. Keep it for Story 1.7.
- `syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/search-dialog.tsx` currently builds command items statically from `sidebarItems`. It must become role-aware.

### Role Matrix

| Capability / Route | SUPER_ADMIN | MANAGE | VIEWER |
| --- | --- | --- | --- |
| Operations Overview `/operations-overview` | yes | yes | yes |
| Telemetry `/telemetry` | yes | yes | yes |
| Alerts `/alerts` | yes | yes | yes |
| Master Data `/master-data/*` | yes | yes | no |
| WAHA Templates `/waha-templates` | yes | no | no |
| Audit Log `/audit-log` | yes | yes | no |
| System Health `/system-health` | yes | no | no |
| Settings `/settings` | yes | yes | yes |

Frontend hiding is UX only. Backend API authorization remains mandatory.

### Backend Implementation Guardrails

- Use Spring Security role authorities already attached by JWT filter. Prefer `hasRole("SUPER_ADMIN")`, `hasAnyRole("SUPER_ADMIN", "MANAGE")`, or equivalent annotations.
- Authentication failure must remain `401`; authorization denial must be `403`.
- Error response shape must stay safe and stable: `code`, `message`, `timestamp`, `traceId`.
- Do not create a frontend-derived permission endpoint unless existing code needs it. Existing `/api/v1/auth/me` already returns role.
- Do not add job-scope values (`TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, `MANAGER`) to JWT authorities, `ApplicationRole`, frontend nav roles, or story tests.
- Do not place business/domain authorization in controllers when later domain services need it; this story may gate foundation endpoints/routes, but future mutations still need service-level permission when implemented.

### Frontend Implementation Guardrails

- Treat `syncro_auth_user` as untrusted display/navigation input. It can hide/show UI, but it must never be security authority.
- Avoid broad client boundaries. Add `"use client"` only where hooks, cookies, router, or dropdown actions require it.
- Use existing shadcn/Radix/sidebar primitives and Tailwind tokens. Do not add UI libraries.
- Search command palette must use same role filter as sidebar. Hidden routes must not appear in search.
- Direct route forbidden state must be visible within shell, not handled by middleware role checks.
- Session-expired handling should be centralized in API/auth boundary, not per page.

### Unauthorized Route UX

Use exact visible copy:

```text
You don't have permission to access this page.
```

Include a link/button to `/operations-overview`. Keep sidebar/header rendered. Do not expose hidden admin route details beyond safe page title/context already visible in URL.

### Previous Story Intelligence

- Story 1.5 selected Spring Security stateless JWT auth baseline.
- Story 1.5 created `auth_users` with `ApplicationRole` values ready for this story.
- Story 1.5 documented browser-managed Phase 1 JWT cookies and XSS limitation. Do not move token to localStorage.
- Story 1.5 route protection is presence-only in Next middleware. Keep this behavior; role checks must not depend on writable browser cookies for security.
- Story 1.5 review fixed timing-oracle and JSON ObjectMapper issues. Do not reintroduce custom ObjectMapper overrides for error handling.

### Project Structure Notes

No structure conflict detected. Expected changed areas:

- Backend security/config/tests:
  - `syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java`
  - `syncro/apps/backend/src/test/java/com/syncro/auth/api/AuthControllerTest.java` or new focused security test under matching test package
- Frontend auth/navigation/shell:
  - `syncro/apps/web/src/lib/auth/auth-session.ts`
  - `syncro/apps/web/src/lib/auth/auth-client.ts`
  - new `syncro/apps/web/src/lib/auth/use-auth-user.ts` or equivalent
  - `syncro/apps/web/src/navigation/sidebar/sidebar-items.ts`
  - optional new `syncro/apps/web/src/navigation/sidebar/filter-sidebar-items.ts`
  - `syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/nav-main.tsx`
  - `syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/search-dialog.tsx`
  - `syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/nav-user.tsx`
  - `syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/app-sidebar.tsx`
  - route/page files for role-hidden dashboard pages if direct-route guard is implemented there

### Testing Requirements

- Backend: MockMvc must prove `401` vs `403` distinction and role matrix for selected endpoints.
- Frontend: run Biome check/lint/build. Manual browser verification required because this changes visible navigation, search, dropdown, direct URL forbidden state, and logout.
- Acceptance evidence must map each AC to command/manual result before status moves to review.
- Do not mark story complete if browser verification is skipped without explicit blocker/risk statement.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.6: Enforce Application Role Access]
- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.7: Implement Plant-Scoped Data Access]
- [Source: _bmad-output/planning-artifacts/app-shell-navigation-specification.md#3. Navigation Items]
- [Source: _bmad-output/planning-artifacts/app-shell-navigation-specification.md#4. User Session Indicator]
- [Source: _bmad-output/planning-artifacts/app-shell-navigation-specification.md#6. Quick Create & Inbox (Boilerplate Removal)]
- [Source: _bmad-output/planning-artifacts/app-shell-navigation-specification.md#7. Search Command Palette]
- [Source: _bmad-output/planning-artifacts/app-shell-navigation-specification.md#8. Shell States]
- [Source: _bmad-output/planning-artifacts/app-shell-navigation-specification.md#15. Story 1.6 Scope Alignment]
- [Source: _bmad-output/project-context.md#Framework-Specific Rules]
- [Source: _bmad-output/project-context.md#Testing Rules]
- [Source: _bmad-output/project-context.md#Critical Don't-Miss Rules]
- [Source: _bmad-output/implementation-artifacts/1-5-choose-and-implement-auth-mode-baseline.md#Previous Story Intelligence]
- [Source: syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java]
- [Source: syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/JwtAuthenticationFilter.java]
- [Source: syncro/apps/web/src/middleware.ts]
- [Source: syncro/apps/web/src/navigation/sidebar/sidebar-items.ts]
- [Source: syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/nav-main.tsx]
- [Source: syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/search-dialog.tsx]
- [Source: syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/nav-user.tsx]
- [Source: syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/app-sidebar.tsx]

## Dev Agent Record

### Agent Model Used

Claude Code (cx/gpt-5.5)

### Debug Log References

- `mvn -f syncro/apps/backend/pom.xml test` — passed, 12 tests.
- `npm --prefix syncro/apps/web run check` — passed.
- `npm --prefix syncro/apps/web run build` — passed.
- `npm --prefix syncro/apps/web run lint` — passed.
- `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1` — passed.
- Browser verification on `http://localhost:3001` with backend local profile on `http://localhost:8080` — SUPER_ADMIN, MANAGE, VIEWER navigation/search/forbidden/logout verified.

### Completion Notes List

- Enabled backend method security and added safe `403 FORBIDDEN` JSON handling while preserving public health/login and authenticated `/api/v1/**` behavior.
- Added role-check foundation endpoints and MockMvc coverage for `SUPER_ADMIN`, `MANAGE`, `VIEWER`, `401` vs `403`, and safe forbidden response shape.
- Added defensive `useAuthUser()` cookie parsing with stable external-store snapshots and reused auth session clear/token helpers.
- Applied role-aware sidebar/search filtering, removed Quick Create/Inbox, and preserved clean public URLs.
- Replaced sidebar footer plant placeholder with Syncro user menu showing identity/role plus Settings and Log out actions; header plant placeholder remains.
- Added shell-visible route guard for role-hidden routes with exact permission-denied copy and `/operations-overview` link.
- Centralized frontend `401` session expiry handling in API/auth boundary with cookie clearing and login redirect using current path.

### File List

- `_bmad-output/implementation-artifacts/1-6-enforce-application-role-access.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthController.java`
- `syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java`
- `syncro/apps/backend/src/test/java/com/syncro/auth/api/AuthControllerTest.java`
- `syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/app-sidebar.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/nav-main.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/nav-user.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/search-dialog.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/audit-log/page.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/installations/page.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/machine-groups/page.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/machines/page.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/plants/page.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/responsibility/page.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/spareparts/page.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/system-health/page.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/waha-templates/page.tsx`
- `syncro/apps/web/src/components/syncro/role-guard.tsx`
- `syncro/apps/web/src/lib/api/syncro-api.ts`
- `syncro/apps/web/src/lib/auth/auth-client.ts`
- `syncro/apps/web/src/lib/auth/use-auth-user.ts`
- `syncro/apps/web/src/navigation/sidebar/filter-sidebar-items.ts`
- `syncro/apps/web/src/navigation/sidebar/sidebar-items.ts`

### Change Log

- 2026-05-26 — Implemented Story 1.6 application-role backend enforcement and role-aware app shell UX.

## Story Completion Status

Story implementation complete. Automated checks and browser verification passed. Ready for review.
