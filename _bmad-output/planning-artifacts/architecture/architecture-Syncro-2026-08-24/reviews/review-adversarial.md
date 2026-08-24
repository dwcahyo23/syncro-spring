# Adversarial Review — ARCHITECTURE-SPINE.md

**Reviewer:** Adversarial engine (module-level incompatibility analysis)
**Reviewed:** `ARCHITECTURE-SPINE.md` 2026-08-24 (239 lines, 14 ADs)
**Method:** Two-module thought experiment per AD — each unit obeys every AD literally yet builds incompatibly.

---

## Verdict

**FAIL — 3 critical, 4 high, 7 medium findings.** The spine permits at least 3 pairs of modules that, if built independently, produce conflicting data shapes, racing writers, or security boundary leaks. The worst gaps are **(a) three-way workorder-status ownership** (AD-4 explicit engine vs AD-5 derived recompute vs AD-8 external-master-wins), **(b) scope-dimension ambiguity** (section vs machine-group filtering leaks protected data), and **(c) unowned stock-mutation owner** (inventory vs sparepart-request both write `sparepart_stock`). Six of the 14 ADs need tightening or splitting; one new AD is needed for concurrency.

---

## Critical Findings

### C1 — Three-way workorder status writer (AD-4 ∧ AD-5 ∧ AD-8)

**Units:** *(a)* Sync upsert job (AD-7/8) — writes external status onto `work_orders.status` for SYNCED rows. *(b)* ON_PROCUREMENT recompute service (AD-5) — sets status to ON_PROCUREMENT when any sparepart-request is not READY, resets to IN_PROGRESS when all are READY. *(c)* Explicit state-machine engine (AD-4) — validates every transition via table-driven logic and writes `_status_history` rows.

**Conflict:** AD-8 says external master status wins; AD-5 says status is derived from sparepart-request state; AD-4 says every transition writes a history row. A synced workorder with a live non-READY request: sync sets status=IN_PROGRESS (AD-8); recompute sets status=ON_PROCUREMENT (AD-5); next sync re-sets to IN_PROGRESS (AD-8). The status column oscillates. The recompute is not a "transition" per AD-4 — it may not write a history row, so the 4h-ack clock (AD-5/FR-181) counts stale window. A terminal workorder (DONE/CLOSED) can be reopened by sync (AD-8 external wins), which AD-4 forbids. No AD defines precedence among the three writers.

**AD gap:** AD-5 recompute is not referenced in AD-4's transition rules; AD-8 does not exclude terminal states from the "external wins" rule; no AD states which writer logs the status-history row for derived transitions.

**Proposed fix:** **Tighten AD-5** — add rule: ON_PROCUREMENT recompute writes a `_status_history` row with `derived=true`; the recompute runs only on transition events (sparepart-request READY/not-READY), not on a poller. **Tighten AD-8** — add rule: external status does not override a locally-derived ON_PROCUREMENT state; external DONE/CLOSED must not regress a workorder with active requests. Add rule: `sync_version` and `last_derived_at` columns to detect races.

---

### C2 — Authorization scope-dimension ambiguity (AD-2 ∧ AD-1 ∧ AD-13)

**Units:** *(a)* Org/scope-derivation service (AD-2/AD-13) — computes `machineGroupIds` a user can access based on `machine_responsibilities.level >= LEADER` + active cross-plant team memberships. *(b)* Maintenance query-layer row filter (AD-1/AD-2) — applies a SQL WHERE clause to workorders/preventive/sparepart-requests. *(c)* OPA input assembler (AD-1/A3) — builds the subject JSON for per-resource decisions.

**Conflict:** AD-2: "section leader read/write only their own machine groups." AD-2: "derived scope is passed in OPA input and also used for SQL row filtering." But the glossary defines sections as multi-group containers (MACHINERY owns Forming + Rolling + CNC). FR-103 explicitly forbids a Forming leader from seeing Rolling workorders *even within the same section*. If the maintenance query layer filters by `section_id` (because the org module assigned groups to sections per FR-101), the leader sees sibling-group workorders — a security violation. If the filter uses `machine_group_id` (correct per AD-2), but the org module's scope-derivation returns `sectionIds` instead of `machineGroupIds`, the inputs don't match. The three units can independently produce three different scope sets. Cross-plant teams (AD-13) must be in the SQL filter, but AD-13 only mentions OPA input — the row-filtering unit may omit teams entirely, causing OPA to allow but the query to return zero rows (denied-in-practice) or vice versa.

