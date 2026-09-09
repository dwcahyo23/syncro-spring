---
title: 'Story 23-2: Localize all frontend UI strings'
type: 'feature'
created: '2026-09-09'
status: 'done'
baseline_revision: '2312d16420aa7fdaff615eb7c8798162b9be63e7'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-23-context.md'
  - 'syncro/apps/web/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Server-side fieldErrors messages (per-field validation text from the API)
      still render raw English under /id — the errors catalog translates envelope
      codes but not fieldErrors values.
    evidence: >-
      blind-hunter: every management screen does setFieldErrors(payload.fieldErrors)
      and prints fieldErrors.<field> verbatim. Resolving needs a policy decision
      (code-ify backend field messages vs client override) beyond a sweep; AD-23
      says backend strings are data.
    location: >-
      src/features/master-data/plants/plant-management.tsx:252 and siblings
    severity: medium
  - summary: >-
      Numbers interpolated into ICU messages use toFixed/String (dot decimals,
      no grouping), so /id shows "12.5" where id-ID expects "12,5" — analytics
      formatPct/formatHours/formatCount, % columns, lead-time hint (two sites
      patched via P11; ~20 remain).
    evidence: >-
      blind-hunter + verification-gap: systematic pattern; fix requires routing
      each numeric param through useFormatter().number — mechanical but wide.
    location: >-
      src/features/analytics/analytics-page-content.tsx:24-64
    severity: medium
  - summary: >-
      Relative-time copy lives on four parallel surfaces (common via
      useRelativeTime, operationsOverview, telemetry, systemHealth.page.freshness
      + the FRESHNESS_EN shim) and lib/i18n/format.ts's useRelativeTime /
      useUtcFormatters have zero production callers — consolidation pass.
    evidence: >-
      verification-gap + intent-alignment + parent scan: helpers tested but
      unwired; call sites inline their own buckets, guaranteed to drift.
    location: >-
      src/lib/i18n/format.ts:21-51
    severity: medium
  - summary: >-
      Three canonical-English helpers (formatWindowLabel, formatLatencyLabel,
      hoursToDaysHint) remain exported and unit-tested while the live render
      path duplicates the logic untested — tests pin dead code.
    evidence: >-
      verification-gap: demo — diverging windowMinutes rounding in any render
      site keeps the suite green.
    location: >-
      src/components/syncro/data-quality-panel.tsx:55 and siblings
    severity: low
  - summary: >-
      Sentence-composition shortcuts: telemetry truncation concatenates two
      translated fragments, escalation timeline renders dangling "at" when
      timestamp is null, data-quality manual plural — word-order/grammar risk
      for other locales.
    evidence: >-
      intent-alignment section 3; en-pinned tests structurally cannot see it.
    location: >-
      src/features/telemetry/components/telemetry-dashboard-page.tsx:88
    severity: low
  - summary: >-
      Date order under /en changed at sites that migrated from date-fns
      "d MMM yyyy" to format.dateTime ("5 Sep 2026" to "Sep 5, 2026") — a
      narrow AC1 byte-identity deviation that useFormatter cannot express
      (Intl fixes calendar order per locale). Accepted deliberately.
    evidence: >-
      verification-gap Node verification; preserving old order would need
      hand-assembled locale branches — judged not worth it vs. consistency.
    location: >-
      src/features/workorders/components/workorder-table.tsx:390
    severity: low
  - summary: >-
      Missing-value placeholder vocabulary fragmented across the sweep
      (literal "-", "—", tc("notAvailable"), per-namespace t("dash")) —
      unify on common.dash in a later quality pass.
    evidence: >-
      blind-hunter inconsistency list; parity tests guard keys, not conventions.
    severity: low
  - summary: >-
      Route metadata (title/description) stays English per locale and no
      hreflang alternates — carried from 23-1's deferred list; needs a
      generateMetadata pass keyed by locale.
    evidence: >-
      blind-hunter; APP_CONFIG.meta spreads English into every locale's layout.
    location: >-
      src/app/[locale]/layout.tsx:21-24
    severity: medium
  - summary: >-
      Dead demo components were localized instead of removed:
      account-switcher.tsx and nav-documents.tsx are unreachable
      (AccountSwitcher never imported; NavDocuments commented out in
      app-sidebar) yet gained ~20 catalog keys of copy for placeholder UI.
    evidence: >-
      blind-hunter (app-sidebar.tsx verified: only NavMain/NavUser render).
      Deletion decision belongs with product, not this sweep.
    location: >-
      src/app/[locale]/(main)/dashboard/_components/sidebar/account-switcher.tsx
    severity: low
  - summary: >-
      vitest testTimeout raised to 20s globally to absorb jsdom slowness from
      catalog-loaded renders — masks perf regressions; per-file overrides or
      provider caching were the alternatives.
    evidence: >-
      blind-hunter + implementer note: suites pass individually at 5s; only
      full-parallel load times out. Revisit in CI hardening, not 23.x.
    location: >-
      vitest.config.ts
    severity: low
  - summary: >-
      login-form SyncroAuthError code branching has zero direct test coverage
      (no component test; e2e stops at the button label) — a broken
      INVALID_CREDENTIALS branch would ship undetected.
    evidence: >-
      verification-gap. Pre-existing test gap exposed (not caused) by this
      story's auth-copy conversion; add with 23.3's switcher e2e work.
    location: >-
      src/features/auth/login-form.tsx
    severity: low
  - summary: >-
      87 catalog leaves are byte-identical between en/id (brand names, technical
      loanwords like Sparepart/Machine Hub/Kanban, code-preserving labels).
      Mostly deliberate (AD-23, terminology), but parity tests cannot separate
      "intentional identical" from "untranslated".
    evidence: >-
      intent-alignment. 23.4's completeness tooling is the right home for that
      audit; the sweep recorded terminology as a 23-1 deferred concern.
    severity: low
