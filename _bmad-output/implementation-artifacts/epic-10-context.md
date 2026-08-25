# Epic 10 Context: Workorder Execution

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Maintenance leaders and technicians run breakdown/preventive workorders end-to-end: create internal workorders (and coexist with synced ones), assign and delegate to technicians, drive the status lifecycle including ON_PROCUREMENT, log multiple repair sessions that feed MTTR, upload evidence and technical drawings, enforce parent-child close rules, and capture technician and maintenance-workorder ratings.

## Stories

- Story 10.1: Workorder Schema & Categories
- Story 10.2: Create & Assign Workorders
- Story 10.3: Status Lifecycle & ON_PROCUREMENT
- Story 10.4: Repair Sessions & MTTR
- Story 10.5: Evidence & Technical Drawings
- Story 10.6: Reports, CP/CPK, FMEA & Stop-Time
- Story 10.7: Todos & Kanban
- Story 10.8: Ratings

## Requirements & Constraints

- Workorders are dual-source. Internal workorders get auto-generated `WO-YYMMxxxx` ids under transaction + row lock (no duplicates under concurrency); synced workorders reuse the external `sheet_no` as id and are idempotently upserted. Synced master fields follow external values; local operational fields (report, evidence, ratings) are preserved.
- Only STAFF_MAINTENANCE or SECTION_LEADER (own group) may create internal workorders; PRODUCTION_LEADER may create only breakdown-category workorders for machines on their own production lines (server-side enforced).
- WO categories carry code + label (e.g. 01 Breakdown, 02 Preventive) with unique codes. Creating/updating categories requires section leader or above and is audit-logged; any workorder-read-scoped user can view them.
- Status transitions follow the explicit lifecycle; invalid transitions return `INVALID_STATE_TRANSITION`. Assignment is `OPEN → ASSIGNED`. A section leader cannot be assigned as executing technician on their own workorders. `DONE → CLOSED` is a leader action. Every transition (including derived ones) writes a status-history row and audit event.
- A parent workorder cannot be CLOSED while any child is not CLOSED/CANCELLED; SUPER_ADMIN/MANAGER may override with an audit-logged note.
- Multiple non-overlapping repair sessions per workorder; cumulative session duration equals workorder MTTR (backend-computed). A workorder cannot reach DONE with no completed session unless a documented reason is provided.
- SLA response time per category = time from OPEN to the first IN_PROGRESS session start; computed backend-side.
- Evidence and technical drawings accept JPEG/PNG/WebP/PDF up to a 10 MB configurable limit, stored in Garage; PostgreSQL keeps object keys only. Replacing/deleting removes the previous object.
- CP/CPK values and a CP/CPK PDF are optional on any category and never mandatory. FMEA failure-type tags are stored and appear in report and machine history. Breakdown workorders require a stop-time reason code (electric/mechanical/pneumatic/hydraulic/etc.) before DONE.
- Section leaders create and assign workorder todos; todo operations are scoped to the workorder's machine group and audit-logged. Kanban views group by backend-provided status and respect derived scope (no client-side state invention).
- After closure, the section leader rates executing technicians; the PRODUCTION_LEADER of the affected line rates the maintenance workorder. Ratings use configurable 1–5-star dimensions (dimensions are data, configured by SUPER_ADMIN), one per rater/workorder, immutable after submission.
- All new schema is additive Flyway migration V41+ with `ddl-auto=validate`. Backend owns state-machine, MTTR, and SLA calculations; frontend renders values only. Tabular views use TanStack Table v9. Terminal states (DONE/CLOSED) are protected from sync regression.
- Authorization gates all mutations via OPA (default-deny) with derived scope `{plantIds, machineGroupIds, activeTeamIds}`; section-leader mutations apply only within the leader's own machine groups.

## Technical Decisions

- Dual-source schema (AD-3): `work_orders.id` is `VARCHAR(50)` UTF-8; `source` enum `SYNCED|INTERNAL`; `parent_id` self-FK (arbitrary varchar, cross-source chains allowed); `sync_version` guards conflict resolution. Internal id generation uses a per-prefix 5-digit sequence with monthly reset under transaction + row lock.
- Idempotent creation (AD-3): create-workorder accepts an optional `idempotencyKey` header; the service dedupes within a 5-minute window; the frontend sends a UUID on creation POSTs. Parent close uses `SELECT ... FOR UPDATE` on child statuses in the same transaction.
- Explicit state machine (AD-4): `DRAFT → OPEN → ASSIGNED → IN_PROGRESS → ON_PROCUREMENT → IN_PROGRESS → DONE → CLOSED`, plus `CANCELLED` from OPEN/ASSIGNED. Derived transitions are written with `source=DERIVED, actor=SYSTEM`. `DONE → CLOSED` requires no non-terminal children (machine-readable blocking code).
- ON_PROCUREMENT derivation (AD-5): a workorder enters ON_PROCUREMENT when any live sparepart request is not READY and resumes IN_PROGRESS when all are READY — recomputed on request transition events (not a poller), guarded by a lock, writing a DERIVED status-history row. Manual leader placement is allowed only when no live request exists. (Actual request module lands in Epic 12; 10.3 builds the derivation machinery.)
- Analytics (AD-6): MTTR = cumulative repair-session durations; SLA = OPEN → first IN_PROGRESS session start; all computed backend-side from workorder data.
- Garage storage (AD-10): evidence and technical drawings go through `ObjectStorageService`; PostgreSQL stores only object keys.
- Ratings (AD-14): section leader never executes their own workorders; rating dimensions are configuration data, not code.

## Cross-Story Dependencies

- Story 10.1 (schema) is the prerequisite for every other story in this epic.
- Story 10.2 (create/assign) depends on the derived section scope from Epic 9 (org module, AD-2) for child-creation access and assignment authorization.
- Story 10.3 (ON_PROCUREMENT) depends on sparepart-request state shape from Epic 12; Epic 12 also supplies the request READY events that drive the derived recompute.
- Story 10.4 (MTTR/SLA) depends on 10.3's lifecycle (SLA measures OPEN → first IN_PROGRESS session start) and 10.1's category definition of target response time.
- Story 10.6 (reports/CP-CPK/FMEA) produces the report fields consumed by Epic 14 WYSIWYG printing; FMEA tags feed machine history.
- Story 10.8 (ratings) requires closed workorders (10.3) and feeds Epic 14 technician-KPI and maintenance-quality dashboards.
- Story 10.7 (kanban) consumes backend-provided statuses from 10.3.