**AD gap:** No AD defines the exact set of scope dimensions (`plantIds`, `sectionIds`, `machineGroupIds`, `machineIds`, `teamIds`) that every query filter must use. No AD mandates a single scope-derivation service (org module) that all modules consume; each module may recompute scope independently and diverge.

**Proposed fix:** **Tighten AD-2** — pin the scope dimensions to `{plantIds, machineGroupIds, activeTeamIds}` (section is a container, not a scoping dimension). Add rule: scope derivation is a single service in the `org` module; the `maintenance` query layer must consume the org service, not recompute. **Tighten AD-13** — add rule: cross-plant team machine IDs are merged into the machine-group scope for SQL filtering; the derived scope passed to OPA and to SQL shall be identical.

---

### C3 — Sparepart-stock dual mutation owner (AD-11 ∧ AD-4 ∧ FR-144)

**Units:** *(a)* Inventory storekeeper service (FR-146/FR-145) — records stock-in, stock-out, and MRE codes. *(b)* Sparepart-request state machine (AD-4/FR-141) — transitions PICKED_UP → CLOSED, which should decrement `stock_on_hand`. *(c)* PENDING_COMPLETION completion (FR-144) — INVENTORY_MAINTENANCE writes a material code onto the Phase 1 `spareparts` master record, which lives in the `masterdata` module.

**Conflict:** AD-11 defines `sparepart_stock(material_code, plant_id, stock_on_hand, ...)` but does not name who mutates `stock_on_hand`. The storekeeper does stock-in (inventory); the PICKED_UP transition should decrement (maintenance). Two modules write the same row. AD-11's "reorder warning is a business-rule signal" doesn't say who evaluates it or who creates the purchase request. FR-144's "material code links to the existing sparepart/material-code identity" requires the maintenance module to write a Phase 1 `spareparts` row — a cross-module write forbidden by the inherited invariant unless mediated by an application service that does not exist in the spine. The Convention "counter deltas backend-owned" does not cover stock — stock is not a counter; it's a quantity that two writers can race on.

**AD gap:** AD-11 has no single mutation owner, no concurrency rule (`version` optimistic lock per convention but not enforced), and no mediation path for the cross-module new-item write. The inherited invariant (module-local repos) directly conflicts with FR-144's requirement.

**Proposed fix:** **New AD** — "Stock mutation is owned by the inventory module; PICKED_UP decrements via an application service call to the inventory module, gated by OPA (AD-1)." Add rule: `sparepart_stock` mutations use `version` optimistic lock; `stock_on_hand` must never go negative. **Tighten AD-11** — add rule: new-item completion (FR-144) calls the `masterdata` module's application service to create a sparepart record; the maintenance module does not write `spareparts` directly.

---

## High Findings

### H1 — Sync module writes workorder tables directly (AD-7/AD-8 vs inherited module-local repo rule)

**Units:** *(a)* Sync importer (AD-7/AD-8) — reads external PostgreSQL, maps to Syncro workorder fields, upserts `work_orders` table. *(b)* Maintenance workorder application service (AD-4) — owns state transitions, history rows, audit. *(c)* Notification service (AD-9/FR-180) — enqueues notifications on workorder lifecycle events.

**Conflict:** AD-7 describes the sync job doing "upsert-idempotent by external id" and AD-8 defines "external master fields win" — both imply the sync module writes `work_orders` master fields directly. But the inherited invariant says "New modules may not reach into another module's repository." `work_orders` is owned by `maintenance.workorder`. If sync writes directly (no call to maintenance app service), it bypasses: history rows (AD-4), audit (AD-4), notification triggers (AD-9/FR-180), and the ON_PROCUREMENT recompute trigger (AD-5). If sync must call the maintenance upsert service, AD-7 does not specify the contract, and the sync module's batch processing (inside a transaction) would not compose with the maintenance service's transaction boundaries. Also, sync's WA notification (AD-7 "notifies only on newly created / important transitions") and maintenance's FR-180 notifications fire on the same events → double WAHA sends.