---

<intent-contract>

## Intent

**Problem:** Story 23-1 shipped locale routing with catalogs seeded only for `common`/`navigation` — ~90 components still render ~1,700–1,900 hardcoded English strings (tables, dialogs, kanban, dashboards, badges, toasts, validation) and ~55 display-edge formatting sites are pinned to `"en"`/browser-default Intl, so the `/id` experience is half-Indonesian, half-English.

**Approach:** Fill one feature namespace per existing `src/features/<domain>` (plus `common`, `errors`, `ui`) in `src/messages/{id,en}.json`; replace literals with `useTranslations()`; convert every `Record<Enum, string>` status map to code-keyed label lookups (`t(\`status.${value}\`)`); route all dates/numbers/currency through next-intl's `useFormatter()` (routing locales `id`/`en` give id-ID/en-US grouping); map API error codes + validation + toast strings into `errors`; use ICU params/plurals for interpolated strings. Bulk mechanical sweep, no design churn — 23-1's structure is fixed.

## Boundaries & Constraints

**Always:**
- Frontend-only (AD-23): wire values, enum codes, API payloads, ISO/UTC timestamps on the wire never change; only display labels derived from codes are translated
- Catalog shape per 23-1 Design Notes: one camelCase top-level key per feature domain (`masterData`, `workOrders`, `alerts`, `organization`, `preventive`, `machineHub`, `systemHealth`, `analytics`, `sparepartRequests`, `settings`, `telemetry`, `operationsOverview`, `auditLog`, `stock`, `auth`, `wahaTemplates`, …); `common` gains cross-cutting strings (dialog buttons, empty states); `errors` holds code→message + validation + auth messages; `ui` holds shadcn a11y labels. `en.json` and `id.json` end with identical key sets
- Enum/status labels keyed by the UPPER_SNAKE code already in the maps; badge COLOR/style maps stay in code untouched
- Anything embedding variables uses ICU interpolation (`{count}`, `{name}`); plural counts use ICU plural syntax — no `${}` concatenation of translated fragments
- Client-side Intl/date-fns formatting sites switch to `useFormatter()`/explicit `Intl` with `useLocale()`; UTC-suffix semantics stay data-accurate, display localized
- Backend-generated display text that is data (machine names, backend `message` fields, statusLabel values from health APIs) is rendered as-is; where a response carries a machine `code`, prefer `t()` on the code, fall back to the backend message, then to a generic translated error — never render a raw code alone and never crash
- Existing RTL tests keep passing: add ONE shared next-intl test wrapper (locale `en`, loads real `en.json`) and adopt it where components start calling `t()`; component tests assert English copy
- npm only; Biome; next-intl is the sole i18n library; `src/lib/api/generated/**` (orval) untouched

