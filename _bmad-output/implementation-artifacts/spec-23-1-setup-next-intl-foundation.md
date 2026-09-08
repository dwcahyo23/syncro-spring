---
title: 'Story 23-1: Setup next-intl Foundation'
type: 'feature'
created: '2026-09-08'
status: 'done'
baseline_revision: 'c974481'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-23-context.md'
  - 'syncro/apps/web/AGENTS.md'
warnings: []
deferred:
  - summary: >-
      Login form never consumes the ?next= param — post-login always lands on
      /operations-overview, discarding the guard's return-to-origin intent.
    evidence: >-
      Verified against baseline: login-form.tsx at c974481 already
      `router.replace("/operations-overview")` with no param read; the original
      middleware set ?next= that nothing consumed. Pre-existing dead contract,
      not introduced by 23-1; implementing the return is a small feature task.
    location: >-
      src/features/auth/login-form.tsx:25
    severity: medium
  - summary: >-
      Feature-level next/link components outside the sidebar still emit
      unprefixed hrefs; under localePrefix "always" clicks from /en pages
      round-trip through middleware which re-prefixes to the DEFAULT locale,
      bouncing English users to /id.
    evidence: >-
      edge-case-hunter enumerated ~9 files (role-guard,
      health-evidence-link, workorder-actions-cell, machine-hub tabs,
      system-health-page, …); URLs work but the locale silently resets.
      Intentional story boundary: "No bulk translation ... (23.2's job)" —
      the Link conversion rides along with 23.2's component sweep.
    location: >-
      src/components/syncro/role-guard.tsx:5
    severity: medium
  - summary: >-
      Metadata is not localized: title/description stay English for every
      locale and no hreflang alternates are emitted.
    evidence: >-
      blind-hunter: [locale]/layout.tsx still spreads APP_CONFIG.meta.
      AC only requires <html lang>; per-locale generateMetadata is a
      follow-on concern Epic 23 never enumerated.
    location: >-
      src/app/[locale]/layout.tsx:21-24
    severity: medium
  - summary: >-
      nav-main active-state matching relies on next-intl's locale-stripped
      usePathname but has no test — reverting the import to next/navigation
      would silently break highlighting with a fully green suite.
    evidence: >-
      verification-gap ran the mutation demo: all 373 vitest tests and the
      running e2e pass either way. Closing it needs an authenticated e2e
      fixture which does not exist yet (tests/support has no login helper);
      23.3's switcher work is the natural place to add one.
    location: >-
      src/app/[locale]/(main)/dashboard/_components/sidebar/nav-main.tsx:144-155
    severity: low
  - summary: >-
      Indonesian catalog has no terminology policy (Alert untranslated vs
      Dasbor/Pemeliharaan translated; loanwords like Work Order, Master
      Data, Sparepart kept as-is).
    evidence: >-
      blind-hunter copy review. The catalog is seeded proof-of-pipeline only;
      copy governance belongs to 23.2 (bulk localization) / 23.4
      (completeness tooling) where the full string set exists.
    location: >-
      src/messages/id.json
    severity: low
  - summary: >-
      [locale]/layout.tsx reads locale from `params` while request.ts reads
      it from next/root-params — two sources of truth for the same value.
    evidence: >-
      blind-hunter; both currently validate via hasLocale so behavior is
      consistent, but drift is possible when 23.2 touches either.
      Standardize when metadata localization (deferred above) forces layout
      changes.
    location: >-
      src/i18n/request.ts:36 and src/app/[locale]/layout.tsx:34
    severity: low
  - summary: >-
      nav-secondary.tsx / nav-documents.tsx still take {title: string,
      url: string} and render plain <a href> (full page load, no locale).
    evidence: >-
      blind-hunter; both components are commented out in app-sidebar.tsx so
      the contract drift is latent. Convert when they are re-enabled (23.2).
    location: >-
      src/app/[locale]/(main)/dashboard/_components/sidebar/nav-secondary.tsx
    severity: low
  - summary: >-
      mergeMessages ships BOTH catalogs in every non-en response; fine at
      ~35 keys but grows linearly with 23.2's sweep.
    evidence: >-
      blind-hunter; mitigated with a ponytail comment naming the upgrade
      path (getMessageFallback or per-locale catalogs). Revisit if /id
      payload measurably regresses.
    location: >-
      src/i18n/request.ts:14-18
    severity: low
  - summary: >-
      system-health-page.test.tsx "6-6-AC3 stale machine evidence links to
      machine hubs" is red — expects /machines/AA-01, component renders
      /machines/by-id/<uuid>.
    evidence: >-
      Reproduced identically at pristine baseline c974481 before any 23-1
      change; unrelated Epic 6 regression. Recorded so future test:unit
      runs attribute the 373rd failure correctly.
    location: >-
      src/features/system-health/components/system-health-page.test.tsx:831
    severity: low
  - summary: >-
      audit-log.atdd-red.spec.ts goto URLs were updated to /id/… but every
      test in the file remains test.skip (ATDD red-phase scaffold) — the
      updates are inert until that story un-skips them.
    evidence: >-
      verification-gap: the spec's e2e command passes trivially on those
      files. Left skipped by design (red-phase placeholder), so no action
      here; un-skipping is that story's job.
    location: >-
      tests/e2e/audit-log.atdd-red.spec.ts:10-47
    severity: low
