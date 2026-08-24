# Rubric-Walker Review — ARCHITECTURE-SPINE.md

- **Review date:** 2026-08-24
- **Reviewer:** rubric-walker (automated)
- **Spine:** `_bmad-output/planning-artifacts/architecture/architecture-Syncro-2026-08-24/ARCHITECTURE-SPINE.md`
- **Grounding verified against:** Phase 1 `architecture.md`; PRD 2026-08-24 (FR-100..FR-181); brainstorm intent 2026-08-24; live codebase `syncro/apps/backend` (migrations V1..V40, `ApplicationRole`, `ResponsibilityLevel`, `GarageObjectStorageService`, `application.yml`, error-shape handlers); OPA GitHub releases API.
- **Method:** judge the spine against the 7-point good-spine checklist. Findings cite spine locations (line numbers) and are tiered critical / high / medium / low.

---

## Gate Summary

**Gate verdict: REVISE — not ready to bind stories.**

The spine is brownfield-faithful on every inherited convention it claims (verified below), the 14 ADs are mostly concrete and enforceable, and OPA 1.19.1 is verified current. But it misses the single most consequential divergence point this initiative owns — the **role-taxonomy migration** (Phase 1 `SUPER_ADMIN/MANAGE/VIEWER` vs the PRD's ten maintenance roles, enforced by a DB CHECK constraint) — and leaves the **operational/environmental envelope** (OPA sidecar deployment, sync-job hosting, bundle distribution, decision-log retention) entirely silent with no Open Questions section. Both must be fixed before story binding.

---

## Checklist Assessment

### C1 — Fixes the real divergence points for the level below and misses none → **PARTIAL**

Handled well (divergence → AD):
- WO dual-source ID collision & duplicate numbering → **AD-3** (L64)
- scattered state-transition conditionals → **AD-4** (L70)
- manual status flapping / downtime mis-attribution → **AD-5** (L76)
- frontend MTBF recalculation & reference id-ordering bug → **AD-6** (L82)
- reference sync's no-tx/no-watermark/no-lock/ordering/notification-storm failures → **AD-7, AD-8** (L88, L94)
- notification pipeline forking per entity type → **AD-9** (L100)
- image bytes in PostgreSQL / orphaned objects → **AD-10** (L106)
- per-machine stock fragmentation → **AD-11** (L112)
- IoT dependency blocking execution → **AD-12** (L118)
- permanent cross-plant access leaks → **AD-13** (L124)
- delegation bypass / self-rating CoI → **AD-14** (L130)
- manual section-leader setup vs derived scope → **AD-2** (L58)
- frontend-visibility-as-enforcement → **AD-1** (L52)

**Missed divergence points:**
1. **Role taxonomy** (see F-1, critical). Phase 1 persists `application_role IN ('SUPER_ADMIN','MANAGE','VIEWER')` (`db/migration/V1__create_auth_baseline.sql`, `auth/domain/ApplicationRole.java`); the PRD defines ten roles (SUPER_ADMIN, MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER, STAFF_MAINTENANCE, TECHNICIAN, INVENTORY_MAINTENANCE, STOREKEEPER, PRODUCTION_LEADER, AUDITOR). The spine's Inherited Invariants (L38–48), AD-1 (L52), and AD-2 (L58) treat "roles" as given and never bind the migration of the role model — the level below has no binding and will diverge (new roles table vs extended CHECK, MANAGE/VIEWER mapping, production-leader line scope).
2. **Approval SoD / threshold escalation (FR-142)** — see F-3, high.
3. **Parent-child close gate (FR-120)** — see F-5, medium.
4. **Manual vs derived ON_PROCUREMENT (FR-114 vs AD-5)** — see F-4, medium.

### C2 — Every AD's Rule is enforceable and actually prevents its stated divergence → **PASS**

All 14 ADs state a concrete, testable rule with an enforcement mechanism:
- AD-1 (L56): interceptor coarse check + query-layer row scoping + per-resource service decisions; decision_id correlated to audit; OPA `POST /v1/data/syncro/authz/<rule>` is the correct OPA data-API shape. Default-deny stated.
- AD-2 (L62): iff-derived leadership from `machine_responsibilities.level = LEADER`; scope passed in OPA input **and** used for SQL row filtering — dual enforcement, prevents the stated drift.
- AD-3 (L68): unique PK + transaction + row lock; `sync_version` guard; monthly per-prefix sequence.
- AD-4 (L74): explicit transition table; `INVALID_STATE_TRANSITION`; every transition writes `_status_history` + audit.
- AD-5 (L80): workorder state recomputed from request state; 4-hour clock excludes ON_PROCUREMENT (FR-181).
- AD-6 (L86): backend-computed; `woStopAt` ordering (not id) — kills the reference bug.
- AD-7 (L92): batch-in-transaction, watermark, distributed lock, quarantine with reason+payload, `sync_runs`, typed config, backoff, Jakarta→UTC, deduped notifications.
- AD-8 (L98): deterministic master-vs-operational split, audit-logged per run.
- AD-9 (L104): additive nullable `target_type`/`target_id`; existing rows unaffected. (Minor wording caveat → L2.)
- AD-10 (L110): Garage + key-only + delete-on-replace (ratifies `SparepartImageService`).
- AD-11 (L116): unique `(material_code, plant_id)`; reorder is a business-rule signal; PR action OPA-authorized.
- AD-12 (L122): calendar/shift scheduling from V40 shift config; "insufficient data" never fabricated.
- AD-13 (L128): expiry-dated membership excluded from OPA input — auto-revoke without redeploy.
- AD-14 (L134): self-execution prohibition + immutable ratings; production-leader rating bound to FR-124.

One soft spot: AD-3's collision-freedom is asserted via ID-format split (`sheet_no` vs `WO-YYMMxxxx`) without stating the external prefix convention (brainstorm intent: external `AP-` vs internal `WO-`); the unique PK makes collision safe regardless, so this is low (L4).

### C3 — Nothing under Deferred could let two units diverge → **PASS (one soft spot)**

Deferred entries (L232–238) are each a single-owner decision with a stated v1 behavior: sidecar-not-WASM, FMEA tag-only, session-hours-only, no OEE, manual MRE, browser-print, phone-bound WA link. None forks a shared integration point.

Soft spot: **"Approval threshold values — configurable; v1 defaults … pending confirmation"** (L238) is a live undecided item folded into Deferred. The tiers are PRD-assumption territory (FR-142) and story teams could implement different defaults; mitigated by "configurable" but should be either confirmed or explicitly escalated. Tracked under F-3.

### C4 — Named tech is verified-current → **PASS**

- OPA **1.19.1** (L185): verified current — GitHub releases API returns `latest tag_name: v1.19.1` (checked 2026-08-24). Matches the rubric expectation.
- Java 25 / Spring Boot 4.0.6 / Next.js 16.2.6 (L182–183): labeled "inherited, authoritative" — correct treatment for an extension spine.
- TanStack Table v9 "(addendum A1)" (L184): flagged as sourced from the PRD addendum; plausible, not independently verifiable here. No action.

### C5 — Ratifies rather than contradicts a brownfield codebase → **PARTIAL**

Every inherited invariant in the table (L38–48) was spot-checked against the codebase and holds:
- `ddl-auto=validate` + Flyway: `application.yml` L25; latest migration is V40 → **V41+ claim correct** (L41).
- Standard error shape `code/message/fieldErrors/timestamp/traceId`: matches all `*ExceptionHandler` `ErrorResponse` records.
- UTC timestamps, uppercase enums: consistent (`TIMESTAMPTZ DEFAULT NOW()`, `ResponsibilityLevel`, status enums).
- Plant scope via `auth_user_plant_assignments`: V2 migration confirmed.
- Job-scope ladder `TECHNICIAN < STAFF < LEADER < SPV < MANAGER` via `machine_responsibilities`: `ResponsibilityLevel` enum + `JobScopeService` confirmed (L45).
- WAHA outbox `notification_jobs` + `notification_attempts` + rate-limit + circuit breaker + escalation: V24/V25/V29/V30/V34/V36 confirmed.
- Garage key-only storage: `GarageObjectStorageService` + `spareparts.image_object_key` (V39) confirmed.
- Audit log, NFR-013a, REST `/api/v1`: confirmed.

The one contradiction-by-omission is the role model (F-1): the spine inherits "SUPER_ADMIN unrestricted; others masked to `auth_user_plant_assignments`" (L44) as if the Phase 1 role shape were compatible with the PRD's role taxonomy. It is not (the DB CHECK constraint forbids the new roles), and the spine neither ratifies nor supersedes it.

### C6 — Covers the spec's (PRD's) capabilities → **PARTIAL (plausible, with gaps)**

Capability → Architecture Map (L219–228) covers every FR range, and the AD binding is mostly plausible:
- Org FR-100..105 → AD-2, AD-13 ✓
- Workorder FR-110..124 → AD-3..6, AD-14 ✓ (with FR-120 gap → F-5)
- Preventive FR-130..134 → AD-12 ✓
- Sparepart FR-140..147 → AD-4, AD-5, AD-11, AD-9 (with FR-142 gap → F-3)
- Sync FR-150..154 → AD-7, AD-8 ✓
- OPA FR-160..164 → AD-1, AD-13 (with FR-162 retention gap → F-6)
- Dashboards FR-170..175 → AD-6, AD-10, AD-12 ✓
- WAHA FR-180..181 → AD-9, AD-5 ✓

Not explicitly bound anywhere in an AD: FR-112 (categories), FR-117 (CP/CPK), FR-119 (todos/kanban), FR-122 (stop-time reason), FR-143 (purchase URL), FR-145 (MRE), FR-174 rating-dimension config. These are workorder-module rules that *could* live below architecture altitude, but an initiative spine that claims "misses none" should at least acknowledge them (L3).

### C7 — Every owned dimension decided/deferred/open → **FAIL**

The spine decides the functional/integration dimensions well, but the **operational/environmental envelope is entirely silent**:
- **Deployment & environments:** how the OPA sidecar is hosted (container in the existing `infra/docker-compose.yml`? separate service? k8s later?), how the scheduled sync job is hosted, and whether the external PostgreSQL is reachable from the same network — nothing in the spine.
- **Infra/provider strategy:** Phase 1 deferred deployment target ("architecture stays container-friendly"); the spine never restates or extends this for the sidecar + external datasource.
- **Operations:** OPA bundle distribution/activation lifecycle (FR-162 demands versioned bundles + status API; the diagram shows "bundle+status+decision log" at L153 but no binding on deployment/activation), decision-log retention/masking (F-6), and the sync job's restart/resume observability beyond `sync_runs`.

There is also **no Open Questions section** in the spine at all (only "Deferred"), so PRD open questions (OQ-1..OQ-7) are neither decided nor explicitly escalated. The altitude owns this envelope; leaving it silent guarantees story-level divergence on where OPA runs and how policies reach it. → **F-2 (high)**.

---

## Findings (tiered)

### F-1 — CRITICAL — Role-taxonomy migration unbound (new PRD roles vs Phase 1 role constraint)

- **Location:** Inherited Invariants L44; AD-1 L56; AD-2 L62; AD-13 L128; AD-14 L134. Absent: any rule about the role model.
- **Problem:** The PRD introduces ten roles (SUPER_ADMIN, MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER, STAFF_MAINTENANCE, TECHNICIAN, INVENTORY_MAINTENANCE, STOREKEEPER, PRODUCTION_LEADER, AUDITOR) but the codebase persists `auth_users.application_role CHECK IN ('SUPER_ADMIN','MANAGE','VIEWER')` (`V1__create_auth_baseline.sql` L10) and `ApplicationRole{SUPER_ADMIN,MANAGE,VIEWER}`. Every Phase 1 service switches on `ApplicationRole` today. The spine says roles are "derived from PostgreSQL and passed as input" but never binds: (a) how the CHECK constraint is migrated (V41+), (b) how `MANAGE`/`VIEWER` map to the new taxonomy, (c) how MANAGER_MAINTENANCE (global), MAINTENANCE_LEADER (plant-assigned), PRODUCTION_LEADER (line-scoped, FR-124), and SECTION_LEADER (derived, AD-2) coexist with the existing plant-assignment model, (d) what exactly goes into OPA `subject.roles`. The level below will diverge (new roles table vs extended enum, inconsistent role→scope mapping).
- **Fix:** Add an AD (or an Inherited-Invariants row + migration binding): "Phase 1 `MANAGE`/`VIEWER` map to `MANAGER_MAINTENANCE`/read-only; the role column is extended by additive migration V41+ (new CHECK); OPA input `subject.roles` is the application role + derived scope per AD-2; PRODUCTION_LEADER scope is line/plant-derived from `auth_user_plant_assignments` (or a new binding); SUPER_ADMIN bypasses (Phase 1)." State the migration explicitly so stories cannot choose.

### F-2 — HIGH — Operational/environmental envelope left silent (checklist 7)

- **Location:** Whole spine; only adjacency is Deferred L232 ("revisit if infra cannot host a sidecar") and diagram L153. No deployment / environments / infra / operations sections; no Open Questions section.
- **Problem:** For an extension that adds its only new long-lived infrastructure (OPA sidecar) and a new scheduled worker (sync job) reaching an external PostgreSQL, the spine never decides how they are deployed, how OPA bundles are distributed/activated per environment, or how operations observe/restart them. PRD open questions (OQ-1..OQ-7) are neither decided nor escalated.
- **Fix:** Add an explicit dimension block (decided or deferred/open-question per item): sidecar containerized alongside the backend in the existing Docker Compose (inherited Phase 1 container-friendly stance); bundle build via `opa build` + activation via status API; sync job hosted as a Spring `@Scheduled` worker with watermark persistence; decision-log retention 30d configurable + input masking (bind FR-162); restate "deployment target deferred; container-friendly" so the extension inherits it. Escalate OQ-1..OQ-7 or explicitly mark each deferred.

### F-3 — HIGH — FR-142 approval SoD not bound; only threshold values deferred

- **Location:** AD-4 L74, AD-11 L116 (nearest); Deferred L238 ("Approval threshold values — configurable; v1 defaults … pending confirmation").
- **Problem:** "Approver ≠ requester" and "est. cost = qty × est. price; threshold tiers; no-price requires section-leader approval" (FR-142) is a security/separation-of-duty rule — a genuine divergence point (requester approving their own request, inconsistent tiering). The spine defers only the threshold *values*, not the SoD *rule*; nothing binds it to AD-1 (OPA) or the application layer.
- **Fix:** Bind SoD into an AD (or AD-4/AD-1): "requester can never approve their own request (server-side, enforced before OPA/state machine); tiering by est. cost is a business-rule signal computed in the application layer; tiers configurable via `escalation_configs` (v1 defaults ≤5M / 5M–50M / >50M IDR); when est. price is absent, request requires section-leader approval regardless of quantity."

### F-4 — MEDIUM — AD-5 pure derivation vs FR-114 manual ON_PROCUREMENT placement unresolved

- **Location:** AD-5 L80 ("The workorder state is recomputed from request state") vs FR-114 ("a section leader can manage status (including placing/removing a workorder on ON_PROCUREMENT)").
- **Problem:** If ON_PROCUREMENT is fully derived from live non-READY requests, a leader's manual placement is immediately overridden by the recompute — the two rules contradict and a story team must guess which wins. Unstated = divergence.
- **Fix:** State the precedence: ON_PROCUREMENT is derived when any live request is non-READY; manual placement is allowed only when no request exists, and is cleared by the same derivation (or explicitly remove manual placement and note the PRD deviation).

### F-5 — MEDIUM — FR-120 parent-child close gate not bound

- **Location:** AD-3 L68 mentions `parent_id` only; AD-4 L74 lists transitions without the gate; Capability map L222 covers "parent-child" generally.
- **Problem:** "Parent cannot CLOSED while any child is not CLOSED/CANCELLED; SUPER_ADMIN/MANAGER override with note" is a concrete workflow rule the level below could implement differently (or skip), and it interacts with AD-4's CLOSED transition and AD-14's leader-close action.
- **Fix:** Extend AD-4's rule: "DONE → CLOSED requires no non-terminal children (machine-readable blocking code); override is a SUPER_ADMIN/MANAGER action audit-logged with reason."

### F-6 — MEDIUM — FR-162 decision-log retention & masking not bound

- **Location:** AD-1 L56, diagram L153 (decision log + status API); PRD assumption "30 days retention, configurable; masking of sensitive fields" (FR-162) is not carried into any AD/Deferred.
- **Problem:** Decision-log retention and input masking are observable, divergence-prone ops properties (retention too short kills the auditor use case FR-164; missing masking leaks WAHA secrets/full phone numbers). The spine's AD-1 binds decision_id correlation but not the log's lifecycle.
- **Fix:** Add to AD-1 (or a Consistency row): "OPA decision logs enabled with input masking for sensitive fields (no WAHA secrets, no full phone numbers); retention 30 days configurable." 

### L-1 — LOW — AD-9 "alert_id semantics remain untouched" overstates the migration

- **Location:** AD-9 L104.
- **Problem:** To support polymorphic targets, `notification_jobs.alert_id` (`V24` L4, `NOT NULL REFERENCES sparepart_alerts(id)`) must become nullable; the existing `uq_notification_jobs_alert_level (alert_id, escalation_level)` and index stay. "Remain untouched" is loose; a pedantic implementer may refuse the nullability change.
- **Fix:** Re-word: "alert_id becomes nullable; existing alert rows keep their non-null alert_id, unique (alert_id, escalation_level) and idempotency_key semantics are preserved (additive migration)."

### L-2 — LOW — Rating-dimension configurability (OQ-1) neither decided nor deferred

- **Location:** AD-14 L134 binds ratings; PRD OQ-1 asks who configures dimensions (SUPER_ADMIN vs MANAGER_MAINTENANCE) and the default set; spine silent.
- **Problem:** FR-121/124 say dimensions are "configurable — data, not code"; if the set of dimensions is data, its configuration authorization is an authorization decision that belongs under AD-1. Silent = two teams could model it differently.
- **Fix:** One line in AD-14 or Deferred: "rating dimensions are data, configured by SUPER_ADMIN (default dimension set pending confirmation), authorization via AD-1."

### L-3 — LOW — Minor unbound workorder rules unacknowledged

- **Location:** Capability map L222 ("FR-110..FR-124"); no AD binds FR-112 (categories), FR-117 (CP/CPK), FR-119 (todos/kanban), FR-122 (stop-time reason), FR-143 (purchase URL), FR-145 (MRE).
- **Problem:** All are below architecture altitude, but a spine claiming to "miss none" should at least acknowledge them so the level below knows they are module-owned (not forgotten).
- **Fix:** Add a "Module-owned rules" note under the map or one sentence in the relevant AD acknowledging these are application-layer rules in `maintenance.workorder` / `maintenance.sparepartrequest`.

### L-4 — LOW — AD-3 collision-freedom relies on unstated ID-format split

- **Location:** AD-3 L68.
- **Problem:** Collision freedom between `sheet_no` and `WO-YYMMxxxx` is asserted via format; the external prefix convention (`AP-`, per brainstorm intent) is not stated. Unique PK makes it safe regardless, so this is informational.
- **Fix:** Optionally state the external prefix convention or note that PK uniqueness is the actual guard.

---

## Counts

- **Critical:** 1 (F-1)
- **High:** 2 (F-2, F-3)
- **Medium:** 3 (F-4, F-5, F-6)
- **Low:** 4 (L-1, L-2, L-3, L-4)
- **Total:** 10

## What is strong (keep on revision)

- Inherited-invariants table is accurate and verifiable against the codebase (V41, ddl-auto=validate, WAHA outbox, Garage, ladder, plant scope, NFR-013a).
- OPA 1.19.1 verified current.
- AD-1's three-layer enforcement (interceptor + query scoping + service decisions) is the right shape and the endpoint path matches OPA's data API.
- AD-7/AD-8 are complete, enforceable hardening rules that directly target the reference sync's failures.
- AD-2's iff-derived section leadership correctly ratifies the existing `machine_responsibilities` ladder.