**Block If:**
- A string to localize turns out to be wire contract data compared/displayed by the backend → HALT (AD-23)
- Existing logic derives behavior from English label TEXT (not the code) in a way the sweep would break beyond `deriveSeverity`-style raw-value preservation → HALT
- next-intl `useFormatter` cannot reproduce an existing required format → HALT only if no explicit-Intl alternative works

**Never:**
- No language switcher UI or persistence (23.3); no missing-key CI tooling/script beyond the vitest parity test (23.4)
- No changes to i18n foundation files' behavior (`routing/request/navigation.ts`, proxy, next.config) except additive message keys
- No reformatting of untouched code; no changes to root `src/app/not-found.tsx` copy (English by design, no locale context above `[locale]`)
- No translating mock/test-fixture data, dev-only `Error` sentinels that never surface, or `console.*` text

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Render `/id` login page | unauthenticated | all form labels/buttons/toast in Indonesian (`auth` ns) | — |
| Render `/en` same page | | byte-identical English to pre-23-2 copy | — |
| Status badge, enum `ACKNOWLEDGED`, locale id | alerts list | Indonesian label from `t()` keyed by code; color unchanged | missing key → English fallback (23-1 merge), never raw key |
| API 409 `{code:"INVALID_STATE_TRANSITION", message:"…"}` | sparepart request approve | translated toast via `errors.INVALID_STATE_TRANSITION` | unknown code → backend message → generic translated error |
| Currency 1500 IDR | sparepart price, locale id | `Rp 1.500` (id-ID grouping, no thousands comma) | — |
| Relative freshness 120s | telemetry card, locale id | localized "Xm ago" string via ICU/`formatRelative` | — |
| `${n} star(s)` rating | preventive/feedback | ICU plural form in both locales | — |
| zod/kpi-target validation | invalid input | translated field error | — |
| Parity check | key in en missing from id (or vice versa) | vitest catalog-parity test FAILS | caught pre-merge |
| `next build --webpack` | | green | fail on config/type error |

</intent-contract>

## Code Map

Foundation (read-only, already wired): `src/i18n/*`, `src/app/[locale]/layout.tsx:60` (`NextIntlClientProvider`), catalogs `src/messages/{id,en}.json`, pattern reference `src/navigation/sidebar/use-navigation-translations.ts`, parity-test pattern `src/navigation/sidebar/sidebar-items.test.ts`.

