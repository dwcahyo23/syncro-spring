---
title: 'Raise Procurement-Risk Alert Within Lead-Time Window'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
baseline_revision: 05c28c8
final_revision: c4c7b63
context:
  - '{project-root}/_bmad-output/project-context.md'
warnings:
  - oversized
---

<intent-contract>

## Intent

**Problem:** FR-087 (Epic 8, story 8.7) requires a distinct, duplicate-prevented `PROCUREMENT_RISK` alert when projected sparepart depletion falls within the procurement lead-time window, so ordering starts before stock-out — but today only the percentage-threshold alert type exists (`sparepart_alerts` has no type discriminator, and no evaluation consumes the story 8-6 projections).

**Approach:** Add an `alertType` discriminator (`THRESHOLD_PERCENTAGE` / `PROCUREMENT_RISK`) to the existing Epic-4 alert path, then extend the telemetry alert stage: after accepted telemetry persists, evaluate procurement risk per machine by reusing `SparepartProjectionService`'s per-installation projections (rate, remaining, lead-time consumption). When an installation's projected depletion falls within its lead-time window, create a `PROCUREMENT_RISK` alert snapshotting the evidence (rate per operating hour, projection basis, lead time used, projected depletion instant). Existing percentage-threshold behavior, dedupe, notifications, and read paths are untouched except additive fields.

## Boundaries & Constraints

**Always:**
- Discriminator: new enum `SparepartAlertType {THRESHOLD_PERCENTAGE, PROCUREMENT_RISK}`; migration adds `alert_type VARCHAR(24) NOT NULL DEFAULT 'THRESHOLD_PERCENTAGE'` (existing rows become THRESHOLD_PERCENTAGE) and makes `threshold_percentage` nullable (NULL for procurement-risk rows; V35 CHECK stays valid because NULL passes it).
- Trigger rule (backend-owned, single owner = projection module): for each installation in `MachineSparepartProjectionsView`, raise when `available == true && leadTimeHours != null && consumptionDuringLeadTime != null && remainingCounters != null && consumptionDuringLeadTime >= remainingCounters`. Equality is inclusive (depletion exactly at window end triggers). `consumptionDuringLeadTime` uses the projection's displayed scale-2 rate × leadTimeHours (8-6 semantics), so the alert evidence matches the card.
- Missing lead time or insufficient rate data is a silent no-op: never an alert, never an error, never a guessed value.
- Duplicate prevention: new partial unique index `(machine_sparepart_installation_id) WHERE status != 'RESOLVED' AND alert_type = 'PROCUREMENT_RISK'` + repository `existsBy...` check + idempotent `DataIntegrityViolationException` catch (threshold pattern). The existing threshold dedupe index `(installation_id, threshold_percentage) WHERE status != 'RESOLVED'` and its `existsByMachineSparepartInstallationIdAndThresholdPercentageAndStatusNot` are UNTOUCHED — procurement-risk rows have NULL threshold so they never collide with it; threshold-change semantics are preserved.
- Evaluation hook: inside `TelemetryPersistenceService.persist`'s existing alert stage (inner try/catch, `sparepart_alert_evaluation_failed` marker), after `evaluateAndCreateAlerts`, call the new `evaluateAndCreateProcurementRiskAlerts(machineId, traceId)`. This runs after the existing per-machine projection-cache eviction, so each evaluation sees fresh data (accepts the per-message Influx recompute as the price of freshness; bounded by the 8-6 reader timeouts, never a 500).
- Perf guard: gate on a cheap `existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(machineId)` on `MachineSparepartInstallationRepository` before invoking the projection service, so machines with no lead-time spareparts never trigger an Influx read.
- Evidence snapshots on the alert (new nullable columns): `lead_time_hours NUMERIC(12,2)`, `rate_per_operating_hour NUMERIC(18,2)`, `calculation_basis VARCHAR(24)` (reuse `CounterRateEstimator.CalculationBasis`), `projected_depletion_at TIMESTAMPTZ`. All four NOT NULL for PROCUREMENT_RISK rows (DB CHECK), all NULL for THRESHOLD_PERCENTAGE rows. CHECKs: `alert_type` in the two-value set; `THRESHOLD_PERCENTAGE ⇒ threshold_percentage IS NOT NULL`; `PROCUREMENT_RISK ⇒ all four evidence fields NOT NULL`; `calculation_basis IS NULL OR IN ('ROLLING_30_DAY','FULL_HISTORY')`.
- Reuse seam: `SparepartProjectionService.getProjectionsForMachine(UUID machineId)` — new no-auth public method (mirrors `ShiftConfigService.resolveByMachineId`), returns the cached-or-computed view, or null when the machine is unknown. It reuses the exact `computeAndCache` path and cache key/TTL. Alert module now depends on the projection module (projection does not depend on alert — no cycle).
- Creation: `@Transactional` creator loops candidate rows: dedupe check → build entity (snapshots) → save (idempotent catch) → `AlertOpenedEvent` (notifications route identically; `NotificationRoutingService` does not reference threshold, no change) → `recordSystem` audit with `traceId` and evidence, label `"ALERT:" + installationId + "@PROCUREMENT_RISK"`.
- Read contract: `AlertView` / `AlertDetailView` gain `alertType` (enum), nullable `thresholdPercentage` (Integer), and nullable evidence fields `leadTimeHours`, `ratePerOperatingHour`, `calculationBasis`, `projectedDepletionAt`. Frontend renders the distinct type label + evidence; it never recomputes the trigger. Null threshold renders as `-`/omitted.
- Pilot seed extends the sparepart INSERT with `material_code` + `lead_time_hours` and adds one IDR `sparepart_price_entries` row (idempotent, `entered_by` = an existing pilot user), per AC.
- 8-7 adds NO user-facing mutation endpoint. FR-088 (job-scope LEADER+) does not apply here — alerts are system-created like threshold alerts; the AC's "role-denial paths return standard errors" is satisfied vacuously (existing read paths keep their 403/404). FR-089 is satisfied by the system audit record.

