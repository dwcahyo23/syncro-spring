# Sprint Change Proposal — Sparepart Procurement Readiness & Operating Calendar

- **Date:** 2026-08-23
- **Trigger:** New stakeholder requirement (product owner Yusuf) raised after Epic 7 pilot completion
- **Workflow:** bmad-correct-course (Incremental review mode)
- **Status:** APPROVED — all five edit proposals approved individually during incremental review

---

## 1. Issue Summary

Syncro Phase 1 (Epics 1–7) is functionally complete: master data, MQTT telemetry ingestion, sparepart lifetime threshold alerting, WAHA escalation, health diagnostics, and pilot validation are all `done`. During post-pilot review, the product owner identified a capability gap that blocks real procurement planning:

1. **No inventory recap identity** — identical spareparts installed on different machines cannot be aggregated for maintenance inventory because spareparts have no shared material code.
2. **No calendar-time projection** — lifetime is expressed only in production counters and consumed percentages; teams cannot answer "how many days until this sparepart must be replaced?" because operating calendars (shifts) are not modeled.
3. **No procurement readiness data** — lead time, estimated price, currency conversion evidence (kurs), and sparepart imagery do not exist, so replacement purchasing cannot be planned or costed.

The PRD explicitly listed *Inventory maintenance management* as a Phase 1 Non-Goal (full IMMS deferred to Phase 3). This change pulls forward a narrow, well-defined slice of IMMS groundwork while keeping full IMMS out of scope.

## 2. Impact Analysis

### Epic Impact
| Epic | Impact |
|---|---|
| Epic 1–7 | **None.** All stories remain valid and `done`. No rework, no rollback |
| **Epic 8 (new)** | Added: Sparepart Procurement Readiness & Operating Calendar (7 stories) |
| Phase 2 CMMS / Phase 3 IMMS roadmap | Note added: Epic 8 is the bridge; full IMMS remains deferred |

### Story Impact
No existing stories change. Seven new stories (8.1–8.7) enter the backlog. Dependencies: 8.1 → 8.4; 8.5 → 8.6 → 8.7; 8.7 also requires 8.2.

### Artifact Conflicts
| Artifact | Conflict | Resolution |
|---|---|---|
| PRD §4 Non-Goals | *"Inventory maintenance management"* contradicts adding procurement attributes | Carve-out exception documented |
| PRD §7 | No FRs for material code, lead time, price/kurs, shift config, counter-rate estimation, images, job-scope permissions | New §7.11 with FR-078–FR-089 |
| PRD §9 | No calculation rules for rate estimation / operating calendar / price normalization | New §9.6–§9.8 |
| Architecture | No object storage service; no price/shift data model; no job-scope enforcement pattern; no moving-average calculation component | Additive deltas (Garage service, tables, modules, API contracts) |
| UX spec | No components for currency input, price history, shift editor, inheritance badge, projections | 8 new shared components + 5 screen changes |