**AD gap:** No AD states whether sync writes via the maintenance module's application service or directly. No AD assigns who decides "important transition" for notification deduplication. The inherited invariant directly contradicts the implication of AD-7/AD-8.

**Proposed fix:** **Tighten AD-7** — add rule: sync module must call `maintenance.workorder.application.WorkorderImportService` (single upsert method) for every row; the service owns status history, audit, ON_PROCUREMENT evaluation, and notification enqueueing. Sync module does not write `work_orders` via JPA. **Tighten AD-8** — add rule: sync does not notify independently; only the maintenance module's transition logic triggers notifications (FR-180). Remove "notifies only on newly created / important transitions" from AD-7 to avoid duplication.

---

### H2 — Machine and plant identity mapping for synced workorders (AD-7/AD-8 gap)

**Units:** *(a)* Sync mapper (AD-7) — maps external machine code e.g. `BF-08410` to Syncro `machine_id`. *(b)* Org/masterdata module — owns the `machines` table and machine-group→section→plant hierarchy. *(c)* OPA input derivation (AD-1) — requires `machineGroupId` and `plantId` for every workorder, which come from the mapped machine.

**Conflict:** The external system sends a machine code that may not exist in Syncro (e.g., a new machine commissioned but not yet registered in Syncro's masterdata). AD-7 is silent: quarantine the row? Create a stub machine? Skip? Meanwhile, OPA row-scoping (AD-1/AD-2) depends on the machine's plant and machine-group — if the machine isn't mapped, the workorder is invisible to every user, even though it was synced successfully. Same for plant code: external plant "GM1" maps to Syncro plant; if the mapping is wrong or missing, a synced workorder lands in the wrong scope. External category codes (OQ-4 unresolved) — what external calls "03 Emergency" may map to "01 Breakdown" for MTBF (AD-6). If sync and MTBF use different mappings, MTBF misses workorders.

**AD gap:** AD-7 defines the sync pipeline (batch, upsert, watermark, lock) but is silent on the machine/plant/category mapping step. AD-8 mentions "machine" as a master field but doesn't prescribe the mapping mechanism. OQ-4 is unresolved and directly affects AD-6.

**Proposed fix:** **Tighten AD-7** — add rule: sync includes a mapping step that resolves external machine code → Syncro `machine_id` via a configurable mapping table; unmapped machines quarantine the row with a clear reason. External plant code → Syncro `plant_id` resolution follows the same pattern. **Tighten AD-8** — add rule: external category is mapped per a configurable mapping (resolve OQ-4 before implementation); unmapped categories default to a configurable fallback.

---

### H3 — 4-hour ack clock vs derived ON_PROCUREMENT history gap (AD-5 ∧ AD-4 ∧ FR-181)

**Units:** *(a)* 4-hour ack escalation worker (FR-181/AD-9) — computes elapsed IN_PROGRESS time excluding ON_PROCUREMENT; sends WAHA at threshold. *(b)* ON_PROCUREMENT recompute service (AD-5) — sets status when request state changes. *(c)* State machine engine (AD-4) — writes `_status_history` rows.

**Conflict:** AD-5 says "the 4-hour ack clock excludes ON_PROCUREMENT time." The escalation worker needs to know when the workorder was in ON_PROCUREMENT. If the recompute service (AD-5) does not write a `_status_history` row (because it's a derived transition, not a human action), the escalation worker can only see the last human-written status. It has no way to know the ON_PROCUREMENT interval. Two implementations: (a) escalation worker reads `_status_history` rows and computes elapsed = sum of IN_PROGRESS segments; (b) escalation worker watches current status and computes from last human transition. If unit (a) finds no history rows for ON_PROCUREMENT, it counts the entire period as IN_PROGRESS (wrong). If unit (b) polls and sees the derived status, it may race with the recompute.

**AD gap:** AD-5 derives the status but does not mandate history rows for the derived state. AD-4 says "every transition writes a `_status_history` row" but doesn't define derivations as transitions. The escalation worker's reference data source (history vs live status) is not specified.

**Proposed fix:** **Tighten AD-5** — add rule: the ON_PROCUREMENT recompute MUST write a `_status_history` row with `source='DERIVED'` and `actor='SYSTEM'`. **Tighten AD-4** — add rule: "every transition" includes derived transitions; derived history rows are distinguishable by `source` field. The escalation worker MUST use `_status_history` rows (not live status) to compute elapsed time.

---

### H4 — Sync mutates MTBF/MTTR inputs and can reopen closed workorders (AD-6 ∧ AD-8 ∧ AD-4)

**Units:** *(a)* MTBF/MTTR computation service (AD-6) — reads `woStopAt` per breakdown workorder, computes inter-arrival times. *(b)* Sync upsert (AD-7/AD-8) — overwrites `woStopAt`, `status`, and `category` from external. *(c)* Workorder close service (AD-4) — transitions DONE → CLOSED.

**Conflict:** AD-8 says external master fields (status, timestamps, machine, category) win. If the external system updates `woStopAt` (e.g., corrects a timestamp), the MTBF computation (AD-6) sees a different value — but MTBF was already computed for the previous period. No invalidation rule exists. Worse, AD-8 lets sync overwrite status from DONE → OPEN (external reopens a workorder). AD-4's terminal state (DONE/CLOSED) is not protected from sync. The parent-child close rule (FR-120) is violated when sync flips a child back to non-CLOSED. The parent was already closed based on the child's CLOSED state; now it's invalid. No AD prevents this.

**AD gap:** AD-8 has no "terminal state protection" clause. AD-6 has no staleness/cache-invalidation rule for when sync modifies analytics inputs. FR-120's parent-child rule has no enforcement against sync mutating child status.

**Proposed fix:** **Tighten AD-8** — add rule: sync must not transition a CLOSED or DONE workorder out of that state; if external sends a regressed status, the row is quarantined with a `TERMINAL_STATE_PROTECTED` code. Override permitted only for SUPER_ADMIN locally. **Tighten AD-6** — add rule: MTBF/MTTR are recomputed from the current dataset; a `last_analytics_at` watermark can be used for incremental computation, but sync mutations invalidate the cache. **New AD** — parent-child close rule (FR-120) must use a pessimistic lock or version check when closing a parent; sync upsert of a child must check parent's close status and reject if parent is CLOSED.

---

## Medium Findings

### M1 — AD-9 polymorphic outbox: idempotency key and alert_id constraint undefined

**Units:** *(a)* Notification enqueue service (AD-9) — writes `notification_jobs` with `target_type/target_id`. *(b)* Notification worker (Phase 1 Epic 5) — dedupes by `alertId + escalationLevel + recipientId`. *(c)* Flyway migration (AD-9) — adds nullable columns to `notification_jobs`.

**Conflict:** Phase 1 dedupe key is `alertId + escalationLevel + recipientId`. For WO notifications, `alert_id` is NULL (no alert). The dedupe key is undefined. If the worker skips dedupe for NULL alert_id, double sends. The constraint "alert_id is extended, not dropped" is ambiguous: does `alert_id` become nullable? If the migration keeps it NOT NULL and adds a default, WO notifications can't use it. If it becomes nullable, existing rows are fine. Two devs: one keeps NOT NULL and breaks WO notifications; one makes it nullable and breaks the existing FK in a different direction.

**AD gap:** No idempotency key for polymorphic targets defined. `alert_id` nullability not specified. Who enqueues the notification (maintenance service directly vs notification service) not stated.

**Proposed fix:** **Tighten AD-9** — add rule: dedupe key for polymorphic targets is `target_type + target_id + template_name + recipient_id`. `alert_id` becomes nullable; the FK constraint is made deferrable or dropped (existing rows are not affected). Add rule: the maintenance module enqueues notifications via the `notification` module's application service, not by writing `notification_jobs` directly.

---

### M2 — OPA input assembly contract not bound in AD-1 (lives only in addendum A3)

**Units:** *(a)* Authz interceptor (AD-1) — builds OPA input for the coarse `allow` check. *(b)* Maintenance application service (AD-1) — builds OPA input for per-resource decision. *(c)* Allowed-actions endpoint (FR-161) — builds OPA input for frontend action lists.

**Conflict:** AD-1 says "minimal input (subject/resource/action/context)" but the exact shape lives only in addendum A3, which is not referenced by the AD. Three modules can assemble three different JSON inputs: the interceptor may omit `resource.attributes.estCost`, the maintenance service may include it, and the allowed-actions endpoint may use a third schema. Policies written against one input shape silently return different results for the others. The addendum (A3) is authoritative for mechanism but AD-1 does not bind it.

**AD gap:** AD-1 does not mandate a single OPA input assembler, does not reference A3, and does not require the interceptor and per-resource decisions to use the same input schema.

**Proposed fix:** **Tighten AD-1** — add rule: OPA input assembly is a single shared service (`PolicyDecisionPoint`) in the `authz` module; the interceptor, application services, and allowed-actions endpoint all use it. The input schema is defined in A3 (add explicit reference: "the input schema in Addendum A3 is authoritative"). Add rule: a failing OPA call (sidecar down) must default-deny except for a configurable degraded-mode allowlist.

---

### M3 — Parent-child close race with sync (FR-120 ∧ AD-8)

**Units:** *(a)* Workorder close service (FR-120) — checks `SELECT COUNT(*) FROM work_orders WHERE parent_id = ? AND status NOT IN ('CLOSED', 'CANCELLED')` to block parent close. *(b)* Sync upsert (AD-7/AD-8) — updates child workorder status from external.

**Conflict:** The parent close check reads child statuses in a transaction T1. Concurrently, sync upsert T2 updates a child from CLOSED to OPEN (external reopened). The close check sees the child as CLOSED (T1 snapshot), allows parent close. Now the child is OPEN but the parent is CLOSED — violating FR-120. Conversely, sync could close a child just as the parent close check is running, causing a false positive block. No locking or ordering rule prevents this.

**AD gap:** No AD defines locking semantics for parent-child close. AD-3 mentions parent_id but no cross-row consistency.

**Proposed fix:** **Tighten AD-3** — add rule: closing a parent requires a `SELECT ... FOR UPDATE` on all children's statuses within the same transaction. Sync upsert of a child workorder must check parent's `status` and reject the upsert with a quarantine entry if the parent is CLOSED (unless SUPERVISOR override).

---

### M4 — Production-leader workorder creation idempotency (FR-110 gap)

**Units:** *(a)* Frontend create-workorder form (FR-110) — POST with retry-on-error. *(b)* Backend create-workorder service (AD-3) — generates `WO-YYMMxxxx` ID, inserts.

**Conflict:** AD-3 ensures no duplicate IDs (transaction + row lock), but a double-submit (user double-clicks, network retry) creates two workorders with different IDs. The same breakdown is reported twice. The frontend uses Orval-generated clients (inherited), which may not implement idempotency keys. AD-3 prevents ID collision but not semantic duplication.

**AD gap:** AD-3 is silent on idempotency keys for creation. No AD defines whether the frontend sends an idempotency key or the backend dedupes by content.

**Proposed fix:** **Tighten AD-3** — add rule: the create-workorder endpoint accepts an optional `idempotencyKey` header; if present, the service dedupes by the key within a configurable window (default 5 minutes). If absent, semantic dedupe (same machine + category + reporter within 5 minutes) is advisory. The frontend sends `idempotencyKey` = UUID on creation POSTs.

---

### M5 — Stock OP/OQ concurrency (AD-11 gap)

**Units:** *(a)* Storekeeper stock-in/out service (FR-146) — writes `stock_on_hand`. *(b)* Sparepart-request PICKED_UP service (AD-4/FR-141) — should decrement `stock_on_hand` at pickup.

**Conflict:** Two concurrent writes to `sparepart_stock.stock_on_hand` lose updates. The Consistency Convention says "version optimistic lock" but AD-11 does not mandate it. The "counter deltas backend-owned" convention (from Phase 1) was written for machine counters, not stock quantities. A lost update means `stock_on_hand` drifts from reality, and the reorder warning (AD-11) fires incorrectly.

**AD gap:** AD-11 has no concurrency rule, no atomic-update pattern, and no negative-stock guard.

**Proposed fix:** **Tighten AD-11** — add rule: all `stock_on_hand` mutations use `version` optimistic lock; negative stock is rejected server-side. `PICKED_UP` decrements via the inventory module's application service (not by writing stock directly). The decrement is atomic: `UPDATE sparepart_stock SET stock_on_hand = stock_on_hand - :qty, version = version + 1 WHERE material_code = :mc AND plant_id = :pid AND stock_on_hand >= :qty AND version = :v`.

---

### M6 — Master-vs-operational field boundary not closed (AD-8)

**Units:** *(a)* Sync upsert (AD-8) — preserves "local operational fields." *(b)* Maintenance workorder service — writes operational fields.

**Conflict:** AD-8 lists "status, timestamps, machine, category" as master fields that external wins, and "report, evidence, ratings" as operational fields that local preserves. The list is not closed. Is `assigned_technician_id` a master or operational field? If the external system has no assignment concept, it's local. If it does, it's master. Is `sparepart_request[].id` an operational field? Two devs classify differently. A sync module that overwrites `assigned_technician_id` with a null external value (because external doesn't have it) would silently unassign the technician. A sync module that preserves it would keep an assignment from a previous external system state.

**AD gap:** AD-8's field classification is an open set. No AD defines the mechanism for declaring which fields are external vs local.

**Proposed fix:** **Tighten AD-8** — add rule: the field classification is defined in a configuration (`sync_field_mappings` table with columns: `field_name`, `domain='MASTER'|'OPERATIONAL'`). The sync module reads this mapping; unmapped fields default to MASTER. The `sync_version` field on the workorder tracks which fields were last updated by sync.

---

### M7 — Workorder id type contract across sources (AD-3 gap)

**Units:** *(a)* Sync module (AD-3/AD-7) — writes `sheet_no` as `work_orders.id` (VARCHAR, any format). *(b)* Frontend route generator (Orval) — generates `/api/v1/workorders/{workorderId}`, may URL-encode or not. *(c)* Internal workorder creation (AD-3) — generates `WO-YYMMxxxx` format.

**Conflict:** `sheet_no` from the external system could be numeric (`12345`), alphanumeric (`WO-2408-001`), or contain characters that conflict with URL encoding (`/`, `#`). The frontend route `{workorderId}` must handle arbitrary strings. The parent-child chain (AD-3) allows cross-source references: an internal child of a synced parent has `parent_id = '12345'` (a sheet_no). A developer typing `parent_id` as a FK to only INTERNAL workorders would break this. The `WO-YYMMxxxx` prefix is not guaranteed unique across history — after a year, `WO-25080001` and `WO-26080001` collide if the prefix doesn't include the year suffix (it does: `YYMM`). But the prefix space is small: `WO-2608xxxx` is only 9999 IDs per month. For a busy plant, this could exhaust.

**AD gap:** AD-3 does not specify the `id` column type (VARCHAR length, charset), does not define the collision-interval semantics across months, and does not specify parent_id typing for cross-source references.

**Proposed fix:** **Tighten AD-3** — add rule: `work_orders.id` is VARCHAR(50) UTF-8. Internal IDs use format `WO-YYMM-XXXXX` (5-digit sequence, 99999 per month). Parent_id is FK to the same table (arbitrary VARCHAR). The sequence resets monthly and is exhausted-safe (99999 per month; if hit, raise an operational alert). Add rule: frontend `{workorderId}` route params must be URI-encoded on the client and decoded server-side.

---

## Summary

| Tier | Count | Key theme |
|------|-------|-----------|
| Critical | 3 | Status writer race, scope-dimension leak, stock owner |
| High | 4 | Sync boundary, machine mapping, ack-clock gap, MTBF/terminal-state |
| Medium | 7 | Outbox idempotency, OPA input contract, parent-child race, creation idempotency, stock concurrency, field classification, id type |

**Action:** 6 ADs need tightening (AD-1, AD-3, AD-5, AD-7, AD-8, AD-11), 1 new AD needed (stock mutation concurrency + owner). Addendum A3 must be bound into AD-1. OQ-4 must be resolved before implementation. The three most dangerous pairs to build today are:

1. **Sync upsert vs ON_PROCUREMENT recompute** — will race on `work_orders.status` and produce inconsistent history.
2. **Scope-derivation service vs maintenance row filter** — will produce different access sets, leaking data or denying legitimate access.
3. **Inventory stock service vs sparepart-request service** — will lose updates on `stock_on_hand` and produce phantom reorder warnings.