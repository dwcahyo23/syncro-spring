---
title: 'Link Health Failures to Operational Evidence'
type: 'feature'
created: '2026-08-21'
status: 'review'
review_loop_iteration: 0
followup_review_recommended: false
baseline_commit: 604a9a5
context: []
warnings: []
---

<intent-contract>

## Intent

**Problem:** The SUPER_ADMIN health dashboard shows dependency/worker failure states and telemetry staleness, but a failed card tells the operator nothing about *where to look next*. WAHA failures give no path to the notification history that holds the actual failure evidence, and a STALE telemetry condition gives no per-machine context, so investigation is guesswork.

**Approach:** Two additive evidence surfaces, backend-owned: (1) `NotificationWorkerStatus` gains a nullable `lastFailedAlertId` (resolved from the most recent FAILED attempt's job) so the WAHA dependency card and Notification Worker card can deep-link to the failing alert's notification history on the alert detail page; (2) a new SUPER_ADMIN-only `GET /api/v1/telemetry/stale-machines` endpoint lists ACTIVE machines whose per-machine freshness is OFFLINE/STALE (backend-computed via `TelemetryFreshnessCalculator`), rendered as an expandable "stale machines" evidence list under the Telemetry Freshness card with links to each machine hub. Every dependency/worker card additionally renders a static "Next step" hint row whenever its resolved severity is warning/critical.

## Boundaries & Constraints

**Always:**
- All evidence data is backend-computed: `lastFailedAlertId` comes from `notification_attempts` (top FAILED in failed window) joined to its `notification_jobs.alert_id`; stale-machine states come from `TelemetryFreshnessCalculator` over Redis latest-telemetry. The frontend renders verbatim and never infers failure evidence.
- New endpoint is SUPER_ADMIN-only, guarded in the controller exactly like `TelemetryFreshnessController` (including the null-principal `user == null ||` FORBIDDEN guard); never exposed on unauthenticated `/actuator/health`.
- Cross-module access only through application services: `TelemetryStaleMachineService` may call `MachineService.list` (machine module application contract) and `LatestTelemetryQueryService` (telemetry module); never a repository from another module.
- Use the injected `Clock` in new backend services; fixed `Clock` in tests (never `Clock.systemUTC()` in tests).
- Frontend reuses the established health-hook pattern (queryKey, 30s refetch, retry 2, 401 → `expireAuthSession`, `createHealthRequestSignal` where used by siblings) and renders backend-provided labels/states verbatim.
- HealthCard's read-only contract stays intact ("renders no mutation or navigation controls"): deep links render as a separate evidence link element next to the card, not inside `HealthCard`. Static "Next step" text rows may render as `HealthMetricRow` children.
- Timestamps ISO-8601 UTC; relative "Xm ago" formatting is display-only via the existing `formatRelativeFreshness`.
- DTO changes are additive only: `lastFailedAlertId` is appended to `NotificationWorkerStatus` (nullable, backward-compatible); no existing field renamed/removed.

**Block If:** No decisions require human input for this story.

**Never:**
- No DB migrations, new infra services, new config keys, or new frontend dependencies/libraries.
- Do not change existing status semantics, `computeOverallBanner` behavior, `/actuator/health` exposure, `/api/v1/telemetry/freshness` or `/api/v1/telemetry/ingest/status` contracts.
- Do not surface `responseDetail`, phone numbers, tokens, connection strings, or any credential in the new evidence UI; `lastFailedAlertId` is a UUID and stale-machine items carry codes only.
- Do not mention pgAdmin in product UI; pgAdmin stays a local/dev-docs-only reference (`syncro/docs/local-development.md` already covers it — no doc change needed in this story).
- Do not paginate or invent thresholds for stale machines: Phase-1 active-machine count is small; one bounded query (size cap documented in code) is sufficient.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Failed WAHA attempt exists | top FAILED attempt in window has jobId → job exists | `lastFailedAlertId = job.alertId` (UUID string in JSON); WAHA + Notification Worker cards render "View notification history" link → `/dashboard/alerts/{alertId}` | Link renders only when id non-null |
| No failed attempt in window | no FAILED rows after failedWindowStart | `lastFailedAlertId = null`; cards show fallback next-step hint text instead of link | No error |
| Failed attempt's job deleted | jobId not found in `notification_jobs` | `lastFailedAlertId = null` (empty Optional handled) | No error, no 500 |
| Stale machines exist | ACTIVE machine, freshness OFFLINE (5–15 min) or STALE (>15 min or INACTIVE-forced) | Item `{machineId, machineCode, plantCode, freshnessState, statusLabel, lastReceivedAt}`; count = items length; freshness card shows "Machines with stale telemetry: N" + expandable list; each row links to `/dashboard/master-data/machines/{machineCode}` | No error |
| Active machine never sent telemetry | Redis latest empty → `latestTelemetry` null | Item included with `lastReceivedAt = null`, state OFFLINE, UI shows "No telemetry received"; sorted first (nulls-first) | No error |
| All machines ONLINE | no ACTIVE machine exceeds 5 min | `staleMachineCount = 0`, `items = []`; UI shows "Machines with stale telemetry: 0" and no list rows | No error |
| Redis read failure | `latestTelemetry` returns null on read error | Treated as no-data for that machine (item with null lastReceivedAt, OFFLINE) — evidence panel degrades to "unknown freshness", never 500 | Log warn already emitted by query service |
| Dependency/worker warning or critical | resolved card severity ∈ {warning, critical} | Card renders "Next step" hint row with the static guidance text for that dependency/worker | Hidden for success/neutral |
| Not authorized | MANAGE/VIEWER or anonymous on `/api/v1/telemetry/stale-machines` | HTTP 403 / 401 | Controller guard throws FORBIDDEN; null principal → 403, never 500 |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerStatus.java` -- MODIFY -- append `lastFailedAlertId` (UUID, nullable) with javadoc; additive contract change.
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerStatusService.java` -- MODIFY -- after resolving the top FAILED attempt, `jobRepository.findById(attempt.getJobId())` → map to `job.getAlertId()`; empty Optional → null.
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` -- check existing `findById` (JpaRepository provides it; add nothing unless absent).
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryFreshnessCalculator.java` -- per-machine ONLINE (≤5m) / OFFLINE (5–15m) / STALE (>15m or INACTIVE) derivation; thresholds are the contract for "stale machines" (page-spec 4.5 ">5 min").
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/LatestTelemetryQueryService.java` -- `latestTelemetry(machineId, manualStatus)`; returns null on Redis failure/empty (evidence panel treats as no-data).
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/LatestTelemetryDto.java` -- `TelemetryData` (machineId, lastReceivedAt, freshnessState) and `FreshnessState` enum with `label()`/`description()`.
- `syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineService.java` -- `list(user, null, null, MachineStatus.ACTIVE, null, page, size, "code,asc")` returns `MachineListView` of application `MachineView(id, plantId, plantCode, plantName, ..., code, name, status, ...)`; cross-module application-service access pattern.
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/api/TelemetryFreshnessController.java` -- exact SUPER_ADMIN guard + OpenAPI annotation pattern to mirror for the new controller (includes null-principal guard).
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryFreshnessService.java` / `TelemetryFreshnessStatus.java` -- DTO + service shape convention for the new stale-machine types.
- `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationWorkerStatusServiceTest.java` -- Mockito + fixed-clock pattern to extend for `lastFailedAlertId`.
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryFreshnessServiceTest.java` / `.../api/TelemetryFreshnessControllerTest.java` -- fixed-clock unit + `@WebMvcTest` guard-test patterns for the new endpoint.
- `syncro/apps/web/src/features/system-health/types/index.ts` -- add `lastFailedAlertId: string | null` to `NotificationWorkerStatus`; add `StaleMachineItem` / `StaleMachineStatus` types mirroring the backend records.
- `syncro/apps/web/src/features/system-health/hooks/use-telemetry-freshness.ts` + `.test.ts` -- hook/fetcher template (queryKey, 30s refetch, retry 2, 401 expiry, shape guard with union/field validation) for `use-stale-machines`.
- `syncro/apps/web/src/features/system-health/components/system-health-page.tsx` -- add evidence surfaces: per-card "Next step" hint rows, WAHA/Notification-Worker deep links, stale-machines count + expandable list under the freshness card.
- `syncro/apps/web/src/features/system-health/components/system-health-page.test.tsx` -- page test template (vi.mock per hook, card-count assertions, state coverage).
- `syncro/apps/web/src/components/syncro/health-card.tsx` -- `HealthCard` (read-only; do NOT add navigation inside), `HealthMetricRow`, `formatDateTimeUtc`; page-local `formatRelativeFreshness` for display-only "Xm ago".
- `syncro/apps/web/src/app/(main)/dashboard/alerts/[alertId]/page.tsx` -- alert detail target route (contains EscalationTimeline + notification history from Story 5.6).
- `syncro/apps/web/src/app/(main)/dashboard/master-data/machines/[machineCode]/page.tsx` -- machine hub target route (param is machineCode).
- `syncro/docs/local-development.md` -- already documents pgAdmin as local/dev-only inspection, not a product feature; the source of truth for AC 6.6-4; no change needed.

## Tasks & Acceptance

**Execution:**

- [x] `syncro/apps/backend/.../notification/application/NotificationWorkerStatus.java` -- MODIFY -- append `UUID lastFailedAlertId` (nullable) to the record; extend the javadoc (alert whose notification failed most recently; null when no FAILED attempt in window or job no longer exists). [AC 6.6-2]
- [x] `syncro/apps/backend/.../notification/application/NotificationWorkerStatusService.java` -- MODIFY -- resolve `lastFailedAlertId` from the existing top-FAILED attempt query: `attemptRepository.findTop...` → `jobRepository.findById(attempt.getJobId())` → `map(NotificationJobEntity::getAlertId)` → `orElse(null)`; reuse the already-fetched attempt (no second query); pass into the record constructor. [AC 6.6-2]
- [x] `syncro/apps/backend/.../telemetry/application/StaleMachineItem.java` -- NEW -- record `(UUID machineId, String machineCode, String plantCode, String freshnessState, String statusLabel, Instant lastReceivedAt)`; `freshnessState` is the enum name (OFFLINE/STALE), `statusLabel` from `FreshnessState.label()`, `lastReceivedAt` nullable (never received). [AC 6.6-3]
- [x] `syncro/apps/backend/.../telemetry/application/StaleMachineStatus.java` -- NEW -- record `(String timestamp, int staleMachineCount, List<StaleMachineItem> items)` following the operational status contract style. [AC 6.6-3]
- [x] `syncro/apps/backend/.../telemetry/application/TelemetryStaleMachineService.java` -- NEW -- injects `MachineService`, `LatestTelemetryQueryService`, `Clock`; `staleMachines(AuthenticatedUser user)`: list ACTIVE machines (`list(user, null, null, ACTIVE, null, 0, 500, "code,asc")` — single bounded page, Phase-1 scale documented in javadoc), for each machine call `latestTelemetry(id, status)`, include when telemetry is null (→ OFFLINE, lastReceivedAt null) or `freshnessState != ONLINE`; sort nulls-first then oldest `lastReceivedAt`, tie-break `machineCode` asc; return count + items + `clock.instant()` timestamp. [AC 6.6-3]
- [x] `syncro/apps/backend/.../telemetry/api/TelemetryStaleMachineController.java` -- NEW -- `GET /api/v1/telemetry/stale-machines`, SUPER_ADMIN guard with null-principal check (mirror `TelemetryFreshnessController` exactly), OpenAPI annotations, `@Tag(name = "telemetry-stale-machines")`. [AC 6.6-3]
- [x] `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationWorkerStatusServiceTest.java` -- MODIFY -- add tests: failed attempt resolves `lastFailedAlertId` from its job; no failed attempt → null; job missing → null. [AC 6.6-2]
- [x] `syncro/apps/backend/src/test/java/com/syncro/notification/api/NotificationWorkerStatusControllerTest.java` -- MODIFY -- assert `lastFailedAlertId` in the 200 JSON (present and null variants). [AC 6.6-2]
- [x] `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryStaleMachineServiceTest.java` -- NEW -- Mockito + fixed clock: OFFLINE included (5–15m), STALE included (>15m), ONLINE excluded (≤5m), never-received active machine included with null `lastReceivedAt` sorted first, INACTIVE machine excluded (query filters ACTIVE), Redis-null telemetry → OFFLINE no-data item, empty result shape (count 0, empty items), deterministic sort. [AC 6.6-3]
- [x] `syncro/apps/backend/src/test/java/com/syncro/telemetry/api/TelemetryStaleMachineControllerTest.java` -- NEW -- `@WebMvcTest`: SUPER_ADMIN 200 with full JSON shape; MANAGE/VIEWER 403; unauthenticated 401; null-principal-tolerant guard verified. [AC 6.6-3]
- [x] `syncro/apps/web/src/features/system-health/types/index.ts` -- MODIFY -- add `lastFailedAlertId: string | null` to `NotificationWorkerStatus`; add `StaleMachineItem` and `StaleMachineStatus` types mirroring backend records. [AC 6.6-2, AC 6.6-3]
- [x] `syncro/apps/web/src/features/system-health/hooks/use-stale-machines.ts` -- NEW -- `fetchStaleMachines` (GET `/api/v1/telemetry/stale-machines`, auth header, 401 → `expireAuthSession`, response shape guard incl. items-array + per-item field validation) + `useStaleMachines` (queryKey `["stale-machines"]`, 30s refetch, retry 2). [AC 6.6-3]
- [x] `syncro/apps/web/src/features/system-health/hooks/use-stale-machines.test.ts` -- NEW -- fetcher tests: 200 resolves payload; 500 rejects; 401 expires session; malformed item shape rejects. [AC 6.6-3]
- [x] `syncro/apps/web/src/components/syncro/health-evidence-link.tsx` -- NEW -- small link component (Next `Link`, external-link icon, accessible name) for deep links rendered next to (not inside) `HealthCard`; no domain logic. [AC 6.6-1, AC 6.6-2]
- [x] `syncro/apps/web/src/features/system-health/components/system-health-page.tsx` -- MODIFY -- (a) static `NEXT_STEP_HINTS` map for db/influxdb/redis/mqtt/wahaCircuitBreaker/ingest/notification workers; render `HealthMetricRow label="Next step"` inside each card only when the card's resolved severity is warning or critical; (b) WAHA card + Notification Worker card: render `HealthEvidenceLink` "View notification history" → `/dashboard/alerts/{lastFailedAlertId}` when `notificationWorker.data?.lastFailedAlertId` is non-null and that card's severity is warning/critical, else the static hint only; (c) freshness card: add `useStaleMachines`; render "Machines with stale telemetry: N" row always when data loaded, and when N > 0 an accessible expandable list (button + `aria-expanded`, or `details`/`summary`) of stale machines — each row: machineCode, state label, lastReceivedAt absolute UTC + display-only relative ("No telemetry received" when null), linking to `/dashboard/master-data/machines/{machineCode}`; include stale-machines in `dataUpdatedAts`, `isLoading`, `isFetching`, `handleRefresh`; (d) hint/link text contains no pgAdmin mention and no secrets. [AC 6.6-1, AC 6.6-2, AC 6.6-3, AC 6.6-4, AC 6.6-5]
- [x] `syncro/apps/web/src/features/system-health/components/system-health-page.test.tsx` -- MODIFY -- add `staleMachinesQuery` mock + fixtures; extend existing counts where the new rows affect them; add tests: dependency DOWN card shows its "Next step" hint (healthy card does not); WAHA/notification failure with `lastFailedAlertId` renders history link with correct `/dashboard/alerts/{id}` href and no link when null; stale-machines count row renders (0 case); N>0 renders expandable list with machine rows linking to `/dashboard/master-data/machines/{code}`, "No telemetry received" for null lastReceivedAt, and the list is collapsed by default + expands on toggle; refresh includes stale-machines refetch; no test fixture contains pgAdmin/credential strings on the evidence surfaces. [AC 6.6-1, AC 6.6-2, AC 6.6-3, AC 6.6-4, AC 6.6-5]
- [x] Verify: run `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="NotificationWorkerStatusServiceTest,NotificationWorkerStatusControllerTest,TelemetryStaleMachineServiceTest,TelemetryStaleMachineControllerTest"` → BUILD SUCCESS; from `syncro/apps/web` run `npm run test:unit`, `npm run build`, and `npx biome lint` on changed files (clean; CRLF formatter diffs are the known environmental baseline). [all ACs]

**Acceptance Criteria:**

- Given a dependency or worker is degraded/unhealthy/stale, when the health dashboard renders the failure state, then the UI shows the status reason and a suggested evidence location or next action. [AC 6.6-1]
- Given WAHA/notification failure evidence exists, when the failure state renders, then the WAHA failure references notification history (deep link to the failing alert's notification history). [AC 6.6-2]
- Given a telemetry stale condition, when the freshness section renders, then it references latest telemetry and machine context (per-machine stale list linking to machine hubs). [AC 6.6-3]
- Given local/dev evidence workflows, pgAdmin remains a docs-only reference and never a product feature of the health UI. [AC 6.6-4]
- Given any health failure state rendered, no secrets or credentials are displayed in the health UI. [AC 6.6-5]

## Spec Change Log

- 2026-08-21: Spec created (draft → ready-for-dev).
- 2026-08-21: Implemented (see Dev Agent Record). All 17 tasks complete; status → review.

## Design Notes

- **Evidence = backend-owned pointers, frontend renders links.** `lastFailedAlertId` is resolved server-side from the attempt/job tables (the same data that already feeds `lastFailureReason`), and stale-machine states reuse `TelemetryFreshnessCalculator` (ONLINE ≤5m / OFFLINE 5–15m / STALE >15m), so the UI never re-derives failure semantics. Per page-spec 4.5, "stale machines" means active machines with lastSeen > 5 minutes → both OFFLINE and STALE qualify.
- **Why a dedicated stale-machines endpoint instead of reusing `GET /api/v1/machines?status=ACTIVE`:** the machines list is plant-scoped and MANAGE/VIEWER-accessible with a different contract; filtering ">5 min = stale" client-side would leak the per-machine staleness rule to the frontend. A SUPER_ADMIN-only telemetry endpoint keeps the rule server-side and gives the health page a stable evidence contract, matching how 6.5 gave freshness its own endpoint instead of reusing `/telemetry/ingest/status`.
- **Cross-module boundary:** `TelemetryStaleMachineService` (telemetry) → `MachineService.list` (machine application service) is the architecture-sanctioned direction ("cross-module access goes through application services"); machine→telemetry already exists (`MachineController` → `LatestTelemetryQueryService`). No repository access across modules.
- **`lastFailedAlertId` additive contract:** appended nullable field; existing consumers (6.4 dashboard, `use-notification-worker-status`) are unaffected. Alert detail already renders notification history + escalation timeline (Story 5.6), so the deep link lands on existing evidence, not new UI.
- **HealthCard read-only contract preserved:** deep links are navigation; rendering them inside `HealthCard` would break its documented "no navigation controls" design. Static hint text rows via `HealthMetricRow` children are fine (the WAHA metric rows already do this).
- **Bounded stale-machines query:** single page of 500 ACTIVE machines is deliberate Phase-1 scope (evidence panel, SUPER_ADMIN-only, 30s poll); revisit pagination only when active-machine count makes the payload meaningful. Sorting is deterministic (never-received first, then stalest, then code) so the evidence list and its tests are stable.
- **pgAdmin stance:** `syncro/docs/local-development.md` already scopes pgAdmin to local/dev inspection only. This story adds no pgAdmin reference anywhere in product UI or backend payloads; the UI next-step text stays product-generic ("verify the service / check backend logs").
- **No-secrets stance:** the new payload fields are a UUID and machine codes/plant codes; `lastFailureReason` (truncated `responseDetail`) remains the only provider-derived text and is unchanged, SUPER_ADMIN-only, and masked-at-source by WAHA error handling.

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="NotificationWorkerStatusServiceTest,NotificationWorkerStatusControllerTest,TelemetryStaleMachineServiceTest,TelemetryStaleMachineControllerTest"` -- expected: BUILD SUCCESS (hermetic, no containers).
- `npm run test:unit` (from `syncro/apps/web`) -- expected: all unit tests pass including new evidence tests.
- `npm run build` (from `syncro/apps/web`) -- expected: production build succeeds, `/dashboard/system-health` compiles.
- `npx biome lint` on changed frontend files -- expected: clean (CRLF formatter-only diffs under `npm run check` are the known environmental baseline, cf. spec-6-5).

**Manual checks (if no CLI):**
- Inspect failure-state renders: dependency DOWN card shows "Next step" hint; WAHA/notification failure with a FAILED attempt shows the notification-history deep link; STALE freshness shows stale-machine count + expandable list linking to machine hubs. Full-stack browser verification may not be runnable unattended; if infra is unavailable, rely on the unit/build gates and state that explicitly.

## Dev Agent Record

### Agent Model Used

GLM-5.3 (ZCode, builtin:zai-start-plan/GLM-5.3)

### Debug Log References

- Backend scoped runs: `mvn -f syncro/apps/backend/pom.xml test -Dtest="..."` (see Verification below for the exact class list).
- Frontend: `npx vitest run` scoped files, `npm run test:unit`, `npm run build`, `npx biome lint` on changed files.

### Completion Notes List

- `lastFailedAlertId` resolves from the already-fetched top FAILED attempt (single repository fetch reused for `lastFailureReason` and the id); missing job → null, no failure → null. Additive DTO field; no existing consumer changed.
- Stale-machine evidence lives in the telemetry module with cross-module access via `MachineService.list` (application service, ACTIVE-only, one bounded page of 500 — documented Phase-1 scope). Never-received machines (null telemetry from empty Redis or read failure) are reported as OFFLINE with null `lastReceivedAt`, sorted first; ONLINE machines excluded; deterministic worst-first ordering.
- `HealthCard` untouched — its read-only "no navigation controls" contract holds; deep links render in a sibling `HealthEvidenceLink` outside the card, and static "Next step" hints are plain `HealthMetricRow` text rows. Verified by the existing 6-4 readonly test (no button/a inside any card) still passing plus explicit `card.contains(link) === false` assertions.
- Stale-machine count row renders for every loaded state (including LIVE freshness), because per-machine staleness can exist while the global ingest path is fresh; the count is backend-owned (`staleMachineCount`), the list is collapsed by default behind an `aria-expanded` toggle, and each row links to the machine hub by `machineCode`.
- pgAdmin is not referenced anywhere in the new UI/payloads; the "no pgAdmin / no credentials" guarantee is asserted by a dedicated page test (`queryByText(/pgadmin/i)` + textContent scan).
- `computeOverallBanner` and all 6.4/6.5 banner semantics untouched; stale machines intentionally do not feed the banner (per-machine evidence only).

**Verification performed:**
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="NotificationWorkerStatusServiceTest,NotificationWorkerStatusControllerTest,TelemetryStaleMachineServiceTest,TelemetryStaleMachineControllerTest"` — BUILD SUCCESS, 28 tests, 0 failures (16 + 12).
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="...13 hermetic classes across notification+telemetry..."` — BUILD SUCCESS, 77 tests, 0 failures (all hermetic tests in both touched modules, incl. all 6.4/6.5 sibling contract tests).
- `npm run test:unit` (syncro/apps/web) — 134 passed, 7 skipped (16 new: 7 fetcher + 9 page tests).
- `npm run build` (syncro/apps/web) — production build succeeded, `/dashboard/system-health` compiled.
- `npx biome lint` on the 6 changed frontend files — clean (one info-level class-order fix applied during dev).

**Residual risks / pre-existing failures (NOT caused by this story):**
- Full backend suite (`mvn test`) currently reports failures that reproduce identically without this story's changes (files untouched by 6-6; deterministic on isolated rerun; the `SparepartAlertCommandServiceTest.acknowledge_cancelsActiveNotificationJobs` failure also exists in a surefire report written at 19:54, before 6-6 development began):
  - `SparepartAlertCommandServiceTest.acknowledge_cancelsActiveNotificationJobs` — test expects `cancelActiveForAlert(..., [PENDING, SENT], ...)` while production (Story 5-8 rate-limit work) passes `[PENDING, SENT, RATE_LIMITED]`; stale test expectation on main.
  - `WahaRateLimiterTest` — 8 × `UnnecessaryStubbingException` (strict-stubs setUp stubbing), pre-existing test hygiene issue on main.
  - `TelemetryPersistenceIntegrationTest` and ~134 cascading context-load errors — Testcontainers failed to start `influxdb:3-core` in this environment (image present locally; startup timed out after 121 s). Environmental, blocks all `@SpringBootTest` integration classes in this run, unrelated to 6-6.
- Full-stack browser verification was not possible unattended; evidence is unit/build-level per the Verification section.

### File List

- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerStatus.java` — modified
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerStatusService.java` — modified
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/StaleMachineItem.java` — new
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/StaleMachineStatus.java` — new
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryStaleMachineService.java` — new
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/api/TelemetryStaleMachineController.java` — new
- `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationWorkerStatusServiceTest.java` — modified
- `syncro/apps/backend/src/test/java/com/syncro/notification/api/NotificationWorkerStatusControllerTest.java` — modified
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryStaleMachineServiceTest.java` — new
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/api/TelemetryStaleMachineControllerTest.java` — new
- `syncro/apps/web/src/features/system-health/types/index.ts` — modified
- `syncro/apps/web/src/features/system-health/hooks/use-stale-machines.ts` — new
- `syncro/apps/web/src/features/system-health/hooks/use-stale-machines.test.ts` — new
- `syncro/apps/web/src/components/syncro/health-evidence-link.tsx` — new
- `syncro/apps/web/src/features/system-health/components/system-health-page.tsx` — modified
- `syncro/apps/web/src/features/system-health/components/system-health-page.test.tsx` — modified
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — modified (status transitions)