### Technical Impact
- **New infrastructure service**: Garage (S3-compatible object storage) in Docker Compose — selected via research over MinIO CE (archived Apr 2026, unmaintained), SeaweedFS (overweight for need), RustFS (alpha).
- **Schema migrations**: additive Flyway migrations only; unique global constraint on `spareparts.material_code`; new append-only `sparepart_price_entries`; new shift config tables.
- **First server-side job-scope enforcement** (LEADER+): extends FR-025 direction toward ABAC; application-role baseline still required.
- **Alert evaluation extension**: second condition producing distinct `PROCUREMENT_RISK` alerts without touching existing percentage-threshold behavior.
- Money precision via `BigDecimal` (existing project rule); counter-rate caching in Redis with explicit TTL (NFR-001a); all timestamps UTC with plant-local wall clock only at shift-definition boundary (rule #252).

## 3. Recommended Approach

**Selected path: Option 1 — Direct Adjustment** (add Epic 8 within existing plan).

- **Option 2 (Rollback)** rejected: nothing built conflicts with the change.
- **Option 3 (MVP Review)** rejected: MVP already achieved and validated; this is purely additive scope.

| Dimension | Assessment |
|---|---|
| Effort | Medium — 7 stories across backend, infra, frontend |
| Risk | Low–Medium — novel pieces: Garage integration, first job-scope enforcement, moving-average estimator (mitigated by Testcontainers, controlled-clock unit tests, contract tests) |
| Timeline | No existing work invalidated; Epic 8 starts immediately (dependencies from Epics 2/3 already done) |
| Rationale | All design decisions locked during elicitation; greenfield addition on a stable foundation; zero rework of completed stories |

## 4. Locked Design Decisions

| # | Decision |
|---|---|
| D1 | Material code: **global scope**, manually entered, **optional** — installation/lifetime flows run without it |
| D2 | Shift config precedence: **machine > machine group**; UI shows inherited-source badge when falling back |
| D3 | Counter rate: **rolling 30-day moving average** per operating hour; fallback full-history average; explicit insufficient-data state |
| D4 | Kurs: **manual entry at price input time**; mandatory kurs-to-IDR when currency ≠ IDR (default IDR); previous entry may be reused when unchanged |
| D5 | Mutation permission: **job scope LEADER and above**, enforced server-side |
| D6 | Sparepart image: **global per sparepart**, stored in **Garage**, PostgreSQL stores URL/key only |
| D7 | Shift bounds: max 3 shifts/day, cross-midnight permitted |
| D8 | Helper feeds **alert logic too**: procurement-risk alert when projected depletion falls within lead-time window (display-only projections additionally provided) |
| D9 | Lead time granularity: fractional hours allowed |

## 5. Detailed Change Proposals (all APPROVED)

### 5.1 PRD (`prds/prd-Syncro-2026-05-22/prd.md`)
1. §4 Non-Goals: carve-out exception for basic procurement attributes (bridge note to Phase 3 IMMS).
2. New §7.11 "Sparepart Procurement Readiness & Operating Calendar": FR-078 optional globally-unique manual material code; FR-079 optional lead time (fractional hours); FR-080 price entries (BigDecimal amount, ISO-4217 currency default IDR, mandatory kurs-to-IDR snapshot when foreign, entered-by/at); FR-081 retained viewable price history with reuse action; FR-082 one global image in Garage with URL-only in PostgreSQL; FR-083 group shift config (≤3 shifts, cross-midnight allowed); FR-084 machine override winning over group config with UI inheritance indication; FR-085 rolling 30-day counter-rate estimation rules; FR-086 calendar-time depletion and lead-time consumption projections; FR-087 procurement-risk alert (distinct type/reason, duplicate-prevented); FR-088 LEADER+ server-side enforcement; FR-089 immutable audit coverage.
3. §9 additions: 9.6 Counter Rate formula/window/fallback/freshness; 9.7 Operating Calendar precedence and timezone rule; 9.8 Price Normalization (`amount × kursToIdr`, IDR-default display).
4. §5 note: first server-side job-scope enforcement (ABAC direction per FR-025).
5. §8 Minimum Screen Set: price history view; form extensions noted.

### 5.2 Architecture (`architecture.md`) — additive delta section
1. **Garage service** in `infra/docker-compose.yml` (stable name `garage`, AR-006 pattern); typed S3 properties; presigned GET URLs (short TTL); optional health indicator (Epic 6 pattern).
2. **Data model**: `spareparts.material_code` (nullable, `uq_spareparts_material_code`), `spareparts.lead_time_hours NUMERIC(12,2)`, `spareparts.image_object_key`; new append-only `sparepart_price_entries` (amount NUMERIC(19,4), currency CHAR(3) default 'IDR', kurs_to_idr NUMERIC(18,6) conditional, normalized IDR amount, actor, timestamp); new `machine_group_shift_configs` + shift window rows and `machine_shift_configs` override table (precedence machine > group).
3. **Backend modules**: `CounterRateEstimator` (30-day rolling window via typed config, fallback, staleness) and `OperatingCalendarCalculator` (shift-aware effective operating time, cross-midnight handling, plant-local timezone typed config default Asia/Jakarta); procurement-risk extension of Epic 4 evaluation path (`PROCUREMENT_RISK`, dedupe key installation+type); job-scope permission utility in auth/security module; Redis cache TTL for computed rates.
4. **API contracts** under `/api/v1`: `PATCH /spareparts/{id}`; `POST|GET /spareparts/{id}/price-entries`; `POST|DELETE /spareparts/{id}/image`; `PUT /machine-groups/{id}/shift-config`; `PUT|DELETE /machines/{id}/shift-config`; projections embedded in machine-hub/installation responses.
5. **Testing strategy**: Testcontainers for migrations/uniqueness (no DB mocks), controlled-clock units for estimators, integration tests for procurement-risk create/dedupe/role-denial, locked DTO contracts, Orval client regeneration; optional pilot seed extension.

### 5.3 UX (`ux-design-specification.md`, `page-specifications.md`)
New shared components: `CurrencyPriceInput`, `PriceHistoryTable`, `MaterialCodeField`, `LeadTimeInput`, `SparepartImageUpload`, `ShiftConfigEditor`, `InheritedConfigBadge`, `CounterRateProjectionCard`.
Screen changes: Sparepart management form/list extensions; Machine group shift editor; Machine Hub override editor + inheritance badge + projections card; Alert views render `PROCUREMENT_RISK` type label/reason; LEADER+ gating with disabled-reason copy. All per UX-DR-019 state coverage, WCAG AA, non-color-only status.

### 5.4 Epics (`epics.md`)
New **Epic 8: Sparepart Procurement Readiness & Operating Calendar** covering FR-078–FR-089:
- 8.1 Add Garage object storage & backend integration
- 8.2 Manage sparepart material code & lead time
- 8.3 Manage estimated price entries with currency & kurs
- 8.4 Manage sparepart image via Garage
- 8.5 Configure shift schedule (group) with machine override
- 8.6 Estimate counter rate & shift-aware projections
- 8.7 Raise procurement-risk alert within lead-time window

Recommended sequence: 8.2 ∥ 8.5 → 8.6 → 8.7; 8.1 before 8.4.

### 5.5 Sprint Status (`sprint-status.yaml`)
Add `epic-8: backlog` plus story keys 8-1 … 8-7 as backlog; refresh `last_updated`.

## 6. Implementation Handoff

**Scope classification: MODERATE** — backlog reorganization required; no fundamental replan.

| Recipient | Responsibility |
|---|---|
| Correct Course executor (this session, on approval) | Apply approved edits to prd.md, architecture.md, epics.md, sprint-status.yaml |
| Sprint Planning (`bmad-sprint-planning`) | Refresh sprint plan including Epic 8 ordering |
| Create Story → Dev Story → Code Review cycle | Implement 8.x stories per established quality gates |
| TEA (optional) | ATDD scaffolds for 8.6/8.7 calculations before dev |

### Success Criteria
1. Identical spareparts on different machines aggregate under one material code in inventory recap queries.
2. Machine Hub shows shift-aware depletion projections with freshness evidence, degrading gracefully to "insufficient data".
3. A procurement-risk alert fires exactly once per installation when projected depletion enters the lead-time window, with audit trail.
4. All price entries carry complete currency/kurs provenance; IDR display works without conversion.
5. Users below LEADER job scope receive server-side denials for all new mutations (not merely hidden UI).
6. Images survive restarts via Garage; PostgreSQL holds references only.

---

*Generated by bmad-correct-course — 2026-08-23.*
