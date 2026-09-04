---
title: 'Story 20-1: KPI Materialized Tables (redesigned 2026-08-31)'
type: 'feature'
created: '2026-09-04'
status: 'done'
baseline_revision: '4d1ec35'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-20-context.md'
  - '_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md'
  - '_bmad-output/planning-artifacts/architecture/architecture-Syncro-2026-08-24/ARCHITECTURE-SPINE.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Maintenance KPIs (MTBF, MTTR, MAR, PM completion, technician KPIs) are currently computed on-the-fly in dashboards, causing slow queries as history grows and inconsistent figures between views.

**Approach:** Create monthly materialized KPI tables (`kpi_mtbf_monthly`, `kpi_mttr_monthly`, `kpi_mar_monthly`, `kpi_pm_completion_monthly`, `kpi_technician_monthly`, `kpi_monthly_breakdown`, `kpi_target`, `kpi_aggregate_refresh_log`) that are computed backend-side on a schedule and after sync mutations. Dashboards read precomputed rows. An idempotent refresh job with audit trail (traceId/timestamp) recomputes per month/scope. Insufficient-data states are explicit (no fabricated values).

## Boundaries & Constraints

**Always:**
- MTBF ordered by `woStopAt` between consecutive breakdown workorders (not by id — reference impl had ordering bug)
- MTTR = cumulative work-log durations per workorder (wall-clock + actual-working variants)
- MAR = (planned_available - downtime) / planned_available × 100 from telemetry ingest state; carries source_status/source_message for unavailable metrics
- PM completion rate = completed/planned from PM execution data; carries source_status/source_message
- Technician KPIs: average_rating (configurable-dimension 1–5 stars), total_wo, first_time_fix_rate (objective measures)
- All KPI computation backend-owned; frontend renders results only
- Every KPI read filtered by organizational scope (plant / machine group / active team); leaders see their groups, managers see plant, global roles see all
- KPI target mutations and materialization refresh runs audit-logged with window and status evidence
- Recomputation idempotent per month/scope
- Units: MTBF days, MTTR minutes, MAR %, PM completion %, ratings 1–5
- Monthly window = calendar month normalized to first-of-month DATE

**Block If:**
- V1 schema and blueprint disagree on a column for any KPI table → HALT blocked with contradiction
- Source data (work_orders, work_logs, work_log_ratings, pm_executions, etc.) insufficient for a KPI → explicit insufficient-data state in materialized row, never zero/fabricated