---

<intent-contract>

## Intent

**Problem:** The frontend has no i18n at all — `<html lang="en">` is hardcoded, every user-facing string is inline English, and there is no locale routing — so Epic 23 (FR-183) cannot start (story 23-2 needs a foundation to translate into).

**Approach:** Install next-intl (the project-decided library, Epic 23 Notes) and restructure `src/app/` under an `app/[locale]/` segment serving `/id` (default) and `/en` with `localePrefix: "always"`; merge next-intl's middleware with the existing auth guard; wire `getRequestConfig` + message catalogs (`messages/id.json`, `messages/en.json`) structured by feature/module mirroring `src/features/<domain>/`; seed the catalogs with the navigation + common namespaces and convert the sidebar nav labels to `t()` as the working proof. No bulk string migration (23.2), no switcher UI (23.3).

## Boundaries & Constraints

**Always:**
- Frontend-only change; backend, API contracts, and generated client untouched (AD-23: backend codes remain the contract)
- Public URL contract preserved: the 12 rewrites + 1 redirect in `next.config.mjs` keep working — unprefixed paths (`/operations-overview`, `/dashboard`, …) resolve via locale redirect to `/id/...` and the rewrite destinations move under `[locale]` in lockstep; `next-intl` plugin (`createNextIntlPlugin`) must work under BOTH `next dev` (turbopack) and `next build --webpack`
- Default locale `id`; locales exactly `["id","en"]`; `<html lang>` follows the active locale; existing providers chain (Tooltip > Preferences > Query > Toaster) and ThemeBootScript preserved across the layout move
- Auth guard behavior unchanged: protected routes still redirect to login with `?next=` — the guard must evaluate the locale-stripped path so `/id/alerts` and `/alerts` behave identically
- Message catalogs are nested JSON keyed by feature/module (`common`, `navigation`, then one key per existing `src/features/<domain>` as 23.2 fills them); `resolveJsonModule` already allows direct import
- Next 16.3 has breaking changes (AGENTS.md): verify next-intl's current major supports it before wiring; consult `node_modules/next/dist/docs/` — do not copy Next 14/15 examples blindly
- npm only (package-lock.json); Biome stays the formatter/linter; no new libraries beyond next-intl itself

**Block If:**
- The installed next-intl version turns out incompatible with Next 16.3 App Router with no viable configuration → HALT (do not swap to another i18n library — library choice is a project decision)
- Preserving the public rewrite contract seems to require dropping `localePrefix: "always"` → HALT (routing strategy is fixed by the AC)

