---
title: Syncro Maintenance Workorder, Preventive & OPA Authorization
created: 2026-08-24
updated: 2026-08-24
status: final
---

# PRD: Syncro Maintenance Workorder, Preventive & OPA Authorization

## 0. Document Purpose

This PRD defines the maintenance execution module for **Syncro** — an internal manufacturing maintenance platform (Java 25 / Spring Boot 4 backend, Next.js 16 frontend). It covers workorder management (dual-source: synced from the internal system + internally created), preventive maintenance, sparepart request & inventory, a hardened external-data sync module, an org structure (sections/teams), and OPA-driven authorization. It builds on the finalized Syncro Phase 1 PRD (`_bmad-output/planning-artifacts/prds/prd-Syncro-2026-05-22/prd.md`, Epics 1–8) and the brainstorm intent (`_bmad-output/brainstorming/brainstorm-org-rbac-opa-2026-08-24/brainstorm-intent.md`). Audience: product owner (Yusuf), backend/frontend implementers, and downstream workflow owners (`bmad-architecture`, `bmad-create-epics-and-stories`). Vocabulary is anchored by the Glossary (§3); functional requirements are globally numbered FR-100+ to avoid collision with the Phase 1 PRD (FR-001…FR-089).

## 1. Vision

Syncro is the single place the maintenance team runs and tracks every repair, preventive check, and sparepart purchase across plants. Today the platform already knows every machine, who is responsible for it, how long its spareparts last, and how to reach people on WhatsApp. What it does not yet do is *work*: capture a breakdown workorder, follow it from report to repair to handover, plan monthly and annual preventive checks, request the exact part, and know the part arrived.

This module makes maintenance execution operational without waiting for IoT. A machine that has no MQTT telemetry yet can still be repaired and maintained — workorders carry their own timestamps, repair sessions, evidence photos, and sparepart requests, and preventive checks run on the calendar, not on counters. For the machines that do stream telemetry, the module reuses existing lifetime and counter data to enrich the picture.

Authorization is the backbone, not an afterthought. Access follows the real organization: a section leader governs only the machine groups they are responsible for, the maintenance leader governs their plant, and the manager governs all plants. Roles, departments, sections, and cross-plant teams are enforced by Open Policy Agent (OPA) on every request server-side, and the frontend only renders what the policy allows. Policy changes deploy without redeploying the backend.

Why it matters: without it, every repair is a paper trail and every part request a guess. With it, the team gets a durable, auditable, policy-governed record of what broke, what was done, what it cost, and what to watch next — the raw material for MTBF/MTTR, FMEA tagging, and later budgeting.

## 2. Target User

### 2.1 Jobs To Be Done

- **Maintenance Manager** — run the whole maintenance organization across plants and see whether the team is fixing things before they fail. *Hire Syncro to sleep at night knowing every open breakdown has an owner.*
- **Maintenance Leader (plant)** — know what is broken in my plant and who is working it. *Hire it to never be surprised by an unassigned breakdown.*
- **Section Leader (machine group)** — govern my machine groups, delegate repairs to technicians, approve part requests, and sign off closed work. *Hire it to delegate work and keep accountability without doing the repair myself.*
- **Technician** — get my assigned jobs, log what I did, upload before/after evidence, and request the part I need. *Hire it to be told exactly what to do next and to prove it was done.*
- **Inventory Maintenance / Storekeeper** — see what parts are requested, replenish stock, chase procurement (MRE), and mark parts ready. *Hire it to know what to order and when to stop chasing spreadsheets.*
- **Production Leader** — raise a breakdown, get a WhatsApp ack when repair exceeds four hours, and rate the maintenance workorder. *Hire it to know the line will be back and that the maintenance crew performed.*
- **Auditor** — see who changed what and which policy decision allowed or denied each action. *Hire it to trace accountability without touching a terminal.*

### 2.2 Non-Users (v1)

- Production operators entering OEE/counter data — production dashboards (OEE, stop-time analytics) are deferred; production leaders only request and rate.
- External procurement staff — the internal system has no API; the purchase request is recorded (MRE code) but fulfilled outside Syncro.
- Suppliers / external vendors — no external login.

### 2.3 Key User Journeys

- **UJ-1. Eko (Section Leader) runs a breakdown to completion.**
  - *Persona + context:* Pak Eko, section leader responsible for machine groups Forming and Rolling; already authenticated.
  - *Entry state:* Production leader reported a breakdown; the synced workorder appears in his section workorder list.
  - *Path:* opens the workorder → assigns a technician → technician logs repair sessions and requests a part → Eko approves the request → storekeeper marks ready → technician finishes → Eko reviews the report and closes.
  - *Climax:* the workorder moves to CLOSED with a complete report and evidence; MTTR recorded.
  - *Resolution:* technician's rating is recorded; the workorder contributes to MTBF/MTTR dashboards.
  - *Edge case:* the part is not in stock — Eko sees the workorder enter PENDING_SPAREPART, gets WhatsApp updates, and the workorder resumes when the part arrives.

- **UJ-2. Dayat (Workshop Leader) handles a workshop internal order.**
  - *Persona + context:* Pak Dayat, workshop leader; a machinery section discovered it needs a turned/milled piece.
  - *Entry state:* an internal workshop workorder (child or standalone) is created for a machine being worked on.
  - *Path:* workshop technicians pick it up, upload a technical drawing (PDF/JPEG/PNG), log sessions, and close.
  - *Climax:* child workorder closes, unblocking its parent.
  - *Resolution:* the machinery workorder resumes and closes.

- **UJ-3. Dini (Section Leader CNC) rates a technician.**
  - *Persona + context:* Pak Dini, section leader; a technician completed a repair on one of his CNC machines.
  - *Path:* opens the closed workorder → opens rating panel → configurable dimensions (e.g. speed, work quality, tidiness) render as 1–5 stars → submits.
  - *Climax:* the rating feeds the technician KPI dashboard.
  - *Resolution:* leader sees the aggregate rating trend.

- **UJ-4. Taryo (Section Leader Utility) runs monthly preventive.**
  - *Persona + context:* Pak Taryo; monthly preventive schedule is due for the Kompresor group.
  - *Path:* opens the preventive calendar → opens the due schedule → fills the checklist → uploads evidence → leader approves (signature) → closes.
  - *Climax:* schedule marked performed; next monthly due date rolls forward.
  - *Resolution:* preventive report print-ready (WYSIWYG, tabular, logo, signature).

