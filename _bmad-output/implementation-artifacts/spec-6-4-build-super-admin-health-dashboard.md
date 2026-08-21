---
title: 'Build SUPER_ADMIN Health Dashboard'
type: 'feature'
created: '2026-08-21'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_commit: cd3ef9cca6bdbf85aadd28c8570e8b435b735ed6
final_revision: 3f77f0f67bcb34be6833ed676d406265d2d6224e
context: []
warnings: [oversized]
---

# Story 6.4: Build SUPER_ADMIN Health Dashboard

## Story

As a SUPER_ADMIN,
I want a health dashboard showing dependency and worker status,
So that I can diagnose why telemetry, alerts, or notifications may stop flowing.

## Acceptance Criteria

1. **Given** SUPER_ADMIN is authenticated **When** user opens system health dashboard **Then** dashboard shows a `HealthCard` for PostgreSQL, InfluxDB, Redis, MQTT/EMQX, WAHA, telemetry ingest worker, and notification worker [Source: _bmad-output/planning-artifacts/epics.md#Story-6.4]
2. **And** each card shows status label, reason, severity, and timestamp [Source: _bmad-output/planning-artifacts/epics.md#Story-6.4]
3. **And** health dashboard is not accessible to non-SUPER_ADMIN roles [Source: _bmad-output/planning-artifacts/epics.md#Story-6.4]
4. **And** UI supports loading, empty, error, stale, read-only, and forbidden states [Source: _bmad-output/planning-artifacts/epics.md#Story-6.4]
5. **And** status communication is not color-only [Source: _bmad-output/planning-artifacts/epics.md#Story-6.4]
6. **And** dashboard is responsive across desktop, tablet, and mobile [Source: _bmad-output/planning-artifacts/epics.md#Story-6.4]

## Tasks / Subtasks

- [x] Task 1: Build shared `HealthCard` component (AC: 1, 2, 4, 5)
  - [x] New file: `syncro/apps/web/src/components/syncro/health-card.tsx` — presentational `"use client"`-free shared component. Props: `title`, `description?`, `statusLabel` (always-visible text), `statusSeverity?` (`SUCCESS|WARNING|CRITICAL|NEUTRAL`), `statusReason?`, `timestamp?` (ISO), `loading?`, `error?`, `empty?`, optional `children` for extra metric rows. Renders shadcn `Card` + a local status badge whose label is always visible text + icon (never color-only). When `error` → text "Unable to check {title}."; when `empty` → "No health data reported."; when `loading` → `Skeleton`. No mutation controls inside the card (read-only by design).
  - [x] New file: `syncro/apps/web/src/components/syncro/health-card.test.tsx` — Vitest: renders title + label + reason + severity + timestamp; error fallback; empty fallback; loading skeleton; not-color-only label via `aria-label`.

- [x] Task 2: Add worker status types + hooks (AC: 1, 2)
  - [x] Modify `syncro/apps/web/src/features/system-health/types/index.ts` — add `wahaCircuitBreaker` to `components` map; add `IngestWorkerStatus` (`status: WorkerState`, `statusLabel`, `statusSeverity`, `statusReason: string|null`, `timestamp`, `mqttState`, `lastAcceptedAt: string|null`, `staleSince: string|null`, `queueDepth`, `queueCapacity`, `acceptedCount`) and `NotificationWorkerStatus` (`status`, `statusLabel`, `statusSeverity`, `statusReason: string|null`, `timestamp`, `lastPollAt: string|null`, `staleSince: string|null`, `pendingJobCount`, `recentFailedCount`, `lastFailureReason: string|null`, `lastSuccessfulSendAt: string|null`, `circuitBreakerState: string|null`) where `WorkerState = "RUNNING"|"STOPPED"|"DEGRADED"`. Mirror the exact JSON field names from `IngestWorkerStatus.java` / `NotificationWorkerStatus.java`.
  - [x] New file: `syncro/apps/web/src/features/system-health/hooks/use-ingest-worker-status.ts` — hand-rolled `fetch` + `useQuery` following `use-quarantine-log.ts` exactly: `getAuthToken()` bearer header, `${API_BASE_URL}/api/v1/telemetry/ingest/status`, throw on `!response.ok`, `refetchInterval: 30_000`, `retry: 2`, stable `queryKey`.
  - [x] New file: `syncro/apps/web/src/features/system-health/hooks/use-notification-worker-status.ts` — same pattern for `/api/v1/notification/worker/status`.

- [x] Task 3: Rebuild System Health page (AC: 1, 2, 4, 5, 6)
  - [x] Modify `syncro/apps/web/src/features/system-health/components/system-health-page.tsx` — replace the inline `DependencyCard`/`ApiHealthCard`/local `StatusBadge` with the shared `HealthCard`. Sections:
    - Header (title + auto-refresh subtitle + Refresh button) and stale-data banner: keep existing `useNow(30_000)` + `STALE_BANNER_THRESHOLD_MS` pattern.
    - **Dependencies**: one `HealthCard` each for PostgreSQL (`components.db`), InfluxDB (`components.influxdb`), Redis (`components.redis`), MQTT/EMQX (`components.mqtt`), WAHA (`components.wahaCircuitBreaker`, show `state`/`failureRate` detail rows). Card fields come from `component.status` + `component.details.statusLabel|statusSeverity|statusReason|timestamp` (AC-6 enriched fields from 6-1) with a normalized fallback (label from status name, severity default).
    - **Workers**: `HealthCard` for Telemetry Ingest (from `useIngestWorkerStatus`; metric rows: MQTT state, queue depth/capacity, accepted count, last accepted, stale since) and Notification Worker (from `useNotificationWorkerStatus`; metric rows: pending jobs, recent failed, last failure reason, last successful send, circuit state).
    - **Telemetry Quarantine Log**: keep existing `QuarantineLogTable` section unchanged.
    - Overall status banner: compute from all three sources — every loaded card healthy → "All systems operational."; any card non-healthy → failure banner. Do not claim failure while a source is still loading/error (only evaluate when each source has resolved).
    - Loading: skeletons per section. Error: `HealthCard error` state (no health check crash). Empty: `HealthCard empty` state. Read-only: cards render no mutation actions.
  - [x] Fix `syncro/apps/web/src/features/system-health/hooks/use-actuator-health-query.ts` — accept BOTH 200 and 503 (Actuator returns HTTP 503 with a structured body when aggregate status is `DOWN`); parse the JSON body in both cases; only throw for other statuses. Do NOT drop the bearer header (harmless, keeps parity with other hooks).
  - [x] Remove the legacy `useHealth` `/api/v1/health` "Backend API" card (not part of AC 1; page-spec 4 has no such card) and drop the now-unused `useHealth` import.

- [x] Task 4: Page tests — states (AC: 4, 5) and cards (AC: 1, 2)
  - [x] New file: `syncro/apps/web/src/features/system-health/components/system-health-page.test.tsx` — Vitest with mocked hooks (follow `telemetry-dashboard-page.test.tsx` vi.mock pattern: mock `use-actuator-health-query`, `use-ingest-worker-status`, `use-notification-worker-status`, `use-quarantine-log`). Cases:
    - renders all seven cards: PostgreSQL, InfluxDB, Redis, MQTT, WAHA, Telemetry Ingest, Notification Worker (AC 1).
    - a healthy card shows status label, severity, reason, and formatted timestamp (AC 2).
    - loading renders skeletons (AC 4).
    - error renders per-card "Unable to check" fallback (AC 4).
    - empty (component missing) renders "No health data reported" (AC 4).
    - stale banner shows "Last updated ... ago" with a Refresh now button that refetches (AC 4).
    - read-only: rendered cards contain no interactive mutation controls (assert no `button`/`a` inside a `HealthCard`) (AC 4).
    - badges expose a non-color-only text label via `aria-label` (AC 5).
    - Refresh button refetches all three health sources (page-spec 4.12).
    - Forbidden state: `RoleGuard` renders "Permission denied" for non-SUPER_ADMIN (AC 3) — add a `role-guard` unit test if none exists, or cover via the route page composition in this file.

- [x] Task 5: Verification (all ACs)
  - [x] Run `npm run check` (Biome), `npm run test:unit`, `npm run build` from `syncro/apps/web`. No backend code changes — do not run/change backend.
  - [x] Confirm route guard wiring already present: `src/app/(main)/dashboard/system-health/page.tsx` (RoleGuard SUPER_ADMIN), middleware `protectedRoutes` includes `/system-health`, sidebar item has `roles: ["SUPER_ADMIN"]`. Verify only; change only if a gap is found.

## Dev Notes

- **No backend changes.** 6.1 exposed the five dependency checks via `/actuator/health` (custom indicators `db`, `redis`, `mqtt`, `influxdb`, `wahaCircuitBreaker`, each enriched with `statusLabel`/`statusSeverity`/`statusReason`/`timestamp` via `DependencyHealthSupport`); 6.2 exposed `GET /api/v1/telemetry/ingest/status`; 6.3 exposed `GET /api/v1/notification/worker/status` (both SUPER_ADMIN-guarded in-controller). The frontend consumes all three; an aggregate `/api/v1/health/summary` is deliberately NOT built (spec-6-1 deferred it; ACs do not require it).
- **Actuator 503 semantics:** when aggregate status is `DOWN`, `/actuator/health` returns HTTP 503 with the normal JSON body. The hook must treat 503-with-body as valid data so partial-failure cards render correctly (Task 3 fix).
- **Worker state is display-only:** state derivation (STOPPED/DEGRADED precedence, stale boundaries) already lives in the backend services. The frontend renders `statusLabel`/`statusSeverity`/`statusReason`/`timestamp` verbatim and must NOT re-derive or branch on translated text.
- **Do NOT generalize `src/components/syncro/status-badge.tsx`:** it is the telemetry-freshness badge (ONLINE/OFFLINE/STALE) used by `telemetry-grid.tsx`, `machine-hub/telemetry-tab.tsx`, `machine-header.tsx`. The shared `HealthCard` carries its own internal status badge (label text + icon + severity styling) to satisfy AC 5 without churning three unrelated callers.
- **Hooks follow the existing feature pattern:** `use-quarantine-log.ts` / `use-actuator-health-query.ts` are hand-rolled `fetch`+`useQuery` in `features/system-health/hooks` (committed precedent). Do not regenerate the orval client.
- **Story boundaries:** DataQualityPanel (6.7), Telemetry Freshness (6.5), DB replication + queue status and latency indicator (6.6/6.7) are OUT of scope. Keep the existing `QuarantineLogTable` section (already committed, FR-073a). Frontend-only story.
- **Verification standard** (per prior frontend stories, e.g. 3-7): comprehensive unit tests covering all states + `npm run check` + `npm run build`. Browser manual check is documented but may not be runnable unattended; if the full stack is unavailable, state that explicitly and rely on the unit/build gates.

### Project Structure Notes

- All changes under `syncro/apps/web`. New production files: `src/components/syncro/health-card.tsx`, `src/features/system-health/hooks/use-ingest-worker-status.ts`, `src/features/system-health/hooks/use-notification-worker-status.ts`. Modified: `system-health-page.tsx`, `types/index.ts`, `use-actuator-health-query.ts`. New tests: `health-card.test.tsx`, `system-health-page.test.tsx`.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-6.4] — AC 1-6.
- [Source: _bmad-output/planning-artifacts/page-specifications.md#4] — System Health layout, sections 4.1-4.3, states 4.10, refresh 4.12.
- [Source: _bmad-output/planning-artifacts/frontend-hardening-specification.md#8] — HealthCard assigned to Story 6.4.
- [Source: _bmad-output/implementation-artifacts/spec-6-1-expose-dependency-health-checks.md] — actuator indicator component keys + enriched details contract.
- [Source: _bmad-output/implementation-artifacts/spec-6-2-report-telemetry-ingest-worker-status.md] — `IngestWorkerStatus` field list.
- [Source: _bmad-output/implementation-artifacts/spec-6-3-report-notification-worker-status.md] — `NotificationWorkerStatus` field list; state-derivation nuances.
- [Source: syncro/apps/web/src/features/system-health/components/system-health-page.tsx] — current page to rebuild.
- [Source: syncro/apps/web/src/features/system-health/hooks/use-quarantine-log.ts] — hook template.

## Change Log

- 2026-08-21: Spec created (draft).
- 2026-08-21: Review pass applied 12 patches: fetch timeout/abort signals on the three health hooks; stale-banner now uses oldest `dataUpdatedAt` (Math.min incl. quarantine) so any stale source fires the banner; overall banner rewritten severity-based (absent data and WARNING states → amber "degraded", only CRITICAL → red "unhealthy"); `formatDateTimeUtc` appends " UTC" and guards invalid dates; actuator 503 handling unit-tested + defensive JSON parse; `computeOverallBanner` exported and branch-tested; `STATUS_LABELS` fallback + empty-string guard; removed `truncate` on metric rows (touch a11y); `deriveSeverity` word-boundary + negative-phrase guard; AC5 test strengthened to assert concrete labels. Deferred: WAHA `failureRate` NaN/Jackson cross-stack concern (DW-NEW). Rejected: CardTitle-as-div heading (project-wide shadcn pattern) and actuator permitAll gating note (accepted design).
- 2026-08-21: Follow-up review pass (bmad-dev-auto, iteration 0) applied 17 patches: banner now shares the same severity resolution as the cards (no banner/card divergence); HealthCard shows last-known status on refetch failure; 401 expires the session on all three health fetchers; `deriveSeverity` negations fully guarded; `detailNumber` rejects non-finite values; metric rows wrap without `capitalize`; `metricString` renders "—" for null/undefined/empty; 503-non-health-payload guard; defensive severity normalization in HealthCard; `createHealthRequestSignal` Safari fallback; NEUTRAL static icon; read-only test scoped to health regions; ICU-robust date assertions; NaN/null/empty-string page coverage. Deferred: none. Rejected: STATUS_LABELS raw-status fallback and "Verifying…" banner state (both intentional design).

## Review Triage Log

### 2026-08-21 — Follow-up review pass (bmad-dev-auto)
- intent_gap: 0
- bad_spec: 0
- patch: 17 (high 3, medium 5, low 9)
- defer: 0
- reject: 2 (low 2)
- addressed_findings:
  - `[high]` `[patch]` Overall banner and dependency cards derived severity from different fields (`component.status` vs `details.statusSeverity`), so a component whose enriched severity disagreed with its coarse actuator status produced a banner contradicting its card. Introduced a single resolution source (`resolvedDependencySeverity`/`resolvedWorkerSeverity`) shared by both the cards and `computeOverallBanner`; added branch tests proving enriched severity wins (UP+CRITICAL → unhealthy, DOWN+SUCCESS → healthy).
  - `[high]` `[patch]` Background refetch failure discarded last-known status: `HealthCard`'s error branch hid cached data. Card now renders "Unable to refresh {title}. Showing last known status." plus the cached rows when data props exist; tested in both component and page suites.
  - `[high]` `[patch]` Session expiry (401) rendered a silent all-amber "could not be verified" page instead of redirecting to login. All three health fetchers now call `expireAuthSession()` on 401 before throwing; unit tests cover 401 for actuator, ingest, and notification fetchers.
  - `[medium]` `[patch]` `deriveSeverity` negative-phrase guard only covered the SUCCESS word list, so "not failed"/"not degraded"/"not stopped" misclassified as CRITICAL/WARNING. Guard now handles negations of every severity keyword (negated bad state → NEUTRAL, negated good state → WARNING); tested.
  - `[medium]` `[patch]` WAHA `failureRate` rendered raw and unguarded, and a NaN/Infinity value slipped through `String()` as "NaN". `detailNumber` now rejects non-finite numbers; page test proves NaN → "—".
  - `[medium]` `[patch]` Metric rows could overflow the card on narrow viewports and the `title` tooltip was pointless after `truncate` removal. Row value now wraps (`min-w-0 break-words`) and `capitalize` removed (was mangling "MQTT" → "Mqtt").
  - `[medium]` `[patch]` Null/undefined worker count fields rendered literally as "null"/"undefined" and empty-string metrics rendered blank. New `metricString` helper renders "—" for null/undefined/empty; applied to all worker metric rows and WAHA detail rows; page tests prove no "null"/"undefined" text.
  - `[low]` `[patch]` A 503 body that parsed as JSON but was not a health payload (e.g. gateway JSON) was accepted and rendered misleading empty cards. Hook now validates `body.status` is a string and throws a status-aware message; tested.
  - `[low]` `[patch]` Runtime severity outside the SUCCESS/WARNING/CRITICAL/NEUTRAL set could crash card render. `HealthCard` now normalizes defensively and `HealthStatusBadge` falls back to NEUTRAL config.
  - `[low]` `[patch]` `AbortSignal.timeout`/`AbortSignal.any` are unsupported before Safari 17.4 and threw before fetch in those browsers. Added `createHealthRequestSignal` with a manual-timeout fallback used by all three hooks.
  - `[low]` `[patch]` NEUTRAL severity used the `Loader2` spinner icon, visually implying loading. Replaced with the static `Minus` glyph.
  - `[low]` `[patch]` AC4 read-only test queried all page cards (including quarantine cards that legitimately contain pagination buttons) and could silently pass even if a control were added to a health card. Scoped the assertion to the "Dependency health" and "Worker health" regions.
  - `[low]` `[patch]` Date assertions depended on full-icu CLDR output ("Aug 21, 2026", "10:00 AM"), brittle on small-icu CI images. Assertions now match on the year and the " UTC" suffix.
  - `[low]` `[patch]` `detailNumber` NaN handling and the helper chain (`dependencyCardProps`, `metricString`) had no direct coverage. Added page tests for non-finite failure rate, null counts, and empty-string metrics.
- addressed_rejects:
  - `[low]` `[reject]` `STATUS_LABELS[component.status]` fallback chain: the raw unknown status rendering with NEUTRAL severity is the intended honest display for an unrecognized backend code; no change.
  - `[low]` `[reject]` Banner vanishes while any source loads: skeleton-per-section already communicates loading; a "Verifying…" banner adds no operator value and was deemed noise.

### 2026-08-21 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 12 (high 2, medium 5, low 5)
- defer: 1 (medium 1)
- reject: 2 (low 2)
- addressed_findings:
  - `[high]` `[patch]` Health hooks had no fetch timeout/abort signal — hung endpoint froze the whole dashboard (permanent skeleton, disabled Refresh, no stale banner). Added `AbortSignal.any([signal, AbortSignal.timeout(10s)])` in all three hooks.
  - `[high]` `[patch]` Background refetch failure kept cached data with frozen `dataUpdatedAt` while other sources refreshed, so the stale banner (Math.max) never fired — silently stale "Running" cards. Now `lastUpdated = Math.min(...)` over the three health sources plus the quarantine log.
  - `[medium]` `[patch]` Banner called missing/absent data "unhealthy" (red) while cards rendered neutral "No health data reported" — contradiction. `computeOverallBanner` now maps absent/unverifiable → degraded (amber).
  - `[medium]` `[patch]` Worker `DEGRADED` (WARNING) and dependency `OUT_OF_SERVICE` (WARNING) collapsed into the red "unhealthy" banner contradicting amber badges. Banner is now severity-based: only CRITICAL → unhealthy, WARNING or unverifiable → degraded.
  - `[medium]` `[patch]` `formatDateTimeUtc` showed UTC timestamps with no timezone marker (misread as local) and threw `RangeError` on unparseable dates. Now appends " UTC" and returns the raw value when the date is invalid.
  - `[medium]` `[patch]` The Actuator 503-with-body handling had zero test coverage. Added `use-actuator-health-query.test.ts` (200/503/500/invalid-JSON) and a defensive JSON parse with a meaningful error message.
  - `[medium]` `[patch]` `computeOverallBanner` branches were untested (only all-error degraded was exercised). Exported it and added direct unit tests for every branch (healthy, loading, DOWN, STOPPED, DEGRADED, OUT_OF_SERVICE, absent, partial-error, all-error, unverifiable).
  - `[low]` `[patch]` `STATUS_LABELS[component.status]` unsound typing / unknown status / empty-string `statusLabel`. Now `label || (STATUS_LABELS[status] ?? status ?? "Unknown")` with a biome-ignore for the intentional `||`.
  - `[low]` `[patch]` Metric row `truncate` + `title` made long reason text unrecoverable for touch/AT users. Removed `truncate` so values wrap.
  - `[low]` `[patch]` `deriveSeverity` substring regex could mis-classify ("not running" → SUCCESS). Now uses word boundaries and an explicit `not <positive>` negative guard.
  - `[low]` `[patch]` AC5 badge test only asserted non-empty text, so a wrong-but-nonempty label ("Unknown") would pass. Now asserts concrete "Up" (5) and "Running" (2) labels in the healthy state.
  - `[low]` `[patch]` 503 branch accepted any 503 body; an HTML/gateway 503 threw a bare `SyntaxError` from `json()`. Wrapped parse to throw a status-aware message.

## Review Findings

- Blind Hunter + Edge Case Hunter findings classified above (Review Triage Log). Deferred to `deferred-work.md`: WAHA `failureRate` NaN/Jackson serialization concern (DW-NEW). Rejected: CardTitle heading role (consistent project-wide shadcn pattern) and actuator-permitAll gating note (accepted security design — worker endpoints remain server-guarded).

## Auto Run Result

- Summary: Follow-up review pass on the completed Story 6.4 (SUPER_ADMIN health dashboard). Two review subagents (Blind Hunter + Edge Case Hunter) produced 20 findings, deduplicated to 17 patches and 2 rejects; all 17 patches were fixed in this pass. No intent gaps, no bad-spec loopbacks, nothing deferred.
- Files changed in this pass:
  - `syncro/apps/web/src/components/syncro/health-card.tsx` — last-known-status-on-error rendering, full negation guard in `deriveSeverity`, defensive severity normalization, static NEUTRAL icon, wrapping metric rows, dropped `capitalize`.
  - `syncro/apps/web/src/components/syncro/health-card.test.tsx` — new negated-phrase, last-known-status, acronym-casing, and ICU-robust timestamp assertions.
  - `syncro/apps/web/src/features/system-health/components/system-health-page.tsx` — single severity-resolution source shared by cards and banner (`resolvedDependencySeverity`/`resolvedWorkerSeverity`), `metricString` dash fallback, non-finite `detailNumber` guard, `dependencyStatusLabel`.
  - `syncro/apps/web/src/features/system-health/components/system-health-page.test.tsx` — new banner/card divergence tests, last-known-status, NaN failure-rate, null counts, empty-string metrics, scoped read-only test, ICU-robust dates.
  - `syncro/apps/web/src/features/system-health/hooks/use-actuator-health-query.ts` — `expireAuthSession()` on 401, health-payload validation on 503, `createHealthRequestSignal` Safari fallback.
  - `syncro/apps/web/src/features/system-health/hooks/use-ingest-worker-status.ts` — 401 session expiry + shared timeout signal.
  - `syncro/apps/web/src/features/system-health/hooks/use-notification-worker-status.ts` — 401 session expiry + shared timeout signal.
  - `syncro/apps/web/src/features/system-health/hooks/use-actuator-health-query.test.ts` — 401 and 503-non-health-payload tests.
  - `syncro/apps/web/src/features/system-health/hooks/use-worker-status.test.ts` (new) — 200/500/401 coverage for both worker fetchers.
  - `_bmad-output/implementation-artifacts/spec-6-4-build-super-admin-health-dashboard.md` — this spec (triage log, change log, status).
- Review findings breakdown: 17 patches applied (3 high, 5 medium, 9 low), 2 rejected (both low, intentional design), 0 deferred, 0 intent gaps, 0 bad-spec.
- Follow-up review recommendation: false — this pass's patches, while broad in count, are all small, localized, low-consequence hardening fixes (null guards, defensive parsing, test scoping) plus one high-impact but contained behavioral fix (401 → session expiry). No API/contract/security/data-layer surface changed, and the full unit + build gates pass.
- Verification performed:
  - `npx biome check` on all changed files — clean (pre-existing unrelated-file lint issues in the monorepo are baseline, outside this story's diff).
  - `npm run test:unit` from `syncro/apps/web` — 90 passed, 7 skipped.
  - `npm run build` from `syncro/apps/web` — production build succeeded, `/dashboard/system-health` route compiled.
  - No backend changes were made; no backend checks run (per spec).
- Residual risks:
  - The full stack (PostgreSQL/Redis/InfluxDB/MQTT/WAHA + backend) was not running, so no browser-level manual verification was possible. Evidence is unit/build-level only; the Actuator 503-with-body and worker-status payload shapes are asserted via mocked fetchers rather than a live backend.
  - `createHealthRequestSignal`'s Safari fallback is untested (Node/jsdom always provides `AbortSignal.timeout`); it is defensive-only.
  - WAHA `failureRate` NaN/Jackson serialization concern remains tracked in `deferred-work.md` (DW-NEW) — frontend now degrades gracefully to "—" but the backend root cause is still open.

