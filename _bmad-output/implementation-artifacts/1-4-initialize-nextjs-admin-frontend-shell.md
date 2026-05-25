# Story 1.4: Initialize Next.js Admin Frontend Shell

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an implementer,
I want the selected Next.js shadcn admin dashboard starter adapted into `syncro/apps/web`,
so that Syncro has a responsive app shell for future operational screens.

## Acceptance Criteria

1. Given repository foundation exists, when frontend starter is installed in `syncro/apps/web`, then frontend runs locally using the starter package scripts.
2. Given selected UX foundation, when the app shell renders, then default light theme and boilerplate theme capability remain available.
3. Given users access Syncro from desktop, tablet, or mobile, when the frontend shell renders, then app shell supports responsive layouts across those breakpoints.
4. Given architecture frontend boundary rules, when route files are inspected, then `app/` contains route shell/page composition only.
5. Given future Syncro domain screens need clear placement, when frontend source is inspected, then `features/`, `components/ui/`, and `components/syncro/` boundaries exist or are documented.
6. Given backend owns domain decisions, when frontend shell is inspected, then no Syncro domain rule is implemented in frontend-only code.
7. Given baseline validation is run, when project validation executes, then frontend package, route shell, Syncro navigation placeholders, and local run docs are covered without committed secrets or generated build output.

## Tasks / Subtasks