- **UJ-5. Rina (Storekeeper) sees a reorder warning.**
  - *Persona + context:* Rina, storekeeper; a sparepart's stock crossed its order point.
  - *Path:* sees the OP/OQ warning on the inventory view → creates a purchase request (or completes a pending new-item request) → records the MRE code when the part is ordered externally.
  - *Climax:* the request progresses to PART_RECEIVED → READY.
  - *Resolution:* the awaiting workorder resumes and the technician picks the part up.

- **UJ-6. Budi (Production Leader) acts on a 4-hour ack and rates the maintenance team.**
  - *Persona + context:* Budi, production leader for his line; a breakdown workorder has been in IN_PROGRESS for more than 4 hours; he has a PRODUCTION_LEADER login.
  - *Entry state:* a WhatsApp (WAHA) message arrives with a direct link, auto-logging him in (the link is bound to his registered WA number).
  - *Path:* taps the link → lands on a task list showing which workorders are acknowledged vs pending and which completed workorders still need his maintenance rating → acknowledges the 4-hour ack → fills the rating.
  - *Climax:* the ack and rating are recorded in the workorder timeline and audit trail.
  - *Resolution:* escalation stops; Budi knows the maintenance status without chasing phone calls, and never misses which items still need his rating.
  - *Edge case:* the notification pile-up problem from the old system — the task list shows exactly what is done vs pending, so nothing is silently missed.

## 3. Glossary

- **Workorder (WO)** — a single maintenance job. Dual source: `SYNCED` (imported from the internal system, ID = external `sheet_no`) or `INTERNAL` (created in Syncro, ID = auto-generated `WO-YYMMxxxx`). Parent-child chains are allowed (e.g. machinery workorder → workshop child).
- **WO Category** — classification with code (01 Breakdown, 02 Preventive, …). Leaders (section leader and above) may add categories.
- **Work Log** — `[CHANGED 2026-08-31 — ORM maturation: "repair session" replaced by work log]` a contiguous logged work period (start/end, description) on a work assignment. A workorder has multiple work assignments, each with multiple work logs (e.g. split by waiting for a part). Start/end may be backdated (never before the workorder was created). Cumulative work-log duration = workorder MTTR contribution.
- **MTTR** — Mean Time To Repair, cumulative sum of all repair-session durations for a workorder.
- **MTBF** — Mean Time Between Failures, time between consecutive breakdown workorders (`woStopAt`-ordered) for a machine.
- **Section** — organizational unit (`MACHINERY`, `UTILITY`, `WORKSHOP`) that owns machine groups. A section leader governs only their own machine group(s).
- **Machine Group** — a process line within a plant (e.g. Forming, Rolling, CNC); belongs to exactly one section. Section leadership is derived from `machine_responsibilities` level `LEADER`.
- **Cross-Plant Team** — a temporary (expiry-dated) team spanning plants for shared repair work; grants scoped extra access without breaking plant segregation.
- **Sparepart Request** — a request for parts/consumables/service on a workorder. Types: `SPAREPART`, `CONSUMABLE`, `SERVICE_EXTERNAL`. Has a state machine and purchase reference URL.
- **Material Code** — globally unique identifier on a sparepart (reuses Epic 8, FR-078); the key that links stock/OP/OQ and the external system's part identity. One code = one sparepart (not per-machine).
- **Stock (OP/OQ)** — optional per-plant stock record keyed by material code: `stock_on_hand`, `order_point` (OP), `order_qty` (OQ). When `stock_on_hand <= OP`, a reorder warning is raised.
- **Purchase Reference URL** — optional link (e.g. Tokopedia) on a request so the storekeeper knows the exact item.
- **PENDING_COMPLETION** — a new-item request the leader created without a material code; INVENTORY_MAINTENANCE/STOREKEEPER completes it (material code, image, est. price).
- **Sync Module** — the hardened pipeline that imports workorders from the internal system's PostgreSQL.
- **OPA** — Open Policy Agent, the policy engine authorizing every request.
- **Allowed Actions** — the set of actions OPA returns for the current subject+resource; the frontend uses it to render menus/buttons, enforcement remains server-side.

**Roles** (application role; operational scope is derived separately from machine responsibilities). `[CHANGED 2026-08-31 — ORM maturation: the role model is now data-driven — job_titles, system_roles, role_permission_mappings, menu_features tables in addition to application_role; SUPER_ADMIN maps roles per user via the UI; OPA remains the enforcement point, with subject.roles enriched from application_role + job_title + system_role.]`

- **SUPER_ADMIN** — full CRUD, all plants, all actions; manages users, sections, teams, policies, audit.
- **MANAGER_MAINTENANCE** — global maintenance role across all plants; highest approval tier; break-glass override.
- **MAINTENANCE_LEADER** — plant-assigned role (one or more plants when another plant lacks a leader); governs all sections in assigned plants.
- **SECTION_LEADER** — derived scope: leader of specific machine group(s) (via `machine_responsibilities` level LEADER); governs only those groups.
- **STAFF_MAINTENANCE** — maintenance staff: creates workorders, preventive programs, FMEA tags, reports; may not approve or execute as section leader.
- **TECHNICIAN** — executes assigned workorders, logs sessions, uploads evidence, requests spareparts.
- **INVENTORY_MAINTENANCE** — superior of STOREKEEPER; approves requests, completes new-item details, records MRE, manages stock/OP-OQ, creates purchase requests.
- **STOREKEEPER** — manages stock in/out, acknowledges and processes requests, marks parts ready.
- **PRODUCTION_LEADER** — production-side login; raises breakdown workorders, acknowledges 4-hour acks via WA link, rates maintenance workorders.
- **AUDITOR** — read-only across scope, views audit trail and OPA decision log.

**Workorder lifecycle** (canonical state machine): `OPEN → IN_PROGRESS → PENDING_SPAREPART → PENDING_REVIEW → CLOSED`. `CANCELLED` is allowed from `OPEN` or `IN_PROGRESS`. `PENDING_SPAREPART` (replaces `ON_PROCUREMENT`) means waiting for part/procurement.

**Sparepart request lifecycle** (canonical state machine): `REQUESTED → ACKED → PROCESSING → [READY | PURCHASE_REQUESTED → PART_RECEIVED → READY] → PICKED_UP → CLOSED`.

**MRE code** — manually entered reference number of an external purchase (e.g. `MRE26023xxxx`), recorded on the sparepart request timeline. Not auto-generated.

## 4. Features