**Block If:** Nothing requires human input. Pinned: trigger uses `consumptionDuringLeadTime >= remainingCounters` (inclusive); evidence snapshotted at creation, never live-computed at read; alert module depends on projection module; threshold dedupe semantics preserved exactly.

**Never:**
- Never touch the threshold evaluation, threshold dedupe, V35 CHECK, or percentage behavior.
- Never change 8-6 projection formula, units (rate is counting-per-OPERATING-hour, lead time in operating hours), cache key, or TTL.
- Never raise an alert when lead time is missing or rate is unavailable (silent).
- Never add a user-triggered alert-create endpoint or a job-scope gate.
- Never duplicate rate/depletion math in the alert module or frontend.
- Never hand-edit `src/lib/api/generated/**`; never add charting/date libraries.
- Never store live-computed evidence in DTOs — evidence must come from the entity snapshot.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| RISK_WITHIN_WINDOW | available, leadTime=36.5h, consumption=548 ≥ remaining=500 | PROCUREMENT_RISK alert created with evidence (rate, ROLLING_30_DAY/FULL_HISTORY basis, 36.5h, projectedDepletionAt) | No error |
| RISK_BOUNDARY_EQUAL | consumption == remaining | Alert created (inclusive boundary) | No error |
| DEPLETED_NOW | remaining == 0 (already depleted) | Alert created (projectedDepletionAt = now, inside any window) | No error |
| OUTSIDE_WINDOW | consumption < remaining | No alert | No error, silent |
| MISSING_LEAD_TIME | sparepart leadTimeHours null | No alert | No error, silent |
| RATE_UNAVAILABLE | view.rateAvailable false (any insufficient reason) | No alert | No error, silent |
| NO_LEAD_TIME_SPAREPARTS | machine has no installation with lead time | Perf guard short-circuits before any Influx read; no alert | No error, silent |
| DUPLICATE | non-RESOLVED PROCUREMENT_RISK already exists for installation | Skip (dedupe); concurrent race caught idempotently | No error |
| BOTH_TYPES | threshold reached AND risk inside window | Two distinct alerts coexist on one installation (THRESHOLD + PROCUREMENT_RISK) | No error |
| THRESHOLD_UNTOUCHED | threshold alert open/resolved flows | Threshold dedupe/index/method unchanged; risk evaluation never creates threshold alerts | No error |
| UNKNOWN_MACHINE | machineId not found | Warn-log skip; no alert, no exception | No error |
| LIST_READ | PROCUREMENT_RISK alert in list/detail | alertType + evidence present; thresholdPercentage null | No error |
| AUDIT | risk alert created | recordSystem CREATE with traceId + evidence map, label `@PROCUREMENT_RISK` | No error |

