---
title: 'Preventive Programs & Schedules'
type: 'feature'
created: '2026-08-27'
baseline_revision: 205f4ec
final_revision: TODO
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-11-context.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** There is no preventive-maintenance capability — routine monthly/annual maintenance cannot be defined per machine or tracked on a calendar, so it never runs unless someone remembers, and the Epic 14 preventive dashboard (FR-172) has no schedule data source (FR-130, FR-131).

**Approach:** Add a `preventive_programs` table (per-machine program with a mechanical/electrical category and a MONTHLY/ANNUAL schedule type, scoped to the machine's group) and a `preventive_schedules` table (materialized due instances with a floating interval that rolls forward from completion). Schedules generate on a calendar anchor computed from the server clock (works without telemetry, AD-12), and a calendar read returns due/overdue items derived server-side. The completion/roll-forward mechanism is exposed as a service method for story 11-2.

## Boundaries & Constraints

**Always:**
- **V54** (additive, on V53):
  - `preventive_programs` — `id UUID PK DEFAULT gen_random_uuid()`, `machine_id UUID NOT NULL REFERENCES machines(id)`, `category VARCHAR(20) NOT NULL CHECK (category IN ('MECHANICAL','ELECTRICAL'))`, `schedule_type VARCHAR(10) NOT NULL CHECK (schedule_type IN ('MONTHLY','ANNUAL'))`, `day_of_month SMALLINT NOT NULL CHECK (day_of_month BETWEEN 1 AND 31)`, `month_of_year SMALLINT CHECK (month_of_year IS NULL OR month_of_year BETWEEN 1 AND 12)`, `title VARCHAR(200) NOT NULL`, `description TEXT`, `active BOOLEAN NOT NULL DEFAULT TRUE`, `created_by UUID NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. Index `idx_preventive_programs_machine` on `machine_id`. (FR-130)
  - `preventive_schedules` — `id UUID PK DEFAULT gen_random_uuid()`, `program_id UUID NOT NULL REFERENCES preventive_programs(id) ON DELETE CASCADE`, `machine_id UUID NOT NULL`, `due_date DATE NOT NULL`, `status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','IN_PROGRESS','PERFORMED','SKIPPED'))`, `completed_at TIMESTAMPTZ`, `performed_by UUID`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. Unique `uq_preventive_schedules_period (program_id, due_date)`. Index `idx_preventive_schedules_status_due` on `(status, due_date)`, `idx_preventive_schedules_machine` on `machine_id`. (FR-131)
  - Audit: drop/re-add `ck_audit_log_entity_type` adding `'PREVENTIVE_PROGRAM'` and `'PREVENTIVE_SCHEDULE'` (V52/V53 pattern, list all prior types).
- **Program CRUD** (`/api/v1/preventive-programs`): `POST` create, `GET` list (scope-filtered), `GET /{id}`, `PUT /{id}` (title/description/active/anchor fields), `DELETE /{id}` (hard delete, cascades schedules). `POST /{id}/generate` regenerates the schedule window idempotently. Gate: SUPER_ADMIN exempt; otherwise in-scope leader (group in derived scope, reusing the 10-7 `isInScopeLeader` semantics) OR STAFF_MAINTENANCE with plant access to the machine's plant. `month_of_year` is required (non-null) when `schedule_type=ANNUAL`, must be null when `MONTHLY`; `day_of_month` clamped to the target month's day count at generation time.
- **Schedule generation (FR-131, AD-12):** On program create and on `POST /{id}/generate`, materialize `SCHEDULED` instances for the next 12 months of anchor dates (MONTHLY = every `day_of_month`; ANNUAL = `month_of_year`/`day_of_month` yearly). Insert is `ON CONFLICT (program_id, due_date) DO NOTHING` — idempotent per schedule period. Anchors computed from the server clock (`LocalDate.now(clock)`); never the client clock. The machine's shift config is resolved via `ShiftConfigService.resolveByMachineId(machineId)` and its source/shifts are surfaced on the schedule view for the operator's calendar context (AD-12 "calendar/shift basis"); a `NONE` shift source does NOT block generation (preventive works without telemetry or shift config).
- **Roll-forward (floating interval):** expose `PreventiveProgramService.rollForwardNext(programId, completedAt)` that computes the first anchor strictly after the completion date and materializes a `SCHEDULED` instance for it (idempotent). Story 11-2's completion flow calls this. It is unit-tested in 11-1.
- **Calendar read** (`GET /api/v1/preventive-schedules`): scope-filtered schedules (plant OR machine-group in derived scope, SUPER_ADMIN unrestricted) ordered by `due_date`. Each view carries a server-derived `status` (the stored status, or `OVERDUE` when stored `SCHEDULED` and `due_date < today`), the machine's shift source/shifts, and `today` from the server clock. Any authenticated user may read.
- **Rego:** `preventive_program_paths := {"/api/v1/preventive-programs", "/api/v1/preventive-programs/*", "/api/v1/preventive-programs/*/generate"}` — mutation allow set `{MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE}`; `preventive_schedule_paths := {"/api/v1/preventive-schedules"}` — reads flow through generic `read_allowed`. Service gates are authoritative for scope.
- **Audit:** program create/update/delete → `PREVENTIVE_PROGRAM` CREATE/UPDATE/DELETE with `entityLabel = title`, plantId from the machine. Schedule generation (on create/generate/roll-forward) → `PREVENTIVE_SCHEDULE` CREATE with `entityLabel = "due " + dueDate`, plantId from the machine.
- **Errors:** unknown machine → 404 `MACHINE_NOT_FOUND`; unknown program → 404 `PROGRAM_NOT_FOUND`; access → 403 `FORBIDDEN`; validation → 400 `VALIDATION_ERROR` with fieldErrors (category/scheduleType enum, dayOfMonth range, monthOfYear required-for-ANNUAL / forbidden-for-MONTHLY, title blank/length).
- **Frontend:** route `/preventive` — lists the user's programs (create form for STAFF/leaders) and a due/overdue schedule list from `GET /api/v1/preventive-schedules`. Loading/empty/error states required. Overdue must be visibly distinct in a non-color-only way (badge + text). Uses existing shadcn components and the `syncroFetch` client — no new dependencies. The schedule list is a simple row list in v1 (not a data grid — TanStack adoption deferred, see Design Notes).

**Block If:** nothing.

**Never:**
- Never touch V47-V53 or add V55 — V54 is the only migration for 11.1.
- Never require telemetry for generation or due/overdue (AD-12) — schedules work for machines with no MQTT.
- Never add daily/weekly/hourly schedule types — MONTHLY/ANNUAL only (FR-130 assumption).
- Never compute due/overdue from the client clock — always `Instant.now(clock)`/`LocalDate.now(clock)`.
- Never roll forward from the original anchor — the floating interval is from completion (AD-12).
- Never create workorders or reports in 11.1 — that is 11.3. No checklist/evidence/signature — that is 11.2.
- Never add new Spring dependencies or a new frontend table library.
- Never let `generate` create duplicate schedule rows for the same `(program_id, due_date)` — unique constraint is the backstop.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| PROGRAM_CREATE_OK | staff/leader, valid body | 201 `PreventiveProgramView`; program + 12mo schedule window persisted; audit CREATE | — |
| PROGRAM_CREATE_BAD_CATEGORY | category not MECHANICAL/ELECTRICAL | 400 VALIDATION_ERROR fieldErrors.category | — |
| PROGRAM_CREATE_BAD_TYPE | scheduleType not MONTHLY/ANNUAL | 400 VALIDATION_ERROR fieldErrors.scheduleType | — |
| PROGRAM_CREATE_ANNUAL_NO_MONTH | ANNUAL without monthOfYear | 400 VALIDATION_ERROR fieldErrors.monthOfYear | — |
| PROGRAM_CREATE_MONTHLY_WITH_MONTH | MONTHLY with monthOfYear | 400 VALIDATION_ERROR fieldErrors.monthOfYear | — |
| PROGRAM_CREATE_FORBIDDEN | user without group/plant access | 403 FORBIDDEN | — |
| PROGRAM_CREATE_UNKNOWN_MACHINE | machineId not found | 404 MACHINE_NOT_FOUND | — |
| PROGRAM_UPDATE_OK | in-scope, valid body | 200 view; schedules regenerated for changed anchors | — |
| PROGRAM_DELETE_OK | in-scope | 204; program + schedules cascade-deleted | — |
| GENERATE_IDEMPOTENT | generate twice on same program | same window, no duplicate rows (unique per period) | — |
| SCHED_READ_OK | any authenticated user | 200 `PreventiveScheduleView[]` scope-filtered, by dueDate | — |
| SCHED_OVERDUE | stored SCHEDULED, due_date < today | view.status = OVERDUE (derived) | — |
| ROLL_FORWARD_OK | completion date given | next anchor after completion materialized once | — |
| ROLL_FORWARD_DUPLICATE | anchor already materialized | no duplicate (ON CONFLICT DO NOTHING) | — |

</intent-contract>

## Code Map

**Migration:**
- `resources/db/migration/V54__preventive_programs_schedules.sql` -- NEW -- 2 tables + audit entity_type extension.

**Domain:**
- `com/syncro/maintenance/preventive/domain/PreventiveCategory.java` -- NEW -- enum MECHANICAL, ELECTRICAL.
- `com/syncro/maintenance/preventive/domain/ScheduleType.java` -- NEW -- enum MONTHLY, ANNUAL.
- `com/syncro/maintenance/preventive/domain/ScheduleStatus.java` -- NEW -- enum SCHEDULED, IN_PROGRESS, PERFORMED, SKIPPED.
- `com/syncro/maintenance/preventive/domain/PreventiveProgram.java` -- NEW -- record (id, machineId, category, scheduleType, dayOfMonth, monthOfYear, title, description, active, createdBy, createdAt, updatedAt).
- `com/syncro/maintenance/preventive/domain/PreventiveSchedule.java` -- NEW -- record (id, programId, machineId, dueDate, status, completedAt, performedBy, createdAt, updatedAt).
- `com/syncro/audit/domain/AuditEntityType.java` -- MODIFY -- + PREVENTIVE_PROGRAM, PREVENTIVE_SCHEDULE.

**Persistence:**
- `com/syncro/maintenance/preventive/infrastructure/db/PreventiveProgramEntity.java` / `PreventiveProgramRepository.java` -- NEW -- CRUD + findByIdWithMachine (join fetch plant/group).
- `com/syncro/maintenance/preventive/infrastructure/db/PreventiveScheduleEntity.java` / `PreventiveScheduleRepository.java` -- NEW -- findByProgramId, findScope (status/due), existsByProgramIdAndDueDate, window query.
- `com/syncro/maintenance/preventive/infrastructure/db/PreventiveScheduleRow.java` -- NEW -- flat row (schedule + program + machine plant/group + category) for the calendar read.

**Application:**
- `com/syncro/maintenance/preventive/application/PreventiveProgramService.java` -- NEW -- create/list/get/update/delete/generate/rollForwardNext + gates + audit + anchor math.
- `com/syncro/maintenance/preventive/application/PreventiveScheduleService.java` -- NEW -- calendar read (scope-filtered, derived OVERDUE, shift context).
- `com/syncro/maintenance/preventive/application/PreventiveMapper.java` -- NEW -- entity ↔ domain ↔ view.

**API:**
- `com/syncro/maintenance/preventive/api/PreventiveProgramController.java` -- NEW -- program CRUD + generate.
- `com/syncro/maintenance/preventive/api/PreventiveScheduleController.java` -- NEW -- GET /api/v1/preventive-schedules.
- `com/syncro/maintenance/preventive/api/PreventiveDtos.java` -- NEW -- request/view DTOs.
- `com/syncro/maintenance/preventive/api/PreventiveExceptionHandler.java` -- NEW -- PREVENTIVE_* / VALIDATION_ERROR / FORBIDDEN / not-found mapping.

**Enforcement:**
- `syncro/authz/policy/authz.rego` -- MODIFY -- preventive_program_paths + preventive_schedule_paths.
- `syncro/authz/policy/authz_test.rego` -- MODIFY -- parity cases.
- `syncro/.env.example` -- MODIFY -- preventive paths in enforced-paths.

**Frontend:**
- `src/app/preventive/page.tsx` -- NEW -- preventive page (Server Component wrapper).
- `src/features/preventive/types.ts` -- NEW -- program/schedule contract types.
- `src/features/preventive/hooks/use-preventive.ts` -- NEW -- programs + schedules fetch/create hooks.
- `src/features/preventive/components/preventive-programs-panel.tsx` -- NEW -- client: programs list + create form.
- `src/features/preventive/components/preventive-schedule-list.tsx` -- NEW -- client: due/overdue schedule rows with shift context.

**Tests:**
- `com/syncro/maintenance/preventive/application/PreventiveProgramServiceTest.java` -- NEW -- CRUD + gates + anchor math + roll-forward.
- `com/syncro/maintenance/preventive/api/PreventiveControllerTest.java` -- NEW -- endpoint shapes + error mapping.
- `com/syncro/db/PreventiveMigrationTest.java` -- NEW -- V54 tables/constraints/seed (Testcontainers).

## Tasks & Acceptance

**Execution:**
- [x] `resources/db/migration/V54__preventive_programs_schedules.sql` -- 2 tables + audit extension.
- [x] Domain enums + records + `AuditEntityType` -- preventive types.
- [x] Entities + repositories + `PreventiveScheduleRow` -- persistence.
- [x] `PreventiveProgramService` -- CRUD + generate + rollForwardNext + gates + audit + anchor math.
- [x] `PreventiveScheduleService` + `PreventiveMapper` -- calendar read + derived OVERDUE + shift context.
- [x] Controllers + DTOs + exception handler -- endpoints + PREVENTIVE_* codes.
- [x] `authz.rego` + `authz_test.rego` + `.env.example` -- preventive paths + parity.
- [x] Frontend: `/preventive` page + programs panel + schedule list + hooks.
- [x] Tests (service + controller + migration).

**Acceptance Criteria:**
- Given an authorized user creates a preventive program for a machine, when submitted, then the program stores category (MECHANICAL/ELECTRICAL) and schedule type limited to MONTHLY/ANNUAL, and it is scoped to the machine's group; a non-authorized user is rejected server-side. [FR-130]
- Given an active program, when schedules are due, then SCHEDULED instances are generated on the calendar anchor from the server clock and work without telemetry; the next due date rolls forward from completion (floating interval); a calendar read lists due/overdue items computed from the server clock with OVERDUE visibly distinct. [FR-131, AD-12]
- Given a schedule is generated twice for the same program period, then no duplicate row is created (unique constraint).
- Given a program is updated or deleted, then anchors regenerate the affected window or the program/schedules are removed; all mutations audit-logged.
- Given OPA enforcement, then preventive-program mutations are default-deny with the four-role allow set and schedule reads are any-authenticated, with parity tests. [FR-160]

## Design Notes

- **Anchor math is the core.** `PreventiveProgramService` owns a small pure helper: `nextAnchor(program, fromDate)` returns the first anchor date on/after `fromDate` (MONTHLY: `(y, m, min(dayOfMonth, daysInMonth))` advancing month by month; ANNUAL: `(y, monthOfYear, min(dayOfMonth, daysInMonth))` advancing year by year). Generation materializes 12 months of anchors from today; `rollForwardNext` uses the same helper with `fromDate = completion date` so the interval floats from completion, not the original anchor. This helper is the main unit-test surface (clamping Feb 29/30/31, year rollover, MONTHLY vs ANNUAL).
- **Overdue is derived, not stored.** `status` column holds the lifecycle (SCHEDULED/IN_PROGRESS/PERFORMED/SKIPPED); `OVERDUE` is computed in the read view when `status=SCHEDULED && due_date < today`. Frontend branches on the stable `status` code and shows an explicit badge+text, never color alone.
- **Shift config is context, not a gate.** `ShiftConfigService.resolveByMachineId` is consulted for the schedule view (source + shift windows, per AD-12 "calendar/shift basis") but `NONE` does not block generation — preventive runs for machines with no shift config and no telemetry.
- **`ON CONFLICT (program_id, due_date) DO NOTHING`** makes generation idempotent; the unique constraint is the backstop for concurrent generates.
- **TanStack deferral:** the installed `@tanstack/react-table` is v8 while the planning addendum references v9 (NFR-P2-10). The 11.1 schedule view is a simple row list, so no data grid is introduced; TanStack adoption for a real grid is deferred to a later preventive story when the v8/v9 question is settled.
- **Module placement:** `com.syncro.maintenance.preventive` (sub-package) because programs/schedules reference machines and reuse the org scope service and shift config; the frontend is `features/preventive/` mirroring `features/workorders/`.

## Verification

**Commands:**
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=PreventiveProgramServiceTest,PreventiveControllerTest,PreventiveMigrationTest"` -- expected BUILD SUCCESS.
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrder*Test"` -- expected no regressions.
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS incl. new preventive parity cases.
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green.
- `cd syncro/apps/web && npx biome check src/features/preventive src/app/preventive` -- expected clean.