> **CHANGE NOTE — 2026-08-31 ORM Maturation (approved Sprint Change Proposal 2026-08-31)**
>
> This Phase 2 PRD is revised to reflect the approved **ORM maturation redesign**: the data schema is **redesigned from scratch following the syncro (Node/Prisma) blueprint** adopted into Syncro-Spring. Because the project is still in the development phase (all current data is seed dummy; real data lives in another database), the redesign ships as a **DB reset + reseed with a fresh migration set (V1..)** — no real data migration is required.
>
> Requirement changes landing in this PRD:
> - **Workorders** — multi-technician assignment (`work_assignments`), work logs per assignment (start/end, backdate, stopped_reason), new 6-status lifecycle (`OPEN → IN_PROGRESS → PENDING_SPAREPART → PENDING_REVIEW → CLOSED`, plus `CANCELLED` from OPEN/IN_PROGRESS). *(FR-113, FR-114, FR-115)*
> - **Ratings** — per-work-log ratings (`work_log_ratings` + criteria) and per-workorder quality ratings with multiple technicians (`work_order_quality_ratings` + `..._technicians`). *(FR-121, FR-124)*
> - **Roles** — data-driven role model (`job_titles`, `system_roles`, `role_permission_mappings`, `menu_features`) in addition to `application_role`; SUPER_ADMIN maps roles per user via the UI; OPA remains the enforcement point. *(Glossary §3 Roles)*
> - **Inventory (new)** — inventory locations, stock balances, transfers, and reservations. *(FR-144, FR-146)*
> - **New modules adopted from the blueprint** — PM execution model (checksheets/schedules/workorders/executions), KPI monthly materialization, IATF compliance (NC/8D/calibration/ECN), and integration (webhook/audit/signature).
> - **Multi-language UI (i18n)** — Indonesian (default)/English frontend via `next-intl` (ICU MessageFormat, similar to ARB in .NET); all user-facing UI localized. *(FR-183, FR-184, FR-185)*
>
> Only the FRs listed above are amended in place; all other FRs are unchanged. FR-183–FR-185 are new additions (see §4.9).

### 4.1 Org Structure & Sections

**Description:** Introduces `sections` (MACHINERY / UTILITY / WORKSHOP) and assigns each machine group to a section. Section leadership is not manually assigned per role — it is *derived* from the existing `machine_responsibilities` ladder: a user whose responsibility level on a machine group is `LEADER` (or above) governs that group. A section leader sees only their own machine group(s) — not other groups in the same section, and not other sections. Section leaders also monitor sparepart lifetime for machines in their group. Cross-plant teams (expiry-dated) grant scoped extra access. Realizes UJ-1, UJ-2.

**Functional Requirements:**

#### FR-100: Manage sections

A SUPER_ADMIN or MANAGER_MAINTENANCE can create, read, update, and deactivate sections (`MACHINERY | UTILITY | WORKSHOP`) per plant.

**Consequences (testable):**
- POST/GET/PUT/DELETE `/api/v1/sections` returns standard error shape on validation failure.
- Deactivating a section with active machine groups is rejected with a machine-readable code.
- Mutation is audit-logged with actor and traceId.

#### FR-101: Assign machine groups to sections

Each machine group belongs to exactly one section. `[ASSUMPTION: section is per-plant, not global across plants.]`

**Consequences (testable):**
- `machine_groups.section_id` is set via a maintenance-leader+ mutation and audit-logged.
- A machine group in an inactive section cannot be assigned to a workorder.

#### FR-102: Derive section leadership from machine responsibilities

A user is a section leader for a machine group when they hold responsibility level `LEADER` or above on that group.

**Consequences (testable):**
- Assigning level LEADER to a user on a machine group grants them section-leader scope for that group without any additional role assignment.
- Demoting/removing the responsibility immediately removes the scope (server-side, enforced by OPA input derivation).

#### FR-103: Scope section leaders to their own machine groups

A section leader reads and mutates workorders/preventive/sparepart requests only for machines in their own machine group(s).

**Consequences (testable):**
- List/detail endpoints for workorders filter rows by derived scope; out-of-scope resource access returns a permission-denied code.
- A section leader for Forming cannot view or mutate Rolling/CNC workorders even within the same section.

#### FR-104: Monitor sparepart lifetime within scope

A section leader can view sparepart lifetime/alerts for machines in their own machine groups.

**Consequences (testable):**
- The sparepart lifetime view respects derived scope.
- Out-of-scope machines do not appear in the section leader's lifetime view.

#### FR-105: Manage cross-plant teams

SUPER_ADMIN / MANAGER_MAINTENANCE can create expiry-dated cross-plant teams and add members; membership grants scoped access to specified machines/workorders across plants.

**Consequences (testable):**
- A technician on an active cross-plant team can work a workorder in another plant's workshop scope.
- Expired team membership automatically revokes the extra scope (OPA input excludes expired teams).
- Team mutations are audit-logged.

### 4.2 Workorder Management

**Description:** The core execution record. Workorders come from two sources: synced from the internal system (source `SYNCED`, ID = external `sheet_no`) or created internally (source `INTERNAL`, auto-generated `WO-YYMMxxxx`, safe under transaction+lock). Lifecycle: `OPEN → IN_PROGRESS → PENDING_SPAREPART → PENDING_REVIEW → CLOSED`, plus `CANCELLED` from OPEN/IN_PROGRESS. `PENDING_SPAREPART` (replaces `ON_PROCUREMENT`) means "waiting for part/procurement": triggered when a sparepart request is not READY, and the workorder resumes IN_PROGRESS when the part is READY. A section leader does not execute workorders themselves — they delegate to technicians. Work is captured per assignment via work logs in multi-log flows. Evidence images (before/after) and technical drawings (PDF/JPEG/PNG) are stored in Garage. CP/CPK values and PDF are optional on all categories. FMEA tag, todo/kanban, status-by-leader, ratings, and machine history derive from the workorder. Realizes UJ-1, UJ-2, UJ-3, UJ-6.

**Functional Requirements:**

#### FR-110: Create internal workorders

A STAFF_MAINTENANCE or SECTION_LEADER (own group) can create an internal workorder with category, machine, description, and optional parent. A PRODUCTION_LEADER can create a breakdown workorder (category 01 Breakdown) for machines on their production lines.

**Consequences (testable):**
- POST `/api/v1/workorders` creates a `WO-YYMMxxxx` id (unique, no collision with synced ids) and source `INTERNAL`.
- Concurrent creation does not produce duplicate ids (transaction + row lock).
- Creating a child workorder requires access to the parent's section scope.
- A production leader creating a workorder is restricted to breakdown category and their own line's machines (server-side).

#### FR-111: Import synced workorders

The sync module imports workorders from the internal system; a synced workorder uses the external `sheet_no` as its id and is marked source `SYNCED`.