**Never:**
- No on-the-fly KPI computation in API endpoints or frontend
- No KPI reads that bypass organizational scope filtering
- No manual SQL for KPI computation — use JPA entities/repositories from story 15-2
- No Redis as source of truth; Redis may cache with TTL and stale indicator only
- No `@OneToMany` collections on KPI entities
- No Lombok, no `Optional` in entity fields

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Refresh MTBF monthly | ≥2 breakdown workorders with woStopAt in month | Row with mtbf_days (BigDecimal) + traceId/timestamp audit | No row if <2 breakdown WOs; insufficient-data in dashboard |
| Refresh MTTR monthly | work_logs with start/end for closed WOs in month | Row with wall_clock_mttr_minutes + actual_working_mttr_minutes | NULL if no work_logs; insufficient-data in dashboard |
| Refresh MAR monthly | Telemetry ingest state provides planned/downtime minutes | Row with mar_percent + source_status/source_message | source_status = INSUFFICIENT_DATA if telemetry gap |
| Refresh PM completion | PM execution data for month | Row with completion_rate + completed_count/planned_count + source_status | source_status = INSUFFICIENT_DATA if no PM data |
| Refresh technician monthly | work_log_ratings + work_orders for technician in month | Row with average_rating + total_wo + first_time_fix_rate | NULL fields if no data; insufficient-data in dashboard |
| Idempotent re-run | Same month/scope, refresh job runs twice | Second run updates same rows (upsert), refresh_log status SUCCESS | RUNNING→SUCCESS/FAILED transition logged |
| Scope filtering | Leader with 2 machine groups opens MTBF dashboard | Only machine groups in scope returned | OPA authorization applied before read |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/kpi/infrastructure/db/KpiTargetEntity.java` -- JPA entity for kpi_targets (G1)
- `syncro/apps/backend/src/main/java/com/syncro/kpi/infrastructure/db/KpiMonthlyBreakdownEntity.java` -- JPA entity for kpi_monthly_breakdowns (G2)
- `syncro/apps/backend/src/main/java/com/syncro/kpi/infrastructure/db/KpiMtbfMonthlyEntity.java` -- JPA entity for kpi_mtbf_monthlies (G3)
- `syncro/apps/backend/src/main/java/com/syncro/kpi/infrastructure/db/KpiMttrMonthlyEntity.java` -- JPA entity for kpi_mttr_monthlies (G4)
- `syncro/apps/backend/src/main/java/com/syncro/kpi/infrastructure/db/KpiMarMonthlyEntity.java` -- JPA entity for kpi_mar_monthlies (G5)
- `syncro/apps/backend/src/main/java/com/syncro/kpi/infrastructure/db/KpiTechnicianMonthlyEntity.java` -- JPA entity for kpi_technician_monthlies (G6)
- `syncro/apps/backend/src/main/java/com/syncro/kpi/infrastructure/db/KpiPmCompletionMonthlyEntity.java` -- JPA entity for kpi_pm_completion_monthlies (G7)
- `syncro/apps/backend/src/main/java/com/syncro/kpi/infrastructure/db/KpiAggregateRefreshLogEntity.java` -- JPA entity for kpi_aggregate_refresh_logs (G7 audit)
- `syncro/apps/backend/src/main/java/com/syncro/kpi/infrastructure/db/*Repository.java` -- JpaRepository interfaces for all KPI entities
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderEntity.java` -- Source: breakdown WOs with woStopAt, status transitions
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkLogEntity.java` -- Source: work_log durations for MTTR, technician_id
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkLogRatingEntity.java` -- Source: technician ratings (1–5)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmExecutionEntity.java` -- Source: PM execution planned/completed
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql` -- Authoritative V1 schema (sections 21: KPI tables)
- `syncro/apps/backend/src/test/java/com/syncro/kpi/infrastructure/db/KpiEntityConventionIntegrationTest.java` -- Integration test template
- `syncro/apps/backend/src/test/java/com/syncro/AbstractPostgresIntegrationTest.java` -- Base for Testcontainers integration tests
- `syncro/apps/backend/src/main/java/com/syncro/kpi/domain/KpiAggregateRefreshStatus.java` -- Enum: RUNNING/SUCCESS/FAILED

## Tasks & Acceptance

**Execution:**
1. `syncro/apps/backend/src/main/java/com/syncro/kpi/application/KpiMaterializationService.java` -- create -- Core service: monthly refresh per KPI type (MTBF, MTTR, MAR, PM completion, technician, breakdown) with idempotent upsert per month/scope, traceId audit, refresh_log gating
2. `syncro/apps/backend/src/main/java/com/syncro/kpi/application/KpiTargetService.java` -- create -- CRUD for kpi_targets per plant/month; audit-log mutations; used by dashboards for actual-vs-target
3. `syncro/apps/backend/src/main/java/com/syncro/kpi/application/KpiScopeService.java` -- create -- Derive organizational scope (plantIds, machineGroupIds, activeTeamIds) from authenticated principal; reuses org module scope derivation
4. `syncro/apps/backend/src/main/java/com/syncro/kpi/api/KpiTargetController.java` -- create -- REST endpoints: GET/POST/PUT /api/v1/kpi/targets (plant-scoped), GET /api/v1/kpi/materialized/{type} (scope-filtered)
5. `syncro/apps/backend/src/main/java/com/syncro/kpi/scheduled/KpiRefreshScheduler.java` -- create -- @Scheduled job (cron from config) + event listener for sync invalidation; calls MaterializationService, writes refresh_log
6. `syncro/apps/backend/src/main/java/com/syncro/kpi/application/KpiQueryService.java` -- create -- Read materialized rows with scope filtering + target join; returns insufficient-data when month missing
7. `syncro/apps/backend/src/test/java/com/syncro/kpi/application/KpiMaterializationServiceTest.java` -- create -- Unit tests: MTBF woStopAt ordering, MTTR wall-clock vs actual-working, MAR source_status, idempotent re-run
8. `syncro/apps/backend/src/test/java/com/syncro/kpi/application/KpiQueryServiceTest.java` -- create -- Unit tests: scope filtering, target join, insufficient-data state
9. `syncro/apps/backend/src/test/java/com/syncro/kpi/infrastructure/db/KpiEntityConventionIntegrationTest.java` -- extend -- Add round-trip tests for all 8 KPI entities (already scaffolded in 15-2)

**Acceptance Criteria:**
- Given the schema redesign migration runs, when KPI tables are created, then all 8 tables exist with correct constraints per sprint-change-proposal-2026-08-31.md §4.5
- Given workorder/work-log/rating data for a closed period, when the KPI job runs, then MTBF (woStopAt-ordered), MTTR (cumulative durations), MAR, PM completion, and technician KPIs are computed backend-side and materialized per month with traceId/timestamp evidence
- Given a leader opens the MTBF/MTTR or technician KPI dashboard, when the dashboard loads, then it reads materialized monthly rows within scope (falls back to explicit insufficient-data state when a month has no row)
- Given a KPI target is configured per plant/month, when materialized rows are read, then actual vs target comparison is available with non-color-only status

### Review Findings

**Resolved decisions (unattended run — rationale recorded):**
- [x] [Review][Decision] AC3 dashboard rewiring → deferred to story 20-2 — 20-2's AC is literally "KPI dashboards show actual vs target"; 20-1 delivers the materialized read API (`GET /api/v1/kpi/materialized/{type}`). Rewiring the 14-2 rolling-30-day live endpoint to monthly rows changes its contract semantics (fleet-hours vs per-machine-days) and belongs in 20-2's scope. KEEP the 14-2 endpoint untouched.
- [x] [Review][Decision] MAR source stub → deferred (DW) — no persisted telemetry availability source exists (AD-12); the adapter's explicit INSUFFICIENT_DATA row is spec-compliant behavior ("never a fabricated value"), not a bug. Wiring InfluxDB availability is a separate data-source story.
- [x] [Review][Decision] PUT-as-upsert vs POST+PUT → keep PUT upsert — idempotent create-or-replace by (plant, month) is the correct REST shape for a unique-keyed config row.

**Patch (applied this pass):**
- [x] [Review][Patch] `V1BaseSchemaMigrationTest.migrationsApplied` asserts 11 migrations — V12 breaks the full suite; bump to 12 + add V12 CHECK parity test (`20.1-DB-001` pattern)
- [x] [Review][Patch] Delete dead duplicate port `KpiSourceDataPort.java` (no impl, no consumers; `KpiSourceDataReader` is the live port)
- [x] [Review][Patch] Delete unused `with*` withers on `KpiTargetEntity` (dead code; bypass injected Clock)
- [x] [Review][Patch] Delete speculative unreferenced repository finders (`findByMonthBetweenAndPlantIdIn` ×5, `findByMonthBetweenAndMachineGroupIds`, `findByPlantIdOrderByMonthDesc`, `findByPlantIdInOrderByPlantIdAscMonthDesc`, `findAllByOrderByPlantIdAscMonthDesc`) — 20-2 adds what it needs
- [x] [Review][Patch] `KpiRefreshGate.tryStart` race + stuck-RUNNING: pessimistic lock on the log row + reclaim RUNNING older than a timeout
- [x] [Review][Patch] MTBF/TECHNICIAN upsert-only leaves orphan rows after data corrections — delete month rows absent from the computed set
- [x] [Review][Patch] `refreshTechnician` mis-attributes multi-plant technicians — key by (plantId, technicianId)
- [x] [Review][Patch] PM completion numerator/denominator window mismatch (completedAt vs scheduledDate) — attribute completed by the WO's scheduled month
- [x] [Review][Patch] MAR can go negative — clamp ≥ 0
- [x] [Review][Patch] PM completion rate can exceed 100 — clamp ≤ 100
- [x] [Review][Patch] Actual-working MTTR can exceed wall-clock — clamp working ≤ wall per interval
- [x] [Review][Patch] `findClosedBreakdownRepairLogIntervals` accepts endTime < startTime — add `l.endTime > l.startTime`
- [x] [Review][Patch] `KpiSourceStatus.PARTIAL` never produced — remove (no CHECK constraint; safe)
- [x] [Review][Patch] `UpsertTargetRequest` has no range validation — `@PositiveOrZero` / `@DecimalMin(0)` `@DecimalMax(100)` on percents
- [x] [Review][Patch] `MethodArgumentNotValidException` bypasses the stable envelope — add handler with fieldErrors
- [x] [Review][Patch] Concurrent target upsert races the unique constraint → 500 — locked read + `TargetConflictException` → 409 envelope
- [x] [Review][Patch] PUT replaces all fields — null request fields should keep existing values (partial update)
- [x] [Review][Patch] Refresh-log message exposes raw exception text via API — store class simple-name only; full detail stays in server logs
- [x] [Review][Patch] Integration test asserts global `findByMonth(...).hasSize(1)` on the shared reused container — scope to the test's own plant/machine ids
- [x] [Review][Patch] Sync-invalidation cooldown silently drops the burst tail — schedule a trailing sweep after remaining cooldown; run sweep off the committing thread (single-thread executor); pin cron `zone = "UTC"`
- [x] [Review][Patch] Missing tests: `WorkorderImportServiceTest` verify `WorkorderSyncedEvent` published; `KpiTargetServiceTest` (role gate, audit, partial update); gate stale-RUNNING reclaim test; `woStopAt` updatedAt-fallback integration case (history-less CLOSED WO)

**Defer (pre-existing / out of scope):**
- [x] [Review][Defer] MAR telemetry availability source not wired — deferred, needs InfluxDB availability design (DW)
- [x] [Review][Defer] Dashboard (14-2) still computes live — deferred to story 20-2 by design
- [x] [Review][Defer] Source reads unbounded (full-history scan per sweep) — deferred, perf windowing (DW)
- [x] [Review][Defer] Backdated sync corrections outside lookback window never re-materialized — deferred (DW)
- [x] [Review][Defer] kpi→maintenance event coupling — deferred, matches existing cross-module event pattern (DW)

**Dismissed (noise/false-positive):** team-id conflation (activeTeamIds already resolved to group ids at derive time); empty-IN guard (adapter guards); null technicianId (schema NOT NULL); diff duplication (review artifact, not code).

## Spec Change Log

## Review Triage Log

- Pass 1 (2026-09-04): layers blind-hunter + edge-case-hunter + verification-gap + acceptance-auditor. Triage: 3 decision (all resolved unattended with rationale), 21 patch (all applied), 5 defer (DW-160..164), ~16 dismissed (dedup + false positives: team-id conflation, empty-IN, null technicianId, diff duplication). Verification: `mvn test -Dtest=*Kpi*,WorkorderImportServiceTest,V1BaseSchemaMigrationTest` → 72 tests green.

## Design Notes

- Materialization runs in a transactional job; each KPI type gets its own refresh_key (e.g., `mtbf:2026-08`, `mttr:2026-08`) for the refresh_log
- Refresh_log gating: RUNNING blocks concurrent refresh for same key; SUCCESS/FAILED with message allows retry
- Source data queries: WorkOrderEntity (status=CLOSED, category=BREAKDOWN), WorkLogEntity (start/end non-null), WorkLogRatingEntity (dimension scores), PmExecutionEntity (status=COMPLETED)
- MTBF: For each machine, find consecutive CLOSED breakdown WOs ordered by woStopAt; avg(days_between) per month
- MTTR: Sum of (end_time - start_time) per WO in minutes; wall_clock = calendar minutes, actual_working = business minutes per plant calendar (future: shift-aware)
- MAR: From telemetry ingest state (planned_available_minutes, downtime_minutes); if ingest missing for month → source_status=INSUFFICIENT_DATA
- PM completion: From PmExecutionEntity; completed_count/planned_count per plant/month; source_status from execution data availability
- Technician: Average of work_log_ratings (1–5) per technician/month; total_wo = distinct work_order_id in work_logs; first_time_fix_rate = WOs with single work_log / total_wo
- Scope: KpiScopeService mirrors org module's `OrgScopeResolver` — plantIds, machineGroupIds, activeTeamIds from principal
- Targets: kpi_target holds monthly_breakdown_target, mtbf_target_days, mttr_target_minutes, oee_quality_percent, oee_performance_percent; created_by for audit

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test-compile` -- expected: BUILD SUCCESS
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=KpiEntityConventionIntegrationTest"` -- expected: all KPI entity round-trips green
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=KpiMaterializationServiceTest,KpiQueryServiceTest"` -- expected: unit tests green
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=*Kpi*"` -- expected: all KPI tests green

**Manual checks (if no CLI):**
- Dashboard API returns materialized rows with target join for in-scope plants/machines
- Insufficient-data state renders as "Data unavailable" not "0" in UI
- Refresh_log shows RUNNING→SUCCESS with traceId for each monthly run