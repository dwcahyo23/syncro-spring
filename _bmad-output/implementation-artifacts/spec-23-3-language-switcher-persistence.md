---
title: 'Story 23-3: Language Switcher & Persistence'
type: 'feature'
created: '2026-09-10'
status: 'done'
baseline_revision: '13b2dff705c00aee46f3ea1895fb4ad125c97efd'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-23-context.md'
  - 'syncro/apps/web/AGENTS.md'
warnings: []
deferred:
  - summary: >-
      The sentinel-auth e2e flow (`syncro_auth_token` planted cookie) assumes
      the proxy guard stays presence-only AND that shell pages make no
      user-cookie-dependent API calls; if the guard ever validates tokens or
      a sampled page gains fetching, these specs break without a real-auth
      fixture existing.
    evidence: >-
      intent-alignment (d) + blind-hunter: proxy.ts checks cookie presence only
      today; /id/settings is a static placeholder now. A real login helper in
      tests/support is the proper home for this (any story needing deeper shell
      e2e).
    location: >-
      tests/e2e/i18n.spec.ts (signInForShell helper)
    severity: low
---

<intent-contract>

## Intent

**Problem:** 23-1/23-2 gave the app /id and /en routes with everything localized, but locale is URL-owned only — there is no user-facing control to switch language, and a returning visitor to `/` always lands on the default `id` regardless of their last choice (`localeDetection: false` deliberately kills Accept-Language guessing).

**Approach:** Add an ID/EN language switcher to the dashboard header's right-side control cluster (alongside LayoutControls / ThemeSwitcher / PlantScopeShell). Switching navigates to the same path under the other locale via the existing i18n navigation (client-side, no reload, path + query preserved) and writes a `NEXT_LOCALE` cookie; the proxy's root redirect (`/` → `/<locale>`) honors that cookie when it names a supported locale. No runtime locale mutation without navigation — the URL stays the single source of truth.

## Boundaries & Constraints