**Consequences (testable):**
- Re-sync of the same `sheet_no` upserts rather than duplicates (idempotent).
- Synced workorders are read-only for external master fields; local operational fields (evidence, report, ratings) are preserved.

#### FR-112: Manage WO categories

Leaders (section leader and above) can create WO categories (code + label, e.g. 01 Breakdown, 02 Preventive).

**Consequences (testable):**
- Category codes are unique; mutation requires role >= SECTION_LEADER and is audit-logged.
- All users with workorder read scope can view categories.

#### FR-113: Assign and delegate workorders

A section leader can assign a workorder (own group) to a technician. Assignment records the primary/lead technician (transition `OPEN → ASSIGNED`) **and** creates a work assignment (`work_assignments`: work_order_id, technician_id, assigned_by, assigned_at, dropped_at, is_active). A workorder can have **multiple work assignments** — one per technician. A section leader cannot be assigned as the executing technician of their own workorders. `[ASSUMPTION: STAFF_MAINTENANCE may still execute if assigned.]`

**Consequences (testable):**
- First assignment transitions OPEN → ASSIGNED and creates a `work_assignments` row with the primary/lead technician.
- Additional assignments create additional `work_assignments` rows for other technicians without re-transitioning status.
- Dropping an assignment marks `is_active = false`/sets `dropped_at`; a workorder may be reassigned.
- The system rejects assigning the section leader as executing technician with a machine-readable code.

#### FR-114: Transition workorder status

A technician can start/resume/complete their assigned workorder; a section leader can manage status (including placing/removing a workorder on PENDING_SPAREPART) within their scope; PENDING_REVIEW → CLOSED is a leader action. Canonical lifecycle: `OPEN → IN_PROGRESS → PENDING_SPAREPART → PENDING_REVIEW → CLOSED`. `CANCELLED` is allowed from `OPEN` or `IN_PROGRESS` only. `[CHANGED 2026-08-31 — ORM maturation: 8-status lifecycle (ON_PROCUREMENT/DONE/CANCELLED-from-ASSIGNED) replaced by the 6-status lifecycle above.]`

**Consequences (testable):**
- Invalid transitions return `INVALID_STATE_TRANSITION` (state machine enforced server-side).
- PENDING_SPAREPART is auto-set when an open sparepart request is not READY, and auto-resumed when the part becomes READY.
- Cancelling a workorder is only accepted while it is OPEN or IN_PROGRESS; cancel is audit-logged with reason.
- Each transition writes a `work_order_status_history` entry + audit.

#### FR-115: Record work logs against work assignments

A technician logs **work logs** (`work_logs`; `[CHANGED 2026-08-31 — ORM maturation: "repair sessions" replaced by work logs per assignment]`) with start/end and description **against a work assignment** (`work_logs.work_assignment_id`). A workorder has multiple work assignments, each with multiple work logs (e.g. split by waiting for a part). Start/end may be **backdated**, but never before the workorder was created. Cumulative work-log duration feeds MTTR.

**Consequences (testable):**
- A work log always references an active work assignment of the logging technician.
- Backdated start/end are accepted when not earlier than the workorder's creation timestamp; earlier values are rejected.
- Work logs cannot overlap in a way that double-counts; cumulative MTTR equals sum of work-log durations.
- Workorder cannot reach CLOSED with no completed work log unless a documented reason is provided.

#### FR-116: Upload evidence and technical drawings

A technician (own assigned workorder) or leader uploads before/after photos and technical drawings (PDF/JPEG/PNG/WebP) to Garage; PostgreSQL stores object keys only.

**Consequences (testable):**
- Accepted content types: image/jpeg, image/png, image/webp, application/pdf; size limit enforced `[ASSUMPTION: max 10 MB per file, configurable]`.
- Uploads respect size/type validation; files survive restarts (Garage).
- Removing an attachment deletes the object.

#### FR-117: Optional CP/CPK on reports

Any workorder category may record CP/CPK values and attach a CP/CPK PDF; never mandatory.

**Consequences (testable):**
- CP/CPK fields accept optional decimal values and an optional PDF attachment on the report.
- A workorder can close without CP/CPK.

#### FR-118: Tag workorders with FMEA

An engineer/staff/leader can tag a workorder with an FMEA failure type.

**Consequences (testable):**
- The FMEA tag is stored and appears in the report and machine history.
- Tagging is audit-logged.

#### FR-119: Manage workorder todos / kanban

A section leader creates workorder todos and assigns them to technicians; a kanban view shows workorders/todos by status.

**Consequences (testable):**
- Todo create/assign/complete is scoped to the workorder's group.
- Kanban view respects derived scope and backend-provided statuses.

#### FR-120: Close parent only when children are done

A parent workorder cannot be CLOSED while any child workorder is not CANCELLED/CLOSED; SUPER_ADMIN/MANAGER may override with a note.

**Consequences (testable):**
- Closing a parent with open children returns a machine-readable blocking code.
- Override is audit-logged with reason.

#### FR-121: Rate technicians (by section leader)

A section leader rates the technicians who executed a workorder in their scope using configurable dimensions rendered as 1–5 stars. `[CHANGED 2026-08-31 — ORM maturation: ratings now also apply per work log — work_log_ratings (work_log_id, criterion_id, score, rated_by) with work_log_rating_criteria/category config — in addition to workorder-level technician ratings.]`

**Consequences (testable):**
- Rating dimensions are configurable — the set of dimensions is data, not code `[ASSUMPTION: SUPER_ADMIN or MANAGER_MAINTENANCE configures the default dimension set; see OQ-1]`.
- A work log can carry its own rating (`work_log_ratings`) scored per configured criterion; ratings are bound to the work log and its technician.
- Only the section leader of the workorder's group can rate; ratings are immutable after submission.
- Rating feeds the technician KPI dashboard.

#### FR-122: Capture stop-time reason on breakdown workorders

A breakdown workorder records a stop-reason code (e.g. electric/mechanical/pneumatic/hydraulic) for later MTBF/OEE analysis.

**Consequences (testable):**
- Reason code is required before DONE for breakdown category workorders.
- Reason codes render as text (not color-only).

#### FR-123: Record SLA response time per category

Each category may define a target response time; overrun is surfaced as a KPI.

**Consequences (testable):**
- Response time = time from OPEN to first IN_PROGRESS session start.
- Overrun is computed backend-side and shown on dashboards.

#### FR-124: Rate maintenance workorders (by production leader)

A production leader rates the maintenance workorder after closure using configurable dimensions rendered as 1–5 stars. `[CHANGED 2026-08-31 — ORM maturation: quality ratings now support **multiple technicians** per workorder via work_order_quality_ratings + work_order_quality_rating_technicians + ..._scores; the rating is bound to the workorder and captures per-technician scores.]`

