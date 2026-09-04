# Epic 20 Context: KPI Materialization

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Monthly KPI tables materialize maintenance KPIs — MTBF, MTTR, MAR, PM completion, and technician KPIs — from workorder, work-log, and rating data, so dashboards read precomputed rows instead of computing on the fly. This keeps dashboard numbers fast and consistent as history grows, prevents divergent figures between views, and preserves KPI history across the development-phase database resets. The epic is part of the 2026-08-31 ORM maturation redesign, adopting module G of the syncro (Node/Prisma) blueprint.

## Stories

- Story 20.1: KPI Materialized Tables (redesigned 2026-08-31)
- Story 20.2: KPI Targets & Dashboard Consumption (redesigned 2026-08-31)

## Requirements & Constraints

- MTBF is computed between consecutive breakdown workorders ordered by `woStopAt` (never by id — the reference implementation had an ordering bug). MTTR is the cumulative sum of work-log durations per workorder.
- KPI dashboards must define units, window (monthly rolling), and freshness; an explicit insufficient-data state is required when data is too sparse (e.g., fewer than 2 breakdown workorders for MTBF, or a month with no materialized row) — never a fabricated value.
- Technician KPIs combine configurable-dimension ratings (1–5 stars) with objective measures (completed count, average MTTR, on-time %, first-time-fix rate), all computed backend-side.
- All KPI computation and state-machine logic is backend-owned; the frontend renders results only.
- Every KPI read is filtered by the user's organizational scope (plant / machine group / active team dimensions); leaders see their groups, managers see their plant, global roles see all.
- KPI target mutations and materialization refresh runs are audit-logged with window and status evidence.
- Recomputation must be idempotent per month/scope.

## Technical Decisions

- Materialized monthly tables (fresh Flyway `V1..` schema set, module `com.syncro.kpi`): `kpi_target`, `kpi_mtbf_monthly`, `kpi_mttr_monthly`, `kpi_mar_monthly`, `kpi_pm_completion_monthly`, `kpi_technician_monthly`, plus `kpi_monthly_breakdown` and a `kpi_aggregate_refresh_log` (unique `refresh_key`, status, message) that gates and evidences each refresh run.
- Blueprint uniqueness keys: `kpi_target` and the plant-level monthly tables are unique per `(plant_id, month)`; `kpi_mtbf_monthly` per `(machine_id, month)`; `kpi_technician_monthly` per `(plant_id, technician_id, month)`. MAR and PM-completion rows carry a `source_status`/`source_message` pair so an unavailable metric is distinguishable from a zero.
- `kpi_target` holds per-plant, per-month targets (monthly breakdown target, MTBF days, MTTR minutes, OEE quality/performance percent) with `created_by`; targets apply to materialized rows for actual-vs-target comparison.
- Refresh runs on a schedule and after external-sync mutations invalidate analytics, so synced workorder changes never leave stale KPI rows.
- Schema conventions: snake_case plural tables, UUID PKs, `Instant`/TIMESTAMPTZ UTC timestamps, named `uq_`/`idx_` constraints, `@Enumerated(STRING)` + CHECK for enums, `ddl-auto=validate`.
- Redis may cache KPI reads with explicit TTL and a stale indicator, but Redis is never the source of truth — materialized PostgreSQL rows are.

## UX & Interaction Patterns

- KPI dashboards show actual vs target with non-color-only status communication (label + value, WCAG AA baseline).
- Insufficient-data and stale states render as clear empty/unavailable states, not zeros or spinner-forever.
- Dense desktop table patterns for monthly KPI grids; responsive down to mobile per the shared admin-dashboard conventions.

## Cross-Story Dependencies

- Depends on Epic 15 (ORM foundation & schema reset) — the fresh migration set and module scaffold must land first; KPI tables are created as part of the redesigned schema.
- Depends on Epic 17 (workorder execution maturation) for `work_orders`, `work_assignments`, `work_logs`, and rating tables — the raw inputs for MTBF/MTTR/technician KPIs.
- Depends on Epic 19 (PM execution model) for schedule/execution data feeding PM-completion KPIs.
- Interacts with Epic 13 (external sync): sync mutations must trigger KPI refresh invalidation.
- Feeds Epic 14 (dashboards & reports): MTBF/MTTR and technician KPI dashboards read these materialized rows rather than computing live.
