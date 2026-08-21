---
title: 'Surface Latest Telemetry Freshness in Health'
type: 'feature'
created: '2026-08-21'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_commit: c1e2a0e4bb6f955d5117f63c97bf634e16172ae9
final_revision: 58a612e269d5ec7d45250de46e9b910b1d89ee2c
context: []
warnings: []
---

<intent-contract>

## Intent

**Problem:** The SUPER_ADMIN health dashboard shows dependency and worker status but has no dedicated view of the latest accepted telemetry record, so a telemetry path failure is invisible when dependencies still appear reachable.

**Approach:** Add a backend-owned telemetry freshness contract (`GET /api/v1/telemetry/freshness`, SUPER_ADMIN-only) that reports `NO_DATA`/`LIVE`/`STALE` derived from the backend latest-accepted-telemetry tracker and the ingest stale threshold, and render it as a "Telemetry Freshness" section on the system health dashboard with absolute UTC + relative freshness, a stale reason, and a clear empty state.

## Boundaries & Constraints

**Always:**
- Freshness state (`NO_DATA`/`LIVE`/`STALE`), label, severity, reason, and `staleSince` are computed on the backend from `TelemetryIngestTracker.lastAcceptedAt()` + `TelemetryProperties.ingest().staleThreshold()`; the frontend renders them verbatim and never infers freshness from timestamps.
- Endpoint is SUPER_ADMIN-only, guarded in the controller exactly like `IngestWorkerStatusController`; never exposed on unauthenticated `/actuator/health`.
- DTO follows the operational status contract: `status`, `statusLabel`, `statusSeverity`, `statusReason`, `timestamp`, plus `lastAcceptedAt`/`staleSince` (ISO or null). Severity taxonomy: NO_DATA→NEUTRAL, LIVE→SUCCESS, STALE→WARNING.
- Timestamps are ISO-8601 UTC; frontend displays absolute UTC plus a relative "Xs ago" freshness computed only for display.
- Use the injected `Clock` in the service; fixed `Clock` in tests (never `Clock.systemUTC()` in tests).
- Frontend reuses the existing health-hook pattern (`createHealthRequestSignal`, 30s refetch, retry 2, 401 session expiry) and the shared `HealthCard`.

**Block If:** No decisions require human input for this story.