</intent-contract>

## Code Map

**Backend (`syncro/apps/backend/src/main/java/com/syncro` unless noted):**
- `resources/db/migration/V41__add_alert_type_and_procurement_evidence.sql` -- NEW -- `alert_type` default THRESHOLD_PERCENTAGE; 4 nullable evidence columns; `threshold_percentage` DROP NOT NULL; partial unique index `sparepart_alerts_proc_risk_dedup_idx`; CHECK constraints (alert_type set, threshold-required, procurement-evidence, basis set). Threshold dedupe index untouched.
- `alert/domain/SparepartAlertType.java` -- NEW -- enum.
- `alert/infrastructure/SparepartAlertEntity.java` -- MODIFY -- add `alertType` (@Enumerated STRING, nullable=false), `leadTimeHours`, `ratePerOperatingHour`, `calculationBasis` (@Enumerated STRING, reuse `projection.application.CounterRateEstimator.CalculationBasis`), `projectedDepletionAt`; `thresholdPercentage` `int` → `Integer` nullable; constructor + getters.
- `alert/infrastructure/SparepartAlertRepository.java` -- MODIFY -- add `existsByMachineSparepartInstallationIdAndAlertTypeAndStatusNot(UUID, SparepartAlertType, SparepartAlertStatus)`.
- `sparepart/infrastructure/MachineSparepartInstallationRepository.java` -- MODIFY -- add `existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(UUID machineId)`.
- `alert/api/SparepartAlertDtos.java` -- MODIFY -- `AlertView`: add `alertType`, make `thresholdPercentage` `Integer` nullable, add evidence fields; `@Schema(nullable=...)` on all.
- `alert/api/SparepartAlertController.java` -- MODIFY -- `toDto` maps the new alertType + evidence fields from `AlertDetailView` into `AlertView`.
- `alert/application/SparepartAlertQueryService.java` -- MODIFY -- `AlertDetailView` same new fields; map entity fields in `toView`.
- `alert/application/SparepartAlertService.java` -- MODIFY -- threshold creation sets `alertType=THRESHOLD_PERCENTAGE`; add `evaluateAndCreateProcurementRiskAlerts(UUID machineId, String traceId)` (non-transactional orchestrator: machine resolve → lead-time perf guard → `projectionService.getProjectionsForMachine` → filter candidates → per-candidate delegation to the creator bean); new dependency `SparepartProcurementRiskAlertCreator`.
- `alert/application/SparepartProcurementRiskAlertCreator.java` -- NEW -- `@Transactional(REQUIRES_NEW)` per-alert create (dedupe check, `saveAndFlush` with narrow dedupe-constraint catch, `AlertOpenedEvent`, system audit); lives in its own bean so the event fires inside a real transaction and a concurrent duplicate never rolls back sibling alerts.
- `projection/application/SparepartProjectionService.java` -- MODIFY -- add no-auth `getProjectionsForMachine(UUID machineId)` → view or null (reuses cache + `computeAndCache`).
- `telemetry/application/TelemetryPersistenceService.java` -- MODIFY -- inside alert stage try/catch, call `alertService.evaluateAndCreateProcurementRiskAlerts(machineId, envelope.traceId())` after threshold evaluation.
- `resources/db/seed/pilot-seed.sql` -- MODIFY -- extend sparepart INSERT with `material_code` + `lead_time_hours`; add idempotent IDR price-entry INSERT referencing an existing pilot user.
- Tests: `alert/application/SparepartAlertServiceTest.java` (extend: trigger/dedupe/silent-no-op/audit/evidence), NEW `alert/application/SparepartProcurementRiskAlertIntegrationTest.java` (Testcontainers Postgres+Redis+InfluxDB3 cloning `SparepartProjectionServiceIntegrationTest` pattern; seed real Influx points + shift windows + lead-time sparepart; assert create/dedupe/non-interference/silent-no-op), NEW `db/SparepartAlertTypeMigrationTest.java` (pattern of `db/DbIndexHygieneMigrationTest`; V41: existing rows default THRESHOLD_PERCENTAGE; both dedupe indexes enforce; evidence CHECKs), NEW `alert/api/SparepartAlertControllerTest.java` (WebMvc pattern of `ProjectionControllerTest`; new fields serialize; null threshold).

