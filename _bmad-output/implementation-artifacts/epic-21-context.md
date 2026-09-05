# Epic 21 Context: IATF Compliance Module

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Establish a dedicated compliance bounded context that captures the quality records IATF audits expect: non-conformances with 8D problem-solving reports, calibration instruments and their records, equipment change notices, machine setup baselines, and lesson-learned entries. These records link back to machines and workorders so quality issues, measurement control, and change control are tracked to closure with evidence, keeping Syncro aligned with IATF requirements without scattering compliance data across maintenance tables.

## Stories

- Story 21.1: Non-Conformance & 8D Reports
- Story 21.2: Calibration & Equipment Change Control
- Story 21.3: Setup Baselines & Lesson Learned

## Requirements & Constraints

- Functional requirements are not yet numbered (requirements pass pending); the acceptance criteria in the epics file are the source of truth for scope.
- Every compliance mutation (create/update of NC, 8D, calibration, ECN, baseline, lesson) must be OPA-authorized and written to the immutable audit log.
- NC and 8D records can reference a workorder and its evidence; 8D follows the D1–D8 workflow linked one-to-one to its NC.
- Calibration must surface overdue and upcoming instruments within the user's operational scope.
- ECNs are approval-tracked (submit → review → approve → execute → close) and linked to machine/workorder evidence.
- Setup baselines and lessons learned must be searchable within scope; lessons tie to NC/8D/workorder events with evidence.
- NFR: backend owns all computation and state logic; the frontend renders values only (no client-side derivation of compliance status).

## Technical Decisions

- New `compliance` bounded context (package `com.syncro.compliance`) per AD-21; it does not reuse maintenance tables for compliance data.
- Schema follows the ORM target blueprint module H, created by the fresh Flyway `V1..` migration set under the development-phase reset (AD-22 — DB reset + reseed, no additive patches). Tables: `non_conformances`, `eight_d_reports`, `calibration_instruments`, `calibration_records`, `equipment_change_notices`, `machine_setup_baselines`, `lesson_learned` (plus `historical_machine_records` in the blueprint, not yet story-scoped).
- Naming conventions: snake_case plural tables/columns, `{singular}_id` FKs, `idx_<table>_<cols>` / `uq_<table>_<cols>` constraints declared in migration.
- Entity mapping conventions: UUID `@Id` (VARCHAR PK only where an existing pattern demands it); cross-aggregate references (workorder, machine) stored as plain UUID columns, not JPA relations, per the AD-3/AD-4 cross-aggregate stance; enums as `@Enumerated(EnumType.STRING)` with uppercase CHECK constraints; timestamps as `Instant`/`TIMESTAMPTZ` (UTC); JSON fields (8D `d1_team`/`d4_root_cause`, baseline `parameters`, lesson `spareparts_used`) and string arrays (lesson `tags`) as JSONB via `@JdbcTypeCode(SqlTypes.JSON)`.
- Key status enums: NC `OPEN/IN_PROGRESS/CLOSED/VERIFIED`; 8D `DRAFT/IN_PROGRESS/CLOSED/EFFECTIVE/INEFFECTIVE`; calibration `VALID/EXPIRING_SOON/EXPIRED`; ECN `DRAFT/UNDER_REVIEW/APPROVED/EXECUTED/CLOSED`.
- Uniqueness: `nc_number`, `report_number`, `instrument_code`, `ecn_number` are unique; `eight_d_reports.nc_id` is unique (1:1 with NC); `machine_setup_baselines` is unique on `(machine_id, version)` with an `is_active` flag; `lesson_learned.project_id` is unique.
- Evidence artifacts (8D `pdf_artifact_url`, calibration `certificate_url`, ECN before/after photos) live in Garage S3; PostgreSQL stores only object keys (AD-10).
- Authorization is OPA default-deny enforced server-side (AD-1); row-level scope comes from the single `org` scope service `{plantIds, machineGroupIds, activeTeamIds}` (AD-2) — compliance queries must consume it, never recompute.

## Cross-Story Dependencies

- All three stories depend on the schema redesign migration (DB reset + reseed) that creates the compliance tables; Story 21.1 is the natural first to land the shared `compliance` schema, and 21.2/21.3 assume those tables already exist.
- NC/8D and lesson-learned records reference workorders and machines owned by the maintenance and master-data modules — those aggregates must exist (they are prerequisites from earlier epics), and links are by UUID, not cross-context JPA relations.
- ECN links to machine and to an executed workorder (`executed_wo_id`); setup baselines optionally reference an ECN (`ecn_id`), so 21.3's baselines depend on 21.2's ECN table being present.
- Lesson-learned entries tie to NC/8D/workorder events, so 21.3 depends on 21.1's NC/8D records existing.
