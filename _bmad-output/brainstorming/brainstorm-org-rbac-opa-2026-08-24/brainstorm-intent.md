# Brainstorm Intent — Org Structure, Workorder/Preventive, Sparepart Requests & OPA RBAC

> Source: `.memlog.md` (brainstorm, 2026-08-24). Clean handoff intent for downstream product-brief / PRD / spec flows.
> Project: Syncro — manufacturing maintenance CMMS (Java 25 / Spring Boot 4 + Next.js 16). Extends Epics 1–8 (plants, machine groups, machines, spareparts, machine responsibility ladder TECHNICIAN<STAFF<LEADER<SPV<MANAGER, telemetry/MQTT, sparepart lifetime alerts, WAHA WhatsApp escalation, Garage S3, material code, price entries, shift config — Epic 8.5 shift config in progress, next migration V41).

## 1. Goal / Why

- Add a Maintenance workorder + preventive module to the CMMS, governed by OPA (Open Policy Agent) authorization.
- Introduce org structure (departments/sections/teams) so access control follows the org automatically instead of double manual setup.
- Keep security enforcement server-side; frontend only decides what to render; policy changes deploy without backend redeploy.
- Guardrails: role+scope as the primary boundary (not ad-hoc rules); org data (machine→section→dept→plant) as the single source from which operational scope is derived.

## 2. Scope In / Scope Out

**In:**
- Org structure: sections, teams, roles; OPA-driven org-aware access control.
- Maintenance workorder domain (dual-source), preventive module, sparepart request & inventory flow, hardened sync module, schema V41+.

**Out (deferred):**
- Production / OEE dashboards (production OEE/telemetry dashboards deferred).
- Telemetry is NOT required for workorder/preventive — machines may not have MQTT yet. MTTR derives from repair-session timestamps; lifetime counter only used when telemetry exists; preventive is calendar/shift-based (shift config 8.5), not counter-based.
- PR to the external system has NO API — MRE is manual-only (no auto-generation).

## 3. Org & Role Model

**Sections:**
- `MACHINERY` — machine groups: Forming (leader Pak Eko), Rolling (Pak Eko), Machining (Pak Didi), CNC (Pak Dini), Heattreatment (Pak Ahri).
- `UTILITY` — Kompresor (Pak Taryo).
- `WORKSHOP` — led by Pak Dayat; no machine group; all machines can be worked on here.

**Role set (final):**
- `SUPER_ADMIN` — full CRUD, all scope, all actions.
- `MANAGER_MAINTENANCE` — global.
- `MAINTENANCE_LEADER` — plant-assigned; fallback multi-plant.
- `SECTION_LEADER` — derived from machine responsibilities level LEADER; scope = own machine group only.
- `STAFF_MAINTENANCE` — replaces engineer + planner.
- `TECHNICIAN`
- `INVENTORY_MAINTENANCE` — superior of storekeeper.
- `STOREKEEPER`
- `PRODUCTION_LEADER`
- `AUDITOR`

**Operational scope:**
- Scope is derived from the machine responsibility ladder: level `LEADER` = section leader of that machine group.
- Section leader scope READ/WRITE = own machine group ONLY; cannot view other sections' workorders; also monitors sparepart lifetime for machines in own group.
- Break-glass / temporary roles / separation of duty / expiry are acknowledged safety mechanisms to design for.

## 4. Workorder Domain

- **Dual source** (`source` ENUM):
  - `SYNCED` — ID = external `sheet_no`.
  - `INTERNAL` — auto-generated `WO-YYMMxxxx`; generator uses transaction + lock to prevent duplicates. Prefixes `AP-` vs `WO-` do not collide.