**Frontend (`syncro/apps/web/src`):**
- `lib/api/generated/**` -- REGENERATE -- `alertView.ts` (the single model used by both list and detail — the controller maps `AlertDetailView` → `AlertView`) gains `alertType`, nullable `thresholdPercentage`, and evidence fields; new `AlertViewAlertType` enum model.
- `features/alerts/alert-type-badge.tsx` -- NEW -- mirrors `alert-status-badge.tsx`: `TYPE_CONFIG` for `THRESHOLD_PERCENTAGE` (current behavior) and `PROCUREMENT_RISK` (distinct label/color/icon/description).
- `features/alerts/alert-list-page-content.tsx` -- MODIFY -- add Type column with `AlertTypeBadge`; Threshold cell renders `-` when `thresholdPercentage` null.
- `features/machine-hub/alerts-tab.tsx` -- MODIFY -- type badge; null-threshold guard.
- `features/alerts/alert-detail-page-content.tsx` -- MODIFY -- `AlertTypeBadge` in header; "Why this alert fired" card branches on `alertType` (PROCUREMENT_RISK renders distinct copy + evidence lines: rate, basis, lead time used, projected depletion date; threshold copy only for THRESHOLD_PERCENTAGE).
- `features/operations-overview/operations-overview-page-content.tsx` -- MODIFY -- guard null threshold (omit threshold segment or show `-`).
- Tests: `features/alerts/alert-type-badge.test.tsx` (NEW, fixture/pure), extend `features/alerts/alert-detail-page-content.test.tsx` (mock-hook pattern; PROCUREMENT_RISK evidence + null threshold cases), NEW `features/alerts/alert-list-page-content.test.tsx` (mock-hook pattern; type column + `-` threshold).

## Tasks & Acceptance

**Execution:**
- [x] `resources/db/migration/V41__add_alert_type_and_procurement_evidence.sql` + migration test -- discriminator + evidence + procurement-risk dedupe; existing rows migrated.
- [x] `alert/domain/SparepartAlertType.java` -- enum.
- [x] `SparepartAlertEntity` + `SparepartAlertRepository` + `MachineSparepartInstallationRepository` -- fields, dedupe method, lead-time perf-guard exists.
- [x] `SparepartProjectionService.getProjectionsForMachine` -- no-auth reuse seam.
- [x] `SparepartAlertDtos.AlertView` + `SparepartAlertQueryService.AlertDetailView`/`toView` -- read contract.
- [x] `SparepartAlertService` threshold type + `evaluateAndCreateProcurementRiskAlerts` + creator -- evaluation + dedupe + audit + event.
- [x] `TelemetryPersistenceService.persist` wiring -- trigger in alert stage.
- [x] Backend tests per matrix (unit, integration, migration, WebMvc) -- prove every row.
- [x] `pilot-seed.sql` extension (material code, lead time, IDR price entry) -- validation data.
- [x] Orval regeneration -- typed hooks/models exist.
- [x] Frontend `AlertTypeBadge` + list/tab/detail/operations changes + fixture tests -- distinct rendering.
- [x] Verify: Maven targeted suite green; web vitest/tsc/biome green; live stack evidence (risk alert fires on pilot with lead-time near-depletion sparepart; dedupe on re-eval; threshold alert coexists; no alert when lead time/rate missing).

**Acceptance Criteria:**