**Consequences (testable):**
- Rating dimensions are configurable (shared dimension config with FR-121).
- Only the PRODUCTION_LEADER of the affected line can rate; one rating entry per workorder; immutable after submission.
- The rating spans all technicians on the workorder via `work_order_quality_rating_technicians`; each technician's score is recorded separately.
- Rating feeds the maintenance-workorder quality view and is separate from per-work-log technician ratings.

**Out of Scope:**
- Aggregate production-side dashboards (deferred); only the rating input and per-workorder view in v1.

### 4.3 Preventive Maintenance

**Description:** Preventive programs per machine with categories (mechanical/electrical), monthly and annual schedule types, checklist with assessment, and WYSIWYG printable reports (tabular view, logo, signature for approval/close by leader/SPV). Preventive is calendar/shift-based and works without telemetry. Realizes UJ-4.

**Functional Requirements:**

#### FR-130: Define preventive programs per machine

STAFF_MAINTENANCE or leader creates a preventive program for a machine with category (mechanical/electrical) and schedule type (MONTHLY or ANNUAL). `[ASSUMPTION: no daily/weekly/hourly needed now.]`

**Consequences (testable):**
- Schedule types are limited to MONTHLY and ANNUAL.
- Program is scoped to the machine's section/group.

#### FR-131: Generate and track schedules

The system generates monthly/annual preventive schedules; a calendar view lists due/overdue items.

**Consequences (testable):**
- Next due date rolls forward from completion (floating interval).
- Due/overdue computation uses server-side clock; works without telemetry.
- Overdue items surface on the preventive dashboard.

#### FR-132: Complete preventive checklist with assessment

A technician/staff completes the checklist; a leader assesses and approves the result.

**Consequences (testable):**
- Checklist result is persisted with performed-by, timestamp, notes, and evidence.
- Approval/assessment is a leader action, signed (signature feature), and audit-logged.

#### FR-133: Print preventive report (WYSIWYG)

The preventive report prints via a WYSIWYG template — tabular view, configurable logo, and signature block for approval/close.

**Consequences (testable):**
- Print output renders checklist, results, signer identity, and timestamps.
- Logo and signature render correctly in print output.

#### FR-134: Auto-create preventive workorders from schedules

A due schedule can generate an internal preventive workorder (category 02 Preventive) assigned to the responsible section.

**Consequences (testable):**
- Generated workorder links back to the schedule.
- Duplicate generation is prevented (idempotent per schedule period).

### 4.4 Sparepart Request & Inventory

**Description:** Requests for parts on a workorder (SPAREPART / CONSUMABLE / SERVICE_EXTERNAL) with a full state machine, approval + SoD, escalation, purchase reference URL, and new-item completion by inventory. Optional stock (OP/OQ) is keyed by material code per plant. MRE code is manual. Realizes UJ-1, UJ-5.

**Functional Requirements:**

#### FR-140: Create sparepart requests

A leader/staff/technician with workorder scope creates a request (SPAREPART with electric/mechanic taxonomy, CONSUMABLE without machine binding, or SERVICE_EXTERNAL bound to the parent workorder) with quantity, purchase reference URL, and optional new-item details.

**Consequences (testable):**
- Type rules enforced: SERVICE_EXTERNAL requires a workorder; CONSUMABLE does not require a machine.
- New-item requests start in PENDING_COMPLETION when no material code is given.
- Request creation is audit-logged.

#### FR-141: Manage the sparepart request state machine

Requests flow `REQUESTED → ACKED → PROCESSING → [READY | PURCHASE_REQUESTED → PART_RECEIVED → READY] → PICKED_UP → CLOSED`; each transition records a timeline event + audit. `[ASSUMPTION: a single workorder with multiple requests enters ON_PROCUREMENT when any is not READY.]`

**Consequences (testable):**
- Invalid transitions return `INVALID_STATE_TRANSITION`.
- Request is ACKed by INVENTORY_MAINTENANCE/STOREKEEPER; PICKED_UP/CLOSED by the workorder's section leader.
- A live open request in a non-READY state holds its workorder in ON_PROCUREMENT.

#### FR-142: Approve with separation of duty

Approving a request whose estimated cost (request quantity × estimated price per unit) exceeds a threshold requires a role above the requester; requester does not approve their own request. Estimated price is sourced from the latest price entry (Phase 1 FR-080) or from the PENDING_COMPLETION completion data.

**Consequences (testable):**
- Approver != requester enforced server-side.
- Threshold-based escalation (configurable durations) applies via `escalation_configs`.
- Estimated cost = qty × est. price; when est. price is unavailable (e.g. new item without price), the request cannot be cost-tiered and requires section-leader approval regardless of quantity `[ASSUMPTION: threshold tiers are configurable; v1 defaults to section-leader ≤ 5M IDR, maintenance-leader 5M–50M IDR, manager >50M IDR]`.

#### FR-143: Record purchase reference URL

A request carries an optional purchase reference URL (e.g. Tokopedia link) visible to storekeeper/inventory.

**Consequences (testable):**
- URL is validated as http(s), stored, and rendered on the request.

#### FR-144: Complete new-item requests

INVENTORY_MAINTENANCE/STOREKEEPER completes a PENDING_COMPLETION request with material code, image, and estimated price; material code links to the existing sparepart/material-code identity. `[CHANGED 2026-08-31 — ORM maturation: completed parts can be stocked at an inventory location (FR-146).]`

**Consequences (testable):**
- Only inventory roles can complete; mutation is audit-logged.
- Duplicate material code rejected (global uniqueness).
- Completion may record the target inventory location; the stock balance is updated at that location.

#### FR-145: Record MRE code

INVENTORY_MAINTENANCE records a manual MRE code on PURCHASE_REQUESTED requests; it is a reference only.

**Consequences (testable):**
- MRE code is manual, format-free (display example `MRE26023xxxx`), and stored on the timeline.
- No auto-generation occurs.

#### FR-146: Track stock by material code with OP/OQ

Optional per-plant stock record keyed by material code with stock_on_hand, order point (OP), order quantity (OQ); a reorder warning fires when stock_on_hand <= OP and recommends a purchase request of OQ. `[CHANGED 2026-08-31 — ORM maturation: inventory is extended with locations, transfers, and reservations.]`

**Consequences (testable):**
- Stock is keyed by material code (one code = one sparepart), not per-machine taxonomy.
- Reorder warning is a business-rule signal; the resulting purchase request is authorized separately by OPA.
- Stock mutation is audit-logged.