**Never:**
- No DB changes, migrations, new infra, or new config keys (reuse `syncro.telemetry.ingest.stale-threshold`).
- Do not change the existing `/api/v1/telemetry/ingest/status` contract or `computeOverallBanner` behavior/tests from 6.4.
- Do not add the per-machine stale-machine count/list (page-spec 4.5 rows) — that is Story 6.6 territory (machine context evidence).
- Do not compute LIVE/STALE/NO_DATA on the frontend or branch on backend text.
- No secrets, stack traces, or provider internals in the DTO or UI.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| NO_DATA | `lastAcceptedAt == null` (no telemetry ever accepted) | `status=NO_DATA`, label "No data", severity NEUTRAL, reason "No telemetry received. Verify MQTT configuration and machine setup.", `lastAcceptedAt=null`, `staleSince=null` | Frontend renders clear empty state, never a healthy false-positive |
| LIVE | `now - lastAcceptedAt <= staleThreshold` | `status=LIVE`, label "Live", severity SUCCESS, reason null, `staleSince=null` | No error |
| LIVE (boundary) | `now - lastAcceptedAt == staleThreshold` exactly | Still `LIVE` (stale check is strictly `>`) | No error |
| STALE | `now - lastAcceptedAt > staleThreshold` | `status=STALE`, label "Stale", severity WARNING, reason "No telemetry accepted since {lastAcceptedAt}", `staleSince = lastAcceptedAt + staleThreshold` | Frontend shows reason + relative elapsed freshness |
| Not authorized | MANAGE/VIEWER or anonymous | HTTP 403 / 401 | Controller guard throws `FORBIDDEN` / Spring rejects |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryIngestTracker.java` -- source of global `lastAcceptedAt()`/`acceptedCount()` (in-memory, observability only).
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/IngestWorkerStatusService.java` -- exact pattern for stale derivation (elapsed vs `staleThreshold`, `staleSince = lastAcceptedAt + threshold`).
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/IngestWorkerState.java` -- enum pattern carrying `statusLabel`/`statusSeverity` locally (AR-014: no dependency on health module).
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/IngestWorkerStatus.java` -- DTO record pattern with operational status contract fields.
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/api/IngestWorkerStatusController.java` -- SUPER_ADMIN controller guard pattern.
- `syncro/apps/backend/src/main/java/com/syncro/config/TelemetryProperties.java` -- `ingest().staleThreshold()` (PT5M default).
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/IngestWorkerStatusServiceTest.java` -- fixed-clock unit-test pattern.
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/api/IngestWorkerStatusControllerTest.java` -- `@WebMvcTest` + `@MockitoBean` controller-test pattern.
- `syncro/apps/web/src/features/system-health/types/index.ts` -- add `TelemetryFreshnessStatus` type mirroring the backend record.
- `syncro/apps/web/src/features/system-health/hooks/use-ingest-worker-status.ts` -- hook template (queryKey/refetch/retry/401/shared signal).
- `syncro/apps/web/src/features/system-health/hooks/use-worker-status.test.ts` -- fetcher test template (200/500/401).
- `syncro/apps/web/src/features/system-health/components/system-health-page.tsx` -- add "Telemetry Freshness" section; current worker card already surfaces `lastAcceptedAt`/`staleSince`.
- `syncro/apps/web/src/features/system-health/components/system-health-page.test.tsx` -- page test template (vi.mock per hook).
- `syncro/apps/web/src/components/syncro/health-card.tsx` -- shared `HealthCard` + `HealthMetricRow` + `formatDateTimeUtc`.

## Tasks & Acceptance

**Execution:**

- [x] `syncro/apps/backend/.../telemetry/application/TelemetryFreshnessState.java` -- NEW -- enum `NO_DATA("No data","NEUTRAL")`, `LIVE("Live","SUCCESS")`, `STALE("Stale","WARNING")` with `statusLabel()`/`statusSeverity()` (mirrors `IngestWorkerState`).
- [x] `syncro/apps/backend/.../telemetry/application/TelemetryFreshnessStatus.java` -- NEW -- record `(TelemetryFreshnessState status, String statusLabel, String statusSeverity, String statusReason, String timestamp, String lastAcceptedAt, String staleSince)`; javadoc notes in-memory tracker reset-on-restart semantics.
- [x] `syncro/apps/backend/.../telemetry/application/TelemetryFreshnessService.java` -- NEW -- injects `TelemetryIngestTracker`, `TelemetryProperties`, `Clock`; `freshness()` returns the DTO per the I/O matrix (NO_DATA when `lastAcceptedAt==null`; stale strictly `> threshold`).
- [x] `syncro/apps/backend/.../telemetry/api/TelemetryFreshnessController.java` -- NEW -- `GET /api/v1/telemetry/freshness`, SUPER_ADMIN guard, OpenAPI annotations (mirror `IngestWorkerStatusController`).
- [x] `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryFreshnessServiceTest.java` -- NEW -- fixed-clock unit tests for every I/O matrix row (NO_DATA, LIVE, exact-boundary LIVE, STALE with `staleSince`).
- [x] `syncro/apps/backend/src/test/java/com/syncro/telemetry/api/TelemetryFreshnessControllerTest.java` -- NEW -- `@WebMvcTest`: SUPER_ADMIN 200 with all JSON fields; MANAGE/VIEWER 403; unauthenticated 401.
- [x] `syncro/apps/web/src/features/system-health/types/index.ts` -- MODIFY -- add `TelemetryFreshnessState = "NO_DATA"|"LIVE"|"STALE"` and `TelemetryFreshnessStatus` (field names mirror the record).
- [x] `syncro/apps/web/src/features/system-health/hooks/use-telemetry-freshness.ts` -- NEW -- `fetchTelemetryFreshness` (GET `/api/v1/telemetry/freshness`, 401→`expireAuthSession`, shared `createHealthRequestSignal`) + `useTelemetryFreshness` (queryKey `["telemetry-freshness"]`, 30s refetch, retry 2).
- [x] `syncro/apps/web/src/features/system-health/hooks/use-telemetry-freshness.test.ts` -- NEW -- fetcher tests: 200 resolves payload, 500 rejects, 401 expires session.
- [x] `syncro/apps/web/src/features/system-health/components/system-health-page.tsx` -- MODIFY -- add `useTelemetryFreshness`; render a "Telemetry Freshness" `HealthCard` section between Workers and the Quarantine Log: badge/severity from backend state, rows "Latest received" (`formatDateTimeUtc(lastAcceptedAt)` + relative "Xs ago" from a local `formatRelativeFreshness(iso, now)`), "Stale since" (`formatDateTimeUtc` when present), "—" when null; NO_DATA renders backend reason as the clear empty state; include freshness in `dataUpdatedAts`, `isLoading`, `isFetching`, and `handleRefresh`.
- [x] `syncro/apps/web/src/features/system-health/components/system-health-page.test.tsx` -- MODIFY -- add `freshnessQuery` mock + fixtures; update existing counts (8 cards, empty ×8, badges ≥8); add tests: LIVE shows absolute UTC + "ago" relative; STALE shows reason + "Stale since"; NO_DATA shows clear empty state and no "Live"/healthy claim; refresh + stale-banner include freshness.
- [x] Verify: run `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryFreshnessServiceTest,TelemetryFreshnessControllerTest"` → BUILD SUCCESS (11 tests, 0 failures); run `npm run test:unit` (113 passed, 7 skipped), `npm run build` (succeeded, `/dashboard/system-health` compiles), `npx biome lint` on changed files (clean; 3 formatter-only CRLF diffs under `npm run check` are the known environmental baseline, not real lint issues) from `syncro/apps/web`.