- Given an installed sparepart has lead time and sufficient counter-rate data, when evaluation finds projected depletion within the lead-time window, then the backend creates a PROCUREMENT_RISK alert with distinct type/reason evidence including rate per operating hour, projection basis (ROLLING_30_DAY or FULL_HISTORY), lead time used, and projected depletion instant. [AC 8.7-1]
- Given the same installation already has a non-RESOLVED PROCUREMENT_RISK alert, when evaluation runs again, then no duplicate is created; existing percentage-threshold alerts and their dedupe are untouched and a THRESHOLD and a PROCUREMENT_RISK alert can coexist on one installation. [AC 8.7-2]
- Given alert list/detail/operations views, when a PROCUREMENT_RISK alert is read, then it renders the distinct type label and reason distinctly; a null threshold never renders as a bogus percentage. [AC 8.7-3]
- Given an installation with missing lead time or insufficient rate data, when evaluation runs, then no alert is created and no error is raised or logged as a failure. [AC 8.7-4]
- Given a PROCUREMENT_RISK alert is created, when the creation completes, then a system audit record with traceId and the evidence is written; no user-facing mutation endpoint or job-scope gate is introduced (FR-088 N/A — system-created like threshold alerts), and existing read-path 403/404 standard errors are unchanged. [AC 8.7-5]
- Given the pilot seed, when applied, then the sparepart carries material code and lead time and has an IDR price-entry example for validation. [AC 8.7-6]

## Spec Change Log

<!-- Append-only. Populated by step-04 during review loops. -->

## Review Triage Log

### 2026-08-25 — Review pass (Blind Hunter + Edge Case Hunter, baseline 05c28c8)
- intent_gap: 0
- bad_spec: 0
- patch: 9: (high 2, medium 2, low 5) -- all fixed in this pass:
  - `[high]` `[patch]` Self-invocation of the transactional creator bypassed the Spring proxy, so the `AlertOpenedEvent` fired with no active transaction and the `AFTER_COMMIT` notification listener silently discarded it — PROCUREMENT_RISK alerts never routed a notification; a concurrent duplicate also poisoned one transaction across sibling alerts. Creator extracted into a `SparepartProcurementRiskAlertCreator` bean (`@Transactional(REQUIRES_NEW)` per alert), `saveAndFlush` inside a narrow dedupe-constraint catch, event+audit inside the transaction; `SparepartProcurementRiskAlertCreatorTest` added (dedupe pre-check, constraint race → DedupConflict, unrelated violation rethrown).
  - `[high]` `[patch]` WAHA notifications for the new type rendered the shared threshold-shaped template as "-%", an operator-facing misrepresentation. `WahaTemplateRenderer` now branches on `alertType` and renders an evidence-based procurement message (lead time, rate, basis, projected depletion); `WahaTemplateRendererTest` added.
  - `[medium]` `[patch]` Detail "Lifetime Evidence" card fabricated zeros ("Baseline 0 · 0.00% · Threshold 0%") for PROCUREMENT_RISK rows. Card hidden for risk type; null consumed % renders `-` in threshold copy; risk detail test asserts the card is absent.
  - `[medium]` `[patch]` Discriminator null contract only half-enforced in the DB. V41 adds mirror CHECKs: THRESHOLD rows must keep their three snapshots; PROCUREMENT_RISK rows must have NULL threshold and NULL snapshots. Migration fixtures updated to satisfy them.
  - `[low]` `[patch]` Candidate filter did not require `projectedDepletionAt != null`; the integrity-violation catch swallowed any constraint as "concurrent". Filter now requires the depletion instant; the creator narrows the catch to `sparepart_alerts_proc_risk_dedup_idx` and rethrows other violations.
  - `[low]` `[patch]` Migration test never exercised the `ADD COLUMN ... DEFAULT 'THRESHOLD_PERCENTAGE'` backfill. Replaced with an insert that omits `alert_type` and asserts the default applies.
  - `[low]` `[patch]` Operations Overview empty-state copy ("All spareparts within threshold") was stale. Now "No open sparepart alerts."
  - `[low]` `[patch]` `AlertTypeBadge` silently dropped unknown values and defaulted `undefined` to Threshold. Now renders an explicit "Unknown" fallback badge; tests updated.
  - `[low]` `[patch]` Javadoc misstated the transaction boundary; the new creator's javadoc states the REQUIRES_NEW / AFTER_COMMIT requirement accurately.