#### FR-146a: Manage inventory locations

Inventory stock is held at named **inventory locations** (`inventory_locations`, e.g. plant store, workshop store); stock balances (`inventory_stock_balances`) are tracked per location.

**Consequences (testable):**
- A location is created/read/updated/deactivated within scope (SUPER_ADMIN/MANAGER_MAINTENANCE/INVENTORY_MAINTENANCE).
- Stock on hand is reported per material code per location.
- Mutation is audit-logged.

#### FR-146b: Transfer stock between locations

Inventory personnel record **inventory transfers** (`inventory_transfers`) moving stock of a material code from a source to a target location.

**Consequences (testable):**
- A transfer debits the source location and credits the target location atomically.
- Transfers are rejected when source stock is insufficient (machine-readable code).
- Transfers are audit-logged with actor, quantities, and timestamps.

#### FR-146c: Reserve stock for workorders

A sparepart request can **reserve** stock (`inventory_reservations`) at a location against a workorder; reservations hold stock for the requesting workorder.

**Consequences (testable):**
- A reservation reduces available (not on-hand) stock at a location; reserved stock cannot be transferred away or consumed by another workorder.
- Reservation is released on request CLOSED/PICKED_UP or when explicitly cancelled.
- Reservations are audit-logged.

#### FR-147: Escalate requests and notify via WAHA

Unacknowledged/overdue requests escalate per `escalation_configs`; WhatsApp notifications fire per step (workorder→report, request part→escalation) to section leaders and above plus inventory roles with phone numbers.

**Consequences (testable):**
- Escalation durations are configurable, not hardcoded.
- WAHA notifications use the existing outbox/worker pattern (polymorphic target); idempotency prevents duplicate sends.
- Rate limiting and circuit breaker apply (reuse Epic 5).

### 4.5 Sync Module (hardened)

**Description:** Imports workorders from the internal system's PostgreSQL. Hardened against the weaknesses of the reference implementation (no transactions, no idempotency, no lock, ordering bugs, notification storms). Runs as a scheduled job with watermark, lock, quarantine, observability, typed config, and audit.

**Functional Requirements:**

#### FR-150: Schedule external workorder sync

A scheduled job imports new/updated workorders from the configured external PostgreSQL (typed config), ordered by `sheet_no`, resumable from a watermark.

**Consequences (testable):**
- Each batch runs in a transaction; failure rolls back and records a failed `sync_run`.
- Concurrent runs are prevented by a lock (Redis/DB advisory).
- The job resumes from the last processed `sheet_no` (watermark) after restart.

#### FR-151: Upsert idempotently by sheet_no

Workorders are upserted by external id; re-sync does not duplicate.

**Consequences (testable):**
- Unique constraint on synced id; re-processing the same sheet_no updates rather than inserts.
- Duplicate rows land in quarantine with reason rather than being dropped silently.

#### FR-152: Resolve sync conflicts

Master fields (status, timestamps, machine, category) follow external values; local operational fields (report, evidence, ratings) are preserved.

**Consequences (testable):**
- After a sync, external master fields match external; local operational fields retain local values.
- Conflict resolution is deterministic and audit-logged per run.

#### FR-153: Quarantine and observe failures

Records that fail mapping land in a sync quarantine store; sync run status/counts appear on the health dashboard.

**Consequences (testable):**
- Failed records are visible to SUPER_ADMIN with reason, raw payload, and traceId.
- Health dashboard shows last run, counts (created/updated/failed), and last run timestamp.
- External DB outage degrades gracefully (retry/backoff, no crash).

#### FR-154: Respect timezone and notification hygiene

External timestamps (Asia/Jakarta) are normalized to UTC; sync notifications are deduplicated and limited to newly created workorders/important transitions.

**Consequences (testable):**
- Stored timestamps are UTC; display converts at boundary.
- No notification storm: identical/unchanged rows do not re-notify.

### 4.6 OPA Authorization & Allowed Actions

**Description:** OPA is the decision point for every authorization request. Deployed as a sidecar HTTP service; Spring backend calls it with a minimal input (subject, resource, action, context); the frontend consumes an allowed-actions endpoint for rendering. Scope is derived from org data in PostgreSQL and passed as input (OPA is not the source of truth for org data). Policy-as-code with CI tests. Realizes all journeys' permission boundaries.

**Functional Requirements:**

#### FR-160: Enforce authorization via OPA on every request

The backend evaluates OPA (default-deny) for each authorization-sensitive request before executing business logic. `[ASSUMPTION: OPA deployed as sidecar HTTP; embedding (WASM/IR) not chosen for v1.]`

**Consequences (testable):**
- A denied request returns the standard permission-denied error shape; no business logic runs.
- Decision result (decision_id) is correlated into the audit record.
- Coarse checks run at the filter/interceptor; row-level filtering applies derived scope in queries.

#### FR-161: Provide allowed-actions for the frontend

The backend exposes an endpoint returning the allowed-actions set for the current subject+resource; the frontend uses it to render menus/buttons.

**Consequences (testable):**
- Frontend never calls OPA directly (NFR-013a).
- Allowed actions are recomputed from OPA per request; hiding is UX only, enforcement remains server-side.

#### FR-162: Deploy policy as code with CI

Rego policies live in the repo; `opa test` runs in CI; bundles are versioned and activated by OPA (status/decision-log APIs wired). `[ASSUMPTION: decision logs retained with masking; default 30 days retention, configurable.]`

**Consequences (testable):**
- CI fails on policy test failure; bundle revision is visible in status.
- Decision logs are enabled with input masking for sensitive fields; retained per retention policy.

#### FR-163: Derive operational scope from org data

Subject input includes roles, plants, sections, machine groups, and active (non-expired) teams derived from PostgreSQL; resource input includes plant/machine/section/attributes.

**Consequences (testable):**
- Out-of-scope plant/machine/section access is denied even when role is present.
- Expired cross-plant team membership yields no extra scope.
- Org changes (assignment demotion) take effect without policy redeploy (scope passed as input).

#### FR-164: Audit policy decisions

Authorization decisions are captured in the decision log and correlated with application audit via decision_id.

**Consequences (testable):**
- An AUDITOR can trace which policy allowed/denied an action, with policy revision and decision_id.
- Decision logs mask sensitive input fields (no WAHA secrets, no full phone numbers).

### 4.7 Dashboards & Reports

**Description:** Machine, workorder, preventive, and KPI dashboards (MTBF/MTTR, technician ratings). WYSIWYG printable reports with logo and signature for workorder and preventive. Realizes UJ-1, UJ-3, UJ-4.

