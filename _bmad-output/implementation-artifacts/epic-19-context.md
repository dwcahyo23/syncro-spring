# Epic 19 Context: Preventive PM Execution Model

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Preventive maintenance is rebuilt on the blueprint's module F execution model: versioned PM checksheets per machine/frequency, checklist categories/items with measurement or OK/NG inputs, yearly schedules with an SPV + production approval workflow and per-date status, PM workorders generated from due schedule dates, and per-item executions with NG findings, blocking workorders, and SPV signature verification. This replaces the earlier preventive_programs/schedules design (previously Epic 11 scope, FR-130..FR-134) and is part of the 2026-08-31 ORM maturation that realigns the Spring schema with the Node/Prisma blueprint. It runs without telemetry — preventive scheduling is calendar/shift-based, floating from completion.

## Stories

- Story 19.1: PM Frequencies & Checksheets
- Story 19.2: PM Checklist Categories & Items
- Story 19.3: PM Schedules & Schedule Dates
- Story 19.4: PM Work Orders
- Story 19.5: PM Executions & Execution Items

## Requirements & Constraints

- PM must work end-to-end for machines without MQTT/telemetry; due/overdue and status-transition dates come from the server clock, never from counters. Lifetime-counter state without telemetry is "insufficient data", never a fabricated value (AD-12).
- Frequencies and schedule types are limited to monthly/annual (no daily/weekly/hourly needed now); preventive scope is the machine's section/group.
- Only approved checksheet revisions become effective; mutating a checksheet after it has been superseded by an approved revision is not allowed — history is preserved through the revision chain.
- Approval workflow for a yearly schedule is strictly sequential (DRAFT → PENDING_SPV_APPROVAL → PENDING_PRODUCTION_APPROVAL → APPROVED → ACTIVE); approvals are signed and audit-logged (FR-172 also covers schedule dashboard visibility).
- Executions: an SPV verifies with signature + timestamp, and an execution carrying unresolved NG items cannot be closed. Critical NG items are flagged and can spawn a finding/blocking workorder.
- All PM mutations are gated by OPA (role >= STAFF_MAINTENANCE to define checksheets/items, leader for approvals/generation, per story FRs) and audit-logged with actor; criticality escalation and workflow transitions are deterministic server-side rules, not UI choices.
- Generation of a PM workorder from a due schedule date is idempotent per schedule period (one workorder per period).
- Entity ↔ migration consistency binds via `ddl-auto=validate`.

## Technical Decisions

- Schema is a fresh, from-scratch Flyway V1.. migration set (AD-22): DB may be reset and re-seeded at will in development; this epic's stories each begin with the redesign migration running (DB reset + reseed). Environments holding real data are excluded from reset; after baseline, additive migrations only.
- New JPA entities follow the blueprint module F (orm-target-blueprint-2026-08-31.md F1–F8): `pm_frequencies`, `pm_checksheets`, `active_checksheets`, `pm_checklist_categories`, `pm_checklist_items`, `pm_schedules`, `pm_schedule_dates`, `pm_work_orders`, `pm_executions`, `pm_execution_items`, owned by the `maintenance.preventive` module (AD-19, AD-12).
- Mapping conventions: `snake_case` plural table names; UUID PKs; enums as `@Enumerated(STRING)` with CHECK constraints; `Instant`/TIMESTAMPTZ; JSONB for `warnings`; unique/index constraints named `uq_<table>_<cols>` / `idx_<table>_<cols>`. Entity package `com.syncro.<context>.infrastructure.db`.
- Checksheet revisioning: `pm_checksheets` has a self-FK `supersedes`, unique (machine_id, frequency_id, revision_no), with approval actor/timestamp and effective_date; `active_checksheets` is a composite-PK (machine_id, frequency_id) pointer to the current active revision (F2, F3).
- Schedule snapshotting: `pm_schedules` references checksheet_id + revision and denormalizes frequency_code/name, unique (plant_id, machine_id, checksheet_id, year); `pm_schedule_dates` unique (schedule_id, planned_date) with SCHEDULED/EXECUTED/MISSED/RESCHEDULED status (F5).
- `pm_executions` ties 1:1 to a PM workorder (unique pm_wo_id) and a schedule date; execution items snapshot per-item category_name/parameter_text/bounds (not FK-only) so results survive checksheet revision, and link to PMChecklistItem and an optional blocking WorkOrder (F7, F8).
- Workorder execution model in the workorder module remains the standard: PM-generated workorders carry machine, template/checksheet reference + revision, assigned technician, scheduled/started/completed dates, certificate_url, and statuses SCHEDULED/ASSIGNED/IN_PROGRESS/COMPLETED/OVERDUE (F6); status transitions via assignment, execution, and server-clock overdue. AD-14 (section leader does not execute own workorders) and role taxonomy AD-15 apply to PM workorders like any other.
- A due pm_schedule generates pm_work_orders on its dates per AD-19; each execution runs against a checksheet revision and records per-item results.

## UX & Interaction Patterns

- Monthly preventive runs from a preventive calendar/dashboard: due schedule → fill checklist → upload evidence → leader/SPV signs → close. Due/overdue items surface on the preventive dashboard (FR-172), scoped by machine group.
- The completed preventive report prints via a WYSIWYG template — tabular view, configurable logo, and signature block — rendering checklist, results, signer identity, and timestamps (FR-133; a print-ready requirement, not just a screen copy).
- Check items capture either a MEASUREMENT actual value (against lsl/nominal/usl bounds) or an OK/NG outcome; NG items may carry notes and photo evidence.

## Cross-Story Dependencies

- Stories 19.1–19.5 share one schema-redesign migration (DB reset + reseed) and are sequential: checksheets (19.1) and checklist content (19.2) precede schedules (19.3), which precede workorder generation (19.4) and execution/verification (19.5). Stories must not ship against the old preventive_programs tables; the whole epic supersedes the prior Epic 11 (FR-130..FR-134) data model.
- Workorder module (Epic 10/17 model): PM workorders reuse the workorder infrastructure (categories incl. 02 Preventive, assignments, work logs, evidence). NG findings may create corrective workorders, linking back via finding_wo_id / blocking_wo_id.
- Signature support (technician/SPV signature refs + signed timestamps) and OPA subject scopes (plantIds/machineGroupIds, active team memberships) are shared services from earlier phases; role/approval separation-of-duty rules (AD-14..AD-16) constrain who may verify.
- FR-172 preventive dashboard surface (Epic 14) consumes schedule/execution state produced here (due/overdue, EXECUTED markers).