- defer: 1: (low 1) -- stale cached projection consumed silently when projection-cache eviction fails (pre-existing 8-6 `ProjectionRedisCache` swallows Redis errors; surfaced incidentally by the alert path) → deferred-work.md.
- reject: 4: (medium 1, low 3)
  - Per-message full projection recompute for lead-time machines is the spec-pinned freshness tradeoff (bounded by the 8-6 reader timeouts, never a 500); per-installation gating would complicate the 8-6 cache contract → residual risk.
  - Alert module reuses `CounterRateEstimator.CalculationBasis` (cross-context enum): spec-pinned alert→projection dependency; the enum string values are DB CHECK and OpenAPI locked.
  - Machine-level `rateAvailable()` gate matches 8-6 per-machine semantics (per-installation `available` is only ever true when the machine rate exists); transient compute failures are bounded by reader timeouts.
  - Redundant machine fetch in the orchestrator + projection seam is negligible; keeping `getProjectionsForMachine(UUID)` simple is preferred.
- addressed_findings: all 9 patches fixed and re-verified — targeted backend suite green (SparepartAlertServiceTest 16, SparepartProcurementRiskAlertCreatorTest 4, SparepartProcurementRiskAlertIntegrationTest 6, SparepartAlertQueryServiceTest 7, SparepartAlertControllerTest 6, SparepartAlertTypeMigrationTest 9, WahaTemplateRendererTest 1, TelemetryPersistenceServiceTest 26), frontend vitest 10/10 on alert suites, tsc exit 0, biome infos-only (6 pre-existing warnings). Pre-existing baseline failures in `SparepartProjectionServiceIntegrationTest` (2) and other Docker-dependent integration suites confirmed unrelated to 8-7.

## Design Notes