**Functional Requirements:**

#### FR-170: Machine dashboard

Shows machines with status, telemetry freshness (when available), open workorders, alerts, and lifetime risk within scope.

**Consequences (testable):**
- Respects derived scope; machine timeline combines telemetry + alert + workorder + preventive.
- Stale/empty/loading/error/forbidden states render per UX-DR-019.

#### FR-171: Workorder dashboard

Shows workorder counts/status/category within scope.

**Consequences (testable):**
- Counts are backend-computed; frontend does not re-aggregate.
- Filtering by plant/section/status/category.

#### FR-172: Preventive dashboard

Shows due/overdue preventive schedules within scope.

**Consequences (testable):**
- Due/overdue from server clock; overdue visibly distinct (non-color-only).

#### FR-173: MTBF/MTTR dashboards

MTBF (between breakdowns, `woStopAt`-ordered) and MTTR (cumulative sessions) per machine/group/period within scope. Units: hours. Window: monthly rolling. Freshness: 30-minute Redis cache TTL, stale indicator. Insufficient-data state when fewer than 2 breakdown workorders exist.

**Consequences (testable):**
- MTBF uses `woStopAt` ordering (not id) — avoids the reference bug.
- Units/window/freshness documented; insufficient-data state explicit.

#### FR-174: Technician KPI dashboard

Shows technician ratings (configurable dimensions, 1–5 stars) and objective KPIs (completed count, average MTTR, on-time %) per technician.

**Consequences (testable):**
- Ratings and KPIs computed backend-side within scope.
- Leader sees own-group technicians; manager sees plant; manager-global sees all.

#### FR-175: WYSIWYG print workorder & preventive reports

Reports print via WYSIWYG templates — tabular view, configurable logo, signature block (leader/SPV input for approval & close). `[ASSUMPTION: browser print of a WYSIWYG HTML report, not server-side PDF; signature is an uploaded image + signer identity.]`

**Consequences (testable):**
- Print includes header/logo, WO fields, sessions, parts, report narrative, CP/CPK (if present), evidence refs, and signature.
- Signature captures signer identity + timestamp; rendered in print.

### 4.8 WAHA Notifications (workorder & escalation)

**Description:** WhatsApp notifications for workorder and sparepart-request lifecycle steps, reusing the Epic 5 outbox/worker/rate-limit/circuit-breaker pattern with a polymorphic notification target. Realizes UJ-1, UJ-6.

**Functional Requirements:**

#### FR-180: Notify workorder lifecycle events

WAHA messages fire on key events (new breakdown, ON_PROCUREMENT, part READY, DONE/CLOSED) to section leaders and above plus inventory roles with phone numbers.

**Consequences (testable):**
- Messages use templates (existing WYSIWYG template editor pattern) with WO-specific variables.
- Each event is idempotent; no duplicate logical notification per event.
- Recipient resolution respects derived scope + phone presence.

#### FR-181: Send 4-hour acknowledgment request

When a workorder has been in IN_PROGRESS (net of ON_PROCUREMENT) for more than 4 hours, a WAHA message is sent to the PRODUCTION_LEADER with a direct link; the link auto-authenticates the leader (bound to their registered WA number) and opens their task list showing which workorders are acknowledged vs pending and which closed workorders still need their maintenance rating. Acknowledging is recorded in the timeline + audit.

**Consequences (testable):**
- Threshold (4h) is configurable.
- The link auto-logs-in the leader without password when the device/WA number matches the registered PRODUCTION_LEADER number; mismatch falls back to normal login.
- The task list distinguishes acknowledged vs pending acks and rated vs unrated closed workorders (solves the "notification pile-up / lost rating tracking" problem).
- Acknowledgment stops further escalation (reuse Epic 5 ack-stop pattern).
- The 4-hour clock excludes ON_PROCUREMENT time.

**Feature-specific NFRs:**
- Notifications must be non-blocking to business logic (outbox pattern, no inline send).
- WAHA credentials/phones must never be logged.

### 4.9 Multi-Language Support (i18n)

**Description:** The web frontend is fully localized in Indonesian (default) and English using `next-intl` with ICU MessageFormat strings (similar to ARB in .NET). All user-facing UI — tables, dialogs, kanban, dashboards, toasts, errors — is localized. Dates, numbers, and currencies render with locale-aware Intl formatting (id-ID vs en-US); the backend remains UTC/ISO with formatting display-only. The user's language choice persists across sessions. Realizes all user journeys (UJ-1…UJ-6). `[ADDED 2026-08-31 — stakeholder requirement added during the ORM maturation redesign.]`

**Functional Requirements:**

#### FR-183: Multi-language UI (Indonesian/English)

The web frontend supports Indonesian (default) and English, using `next-intl` with ICU MessageFormat strings (similar to ARB in .NET). All user-facing UI (tables, dialogs, kanban, dashboards, toasts, errors) is localized.

**Consequences (testable):**
- All user-visible strings come from message catalogs (en.json, id.json), not hardcoded in components.
- Missing keys fall back to English; never render raw keys.
- Locale routing `/id` and `/en`.

#### FR-184: Locale-aware formatting

Dates, numbers, and currencies render with locale-aware Intl formatting (id-ID vs en-US).

**Consequences (testable):**
- Dates shown in the active locale format.
- No locale-dependent logic in backend (backend stays UTC/ISO; formatting is display-only).

#### FR-185: Language preference persistence

The user's language choice persists across sessions.

**Consequences (testable):**
- Switcher in navbar; choice stored (cookie/localStorage).
- Revisiting the app keeps the chosen language.

## 5. Non-Goals (Explicit)

- **No production/OEE dashboards** (production OEE/stop-time analytics) in this module; production leaders only request workorders, acknowledge, and rate.
- **No telemetry dependency** — workorder and preventive must function for machines without MQTT; telemetry only enriches (lifetime/projections) when present.
- **No live integration with the external procurement system** — no API; MRE is a manual reference only.
- **No auto-generated MRE codes.**
- **No self-service technician self-rating** — technicians are rated by their section leader.
- **No stock as source of truth for lifetime or alerts** — lifetime stays counter-based; stock/OP/OQ is an optional parallel signal.
- **No public/operator self-service portal.**
- **No full CMMS breadth** (e.g. RCM, asset registry expansion, cost budgeting) — only maintenance execution, preventive, sparepart request, sync, org, and OPA.

## 6. MVP Scope

### 6.1 In Scope

