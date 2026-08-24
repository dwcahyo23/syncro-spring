# Addendum — Syncro Maintenance Workorder, Preventive & OPA Authorization

> Technical depth, mechanism decisions, and implementation notes that earned a place but do not belong in the PRD itself. Anything restating an FR/Glossary term lives in the PRD; this file holds only what the PRD does not.

## A1. UI & Tabular Views (TanStack Table v9)

- **Decision:** Main operational views — workorder list/kanban-table, preventive schedules, sparepart requests, technician/MTBF dashboards — use **TanStack Table v9** (latest major) for tabular views, consistent with the familiar dense tabular pattern of the reference system (`E:\01 DEV\PRISMA ORM\iot.gm.remastered`).
- **Rationale:** The maintenance team is already fluent in dense table UIs; card/grid-first views would raise the adoption barrier for an internal tool. TanStack Table v9 is headless (pairs with shadcn/Radix), supports server-side sorting/filtering/pagination, and has stable v9 typing.
- **Project rules to honor:** server-side mode serializes sorting/filtering/pagination to backend query names; no hidden client-side filtering mixed with server pagination; row actions always driven by backend-provided allowed actions from OPA, never client-side reimplementation.
- **Scope note:** FR-119 kanban is a status-grouped tabular/list view, not a separate board engine.

## A2. Sync Module — Hardened Design (replaces reference sync)

The reference (`iot.gm.api.remastered`) synced via cron (`EVERY_MINUTE`) with a `PgQuery` class reading `sch_ot.mow_mtn_appm` — no transaction, no watermark, no lock, MTBF ordered by id (bug). Syncro's hardened version:

- Scheduled job; typed config (`SyncProperties`) for the external PostgreSQL connection — no hardcoded host/credentials.
- Batch upsert by `sheet_no` inside a transaction; watermark (`last_synced_sheet_no`) for resume; distributed lock (Redis/DB advisory) prevents concurrent runs.
- Ordering by `sheet_no` ASC (fixes the reference's ordering bug).
- Conflict rule: external master fields win; local operational fields (report, evidence, ratings) preserved; `sync_version` for detection.
- Failed records → `sync_quarantine` (reason, raw payload, traceId); `sync_runs` audit; health-dashboard observability (Phase 1 Epic 6 pattern).
- Timezone: external `Asia/Jakarta` → UTC on write.
- Notification hygiene: WA only on newly created workorders / important transitions, deduplicated (avoid the reference's notification storm).
- No push-back: external system has no API; MRE code is recorded manually as reference only.

## A3. OPA Integration — Mechanism

- **Deployment:** OPA as a sidecar HTTP service (REST Data API `POST /v1/data/syncro/authz/...`). Not embedded WASM/IR (`java-opa-sdk` is early-stage; WASM lacks many built-ins).
- **Spring Boot wiring:** `PolicyDecisionPoint` service via RestClient + Resilience4j (timeout/retry/circuit-breaker — same pattern as WAHA client in Phase 1). Coarse `allow` check in an interceptor; row-level scoping applied in query layer via derived scope; per-resource decisions inside application services.
- **Input:** minimal, self-contained JSON — `subject` (userId, roles, plantIds, sectionIds, machineGroupIds, active teamIds, production-line scope) + `resource` (type, id, plantId, sectionId, machineId, attributes e.g. category/status/owner/estCost) + `action` + `context`. Org data stays in PostgreSQL and is passed as input — OPA is never the source of truth for org data.
- **Frontend:** Spring endpoint `/api/v1/authz/allowed-actions` returns the allowed-actions set; Next.js renders menus/buttons from it (never calls OPA directly — NFR-013a). Hiding is UX only; enforcement remains server-side.
- **Policy-as-code:** Rego in repo; `opa test` in CI; versioned bundle (bundle API); status + decision-log APIs wired; decision logs masked for sensitive fields (WAHA secrets, phones) and retained (default 30 days).
- **Audit correlation:** OPA returns `decision_id` per decision; stored alongside the app audit record for join-back to the OPA decision log.

## A4. Approval Threshold & Cost Basis

- Approval threshold is based on **estimated cost** = request quantity × estimated price per unit.
- Estimated price source: latest `sparepart_price_entries` (Phase 1 Epic 8, FR-080) or the PENDING_COMPLETION completion data for new items.
- Tier defaults (configurable, v1): section-leader ≤ 5M IDR; maintenance-leader 5M–50M IDR; manager >50M IDR. SoD: requester ≠ approver.
- Deferred: MTTR labor-cost projection via UMR — session hours are recorded in v1; conversion is a future calculation. Shift-config operating-days-per-month is available in settings for future use.

## A5. WA Link Auto-Login (Production Leader)

- WAHA message contains a direct link carrying a **short-lived token** bound to the recipient's registered WA number; opening the link auto-authenticates that PRODUCTION_LEADER.
- Token lifecycle: expires after first use or a configurable TTL (default 15 min), whichever comes first; revoked on expiry, phone mismatch, or login reuse — a stale link never grants residual access; mismatch falls back to a normal login.
- The landing view is a task list: workorders acknowledged vs pending, and closed workorders rated vs unrated — solving the reference's notification pile-up / lost-rating problem.
- v1 trusts phone-number matching (no PIN/OTP); security hardening noted as PRD §6.2 deferred item for multi-plant rollout.