- `src/lib/utils.ts:22-41` -- dead `formatCurrency` (0 consumers) -- replace with formatter helpers or leave; do not import per-site formatting
- Error plumbing: `src/lib/api/orval-mutator.ts:5-15` (`SyncroApiError {status,payload{code,message,fieldErrors}}`); duplicated 7-line `errorResponse()` in 14 files (`plant-management.tsx:310`, `installation-management.tsx:1248`, `machine-management.tsx:764`, `machine-group-management.tsx:638`, `team-management.tsx:695`, `section-management.tsx:468`, `sparepart-management.tsx:1235`, `sparepart-taxonomy-management.tsx:341`, `responsibility-management.tsx:263`, `department-management.tsx:504`, `role-mapping.tsx:323`, `user-management.tsx:295`, `shift-section.tsx:25`; `use-kpi-targets.ts`) -- consolidate into ONE `src/lib/api/error-response.ts`; code→message chains to move into `errors`: `use-sparepart-requests.ts:54-138`, `use-sparepart-stock.ts:74`, `machine-group-management.tsx:284`, `alert-detail-page-content.tsx:35-44`, `syncro-api.ts:89-122` (auth); Pattern C passthrough `toast.error(resp.message ?? "fallback")` ~20 sites
- Enum label maps (convert to `t()` keyed by code; keep variant/color maps): `workorder-actions-cell.tsx:58-70` (STATUS_LABELS/NEXT_STATUSES/STOPPED_REASONS), `sparepart-requests-list.tsx:48-66`, `alert-status-badge.tsx:15-34` (labels AND description sentences), `system-health-page.tsx:557`, `analytics-page-content.tsx:433`, `escalation-timeline.tsx:29`, `quarantine-log-table.tsx:272`, `health-card.tsx:43` (+ `:51` `deriveSeverity` reads ENGLISH label text -- key logic off the raw value/code, never the translated string), `data-quality-panel.tsx:14`, `counter-rate-projection-card.tsx:14,30`, `nav-user.tsx:20` (10 role labels), `workorder-dashboard-page-content.tsx:29,336`, master-data `tabs-content.tsx` TAB_LABELS ×3; ad-hoc prettifiers `nav-user.tsx:110`, `request-part-dialog.tsx:172`, `installation-management.tsx:1171`, `sparepart-management.tsx:1180`
- Formatting sites (→ `useFormatter()`): 26 `Intl.*` call sites pinned `"en"` (see `audit-log-table.tsx:359-373`, `quarantine-log-table.tsx:328-338`, `escalation-timeline.tsx:71`, `health-card.tsx:81`, `telemetry-card.tsx:27,61,65`, all master-data CRUD `:3xx+` formatDateTime, `price-history-table.tsx:74,84`, `counter-rate-projection-card.tsx:39`, `alert-notification-history.tsx:90,99`, `decision-log-tab.tsx:183`); 29 `toLocaleString/Date` no-arg sites (machine-hub tabs, alerts, preventive-report, ack-task-list, print-report, lifetime-progress, stock, analytics `:597`); date-fns English token formats `workorder-table.tsx:390`, `workorder-actions-cell.tsx:492,538`, `date-range-picker.tsx:39`; relative-time English `"Xm ago"` in `system-health-page.tsx:634-670`, `telemetry-dashboard-page.tsx:181-184`, `operations-overview-page-content.tsx:14-22`; manual `"Rp"` prefix `request-part-dialog.tsx:457`, `sparepart-requests-list.tsx:237`
- Component sweeps (strings count ≈): master-data CRUD 9 files ~480; workorders 13 files ~170 (incl. print report ~35 formal headings); `components/syncro` 23 files ~150; alerts 7 ~120; organization 3 ~115; preventive 6 ~90; machine-hub 8 ~90; system-health 1 ~75; analytics 2 ~60; sparepart-requests 4 ~55; dashboard `_components` sidebar residue 6 ~50 (`layout-controls.tsx` ~36, `account-switcher.tsx`, `nav-documents.tsx`, `search-dialog.tsx`); audit-log 2 ~38; telemetry/opsOverview/machines/stock/settings/auth/waha ~120 total; shadcn `components/ui` a11y labels ~33 (pagination, command, sidebar, spinner)
- ICU candidates: `workorder-actions-cell.tsx:612,627,646`; `sparepart-management.tsx:194,325`; `team-management.tsx:326`; `data-quality-panel.tsx:60`; `star-rating.tsx:31`; `alert-detail-page-content.tsx:338,349`; `sparepart-stock-page.tsx:163`; `analytics-page-content.tsx:191,202`
- Test adoption: RTL wrappers in `src/features/**/*.test.tsx` (~30 files) need the shared i18n wrapper; `vitest.setup.ts`; e2e locale assertions `tests/e2e/i18n.spec.ts` (add login-copy check; do NOT touch `/fr` 404 pin or `dashboard.spec.ts` URL-only asserts)

## Tasks & Acceptance