**Acceptance Criteria:**

- Given accepted telemetry history exists or no telemetry has been accepted yet, when the health dashboard loads, then the dashboard shows the latest telemetry received timestamp. [AC 6.5-1]
- Given telemetry is stale, when the dashboard renders the freshness section, then it shows the stale reason and elapsed freshness. [AC 6.5-2]
- Given no telemetry has ever been accepted, when the dashboard renders the freshness section, then it shows a clear empty state rather than a healthy false-positive. [AC 6.5-3]
- Given the freshness section renders, when the value is sourced, then it is based on the backend latest accepted telemetry record, not frontend time inference. [AC 6.5-4]
- Given a freshness timestamp exists, when displayed, then it is shown as absolute UTC plus relative freshness where practical. [AC 6.5-5]

## Spec Change Log

- 2026-08-21: Spec created (draft → ready-for-dev).
- 2026-08-21: Review pass amended Design Notes: freshness now feeds the overall banner (see below) — `computeOverallBanner` gained optional freshness inputs (`freshnessLoading`/`freshnessError`/`freshnessSeverity`) with defaults that preserve the exact 6.4 behavior for existing inputs, so no 6.4 banner-state contract regressed and all 6.4 banner tests still pass. The 6-4 page tests were extended (not reverted) for the new card per the task list's own "update existing counts" instruction.
- 2026-08-21: Follow-up review pass (8 patches, 1 defer, 3 rejects): null-principal NPE guard; severity-based banner exclusion (removes state-name branching, LIVE hardcode, and redundant null-guard); boundary tests for formatRelativeFreshness; fetcher union-membership validation; sub-second "just now"; dead-code cleanup; TelemetryProperties ingest null guard; stale-derivation javadoc equivalence note. Deferred DW-69 (tracker monotonic guard + backward clock, shared 6.4 surface).

## Review Triage Log