- **Why alert-type discriminator + nullable threshold:** alerts must carry a type for distinct rendering and per-type dedupe; PROCUREMENT_RISK has no threshold, so the column becomes nullable. V35's CHECK passes NULL; no change needed there. The existing threshold dedupe index keeps exact semantics (threshold-change still creates a fresh alert), while the new partial index `(installation_id) WHERE alert_type='PROCUREMENT_RISK'` enforces one open risk alert per installation — a single unique index on `(installation_id, alert_type)` was rejected because it would wrongly dedupe two THRESHOLD alerts after a threshold change.
- **Why `consumptionDuringLeadTime >= remainingCounters` as the trigger:** `consumptionDuringLeadTime = round(rate × leadTimeHours, 0 HALF_UP)` is the expected counter consumption during the lead-time window (8-6). Depletion falls within the window ⟺ remaining ≤ rate × leadTime ⟺ consumption ≥ remaining. Reusing the projection's displayed value keeps the alert evidence identical to the hub card and avoids duplicating math in the alert module.
- **Why evidence is snapshotted, not live-computed:** matches the existing alert snapshot pattern (consumed %, counters) and the house rule that reads are evidence, not live derivation; the alert stays stable even after the projection cache turns over.
- **Golden example:** rate=15.00/op-h, leadTimeHours=36.5, remaining=500 ⇒ consumptionDuringLeadTime=round(15×36.5)=548 ≥ 500 ⇒ alert with ratePerOperatingHour=15.00, basis=ROLLING_30_DAY, leadTimeHours=36.5, projectedDepletionAt=<8-6 calendarDepletion result>.
- **Continuity:** reuses 8-6 projection service/cache/estimator, 4-x alert service/audit/event idioms, and the Testcontainers triple-container pattern. Threshold path is deliberately byte-for-byte untouched.

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test "-Dtest=SparepartAlertServiceTest,SparepartProcurementRiskAlertIntegrationTest,SparepartAlertQueryServiceTest,SparepartAlertControllerTest,TelemetryPersistenceServiceTest,SparepartAlertTypeMigrationTest"` -- expected: BUILD SUCCESS (create/dedupe/non-interference/silent-no-op/audit/evidence + V41 migration).
- `cd syncro/apps/web && npm run generate:snapshot && npm run generate:api && npm run test:unit` -- expected: regenerated alert models/hooks; suite green.
- `npx tsc --noEmit`; `npx biome check <touched files>` -- expected exit 0 / no new diagnostics.

**Manual checks:**
- Boot stack + seed pilot; publish counting telemetry until a lead-time sparepart's projection shows depletion inside its window; confirm a PROCUREMENT_RISK alert appears in list/detail with distinct label + evidence and a THRESHOLD alert can coexist; confirm no alert when lead time/rate is missing; re-evaluation adds no duplicate.

## Auto Run Result

Status: done (final_revision c4c7b63; baseline 05c28c8).

**Summary:** Added a `PROCUREMENT_RISK` alert type to the Epic-4 alert path (V41 discriminator + nullable threshold/snapshots with mirror CHECKs + per-installation dedupe index), evaluated on accepted telemetry by reusing the story 8-6 projections. When an installation's projected depletion falls within its lead-time window, a distinct alert is created snapshotting rate, projection basis, lead time used, and projected depletion instant, deduped per installation, audited with traceId, and routed to notifications. Threshold alerts, dedupe, and evaluation are untouched. Frontend renders the type distinctly (badge, evidence card, null-threshold-safe list/tab/detail/operations) and the WAHA message is now type-aware.

**Files changed:** backend — V41 migration, `SparepartAlertType`, `SparepartAlertEntity`, `SparepartAlertRepository`, `MachineSparepartInstallationRepository`, `SparepartAlertService` (threshold type + procurement-risk orchestrator), new `SparepartProcurementRiskAlertCreator` (transactional per-alert creator), `SparepartAlertDtos`/`SparepartAlertQueryService`/`SparepartAlertController` (read contract), `SparepartProjectionService.getProjectionsForMachine`, `TelemetryPersistenceService` wiring, `WahaTemplateRenderer` type-aware branch, pilot-seed extension; frontend — Orval regen (AlertView fields + AlertViewAlertType), `AlertTypeBadge` (+tests), alert list/tab/detail/operations type+null handling (+tests).

**Review findings breakdown:** 9 patches applied (2 high: transaction/notification-drop from self-invocation fixed via the creator bean with REQUIRES_NEW + saveAndFlush narrow catch; misleading threshold-shaped WAHA copy fixed with a type-aware evidence message), 2 medium (fabricated-zero Lifetime Evidence card hidden for risk; DB mirror CHECKs for the discriminator contract), 5 low (filter depletion-instant guard, migration-test default backfill, operations empty copy, unknown-type badge fallback, javadoc). 1 deferred (stale cached projection under eviction failure → DW-124). 4 rejected as spec-pinned (per-message recompute freshness tradeoff, cross-context enum reuse, machine-level rate gate, redundant machine fetch).

**Verification performed:** targeted backend suite green — SparepartAlertServiceTest 16, SparepartProcurementRiskAlertCreatorTest 4, SparepartProcurementRiskAlertIntegrationTest 6 (Testcontainers Postgres+Redis+InfluxDB3), SparepartAlertQueryServiceTest 7, SparepartAlertControllerTest 6, SparepartAlertTypeMigrationTest 9, WahaTemplateRendererTest 1, TelemetryPersistenceServiceTest 26. Frontend vitest alert suites 10/10, tsc exit 0, biome infos-only (6 pre-existing warnings). OpenAPI snapshot was updated manually because the live backend was unavailable; regenerate against the live backend when the Docker stack is up.

**Residual risks:** OpenAPI snapshot not regenerated from a live backend (contract drift possible until verified); per-message projection recompute on the ingest path for lead-time machines is a documented freshness tradeoff; `SparepartProjectionServiceIntegrationTest` (2 eviction tests) and other Docker-dependent suites fail at baseline, unrelated to 8-7; counter-reset-vs-wrap fabrication remains a pre-existing Epic-4-wide concern.