**Always:**
- Frontend-only (AD-23); no backend, API, or generated-client changes
- Switcher renders from message catalogs (new `navigation.languageSwitcher` namespace, en + id), keeps `src/messages/catalog-parity.test.ts` green, and uses non-native shadcn/Radix controls (project UI rule) — follow the existing `theme-switcher.tsx` / `layout-controls.tsx` component shapes
- Navigation uses `Link`/`usePathname` from `@/i18n/navigation` (pathname is locale-stripped): switch from `/id/<path>` lands on `/en/<path>` with query intact, client-side — AC "without a full page reload" is satisfied by i18n Link navigation
- Cookie: name `NEXT_LOCALE` (next-intl's default so future `localeDetection` stays compatible), written via `setClientCookie` from `src/lib/cookie.client.ts` (long-lived — 365 days), sameSite lax
- The cookie is consulted ONLY for the bare-root redirect in `src/proxy.ts`; explicit prefixed URLs always win over the cookie (no /id→/en rewriting); cookie value is validated against `routing.locales` (`isLocale`) before use, unknown/absent → default `id` (23-1 behavior preserved)
- `localeDetection: false` stays — no Accept-Language negotiation is introduced (23-1 decision stands; persistence is cookie-only)
- `<html lang>` updates automatically via the locale segment navigation

**Block If:**
- AC "switching re-renders all strings without a full page reload" proves impossible via locale-segment Link navigation (e.g. the provider does not swap catalogs client-side) → HALT; runtime locale mutation without a URL change is an architecture decision, not a sweep
- Honoring `NEXT_LOCALE` on `/` cannot be composed in `proxy.ts` without breaking the existing intl-then-guard order or the 23-1 `/` → `/id` e2e contract → HALT

**Never:**
- No per-request Accept-Language detection, no localStorage as the persistence mechanism (cookie is SSR-visible; localStorage variant not needed)
- No switcher on auth/login pages (no app navbar exists there — out of shell scope)
- No changes to `src/i18n/{routing,request}.ts` behavior beyond what the proxy needs; no `getMessages`/`setRequestLocale` refactor
- No missing-key CI tooling (23.4), no bulk re-translation; no switcher in `components/ui`

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Click English on `/id/dashboard/workorders?tab=x` | logged-in shell | land `/en/dashboard/workorders?tab=x`, `lang="en"`, English shell, no reload | — |
| New tab visits `/` after that click | `NEXT_LOCALE=en` cookie | redirect `/en` (not `/id`) | — |
| `/` with cookie `NEXT_LOCALE=xx` | unsupported value | redirect `/id` (default) | cookie ignored, never 500 |
| `/` with no cookie | fresh visitor | redirect `/id` (23-1 behavior) | — |
| Visit `/id/...` directly | cookie says en | stays `/id/...` — URL wins | — |
| Auth page (`/en/auth/v2/login`) | no navbar | no switcher present | — |

## Verifiable assertions (e2e)
- Switching from `/id` sets `NEXT_LOCALE=en` and lands `/en` with `lang="en"`
- After switching, requesting `/` resolves to `/en`
- Invalid cookie value still yields `/id` from `/`

</intent-contract>

## Code Map

- `src/app/[locale]/(main)/dashboard/layout.tsx:60-64` -- header-right cluster; mount point for the switcher (order: LanguageSwitcher before LayoutControls or after ThemeSwitcher — match visual density, one line of judgment)
- `src/app/[locale]/(main)/dashboard/_components/sidebar/theme-switcher.tsx` -- client-control pattern (Button + aria-label via t()); `layout-controls.tsx` -- Radix Select/DropdownMenu usage to copy
- `src/i18n/navigation.ts` -- `Link`, `usePathname` (locale-stripped), `useRouter`; the switch navigates via `<Link href={usePathname()} locale={other}>` — verify createNavigation's Link accepts a `locale` prop (it does per next-intl v4 BaseLink)
- `src/i18n/routing.ts` -- `routing.locales`, `isLocale`, `defaultLocale`; `AppLocale` type
- `src/proxy.ts:24-34` -- `handleI18nRouting(request)` then 3xx short-circuit; add the cookie consultation before delegating to intl middleware for `pathname === "/"` only; 23-1's guard logic below must not move
- `src/lib/cookie.client.ts:10` -- `setClientCookie(key, value, days)`; `NEXT_LOCALE` name constant goes in `src/i18n/routing.ts` or a tiny new module (implementer's call, one file max)
- `src/messages/{en,id}.json` -- add `navigation.languageSwitcher` (labels "Language"/"English"/"Bahasa Indonesia", aria template); parity test guards symmetry
- `tests/e2e/i18n.spec.ts` -- existing locale routing specs; add the switcher flow (cookie via `context`/`document.cookie`, `/` redirect outcome via final URL)
- `src/i18n/routing.test.ts` -- unit pattern for a pure root-locale negotiation helper

## Tasks & Acceptance

**Execution:**
- `src/i18n/switch-locale.ts` (+ test) -- create -- pure helper: `rootLocaleFromCookie(rawCookieValue | undefined): AppLocale` (validates via `isLocale`, default `id`) + `NEXT_LOCALE` constant; unit tests the matrix cookie arms
- `src/app/[locale]/(main)/dashboard/_components/sidebar/language-switcher.tsx` -- create -- "use client"; Radix dropdown (ID/EN options, current-locale aria); on select: `setClientCookie(NEXT_LOCALE, target)` then i18n-`Link` navigation to `usePathname()` with `locale={target}` (or router.push with locale) — query preserved by construction
- `src/app/[locale]/(main)/dashboard/layout.tsx` -- extend -- render `<LanguageSwitcher />` in the header-right cluster
- `src/proxy.ts` -- extend -- when `request.nextUrl.pathname === "/"`: read `NEXT_LOCALE` cookie, if `rootLocaleFromCookie` yields a locale different from what intl would pick, 307 → `/${locale}` (set the intl cookie alongside? keep simple: bare redirect; intl sets its own NEXT_LOCALE on response anyway); otherwise untouched
- `src/messages/{en,id}.json` -- extend -- `navigation.languageSwitcher` keys
- `tests/e2e/i18n.spec.ts` -- extend -- the three verifiable assertions (unauthenticated-safe flow: run on `/id` shell root or a public-prefixed page; if the navbar requires login, drive `/id/operations-overview` and tolerate login-redirect only for the persistence assertion, or use the cookie set + `/`→`/en` URL as primary)

**Acceptance Criteria:**
- Given the app shell renders at `/id/...`, when the user picks English, then the URL becomes `/en/<same path and query>` with `lang="en"` and strings re-render without a full page reload (AC1)
- Given the user switched to English, when a new navigation visits `/`, then it resolves to `/en` (cookie persistence); given no/invalid cookie, `/` still resolves to `/id` (AC2)
- Given the app shell at any page, then the switcher is reachable from the header on every shell page (auth pages excluded — they have no shell) (AC3)
- Given `next build --webpack` + lint + unit + targeted e2e, then all green per `## Verification` (AC4)

## Spec Change Log

## Review Triage Log

### 2026-09-10 — Review pass 1
- intent_gap: 0
- bad_spec: 0
- patch: 5 (high 1, medium 2, low 2)
- defer: 1 (low 1)
- reject: 7
- addressed_findings:
  - `[high]` `[patch]` AC2 persistence is silently downgraded: next-intl's `syncCookie` (runs on every prefixed DOCUMENT request) rewrites `NEXT_LOCALE` as a session cookie to the last-VISITED locale whenever the carried value differs — after a hard cross-locale visit (bookmark/typed URL/refresh), the switcher's 365-day cookie loses both its expiry and its value, so a returning visitor lands on "last visited" instead of "last chosen". Fix: `localeCookie: false` in `defineRouting` (the switcher becomes the cookie's sole writer; the middleware stops syncing), with an e2e pinning switch → cross-locale document visit → `/` still resolves to the chosen locale. Verified against `node_modules/next-intl/dist/esm/production/middleware/syncCookie.js` (both the absent-cookie and outdated-value arms) and `BaseLink`/client `syncLocaleCookie`.
  - `[medium]` `[patch]` Unsupported-cookie e2e arm was weak: `toHaveURL(/\/id(\/|\?|$)/)` also accepts `/id/xx` and `status<500` admits 404 — tightening to root-`/id`-exact pins the "cookie ignored, never 500" contract at the asserting layer.
  - `[medium]` `[patch]` `switchTo` loses the URL hash and collapses duplicate query params (`Object.fromEntries(new URLSearchParams(...))` keeps the last value); carry `window.location.hash` and build `query` values via `getAll`.
  - `[low]` `[patch]` Two code comments misattribute mechanism: switcher's "next-intl's cookie sync runs inside push" and proxy's "intl would always pick the default here" — reword to the verified truth (client `syncLocaleCookie` precedes push; middleware `syncCookie` on document requests — mooted after the high patch).
  - `[low]` `[patch]` `switchAria` says "Click to switch language" (cycle-copy lifted from the theme switcher) on a dropdown trigger — reword both locales to menu semantics.
  - `[low]` `[defer]` see frontmatter deferred[].
- rejected (noted, not actioned):
  - "returning visitor never touching the switcher can land on /en" — blind-hunter misread the absent-cookie arm's direction (it writes the SERVED locale, not the browser's); mooted entirely by `localeCookie: false`.
  - proxy-level unit test — the composition seam is covered by the e2e suite (its natural HTTP surface); repo has zero proxy unit tests by existing convention.
  - `Secure`/`__Host-` cookie pinning — app is served over http in dev/deployment so far; attribute change would break local dev, security-hardening concern outside Epic 23's scope.
  - whitespace/encoded cookie-value normalization — `isLocale` strictness already falls back to default safely; no real producer of padded values.
  - AC3 "every shell page" breadth testing — the switcher mounts once in the shared `dashboard/layout.tsx`; universality is structural, one sample page is the right e2e budget. `unauthorized` and the top-level redirect stubs are auth/non-shell surfaces the spec's Never clause excludes.
  - e2e importing the `NEXT_LOCALE` constant — module resolution in Playwright's tsconfig unproven; the unit test pins the constant and the proxy contract is exercised through the browser anyway.
  - multi-string AC1 breadth assertion beyond page copy — the entire shell renders from one provider keyed by URL locale; aria-label + `lang` + (new) one page-content string is sufficient mechanism evidence.

## Design Notes

- Cookie honored only at the bare-root redirect: this keeps "URL owns locale" (23-1) intact — bookmarks, shared prefixed links, and back/forward never fight the cookie; the cookie exists to pick a locale only when the URL carries none.
- `NEXT_LOCALE` deliberately matches next-intl's built-in cookie name so a future flip to `localeDetection: true` needs no migration.
- `localeDetection: false` retained: next-intl's detection also consults Accept-Language, which 23-1 explicitly excluded; manual cookie read in proxy is the narrower mechanism.
- AC3 "every page" is read as every app-shell page (the navbar exists only there) — the epic context anchors the switcher "alongside existing header-right controls", which live in the dashboard layout. Login-shell switcher UI is out of scope.
- (Review pass 1) `localeCookie: false` in defineRouting: next-intl's own cookie sync (middleware `syncCookie` on document requests + client `syncLocaleCookie`) would otherwise rewrite `NEXT_LOCALE` to the last-VISITED locale as a SESSION cookie, silently defeating the switcher's 365-day write (AC2). With it off, the switcher is the cookie's sole writer and the proxy's root branch its sole reader. A future flip to `localeDetection: true` must re-enable `localeCookie` too. This is the sanctioned exception to the Never clause: the proxy branch is inert against a racing writer.
- Switch navigation builds the href as `${pathname}${search}${hash}` from `window.location` at click time — raw search string, so multi-value params survive verbatim; `usePathname()` alone drops query and hash.

## Verification

**Commands:**
- `npm --prefix syncro/apps/web run build` -- expected: webpack green incl. type-check
- `npm --prefix syncro/apps/web run lint` -- expected: Biome error count not above the pre-existing 12 (same files)
- `npm --prefix syncro/apps/web run test:unit` -- expected: all green except the known deferred `system-health-page 6-6-AC3` baseline failure; new switch-locale tests pass
- `npm --prefix syncro/apps/web run test:e2e -- i18n` -- expected: all green incl. new switcher flow specs

## Auto Run Result

Status: done

**Summary of implemented change:** Language switcher + persistence on top of the 23-1/23-2 i18n foundation — a Radix dropdown in the dashboard header-right cluster (mounted once in `dashboard/layout.tsx`, present on every shell page) that swaps locale client-side via `@/i18n/navigation`'s router with the full path+query+hash preserved, writing a 365-day `NEXT_LOCALE` cookie; `src/proxy.ts` gained a pre-intl branch honoring that cookie for the bare root only (307 to `/<cookie-locale>` when it differs from the default, exact `isLocale` validation); `defineRouting` gained `localeCookie: false` (review-found) so next-intl's own middleware/client cookie sync cannot downgrade or flip the switcher's write; `src/i18n/switch-locale.ts` is the pure helper + `NEXT_LOCALE` constant shared by proxy and component.

**Files changed (9):** `src/i18n/switch-locale.ts` (new) + `switch-locale.test.ts` (new), `src/i18n/routing.ts` (+`localeCookie: false`), `src/i18n/routing.test.ts` (+pin), `src/app/[locale]/(main)/dashboard/_components/sidebar/language-switcher.tsx` (new), `src/app/[locale]/(main)/dashboard/layout.tsx` (mount), `src/proxy.ts` (root branch + comments), `src/messages/{en,id}.json` (`navigation.languageSwitcher`), `tests/e2e/i18n.spec.ts` (+6 switcher/persistence specs incl. the cookie-downgrade pin and the multi-value-query/hash pin).

**Review findings breakdown (pass 1):** 5 patches applied (1 high — next-intl's `syncCookie` session-downgrade collision defeating AC2 persistence, fixed via `localeCookie: false` + regression e2e pin; 2 medium — unsupported-cookie assertion tightened to exact 307 + `Location` pathname `/id`, hash/multi-param loss; 2 low — comment truths, dropdown-semantics aria), 1 deferred (frontmatter `deferred:` — sentinel-auth e2e fixture assumption), 7 rejected.

**Follow-up review recommendation:** true — 1 patched finding was `high`; score `3×2 + 1×2 = 8` (≥5 threshold), plus the high-severity rule.

**Verification performed:** `npm run build` ✓ compiled, 85/85 pages, type-check green. `npm run lint`: 12 errors — exact pre-existing baseline, touched files individually clean. `npm run test:unit`: 390/391 (sole failure = deferred baseline `system-health-page 6-6-AC3`, untouched). `npm run test:e2e -- i18n`: 19/19 (13 pre-existing 23-1/23-2 specs + 6 new; the 23-2 public-path parametrization stays green against the cookie-aware proxy). One environment note: the first e2e attempt timed out waiting for the webServer behind a stale dev-server process; the rerun passed unchanged.

**Residual risks:** a future flip of `localeDetection: true` requires re-enabling `localeCookie` together (documented in the routing comments); the sentinel-auth e2e flow assumes presence-only guard semantics (deferred note); `maxRedirects: 0` pins next-intl's 307 default as a contract — loud failure, not silent drift, if the library changes.