- [x] Task 1: Install/adapt selected frontend starter in `syncro/apps/web` (AC: #1, #2)
  - [x] Replace placeholder `.gitkeep` only if real starter files make it unnecessary.
  - [x] Use `arhamkhnz/next-shadcn-admin-dashboard` as source foundation, aligned with v2.2.0 frontend hardening spec.
  - [x] Preserve Next.js 16, TypeScript, Tailwind CSS v4, shadcn/ui, React Hook Form, Zod, Zustand, TanStack Table, Biome, Husky, npm, and Geist baseline from starter.
  - [x] Remove nested `.git` metadata if starter is cloned/copied into `syncro/apps/web`.
  - [x] Do not add new npm packages unless starter install requires them; Phase 1 domain components require zero new dependencies beyond boilerplate stack.
- [x] Task 2: Strip/adapt demo shell into Syncro app shell (AC: #2, #3, #4, #5)
  - [x] Keep boilerplate sidebar/header/layout provider, theme presets, dark-mode capability, shadcn/ui primitives, form/table conventions, responsive sidebar behavior, and preference store.
  - [x] Strip demo dashboard routes/content, mail app, demo users page, demo data, legacy dashboards, coming soon page, external page, and support card.
  - [x] Replace app name/branding with Syncro.
  - [x] Configure navigation placeholders in this order: Operations Overview, Telemetry, Alerts, Master Data, WAHA Templates, Audit Log, System Health, Settings.
  - [x] Create route shell placeholders for future routes without implementing domain workflows.
- [x] Task 3: Establish frontend source boundaries (AC: #4, #5, #6)
  - [x] Ensure `src/app/` contains route shells/page composition only.
  - [x] Ensure `src/features/` exists for `operations`, `machines`, `telemetry`, `alerts`, `master-data`, `waha-templates`, `audit-log`, `system-health`, and `settings` or document deferred creation if starter structure requires incremental creation.
  - [x] Ensure `src/components/ui/` remains generic shadcn/ui.
  - [x] Ensure `src/components/syncro/` exists for Syncro domain visual components; build only lightweight shell-required component(s) if needed, not full future domain components.
  - [x] Do not implement role guards, auth mode, plant scoping, telemetry calculations, alert rules, or API-backed domain state in this story.
- [x] Task 4: Add Syncro frontend theme/status foundation (AC: #2, #3)
  - [x] Add Syncro semantic status CSS tokens to `src/app/globals.css` after existing theme variables.
  - [x] Preserve default light theme and existing theme preset switching.
  - [x] Add utility support for tabular telemetry numbers and monospace technical values if compatible with starter CSS.
  - [x] Do not create one-off inline status colors; future status rendering must use semantic token/component path.
- [x] Task 5: Add local docs and validation coverage (AC: #1, #7)
  - [x] Update `syncro/docs/local-development.md` with frontend install/run/build/lint commands from `syncro/apps/web`.
  - [x] Update `syncro/README.md` only if version/package baseline differs from current documented Next.js 16 / Node 22 / npm 10 assumptions.
  - [x] Extend `syncro/scripts/validate-syncro-baseline.ps1` to verify frontend essentials without depending on caller working directory.
  - [x] Ensure validation does not require `.env`, secrets, dev server, `.next/`, `node_modules/`, or build output in Git.
- [x] Task 6: Verify frontend shell works (AC: #1, #2, #3, #7)
  - [x] Run package install in `syncro/apps/web` using starter package manager.
  - [x] Run frontend lint/type/build command available from package scripts.
  - [x] Start frontend dev server and open shell in browser.
  - [x] Verify desktop, tablet, and mobile responsive shell behavior manually.
  - [x] Verify console has no critical runtime errors on shell load.
  - [x] Record exact commands and blockers in Dev Agent Record; do not claim browser verification unless performed.

### Review Findings

- [x] [Review][Patch] Add missing settings feature boundary [syncro/apps/web/src/features/settings]
- [x] [Review][Patch] Extend baseline validation to require settings feature boundary [syncro/scripts/validate-syncro-baseline.ps1]
- [x] [Review][Patch] Remove committed starter dashboard image or confirm project asset ownership [syncro/apps/web/media/dashboard.png]
- [x] [Review][Patch] Fix web pre-commit hook paths so they work from repository root [syncro/apps/web/.husky/pre-commit:1]
- [x] [Review][Patch] Encode/decode client cookie values and set SameSite consistently [syncro/apps/web/src/lib/cookie.client.ts:9]
- [x] [Review][Patch] Constrain preference cookie server action inputs or remove unused generic setter [syncro/apps/web/src/server/server-actions.ts:9]
- [x] [Review][Patch] Remove unused auth form validation/demo submit behavior before auth story [syncro/apps/web/src/app/(main)/auth/_components/login-form.tsx:11]
- [x] [Review][Patch] Remove unused auth form validation/demo submit behavior before auth story [syncro/apps/web/src/app/(main)/auth/_components/register-form.tsx:10]
- [x] [Review][Patch] Remove blanket `*.sh` ignore from web app gitignore [syncro/apps/web/.gitignore:21]

## Dev Notes

### Scope Boundary

This story initializes the frontend shell only. It must not implement login behavior, auth mode choice, backend API integration beyond static config placeholders, role access enforcement, plant-scoped filtering, master-data CRUD, telemetry UI data, alert flows, WAHA templates, health data, or Syncro domain calculations.

The correct output is a runnable, responsive Next.js admin shell with Syncro navigation and folder boundaries ready for future stories.

### Current State From Previous Stories

- Story 1.1 created monorepo structure under `syncro/`, `.env.example`, local docs, and cwd-independent validation script.
- Story 1.2 added Docker Compose infrastructure with stable service names: `postgres`, `pgadmin`, `redis`, `influxdb`, `emqx`, `waha`.
- Story 1.3 created Spring Boot backend under `syncro/apps/backend`, `/api/v1/health`, backend env/profile config, backend tests, and validation coverage.
- Current `syncro/apps/web` contains only `.gitkeep`; this story fills that reserved location.
- Preserve `syncro/scripts/validate-syncro-baseline.ps1` root discovery via `$PSScriptRoot`; do not weaken existing backend/infra checks.

### Required Frontend Baseline

| Area | Requirement |
| --- | --- |
| Frontend root | `syncro/apps/web` |
| Framework | Next.js 16 |
| Runtime | Node.js 22 LTS |
| Package manager | npm 10+ |
| Language | TypeScript 5.x |
| Styling | Tailwind CSS v4 + shadcn/ui |
| Starter | `arhamkhnz/next-shadcn-admin-dashboard` |
| Boilerplate version reference | v2.2.0 per frontend hardening spec |
| State | Zustand only for local UI/session shell state |
| Forms/tables | React Hook Form + Zod + TanStack Table |
| Tooling | Biome, Husky, TypeScript strict |

If upstream starter versions conflict with documented baseline, prefer actual starter compatibility and record exact versions in README/docs and Dev Agent Record.

### Starter Keep / Strip / Adapt Rules

Keep:

- Sidebar, sticky header, layout provider, responsive sidebar collapse.
- Existing theme presets, default light theme, dark mode capability, next-themes.
- shadcn/ui components, forms, tables, command palette, Zustand preferences, Biome/Husky/TypeScript tooling, Geist font.

Strip:

- Demo dashboards (`dashboard/default`, `crm`, `finance`, `analytics`, `productivity`, `ecommerce`, `academy`, `logistics`).
- Mail app, demo users page, demo `src/data/`, legacy dashboards, coming soon page, external page, support card.

Adapt:

- App name/branding to Syncro.
- Sidebar navigation to Syncro 8-item order.
- Account switcher location/pattern reserved for future `PlantScopeSelector`.
- Login screen may remain as static boilerplate route, but must not wire auth behavior until Story 1.5.
- Search dialog can remain shell-only; no domain search implementation yet.

### Required Route/Folder Shape

Target shape after initialization may follow starter route groups, but must preserve these boundaries:

```text
syncro/apps/web/src/app/
  (auth)/login/page.tsx
  (dashboard)/layout.tsx
  (dashboard)/operations-overview/page.tsx
  (dashboard)/telemetry/page.tsx
  (dashboard)/alerts/page.tsx
  (dashboard)/master-data/plants/page.tsx
  (dashboard)/master-data/machine-groups/page.tsx
  (dashboard)/master-data/machines/page.tsx
  (dashboard)/master-data/spareparts/page.tsx
  (dashboard)/master-data/installations/page.tsx
  (dashboard)/master-data/responsibility/page.tsx
  (dashboard)/waha-templates/page.tsx
  (dashboard)/audit-log/page.tsx
  (dashboard)/system-health/page.tsx
  (dashboard)/settings/page.tsx
  not-found.tsx
syncro/apps/web/src/features/
syncro/apps/web/src/components/ui/
syncro/apps/web/src/components/syncro/
syncro/apps/web/src/lib/api/
syncro/apps/web/src/lib/auth/
syncro/apps/web/src/lib/formatting/
syncro/apps/web/src/stores/
syncro/apps/web/src/types/
```

Do not force exact route-group names if starter conventions differ, but final user-facing routes should match `/operations-overview`, `/telemetry`, `/alerts`, `/master-data/...`, `/waha-templates`, `/audit-log`, `/system-health`, and `/settings`.

### Navigation Requirements

Sidebar order:

1. Operations Overview
2. Telemetry
3. Alerts
4. Master Data
   - Plants
   - Machine Groups
   - Machines
   - Spareparts
   - Installations
   - Responsibility
5. WAHA Templates
6. Audit Log
7. System Health
8. Settings

System Health may be marked SUPER_ADMIN-only in data/config comments/types, but do not enforce role hiding until Story 1.6.

### Frontend Boundary Rules

- Next.js must not access PostgreSQL, InfluxDB, Redis, EMQX, or WAHA directly.
- All future data access goes through Spring Boot `/api/v1` APIs or controlled route handlers that proxy backend.
- No database credentials, WAHA API keys, or MQTT credentials in frontend env/browser code.
- `NEXT_PUBLIC_API_URL` is acceptable for browser-visible backend base URL only.
- Backend remains source of truth for permissions, statuses, lifecycle transitions, sparepart lifetime calculations, telemetry freshness rules, allowed actions, and status reasons.
- Frontend shell may show static placeholder text/cards, but must not encode domain business rules.

### UX and Accessibility Requirements

- Preserve responsive shell for desktop ≥1024px, tablet 768-1023px, mobile <768px.
- Default experience is light theme; optional dark/control-room style remains through boilerplate theme capability.
- Status must never rely on color alone; semantic tokens support future `StatusBadge`.
- Keep focus-visible styles and keyboard-accessible navigation from boilerplate.
- Mobile touch targets for primary shell actions should stay practical, around 44px where layout allows.
- Placeholder pages should clearly state future module purpose and avoid fake operational data that could be mistaken for real backend state.

### Validation Requirements

Run from repository root unless tool requires app directory:

```powershell
pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1
npm --prefix syncro/apps/web install
npm --prefix syncro/apps/web run build
```

Also run any available starter checks, for example:

```powershell
npm --prefix syncro/apps/web run lint
npm --prefix syncro/apps/web run typecheck
npm --prefix syncro/apps/web run format:check
```

Use actual package script names from `syncro/apps/web/package.json`; do not invent scripts.

For browser verification:

```powershell
npm --prefix syncro/apps/web run dev
```

Then open local dev URL printed by Next.js and verify shell load, navigation, theme control, and responsive layout at desktop/tablet/mobile sizes. If dev server cannot run, record exact blocker.

### Testing Requirements

- At minimum, package build/type/lint scripts must pass if available.
- Manual browser verification is required before reporting story complete because this is a UI/frontend shell story.
- Validation script should check static structure and documented commands, not generated build output or `node_modules`.
- Do not commit `.next/`, `node_modules/`, coverage, logs, local env files, or generated binaries.

### Previous Story Intelligence

- Story 1.3 initially risked committing hardcoded local defaults; avoid embedding secrets or infrastructure URLs in frontend config.
- Story 1.3 fixed tests to avoid live PostgreSQL dependency; frontend validation should likewise not require local infra.
- Story 1.3 preserved CSRF defaults outside `/api/v1/**`; do not make frontend assumptions about final auth/CSRF mode until Story 1.5.
- Existing validation script is cwd-independent; preserve that behavior.
- Current backend uses `/api/v1/health` as smoke endpoint, but frontend shell should not depend on backend availability for this story.

### Project Structure Notes

No structure conflict detected. `syncro/apps/web` is reserved and currently empty except `.gitkeep`. This story may add frontend source, package files, lockfile, and docs under allowed project scope. Generated directories such as `.next/` and `node_modules/` must remain untracked.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.4: Initialize Next.js Admin Frontend Shell]
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend: arhamkhnz/next-shadcn-admin-dashboard]
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Architecture]
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Security Boundary]
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Boundaries]
- [Source: _bmad-output/planning-artifacts/architecture.md#Complete Project Directory Structure]
- [Source: _bmad-output/planning-artifacts/frontend-hardening-specification.md#Boilerplate Inventory]
- [Source: _bmad-output/planning-artifacts/frontend-hardening-specification.md#Sidebar Navigation Configuration]
- [Source: _bmad-output/planning-artifacts/frontend-hardening-specification.md#Route Structure After Initialization]
- [Source: _bmad-output/planning-artifacts/frontend-hardening-specification.md#Implementation Sequence]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Design System Foundation]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Responsive Design & Accessibility]
- [Source: _bmad-output/planning-artifacts/page-specifications.md#Operations Overview]
- [Source: _bmad-output/implementation-artifacts/1-3-initialize-spring-boot-backend-skeleton.md]
- [Source: syncro/README.md#Version Baseline]
- [Source: syncro/.env.example]
- [Source: syncro/scripts/validate-syncro-baseline.ps1]

## Dev Agent Record

### Agent Model Used

cx/gpt-5.5

### Debug Log References

- `npm install --include=optional --force` regenerated frontend dependencies and lockfile; `husky` reported `.git can't be found` from app subdirectory, non-blocking.
- `Format-Hex node_modules\@next\swc-win32-x64-msvc\next-swc.win32-x64-msvc.node -Count 4` showed `4D 5A 78 00`; native SWC still installed, and clean build completed successfully.
- `npm run build` passed with Next.js 16.2.6 webpack build after clean reinstall.
- `npm run lint` passed.
- `npm run check` passed.
- `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1` passed.
- `npm run dev` started Next.js 16.2.6 Turbopack on `http://localhost:3001` because port 3000 was occupied by process 20260.
- Browser verification opened `/operations-overview`, desktop/tablet/mobile responsive snapshots passed, and console showed no errors after route rewrite fix.
- Code review follow-ups applied: removed starter image/demo auth form components, fixed cookie persistence, removed unused generic server cookie setter, fixed web Husky paths, narrowed `.gitignore`, and extended baseline validation for feature boundaries.
- Review follow-up validation passed: `npm --prefix syncro/apps/web run check`, `npm --prefix syncro/apps/web run build`, `npm --prefix syncro/apps/web run lint`, and `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1`.

### Completion Notes List

- Adapted Next.js shadcn admin starter into `syncro/apps/web` with Syncro branding, shell navigation, layout providers, theme capability, status tokens, source boundaries, and placeholder module routes.
- Removed demo mail route/content and replaced auth/unauthorized pages with static shell placeholders so no auth/domain behavior is implemented before later stories.
- Added public route rewrites for Syncro shell URLs while preserving starter dashboard route group structure.
- Updated local development docs and baseline validation to cover frontend package, route shells, navigation, boundaries, status tokens, and documented commands without requiring secrets or build output.
- Verified build, lint, Biome check, baseline validation, dev server startup, browser shell load, desktop/tablet/mobile responsiveness, and console state.
- Addressed all code review patch findings and re-ran web check/build/lint plus baseline validation successfully.

### File List

- `_bmad-output/implementation-artifacts/1-4-initialize-nextjs-admin-frontend-shell.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `syncro/apps/web/.gitkeep` (deleted)
- `syncro/apps/web/.gitignore`
- `syncro/apps/web/.husky/pre-commit`
- `syncro/apps/web/CONTRIBUTING.md`
- `syncro/apps/web/LICENSE`
- `syncro/apps/web/README.md`
- `syncro/apps/web/biome.json`
- `syncro/apps/web/components.json`
- `syncro/apps/web/next.config.mjs`
- `syncro/apps/web/package-lock.json`
- `syncro/apps/web/package.json`
- `syncro/apps/web/postcss.config.mjs`
- `syncro/apps/web/src/**/*`
- `syncro/apps/web/media/dashboard.png` (deleted after code review)
- `syncro/apps/web/tsconfig.json`
- `syncro/apps/web/tsconfig.scripts.json`
- `syncro/docs/local-development.md`
- `syncro/scripts/validate-syncro-baseline.ps1`

### Change Log

| Date | Version | Description | Author |
| --- | --- | --- | --- |
| 2026-05-26 | 1.0 | Implemented Syncro Next.js admin frontend shell and validation coverage. | Dev Agent |
| 2026-05-26 | 1.1 | Addressed code review follow-ups and marked story done. | Dev Agent |