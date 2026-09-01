# Epic 17 Context: Workorder Execution Maturation

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Workorder execution is matured on the syncro Prisma blueprint's modules B+C: multi-technician work assignments with full assignment audit, per-assignment work logs supporting backdate and stopped-reason capture, the WO-YYMMXXXX (no-dash) ID format with a compact 6-status lifecycle, per-work-log ratings against configurable criteria, workorder-level quality ratings spanning multiple technicians, and a frontend Assign & Work dialog that mirrors the reference repair timeline.

## Stories

- Story 17.1: Multi-Technician Work Assignments
- Story 17.2: Work Logs per Assignment
- Story 17.3: WO ID Format & 6-Status Lifecycle
- Story 17.4: Work Log Ratings
- Story 17.5: Workorder Quality Rating Multi-Technician
- Story 17.6: Frontend Assign & Work Dialog

## Requirements & Constraints

- Assignments are multi-technician via `work_assignments` (work_order_id, technician_id, assigned_by, assigned_at, dropped_at, is_active); `work_orders.assigned_technician_id` remains the lead technician. (FR-113)
- First assignment transitions OPEN to ASSIGNED; additional assignments do not re-transition. Dropping marks `is_active=false` + `dropped_at`. (FR-113)
- Canonical 6-status lifecycle: OPEN, IN_PROGRESS, PENDING_SPAREPART, PENDING_REVIEW, CLOSED; CANCELLED only from OPEN or IN_PROGRESS. Invalid transitions return `INVALID_STATE_TRANSITION`. (FR-114)
- Work logs are recorded per work assignment with start/end (backdate allowed, minimum = workorder's created_at), a `stopped_reason` enum, and `activity_note`/`completion_note`. Cumulative duration feeds MTTR. (FR-115, AD-18)
- A section leader cannot be the executing technician on a workorder in their own group. (AD-14)
- Per-work-log ratings: configurable criteria (1--5 score), bound to work log and technician, immutable after submission. (FR-121)
- Workorder-level quality ratings by PRODUCTION_LEADER after closure, spanning all technicians via pivot tables, one rating per workorder, immutable. (FR-124)
- Execution is backend-owned: MTTR derivation, state machine, status history, audit. Frontend renders only. (NFR-P2-2)
- All new schema follows the fresh V1.. migration set (schema reset). (NFR-P2-3)
- Rating criteria and categories are data (not code), configured by SUPER_ADMIN. (AD-14)

## Technical Decisions

- **Module**: `com.syncro.maintenance.workorder` package with `api/application/domain/infrastructure` layers.
- **Workorder ID**: VARCHAR(50) PK, format `WO-YYMMXXXX` (no dash), generated as `WO-%s%05d` under transaction + row lock for uniqueness.
- **Source enum**: Only `EXTERNAL` or `INTERNAL` (no WHATSAPP/WEB/SYSTEM).
- **Tables**: `work_assignments` (uq: work_order_id + technician_id + assigned_at), `work_logs` (per assignment, FK to work_order + user), `work_log_rating_criteria` + `work_log_rating_criterion_categories` (link to WOCategory), `work_log_ratings` (uq: work_log_id + criterion_id), `work_order_quality_ratings` (uq: work_order_id), `work_order_quality_rating_technicians` (pivot, uq: quality_rating_id + technician_id), `work_order_quality_rating_scores` (uq: quality_rating_id + criterion_id).
- **Existing columns**: `work_order_categories` gains `plant_id` (nullable, global when null) and `requires_rating` boolean. `work_orders.assigned_technician_id` retained as lead technician.
- **Status transitions** write `work_order_status_history` row + audit; CANCELLED only from OPEN or IN_PROGRESS.
- **PENDING_SPAREPART** is auto-set when an open sparepart request is not READY, and auto-resumed when the part becomes READY.

## UX & Interaction Patterns

- Table-first workorder hub: the workorder table is the primary view, with an Actions column leading to a dialog.
- Non-native shadcn/Radix selects and DateTimePickers for the Assign & Work dialog.
- Multi-technician selection + work log per assignment with backdate enforcement (DateTimePicker min = workorder's created_at).
- Each work log requires an activity note; optional stopped reason and completion note.
- All dialog state is rendered from backend data -- no client-side status invention.
- Format ID displays as `WO-YYMMXXXX`.
- Kanban view retained with filter + virtualization.

## Cross-Story Dependencies

- Requires Epic 15 (ORM Foundation & Schema Reset) for the fresh Flyway migration set and module package scaffold.
- Requires Epic 16 (Org & Identity Maturation) for departments, job titles, system roles, machine areas, and user bindings that underpin technician identity, role-based access, and section-leader scope derivation.
- Within the epic: 17-1 (assignments) and 17-3 (WO lifecycle) are foundational -- 17-2 (work logs) depends on 17-1; 17-4 (work log ratings) depends on 17-2; 17-5 (quality ratings) depends on 17-3; 17-6 (frontend dialog) depends on all backend stories.