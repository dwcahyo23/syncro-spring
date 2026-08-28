# Epic 12 Context: Sparepart Request & Inventory

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Leaders request parts; storekeeper/inventory manage the request state machine, stock OP/OQ by material code, MRE recording, and escalation with WAHA notifications.

## Stories

- Story 12.1: Request Creation & Types
- Story 12.2: Request State Machine
- Story 12.3: Approval, Separation of Duty & Escalation
- Story 12.4: Stock OP/OQ & New-Item Completion

## Requirements & Constraints

- FR-140: Create requests (SPAREPART/CONSUMABLE/SERVICE_EXTERNAL) with qty, purchase URL, optional new-item.
- FR-141: Manage request state machine (REQUESTED→ACKED→PROCESSING→[READY|PURCHASE_REQUESTED→PART_RECEIVED→READY]→PICKED_UP→CLOSED); timeline events + audit.
- FR-142: Approve with separation of duty (approver != requester); threshold by estimated cost (qty x est price).
- FR-143: Record purchase reference URL.
- FR-144: Complete new-item requests (PENDING_COMPLETION) with material code, image, est price.
- FR-145: Record manual MRE code (no auto-generation).
- FR-146: Track stock by material code per plant with OP/OQ reorder warning.
- FR-147: Escalate requests per configurable escalation_configs; WAHA per step.
- NFR-P2-3: All new schema via Flyway V41+; ddl-auto=validate; additive migrations.
- NFR-P2-5: Separation of duty (approver != requester) enforced server-side before OPA.
- NFR-P2-6: Stock mutations use optimistic lock + atomic conditional update; no negative stock.
- NFR-P2-8: Sync module writes workorders only via maintenance WorkorderImportService (module boundary).
- NFR-P2-10: Frontend uses TanStack Table v9 for tabular main views.

## Technical Decisions

- **Request state machine:** `REQUESTED → ACKED → PROCESSING → [READY | PURCHASE_REQUESTED → PART_RECEIVED → READY] → PICKED_UP → CLOSED`; each transition writes a timeline event + audit. Invalid transitions return `INVALID_STATE_TRANSITION`.
- **ON_PROCUREMENT wiring (AD-5):** when a request on a workorder is not READY, the workorder enters ON_PROCUREMENT (derived); resumes IN_PROGRESS when all requests are READY. Recomputed on transition events, guarded by a lock, writes `_status_history` with `source=DERIVED`, `actor=SYSTEM`.
- **Role gates:** ACK/PROCESSING/READY/PART_RECEIVED by INVENTORY_MAINTENANCE/STOREKEEPER; PICKED_UP/CLOSED by the workorder's section leader.
- **MRE code:** manual only (no auto-generation), format-free (e.g. MRE26023xxxx), stored on the timeline.
- **Approval SoD (AD-16):** requester can never approve their own request, enforced server-side before OPA/state machine. Threshold tiers by estimated cost (qty × est. price); configurable via `escalation_configs` (defaults ≤5M / 5M–50M / >50M IDR); no-price requests require section-leader approval.
- **Stock:** `sparepart_stock(material_code, plant_id, stock_on_hand, order_point, order_qty)` unique per (material_code, plant_id). Reorder rule (`stock ≤ OP → PR qty=OQ`) is a business-rule signal in the application layer; the PR action is OPA-authorized. Stock mutations use optimistic lock + atomic conditional update; negative stock rejected. PENDING_COMPLETION creates/updates `spareparts` via the masterdata module's application service — never direct cross-module writes.
- **Escalation:** configurable durations from `escalation_configs` (not hardcoded); WAHA per step with idempotency (dedupe key `target_type+target_id+template+recipient`) and rate-limit/circuit-breaker (Epic 5 patterns).
- **Request types (FR-140):** SPAREPART binds machine + sparepart (electric/mechanic taxonomy); CONSUMABLE has no machine binding; SERVICE_EXTERNAL requires a workorder. Unknown/absent material code → PENDING_COMPLETION.
- **Module boundary (AD-7/AD-8):** stock and request state mutations never write `work_orders` directly — the ON_PROCUREMENT recompute flows through maintenance application services.

## UX & Interaction Patterns

- Request list uses TanStack Table v9, server-side mode (sort/filter/page serialize to backend query names).
- Row actions driven by backend-provided allowed-actions.
- 2026-08-28 (commit f6ac419): the request-part dialog was upgraded with BOM search (`useListSpareparts`) + a local draft cart; the Sparepart Requests route is now a server-paginated list page from `GET /api/v1/sparepart-requests` (scope-filtered plant/machine-group).

## Cross-Story Dependencies

- Depends on Story 12.1 (create/types) — done.
- ON_PROCUREMENT recompute depends on Epic 10 workorder transition/history machinery (AD-4/AD-5).
- Escalation/WAHA depends on Epic 5 notification patterns (rate-limit, circuit-breaker, outbox) and `escalation_configs`.
- New-item completion depends on Epic 8 master-data material-code/price/image flows (SparepartService.patchProcurement, Garage).
- OPA enforcement (FR-142/FR-160) depends on Epic 9 PolicyDecisionPoint + role taxonomy.