**P0 — Execution spine (must-have):**
- Org: sections, machine-group→section assignment, derived section leadership, cross-plant teams.
- Workorders: dual source, lifecycle (with ON_PROCUREMENT), categories, multi repair sessions, parent-child close rule, production-leader breakdown creation.
- Sync module (hardened).
- OPA enforcement + allowed-actions + policy-as-code.

**P1 — Full maintenance execution:**
- Preventive: monthly/annual, checklist assessment, signature approval, WYSIWYG print.
- Sparepart request: full state machine, types, PENDING_COMPLETION, purchase URL, MRE, approval+SoD, escalation, WAHA.
- Stock OP/OQ by material code (optional feature).
- Evidence + technical drawings (before/after, CP/CPK optional, FMEA tag, stop-time reason, ratings).

**P2 — Insight & quality:**
- Todos/kanban.
- Dashboards: machine, workorder, preventive, MTBF/MTTR, technician KPI.
- WAHA notifications (workorder + 4-hour ack with auto-login link).
- Production-leader rating of maintenance workorders (FR-124).

### 6.2 Out of Scope for MVP

- Production OEE/stop-time dashboards — deferred (v2).
- Project budgeting / cost per workorder — deferred (v2). `[NOTE FOR PM: cost fields are structurally anticipated (parts + labor) but not reported in v1.]`
- Preventive schedule types beyond MONTHLY/ANNUAL — deferred.
- Self-service technician profile/skills matrix — deferred (v3).
- Live procurement integration — deferred (no external API).
- Full FMEA management (RPN worksheets) — only FMEA tagging in v1. `[NOTE FOR PM: FMEA tag is a hook for a future FMEA module.]`
- MTTR labor-cost projection (via UMR) — v1 records session hours; cost projection is a deferred calculation. `[NOTE FOR PM: shift config operating-days-per-month is available in settings for future use.]`
- Auto-login WA link security hardening beyond phone-number binding — v1 trusts the phone number matching. `[NOTE FOR PM: review if additional auth (PIN/OTP) is needed before exposing to more plants.]`

## 7. Success Metrics

*Each SM cross-references the FRs it validates.*

**Primary**
- **SM-1: Breakdown-to-close turnaround** — median time from OPEN to CLOSED for breakdown workorders, tracked monthly. Validates FR-114, FR-115, FR-120.
- **SM-2: Preventive schedule compliance** — % of monthly/annual schedules completed on time. Validates FR-131, FR-132.
- **SM-3: Authorization correctness** — zero permission violations in audit/decision-log review (out-of-scope access always denied). Validates FR-103, FR-160, FR-163.

**Secondary**
- **SM-4: Sparepart request cycle time** — median REQUESTED→READY and PICKED_UP. Validates FR-141, FR-147.
- **SM-5: Sync reliability** — sync job completes within 15-minute window; zero duplicate workorders from re-sync. Validates FR-150, FR-151.
- **SM-6: Adoption** — internal workorders created in Syncro (INTERNAL source) grow month-over-month as technicians/leaders use the app. Validates FR-110, FR-119.

**Counter-metrics (do not optimize)**
- **SM-C1: Rating inflation** — average technician rating rising without corresponding improvement in objective KPIs (MTTR, on-time). Counterbalances SM-1/SM-6; prevents leaders from gaming ratings.

## 8. Open Questions

1. **OQ-1** What is the initial default set of configurable rating dimensions (technician + maintenance workorder) for v1 — e.g. speed, work quality, tidiness — and who configures them (SUPER_ADMIN only, or MANAGER_MAINTENANCE too)?
2. **OQ-2** What is the default escalation duration per step in `escalation_configs` (ack, processing, purchase, ready)?
3. **OQ-3** Are stop-time reason codes a fixed enum or configurable taxonomy?
4. **OQ-4** Does the synced workorder source define the WO Category set, or does Syncro map external priority → local category (01 Breakdown mapping)?
5. **OQ-5** Signature capture: image upload (signature pad/photo) or typed + confirmation (PrivyID-like) — confirm v1 mechanism.
6. **OQ-6** Is the 4-hour ack threshold per-workorder configurable or a global config?
7. **OQ-7** Is the UMR (standard wage) source and update cadence defined for the deferred MTTR labor-cost projection, or left entirely to settings?

## 9. Assumptions Index

*Every `[ASSUMPTION]` from the document, surfaced for explicit confirmation:*
- **§4.2 FR-121** — Rating dimensions configured by SUPER_ADMIN/MANAGER_MAINTENANCE. [ASSUMPTION inline]
- **§4.2 FR-116** — Upload max 10 MB per file, configurable. [ASSUMPTION inline]
- **§4.3 FR-130** — Preventive schedule limited to MONTHLY/ANNUAL. [ASSUMPTION inline]
- **§4.4 FR-141** — ON_PROCUREMENT held by any non-READY request. [ASSUMPTION inline]
- **§4.4 FR-142** — Approval threshold tiers with configurable IDR ranges. [ASSUMPTION inline]
- **§4.6 FR-160** — OPA sidecar HTTP, not WASM/IR. [ASSUMPTION inline]
- **§4.6 FR-162** — Decision logs 30-day retention, configurable. [ASSUMPTION inline]
- **§4.7 FR-175** — Browser print, not server PDF; signature = image + identity. [ASSUMPTION inline]
- **§4.8 FR-181** — WA link auto-login bound to phone number. [ASSUMPTION inline]

## 10. Phase 1 Contract References

The following Phase 1 (Syncro 2026-05-22 PRD) contracts are referenced but defined externally. For convenience, the specific definitions are:

- **Standard error shape** (FR-100, FR-160): `{ "code": "MACHINE_READABLE_CODE", "message": "human-safe message", "fieldErrors": [ ... ], "timestamp": "ISO-8601 UTC", "traceId": "uuid" }`. Never exposes Java class names, stack traces, SQL errors, or secrets.
- **NFR-013a** (FR-161): "Next.js shall not access PostgreSQL, InfluxDB, Redis, EMQX, or WAHA directly. All data through Spring Boot APIs."
- **UX-DR-019** (FR-170): "Every operational component shall support loading, empty, error, stale, read-only, and forbidden states."
- **FR-078** (Glossary, "Material Code"): "A sparepart may carry an optional manually-entered material code that is globally unique and not plant-scoped; installation and lifetime flows do not require it."
- **Epic 5 patterns** (FR-147, FR-181): WAHA notifications use the PostgreSQL-backed outbox pattern with notification job table, worker polling, attempt history, rate limiting (Redis), circuit breaker (Resilience4j), and escalation by responsibility level with 15-minute default interval.

Full source: `_bmad-output/planning-artifacts/prds/prd-Syncro-2026-05-22/prd.md`.