- **Categories** (`work_order_categories`): 01 Breakdown, 02 Preventive, and beyond; leader+ can add.
- **Parent–child workshop chain**: parent WO cannot close while a child WO is open/working; override only by SUPER_ADMIN/MANAGER with a note. Workshop WO can be standalone (from non-internal-maintenance/production request) or a child WO.
- **Repair sessions**: multiple sessions per WO; **MTTR = total cumulative of all repair sessions** (timestamp-based).
- **Evidence**: before/after attachments; technical drawings as PDF/JPEG/PNG.
- **CP/CPK**: optional; all categories can input values + PDF attachment.
- **FMEA tag** on WO.
- **Todo/kanban** for work tracking; WO **status transitions** decided by leader.
- **Ratings**: technician & maintenance WO rating by production leader; KPI rating dashboard.
- **Dashboards**: machine, workorder, preventive; **MTBF/MTTR** (MTBF ordered by `woStopAt`, not id — do not repeat the reference bug).
- **Extra proactive features captured**: stop-reason code per breakdown WO, SLA response time per category, cost per WO (parts+labor), machine criticality for escalation priority, preventive due-date by shift, machine timeline hub (telemetry+alert+WO+preventive).

## 5. Preventive Domain

- Preventive per machine; categories mechanical/electrical.
- Schedule + assessment.
- WYSIWYG report print.
- Calendar-based scheduling that works **without telemetry** (calendar/shift driven, per 8.5 shift config).

## 6. Sparepart Request & Inventory

- **State machine**: `REQUESTED → ACKED → PROCESSING → [READY | PURCHASE_REQUESTED(MRE) → PART_RECEIVED → READY] → PICKED_UP → CLOSED`.
- **Types**: `SPAREPART` (electric/mechanic), `CONSUMABLE` (general small items, e.g. isolation; may be without machine binding), `SERVICE_EXTERNAL` (external services, bound to parent WO, no stock flow).
- **New item**: request may be `PENDING_COMPLETION` without material code; INVENTORY_MAINTENANCE/STOREKEEPER completes material code, image, est. price.
- **`purchase_reference_url`** on request (e.g. Tokopedia link) so storekeeper knows the item.
- **Stock**: OP/OQ bound to **MATERIAL CODE** (global, 1 code = 1 sparepart) — not BOM/taxonomy per machine; per-plant with global reference. Table `sparepart_stock(material_code, plant_id, stock_on_hand, order_point, order_qty)`. Reuses material code from Epic 8 (FR-078).
- **MRE**: code manual by INVENTORY_MAINTENANCE (not auto-generated), format ~`MRE26023xxxx`, recorded as reference only (PR to internal system has no API).
- **Escalation**: dynamic thresholds via `escalation_configs` table (`event_type`, `duration`, `escalate_to_role`, `template`) — configurable, not hardcoded. WAHA notification per step (WO→report, request part→escalation).

## 7. Sync Module (hardened — locked decisions)

- Transaction; idempotency by `sheet_no`; watermark/checkpoint; lock anti-concurrency; ordering by `sheet_no`.
- Conflict resolution: master fields → external wins; operational fields → local wins.
- Quarantine of failed records; observability on health dashboard; typed config; retry/backoff.
- Notification hygiene; timezone Asia/Jakarta → UTC; audit via `sync_runs`.

## 8. OPA Integration

- **REST sidecar** (bundle + decision log + status), not embedded; called by backend via HTTP (`RestClient` + Resilience4j, consistent with WAHA pattern).
- **Input schema**: `subject` (user, roles, dept, section, teams) + `resource` (machine/part/WO) + `action` + `context`.
- **`/allowed-actions` endpoint** for frontend menu hiding — frontend never calls OPA directly; enforcement remains server-side.
- **Policy-as-code** + CI (test fixtures) + decision log; not edited directly in production.
- **Scope** derived from org data in PostgreSQL (machine→section→dept→plant).
- Data scoping: queries row-level filtered by scope before reaching the user.

## 9. Schema Additions (V41+)

New tables:
- `sections`; `machine_groups.section_id`
- `sparepart_stock`, `sparepart_requests`
- `work_orders`, `work_order_categories`, `work_order_status_history`, `work_order_repair_sessions`, `work_order_attachments`, `work_order_reports`
- `ratings`
- `preventive_*`
- `sync_*`
- `escalation_configs`
- org `teams`

Migration also required:
- `notification_jobs` → polymorphic target (`target_type`/`target_id`) for WO/preventive/escalation notifications.

## 10. Integration Constraints to Respect

- Reuse existing patterns: machine responsibility ladder, plant scope, WAHA outbox, Garage storage, audit log.
- `ddl-auto=validate`; bounded-context layout.
- Next migration is **V41**.

---

*Distilled from the brainstorming memlog — chosen and critical discoveries only.*