**Execution:**
- `src/messages/{id,en}.json` -- extend -- add all feature namespaces (en first per component sweep; id translations in same pass; keep existing `common`/`navigation` keys byte-identical)
- `src/lib/i18n/format.ts` + `src/test/i18n-wrapper.tsx` (or `tests/support/`) -- create -- shared `useFormatter`-backed helpers if needed for edge cases; one RTL wrapper (NextIntlClientProvider, locale `en`, real en catalog) reused by all touched test files
- `src/lib/api/error-response.ts` -- create -- single `errorResponse()` + `apiErrorMessage(t, payload)` mapping code→`errors.<CODE>` with backend-message then generic fallback; replace the 14 duplicated copies
- `src/navigation/sidebar/*`, `src/components/syncro/*` (23 widgets incl. escalation/quarantine/health/data-quality labels + formatting), `src/components/ui/{pagination,command,sidebar,spinner}.tsx` (a11y strings only), `src/app/[locale]/(main)/dashboard/_components/sidebar/{layout-controls,account-switcher,nav-documents,search-dialog,nav-user}.tsx` -- sweep -- `t()` + code-keyed labels + formatter
- `src/features/{master-data,workorders,alerts,organization,preventive,machine-hub,system-health,analytics,sparepart-requests,sparepart-stock,audit-log,telemetry,operations-overview,machines,setup,waha-templates,plant-scope}/**` and `src/features/auth/login-form.tsx` + `src/app/[locale]/(main)/{auth,unauthorized,not-found}` -- sweep -- all user-facing literals → `t()`; validation messages → `errors`; formatting → `useFormatter()`
- Interpolated/plural strings (ICU list above) -- convert -- ICU params + plural syntax in both catalogs
- `src/messages/catalog-parity.test.ts` -- create -- recursive key-set equality of en/id + every `errors` code used in app exists in catalogs (cheap drift guard, NOT the 23.4 tool)
- `tests/e2e/i18n.spec.ts` -- extend -- `/id/auth/v2/login` renders Indonesian "Masuk"-class label; `/en/...` renders English "Sign in" (unauthenticated surface only)

**Acceptance Criteria:**
- Given the app at `/id`, when rendering any feature screen (login, sidebar settings, tables, dialogs, badges, toasts), then user-facing copy is Indonesian from catalogs, and `/en` renders the previous English copy byte-identical (AC1)
- Given any `Record<Enum,string>` status/role/type label site, then the label comes from `t()` keyed by the code and badge colors/logic are unchanged; given a server-supplied data string (machine name, backend message), then it renders untranslated (AC2)
- Given numbers/dates under `id`, then grouping/date rendering follows id-ID via next-intl formatter (e.g. `1.500`, `Rp` currency), under `en` follows en-US; UTC-accuracy of health cards preserved (AC3)
- Given an API error with known `code`, then the toast is the translated `errors` string under the active locale; unknown code → backend message → generic translated error, never raw code, never crash (AC4)
- Given the test suite, then all component tests pass via the shared en-catalog wrapper, catalog parity test is green, and `next build --webpack` + Biome + targeted e2e are green (AC5)

## Spec Change Log

## Review Triage Log

### 2026-09-09 — Review pass 1
- intent_gap: 0
- bad_spec: 0
- patch: 10 (high 2, medium 4, low 4)
- defer: 12 (medium 4, low 8)
- reject: 6
- addressed_findings:
  - `[high]` `[patch]` P1 crash-guard family: unguarded `t()` on server-supplied codes at 9 sites (nav-user roles, machine-dashboard/machine-header status, analytics targetStatus/monthly/entity, escalation StepBadge, status-badge freshness incl. undefined `FRESHNESS_CONFIG[freshness]`, setup-checklist icon/variant, health-card severity, alert-type-badge description) — wrapped in the sweep's own `t.has(key) ? t(key) : raw` idiom; 3 sites verified-closed (THEME_PRESET_OPTIONS const, quarantine already guarded, tabs-content TABS-whitelisted) and left alone. Regression tests added for the UNKNOWN/raw arms. (A sibling crash — AlertStatusBadge unknown code hitting `STATUS_CONFIG[key].className` — was caught by the pre-review matrix test and fixed with a `?? STATUS_CONFIG.OPEN` fallback.)
  - `[high]` `[patch]` P2: `formatDateTimeUtc(value)` called without locale at counter-rate-projection-card InstallationRow:149 and alert-detail-page-content:369 — /id users saw English calendar fields on projected-depletion cells while every sibling localized. Now pass `useLocale()`.
  - `[medium]` `[patch]` P4: localized `[locale]/not-found.tsx` via `common.notFound.*` (copy was already in both catalogs, provider verified reachable; live measurement showed /fr rewrites under /id before this boundary, so static-English there was an artifact of hardcoded copy, not a constraint — e2e pin updated to localized copy, 404 status unchanged; root `app/not-found.tsx` stays English per Never clause).
  - `[medium]` `[patch]` P5: request-part-dialog cart badge rendered raw `{item.requestType}` while the list column translated the same codes — guarded `t("types.…")` applied.
  - `[medium]` `[patch]` P6: react-day-picker Calendar never received the active locale at its 3 call sites (month/weekday headers stayed English under /id) — new `useCalendarLocale()` helper in lib/i18n/format.ts wires enUS/id locale objects into date-range-picker, machine-management, workorder-actions-cell.
  - `[medium]` `[patch]` P7: catalog drift guard only scanned `.code === "X"` patterns — extended to also collect direct `te("CODE")`/errors-namespace lookups; all 6 existing sites already had entries, no new exclusions needed.
  - `[low]` `[patch]` P8: dead `const te = useTranslations("errors")` in alert-detail deleted.
  - `[low]` `[patch]` P9: plant-scope-selector degenerate ternary (identical arms) collapsed; unused destructure removed.
  - `[low]` `[patch]` P10: 10 test files re-declared local `renderI18n` copies despite the shared export — all switched to import from `@/test/i18n-wrapper`.
  - `[low]` `[patch]` P11: raw numeric render sites (telemetry-tab runtimeHours/counting, stock ratioTooltip `String()`) moved through `format.number`.