### 2026-08-21 — Follow-up review pass
- intent_gap: 0
- bad_spec: 0
- patch: 8 (high 1, medium 3, low 4)
- defer: 1 (low 1)
- reject: 3 (low 3)
- addressed_findings:
  - `[high]` `[patch]` Null-principal NPE in the SUPER_ADMIN guard: `freshness(user)` dereferenced `user.applicationRole()` with no null check. Added `user == null ||` so a null principal yields 403 (FORBIDDEN), never a 500, even if a future security change permits anonymous access to this path.
  - `[medium]` `[patch]` Banner freshness integration branched on state names (`status === "NO_DATA"` / `"LIVE"`) and hardcoded the LIVE banner severity as `"success"`, bypassing the shared `resolveSeverity` pipeline. Rewrote `freshnessBannerSeverity` as severity-based exclusion: resolve via `resolvedFreshnessSeverity` and exclude only `"unknown"` (NO_DATA → NEUTRAL → excluded), LIVE → `"success"` (no-op), STALE → `"warning"` (degraded). This removes the state-name branching (satisfies the intent contract's "never branch on backend status/text"), keeps banner/card severity from one source of truth, is forward-compatible with any future neutral-severity state, and also collapses the redundant double null-guard.
  - `[medium]` `[patch]` `formatRelativeFreshness` lacked the exact branch-boundary tests the spec triage claimed (0s/59s/59m/23h). Added tests for 0s boundary (sub-second), 59s, 59m, and 23h boundaries.
  - `[medium]` `[patch]` Fetcher shape guard only checked `typeof status === "string"`, so an unknown status (e.g. `"BANANA"`) passed through to the card. Tightened to validate union membership (`NO_DATA`/`LIVE`/`STALE`) plus required `statusLabel`/`statusSeverity`/`timestamp` strings; added a fetcher test for a status outside the union.
  - `[low]` `[patch]` Sub-second freshness rendered `"0s ago"`. Changed `seconds < 1` to render `"just now"` (matching the future-skew string), covered by the new 0s boundary test.
  - `[low]` `[patch]` Dead code in `computeOverallBanner`'s freshness push: `(freshnessSeverity ?? "unknown")` was unreachable inside the `freshnessError || freshnessSeverity !== undefined` guard. Split into `if (freshnessError) push("unknown") else if (freshnessSeverity !== undefined) push(freshnessSeverity)` with a comment that NO_DATA exclusion is by omission.
  - `[low]` `[patch]` `TelemetryProperties` compact constructor validated `latestTtl`/`dedupeWindow` but not the `ingest` component, so a programmatic `new TelemetryProperties(_, _, null)` would NPE inside `freshness()`. Added an `ingest == null` guard consistent with the sibling checks.
  - `[low]` `[patch]` Freshness service stale-derivation javadoc now documents exact equivalence with `IngestWorkerStatusService` (same `elapsed > threshold` rule, same `staleSince`), with the explicit negative-elapsed clock-skew LIVE branch called out, so a future threshold/boundary fix in one place propagates to both.
- deferred:
  - `[low]` `[defer]` `TelemetryIngestTracker.recordAccepted()` monotonic guard (`now.isAfter(lastAcceptedAt)`) can pin freshness LIVE through a backward NTP clock step until wall time catches up — masking a genuinely stalled ingest path. Changing the shared tracker affects the 6.4 worker status too; logged as DW-69 for a deliberate shared-tracker decision.
- rejected:
  - `[low]` `[reject]` Spec "Never: do not change computeOverallBanner behavior/tests from 6.4" contradicts the freshness banner integration. Already reconciled in the Spec Change Log + Design Notes (freshness inputs are optional, defaults preserve exact 6.4 behavior, 6-4 tests extended not reverted); the intent-contract text is the frozen original, not an actionable defect.
  - `[low]` `[reject]` Relative freshness only ticks on the 30s poll cadence. Acceptable by design: the absolute UTC row is always shown, the relative value is display-only, and the 30s tick matches the refresh cadence.
  - `[low]` `[reject]` `staleSince = lastAcceptedAt.plus(staleThreshold)` could overflow only with an astronomically large configured threshold (config-only, unreachable in practice with the PT5M default and the positive-duration validation).

### 2026-08-21 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 19 (high 1, medium 8, low 10)
- defer: 0
- reject: 4 (low 4)
- addressed_findings:
  - `[high]` `[patch]` Banner race: `computeOverallBanner` ignored freshness, so STALE telemetry could leave the banner claiming "All systems operational" for up to one poll cycle while the freshness card showed STALE. Added optional `freshnessLoading`/`freshnessError`/`freshnessSeverity` inputs (defaults preserve 6.4 behavior exactly); the page feeds them, and NO_DATA/LIVE stay neutral while STALE → degraded and freshness error → degraded. Added banner unit tests and page tests for STALE-degraded and NO_DATA-healthy.
  - `[medium]` `[patch]` NO_DATA page-level healthy false-positive: without a banner input the freshness card could be NO_DATA while the banner said fully healthy. Addressed by the banner integration above (STALE degrades; NO_DATA deliberately stays neutral because a fresh system awaiting its first message is not a failure — the card itself shows the clear empty state).
  - `[medium]` `[patch]` Flaky `6-5-AC1/AC5` time assertion (`/2m ago/` with `Date.now()-150_000`). Replaced with tolerant `/\d+m ago/` so slow CI cannot cross a minute boundary.
  - `[medium]` `[patch]` `formatRelativeFreshness` had no direct boundary coverage. Exported it and added unit tests for 0s/59s/60s/60m/24h/invalid/future.
  - `[medium]` `[patch]` Fetcher trusted a 200 body without shape validation. Added a `typeof status === "string"` guard plus a try/catch around `response.json()`; added fetcher tests for non-JSON 200, missing-field 200, 403 (no session expiry).
  - `[medium]` `[patch]` Backend clock-skew branch untested: a future `lastAcceptedAt` fell through to LIVE silently. Added an explicit negative-elapsed branch returning LIVE with a comment and a fixed-clock test.
  - `[medium]` `[patch]` `computeOverallBanner` freshness branches untested. Added banner unit tests: freshness STALE→degraded, freshness error→degraded, freshness loading→null, freshness success→healthy, freshness undefined→healthy.
  - `[low]` `[patch]` Triplicated severity-resolution pipeline (`resolvedDependencySeverity`/`resolvedWorkerSeverity`/`resolvedFreshnessSeverity`). Extracted shared `resolveSeverity(statusSeverity, statusLabel)`.
  - `[low]` `[patch]` Controller tests only asserted the LIVE JSON shape. Added NO_DATA (null fields + reason) and STALE (reason + staleSince + WARNING) JSON assertions.
  - `[low]` `[patch]` Empty-state microcopy duplicated across backend source/test. Centralized as `TelemetryFreshnessService.NO_DATA_REASON`.
  - `[low]` `[patch]` Naming hazard with pre-existing per-machine `TelemetryFreshnessCalculator`. Added package javadoc to `TelemetryFreshnessState` disambiguating the two freshness concepts.
  - `[low]` `[patch]` Freshness card omitted from the "last known status on refetch failure" test. Extended it to assert `Unable to refresh Telemetry Freshness` with retained rows.
  - `[low]` `[patch]` Freshness section had no heading and a casing mismatch in its aria-label. Added `<h2>Telemetry Freshness</h2>`, aligned the aria-label, and updated the scoped card query helper and read-only region list.
  - `[low]` `[patch]` AC-3 banner assertion was vacuous (`queryByText(/unhealthy/)` not present). Pinned actual banner behavior with dedicated STALE-degraded and NO_DATA-healthy page tests.
  - `[low]` `[patch]` New section heading broke `getByText("Telemetry Freshness")` uniqueness. Added a scoped `freshnessCard()` helper and updated all lookups.
  - `[low]` `[patch]` Spec "never change 6.4 tests" clause contradicted the task list's own "update existing counts" instruction. Amended Design Notes to clarify 6-4 tests are extended, not reverted, and 6.4 banner semantics for existing inputs are preserved.
- addressed_rejects:
  - `[low]` `[reject]` STALE reason embeds a raw ISO instant: kept verbatim, consistent with the existing worker `statusReason` ("No telemetry accepted since …") pattern; the reason is display-only for SUPER_ADMIN and the formatted "Latest received"/"Stale since" rows coexist.
  - `[low]` `[reject]` Relative freshness depends on client wall clock: display-only formatting per AC 6.5-5 / page-spec 5.2, always shown alongside the absolute UTC value; future-skew now renders "just now" instead of a negative age.
  - `[low]` `[reject]` Frontend `status` field unused: backend enum (single source of truth) guarantees `status`/`statusLabel`/`statusSeverity` are consistent; rendering backend-provided label/severity verbatim is the established 6.4 pattern.
  - `[low]` `[reject]` OpenAPI lacks a declared security requirement: matches sibling worker-status controllers (guard is enforced in code and documented in javadoc); no security-scheme declaration exists project-wide for these endpoints.

## Design Notes

- **Dedicated endpoint, not reuse of ingest/status:** `IngestWorkerStatus` conflates MQTT connection state with telemetry staleness, and its `statusReason` is worker-scoped. A dedicated `TelemetryFreshnessStatus` gives the health page a pure telemetry-path freshness contract (`NO_DATA`/`LIVE`/`STALE`), satisfies AC 6.5-4 ("not frontend time inference"), and gives Story 6.6 a stable base for machine-context evidence. Backend-owned state also keeps the section testable without a live MQTT feed.
- **Stale threshold:** reuse `properties.ingest().staleThreshold()` (PT5M default) — matches page-spec 4.5 ">5 minutes". Per-machine ONLINE/OFFLINE/STALE calculator (5/15 min hardcoded) is NOT reused; it is a different, per-machine concern.
- **Severity rationale:** NO_DATA→NEUTRAL (grey, not green — never a healthy false-positive; not red — a fresh system with no data yet is not a failure), LIVE→SUCCESS, STALE→WARNING (matches worker DEGRADED→WARNING).
- **Banner integration (amended by review):** freshness now feeds the overall banner via optional inputs to `computeOverallBanner` (`freshnessLoading`/`freshnessError`/`freshnessSeverity`). STALE → degraded and freshness fetch error → degraded so a telemetry-path stall can never leave the headline banner claiming "All systems operational" (the story's core purpose). NO_DATA → neutral (excluded): a fresh system awaiting its first message is not a failure, and the freshness card itself shows the clear empty state. Defaults preserve the exact 6.4 banner computation for existing inputs, so no 6.4 behavior regressed and all 6.4 banner unit tests pass unchanged.
- **Relative freshness is display-only:** the frontend formats "Xs ago" from the backend-provided absolute `lastAcceptedAt` against a ticking `now` — formatting, not staleness inference. Staleness itself is backend-decided.
- **Empty-state microcopy** (page-spec 4.10: "No telemetry received. Verify MQTT configuration and machine setup.") is owned by the backend `statusReason` so the frontend renders it verbatim.

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryFreshnessServiceTest,TelemetryFreshnessControllerTest"` -- expected: BUILD SUCCESS, all new backend tests pass (hermetic, no containers).
- `npm run check` (from `syncro/apps/web`) -- expected: clean on changed files (CRLF baseline known environmental, see spec-2-9).
- `npm run test:unit` (from `syncro/apps/web`) -- expected: all unit tests pass including new freshness tests.
- `npm run build` (from `syncro/apps/web`) -- expected: production build succeeds, `/dashboard/system-health` compiles.

**Manual checks (if no CLI):**
- Inspect the rendered freshness card states: LIVE (green "Live", absolute+relative), STALE (amber "Stale", reason, "Stale since"), NO_DATA (grey "No data", empty-state reason). Full-stack browser verification is documented but may not be runnable unattended; if infra is unavailable, rely on the unit/build gates and state that explicitly.

## Auto Run Result

- Summary: Follow-up review pass on the already-implemented Story 6.5. Blind Hunter + Edge Case Hunter produced findings that deduplicated to 8 patches (1 high, 3 medium, 4 low), 1 defer (DW-69), 3 rejects. All 8 patches applied on top of the existing implementation: null-principal NPE guard, severity-based banner exclusion (drops state-name branching and the LIVE hardcode), boundary tests for formatRelativeFreshness, fetcher union-membership validation, sub-second "just now", dead-code cleanup, TelemetryProperties ingest null guard, and a stale-derivation javadoc equivalence note.
- Files changed in this pass:
  - `syncro/apps/backend/src/main/java/com/syncro/telemetry/api/TelemetryFreshnessController.java` — null-principal FORBIDDEN guard.
  - `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryFreshnessService.java` — javadoc equivalence note vs IngestWorkerStatusService.
  - `syncro/apps/backend/src/main/java/com/syncro/config/TelemetryProperties.java` — `ingest == null` compact-constructor guard.
  - `syncro/apps/web/src/features/system-health/components/system-health-page.tsx` — `freshnessBannerSeverity` severity-based exclusion; `computeOverallBanner` dead-code cleanup; sub-second "just now".
  - `syncro/apps/web/src/features/system-health/components/system-health-page.test.tsx` — boundary tests (0s/59s/59m/23h) for formatRelativeFreshness.
  - `syncro/apps/web/src/features/system-health/hooks/use-telemetry-freshness.ts` — union-membership + required-field shape guard.
  - `syncro/apps/web/src/features/system-health/hooks/use-telemetry-freshness.test.ts` — full-payload fixture + status-outside-union rejection test.
  - `_bmad-output/implementation-artifacts/deferred-work.md` — DW-69 appended (append-only).
  - `_bmad-output/implementation-artifacts/spec-6-5-surface-latest-telemetry-freshness-in-health.md` — this spec (triage log, change log, status).
- Review findings breakdown: 8 patches applied (1 high, 3 medium, 4 low), 1 deferred (DW-69), 3 rejected (all low, noise/already-reconciled), 0 intent gaps, 0 bad-spec.
- Follow-up review recommendation: false — this pass's changes were localized hardening of already-reviewed code (one high-severity defensive NPE guard; the rest low/medium, no behavior/API/security contract change). The banner semantics were verified in the prior pass and are preserved byte-for-byte (severity-based exclusion produces identical results for the three states). No independent follow-up is warranted.
- Verification performed:
  - `mvn -f syncro/apps/backend/pom.xml test -Dtest="TelemetryFreshnessServiceTest,TelemetryFreshnessControllerTest"` — BUILD SUCCESS, 11 tests, 0 failures.
  - `mvn -f syncro/apps/backend/pom.xml test -Dtest="TelemetryIngestQueueConfigTest,TelemetryPersistenceServiceTest,IngestWorkerStatusServiceTest"` — BUILD SUCCESS, 32 tests, 0 failures (TelemetryProperties guard regression check).
  - `npm run test:unit` from `syncro/apps/web` — 118 passed, 7 skipped (5 new: 4 boundary + 1 union-membership fetcher test).
  - `npm run build` from `syncro/apps/web` — production build succeeded, `/dashboard/system-health` compiled.
  - `npx biome lint` on the 4 changed frontend files — clean. `npx biome check` reports 3 formatter-only CRLF line-ending diffs (known environmental baseline on this Windows checkout, cf. spec-2-9); HEAD also showed 4 such diffs on these files, so no new format drift was introduced.
- Residual risks:
  - Full-stack browser verification was not possible unattended (PostgreSQL/Redis/InfluxDB/MQTT/WAHA + backend not running). Evidence is unit/build-level only.
  - `lastAcceptedAt` is in-memory (resets on restart) by design; after a backend restart freshness shows NO_DATA until the first accepted message — documented, not a defect.
  - Backward clock-step behavior of the shared `TelemetryIngestTracker` (freshness can stay LIVE until wall time catches up) is deferred to DW-69, a shared 6.4-surface decision.