**Never:**
- No bulk translation of the ~91 string-bearing components (23.2's job) — only navigation + common seeds prove the pipeline
- No language switcher UI (23.3), no missing-key CI tooling (23.4)
- No backend changes, no new env vars beyond what next-intl requires, no Pages Router, no `NEXT_PUBLIC_*` locale leakage into business logic

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Visit `/` | no locale | 307 → `/id` (default locale) | — |
| Visit `/en` | logged out | app shell renders, `lang="en"`, English nav labels | — |
| Visit `/id/alerts` | no auth cookie | redirect `/auth/v2/login?next=/id/alerts` (guard sees protected after locale strip) | — |
| Visit `/operations-overview` | public short URL | locale redirect then rewrite → dashboard page renders | — |
| Unsupported locale `/fr/...` | unknown prefix | 404 (not silently id) | — |
| Missing message key | key absent in id.json | next-intl falls back per config; dev warning, no raw-key crash | — |
| Build | `next build --webpack` | compiles with plugin applied | fail build on config error |

</intent-contract>

## Code Map

- `syncro/apps/web/package.json` -- deps/scripts; npm manager; `build: next build --webpack`
- `syncro/apps/web/next.config.mjs` -- 12 rewrites + 1 redirect (public URL contract); add createNextIntlPlugin
- `syncro/apps/web/src/middleware.ts:6-48` -- auth guard protectedRoutes + matcher; merge next-intl createMiddleware
- `syncro/apps/web/src/app/layout.tsx:27,42-55` -- `<html lang="en">` + providers chain -- moves under `[locale]`
- `syncro/apps/web/src/app/(main)/` + bare `app/{preventive,sparepart-requests,workorders}` -- ~46 pages to relocate under `app/[locale]/`
- `syncro/apps/web/src/navigation/sidebar/sidebar-items.ts:73-83` -- nav titles -- first `t()` conversion proof
- `syncro/apps/web/src/lib/utils.ts:22-42` -- formatCurrency default en-US (23.2 concern; leave)
- `syncro/apps/web/tests/e2e/dashboard.spec.ts:7-9`, `audit-log.atdd-red.spec.ts:9-48`, `config-hardening.atdd-red.spec.ts:22-51` -- unprefixed navigations to update
- `syncro/apps/web/vitest.config.ts` + `src/app/(main)/dashboard/_components/sidebar/theme-switcher.tsx` -- test setup + switcher-adjacent pattern (23.3)
- `syncro/apps/web/AGENTS.md` -- Next 16.3 breaking-change warning

## Tasks & Acceptance

**Execution:**
- `syncro/apps/web/package.json` -- extend -- `npm install next-intl` (verify Next 16.3-compatible major first)
- `src/i18n/routing.ts` + `src/i18n/request.ts` -- create -- defineRouting({locales:["id","en"], defaultLocale:"id", localePrefix:"always"}), getRequestConfig loading `messages/{locale}.json`
- `next.config.mjs` -- extend -- createNextIntlPlugin wrapper; rewrites/redirect gain `[locale]`-aware sources/destinations preserving the public contract
- `src/middleware.ts` -- extend -- next-intl createMiddleware composed with the auth guard (locale-stripped path evaluation); matcher covers prefixed + unprefixed
- `src/app/[locale]/layout.tsx` (+ `generateStaticParams`/`setRequestLocale`) -- create -- move root layout here; `<html lang={locale}>`; providers chain intact; `src/app/layout.tsx` removed or reduced per Next 16 requirement
- `src/app/[locale]/(main)/...` + relocate bare routes -- move -- all ~46 pages under `[locale]` (pure file moves, no content edits)
- `messages/id.json` + `messages/en.json` -- create -- `common` + `navigation` namespaces seeded (all sidebar titles); structure documented for 23.2
- `src/navigation/sidebar/sidebar-items.ts` + rendering component -- convert -- titles to message keys resolved via `t()` (proof the pipeline renders localized strings)
- Tests: `src/i18n/routing.test.ts` (vitest: locale list, default id, prefix behavior); update the three e2e specs to locale-prefixed expectations; add `tests/e2e/i18n.spec.ts` (visit `/` → `/id`; `/en` renders `lang="en"`; `/fr` 404s)

**Acceptance Criteria:**
- Given the app runs, when visiting `/id` and `/en`, then the shell renders with the matching `<html lang>` and the sidebar labels come from the locale's catalog (AC1)
- Given a request to any previously-public unprefixed URL (`/`, `/operations-overview`, `/dashboard`), then it resolves via locale redirect to the same page under `/id` — no public URL breaks (AC2)
- Given an unauthenticated visit to a protected route in either locale form, then the login redirect still fires with the correct `?next=` (AC3)
- Given `next build --webpack` and `next dev` (turbopack), then both compile with the i18n plugin active (AC4)

## Spec Change Log

## Review Triage Log

### 2026-09-09 — Review pass 1
- intent_gap: 0
- bad_spec: 0
- patch: 12 (high 4, medium 4, low 4)
- defer: 10 (medium 3, low 7)
- reject: 6
- addressed_findings:
  - `[high]` `[patch]` proxy.ts matcher used full-regex syntax `([\w-]+)?/…` which Next's path-to-regexp rejects — webpack build failed. Replaced with valid `:path*` entries, unprefixed + `/:locale/...` pairs for the dotted-path trees.
  - `[high]` `[patch]` 16 redirect-stub pages called next-intl's `redirect()` with a bare string; v4 requires `{href, locale}`. Pages now read `params.locale` and pass the object form (build failed type-check before this).
  - `[high]` `[patch]` `Messages` recursive type alias circularly self-referenced (TS2456); converted to an interface with index signature.
  - `[high]` `[patch]` Redirect stubs dropped the active locale (unprefixed `redirect()` re-prefixed to /id, failing the /en e2e). All 16 made locale-aware; e2e "visiting /en renders lang=en" green.
  - `[medium]` `[patch]` `redirectToLogin` (401-expiry sink) hardcoded unprefixed `/auth/v2/login` — /en users bounced to the Indonesian login shell. Extracted pure `buildLoginUrl(currentPathname, nextPath)` locale helper + unit test.
  - `[medium]` `[patch]` next.config rewrite/redirect `:locale` params unconstrained — unknown prefixes could rewrite to wrong-shaped destinations. Constrained all 13 entries to `(id|en)`.
  - `[medium]` `[patch]` AC1 sidebar-label proof had no test: added `sidebar-items.test.ts` asserting every titleKey/labelKey resolves in BOTH catalogs (mutation demo: typos now caught).
  - `[medium]` `[patch]` AC2 spot-checked 1 of 13 public URLs: parametrized the e2e previously-public test over 6 representative paths (operations-overview, dashboard redirect, alerts, analytics, settings, master-data). 11/11 green.
  - `[low]` `[patch]` Dead `barePathname === "/"` branch in proxy.ts `?next=` construction removed (`/` never reaches the guard).
  - `[low]` `[patch]` `comingSoon: "Segera"` → `"Segera hadir"` (idiomatic Indonesian for a Soon badge).
  - `[low]` `[patch]` Root `app/not-found.tsx` deleted with no replacement — /fr 404 rendered the Next default. Restored a branded root not-found (plain next/link; proxy re-prefixes) and pinned the render in the e2e.
  - `[low]` `[patch]` mergeMessages ships both catalogs per non-en response — added ponytail comment naming ceiling and upgrade path (getMessageFallback / per-locale).

## Design Notes

- `localePrefix: "always"` (not "as-needed") so every URL carries an explicit locale and the rewrite table can map 1:1 under `[locale]`; `/` → `/id` redirect is the default-locale entry.
- Middleware composition order: next-intl middleware first (sets locale), then the auth check on the locale-stripped pathname; `?next=` keeps the full prefixed path so post-login return lands in the right locale.
- Catalogs live at `src/messages/{id,en}.json` (next-intl convention) keyed by feature; 23.2 adds one namespace per domain without further structural change.
- next-intl v4's `redirect()`/`getPathname()` take `{href, locale}` objects, not strings — the 16 redirect-stub pages under `[locale]` are async components that await `params.locale` and call `redirect({href, locale})` (keeps the active locale; a bare/unprefixed redirect silently lands /en users on /id).
- Rewrite/redirect sources and the proxy matcher constrain the locale param to `(id|en)` — Next's matcher syntax rejects `(...)?` regex quantifiers (build error), and unconstrained `:locale` would let unknown prefixes rewrite to wrong-shaped destinations. Unsupported prefixes 404 via the `[locale]` layout `hasLocale` guard + `[...rest]` catch-all; a branded root `app/not-found.tsx` covers 404s thrown above the `[locale]` segment.
- 401-expiry client redirect uses pure `buildLoginUrl(currentPathname, nextPath)` (locale from the current URL, default-locale fallback) — jsdom can't reliably mock `window.location`, so the locale rule lives in a testable helper.

## Verification

**Commands:**
- `npm --prefix syncro/apps/web run build` -- expected: webpack build green with next-intl plugin
- `npm --prefix syncro/apps/web run lint` -- expected: Biome clean
- `npm --prefix syncro/apps/web run test:unit` -- expected: vitest green incl. new routing tests
- `npm --prefix syncro/apps/web run test:e2e -- i18n dashboard audit-log.atdd-red config-hardening.atdd-red` -- expected: locale-aware specs green (dev server via playwright webServer)

## Auto Run Result

Status: done (pass 2; pass 1 HALTED blocked on the user-denied `npm install next-intl`, which the user then ran manually)

**Summary of implemented change:** next-intl foundation for the i18n epic — `src/i18n/{routing,request,navigation}.ts` (defineRouting id/en, default id, `localePrefix: "always"`, `localeDetection: false`; English-fallback message merge; `createNavigation`), `src/messages/{id,en}.json` (common + navigation namespaces), `src/app/` restructured under `[locale]/` (all ~46 pages via `git mv`; `generateStaticParams` for both locales; `<html lang={locale}>`; providers chain + ThemeBootScript intact), `middleware.ts` → `proxy.ts` (next-intl middleware composed with the auth guard evaluating the locale-stripped path, locale-qualified login redirect with prefixed `?next=`), `next.config.mjs` (`createNextIntlPlugin`; the 12 rewrites + 1 redirect gained `(id|en)`-constrained `:locale` sources preserving the public URL contract), sidebar data model converted to message keys resolved via `t()`, feature-level `Link`/`useRouter`/`usePathname` switched to `@/i18n/navigation`, root + `[locale]` not-found, e2e/unit test suites for locale routing and catalog coverage.

**Files changed (105):** `package.json`/`package-lock.json` (+next-intl 4.14.2, 726 pkgs audited), `next.config.mjs`, `src/middleware.ts`→`src/proxy.ts`, `src/i18n/*` (3 new + routing.test.ts), `src/messages/{id,en}.json` (new), `src/app/[locale]/**` (63 renames; layout/not-found/[...rest] new; root `app/not-found.tsx` restored), redirect stubs ×16, sidebar/nav components ×7, auth-client.ts + new test, alert/machine-hub feature links ×7, `tests/e2e/i18n.spec.ts` (new, 11 tests), dashboard/audit-log/config-hardening specs updated, `.gitignore`.

**Review findings breakdown:** 12 patches applied (4 high — matcher syntax, redirect-call shape, circular type, locale-dropping stubs; 4 medium — 401 locale, unconstrained `:locale`, AC1 catalog test, AC2 parametrize; 4 low — dead branch, copy, root not-found, ponytail note), 10 deferred (frontmatter `deferred:`), 6 rejected (bulk English strings [spec explicitly excludes], en-side key parity CI [23.4], shared protectedRoutes constant [3rd-list consolidation speculative], lockfile native-dep churn [next-intl's own transitive graph — "no new libraries" satisfied], Accept-Language detection [AC fixes default-id], `:locale` matcher unconstrained in proxy [next-intl 404s before guard]).

**Follow-up review recommendation:** true — 4 high patched. Score `3×4 + 1×4 = 16` (≥5 threshold).

**Verification performed:** `npm run build` (webpack) green — Compiled successfully, all `/id/*` + `/en/*` SSG routes, `ƒ Proxy (Middleware)` registered. `npm run lint`: 12 errors / 133 infos — **identical to the pristine-baseline count** (measured via stash; all 12 pre-existing in untouched files); `biome --diagnostic-level=error` clean on every 23-1 file. `tsc --noEmit` exit 0. `test:unit` 372/373 (new catalog + auth tests pass; the 1 failure is the deferred baseline `6-6-AC3`). `test:e2e` i18n 11/11, dashboard 1/1, targeted atdd-red 9 skipped (pre-existing red-phase). One environment note: the pass-1 turbopack HMR panic (`CellId … no longer exists`, loop-reloading 404 page) was reproduced as a stale-cache artifact — cleared by deleting `.next/dev` with the server stopped; a fresh server passes every locale test, and the Playwright webServer path spawns its own clean server.

**Residual risks:** feature-level unprefixed `next/link`s round-trip middleware re-prefixing to the default locale (23.3/23.2 sweep; deferred); `?next=` still unconsumed by login-form (deferred); root not-found English-only by design (no locale context above `[locale]`); 23-2 starts with the redirect-stub pages already locale-aware so no rework, but must also convert the remaining `Link` call sites.