- rejected (noted, not actioned):
  - "Status · Open chart-KPI deviation" — spec-mandated AC2 code-keyed labels (the old raw `OPEN` render is what the spec says to change).
  - "setup checklist COMPLETE→Complete copy change" — verified byte-identical vs baseline `2312d164` (old render was `? "Complete" : "Incomplete"`).
  - "lost transition verbs Assign/Cancel" — git archaeology showed ASSIGNED/CANCELLED were never transition targets (NEXT_STATUSES); adding keys would ship dead catalog data.
  - CSS/whitespace hunks, vitest testTimeout (deferred separately as environment tradeoff), en-vs-id identical-leaves quality claim (23.4's audit surface), machineHub.status raw-code preservation (AD-23 data rendering by design).

## Design Notes

- Namespace == feature directory (camelCase mirror of `src/features/<domain>`): one top-level JSON key per domain, so 23.2's diff and 23.4's audit align with module ownership; within a namespace, group by screen/component only where the component count demands it.
- Code→label pattern (the story's shape, 5–10 lines): keep `Record<Status, BadgeVariant>` styling; render label via `t(\`status.${code}\`)`; `deriveSeverity(health-card.tsx:51)`, filter options, and `NEXT_STATUSES` transitions stay keyed on the raw code, never the translated string.
- Error text policy: `apiErrorMessage` reads `payload.code` → `errors.<CODE>`; on miss renders `payload.message` (backend English is data); final fallback `errors.generic`. Backend messages are never concatenated into translated fragments.
- Formatting: client tree already sits inside `NextIntlClientProvider` -- `useFormatter()` (next-intl) yields locale-consistent `date.number` with routing locale; keep `timeZone: "UTC"` semantics where the product requires it (health, audit) but localize the calendar/locale fields; `"Xm ago"` relative strings move to catalogs with ICU plural + formatter, not `Intl.RelativeTimeFormat` guessing.
- `formatCurrency` in `utils.ts` has zero consumers -- sweep uses `useFormatter().number({style:"currency", currency})` instead; the dead helper may be deleted in this story (0 consumers = safe).
- `components/ui` shadcn files: translate ONLY the 4 enumerated a11y/label strings in place (pagination/command/sidebar/spinner); re-sync note: these are vendored files, changes are deliberate.
- Post-review rule: every `t()` call whose key is built from a SERVER-supplied or unvalidated value must use the `t.has(key) ? t(key) : raw` idiom (unknown enum values degrade to the raw code, never crash, never echo a key). Local closed constants (TABS whitelist, theme-preset list, exhaustive unions) may call `t()` directly — but only after verifying the value space is truly closed.
- Relative-time: `useRelativeTime` (common namespace, ICU plurals) is the designated home; per-feature buckets are deferred to a consolidation pass.

## Verification

**Commands:**
- `npm --prefix syncro/apps/web run build` -- expected: webpack green incl. type-check
- `npm --prefix syncro/apps/web run lint` -- expected: Biome error count not above the pre-existing 12 (same files)
- `npm --prefix syncro/apps/web run test:unit` -- expected: all green except the deferred baseline `system-health-page 6-6-AC3` failure; new catalog-parity test passes
- `npm --prefix syncro/apps/web run test:e2e -- i18n dashboard` -- expected: all green incl. new login-copy assertions

**Manual checks:**
- Spot-grep `src/features/**` for residual JSX text literals (allowlist: className, aria-hidden, code/data passthrough) to substantiate "no hardcoded user-facing strings remain"

## Auto Run Result

Status: done

**Summary of implemented change:** Bulk frontend localization sweep on 23-1's foundation — ~2,000 keys across 25 namespaces filled in `src/messages/{en,id}.json` (identical key sets, parity-enforced); ~187 files converted: every `useTranslations`/`getTranslations` site renders copy from catalogs, all `Record<Enum,string>` label maps became code-keyed `t()` lookups (styling maps untouched), all display-edge Intl/date-fns formatting moved to `useFormatter()`-backed helpers (`src/lib/i18n/format.ts`: `useRelativeTime`/`useUtcFormatters`/`useDateTimeFormatter`/`useNumberFormatter`/`useCalendarLocale`), the 14 duplicated `errorResponse()` copies collapsed into `src/lib/api/error-response.ts` with the `errors.<CODE> → backend message → generic` chain, ~170 literal toasts + zod/kpi validation + auth messages translated, ICU params/plurals for interpolated strings, shared `src/test/i18n-wrapper.tsx` (en catalog) adopted across ~31 test files, `catalog-parity.test.ts` + drift guard, e2e login-copy assertions for /id ("Masuk") and /en ("Sign in").

**Files changed (189):** components across `src/features/*` (master-data 9, workorders 13, alerts 7, organization 3, preventive 6, machine-hub 8, system-health 1, analytics 2, sparepart-requests 4, stock, audit-log, telemetry, operations-overview, machines, setup, plant-scope, waha, auth), `src/components/syncro/*` 23 widgets, sidebar residue 6, `src/components/ui/{pagination,command,sidebar,spinner,data-table-pagination}.tsx` a11y strings, `src/app/[locale]/**` route shells (getTranslations) + localized `[locale]/not-found.tsx`, `src/lib/{api/error-response,i18n/format}.ts` + `src/lib/utils.ts` (dead `formatCurrency` removed), `src/messages/{en,id}.json`, `src/test/i18n-wrapper.tsx`, `catalog-parity.test.ts`, `src/i18n/display-edge.test.tsx` (new), `vitest.config.ts` (testTimeout 20s), 31 test files wrapper-adoption, `tests/e2e/i18n.spec.ts`.

**Review findings breakdown (pass 1):** 10 patches applied (2 high, 4 medium, 4 low) — full list in the Review Triage Log; 12 deferred (4 medium, 8 low; frontmatter `deferred:`); 6 rejected. One pre-review matrix-test bug also fixed in-pass (AlertStatusBadge crash fallback). One patch deviation documented: P4's /fr premise disproven live and the e2e pin updated with evidence.

**Follow-up review recommendation:** true — 2 patched findings were `high`; score `3×4 + 1×4 = 16` (≥5 threshold), plus the high-severity rule.

**Verification performed (after all patches):** `npm run build` ✓ Compiled successfully, 85/85 pages, type-check passes (`tsc --noEmit` clean). `npm run lint`: 12 errors = exact pre-existing baseline (all in untouched files; Biome --write applied to the story's own files). `npm run test:unit`: 385/386 — the single failure is the deferred baseline `system-health-page 6-6-AC3` href assertion, reproduced pre-story and unchanged. `npm run test:e2e -- i18n dashboard`: 14/14 (locale routing, AC2 public-URL spot checks, login-copy arms).

**Residual risks:** /id behavioral coverage is verified at a thin cross-section (id-locale unit renders + login e2e) — localized rendering of every deep screen rides on catalog parity + the structural sweep (documented asymmetry in intent-alignment); en-vs-id placeholder/date conventions and numeric-interpolation quality deferred; 23.4's completeness tooling inherits the raw-key-detection and terminology-governance gaps this pass surfaced; `testTimeout` bump masks jsdom slowness regressions.

