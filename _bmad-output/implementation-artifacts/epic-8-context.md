# Epic 8 Context: Sparepart Procurement Readiness & Operating Calendar

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Give maintenance teams procurement readiness data (material code, lead time, priced sparepart history with kurs evidence, and a global image) plus shift-aware counter projections, so replacement can be planned in calendar time instead of only in production counters, and a distinct procurement-risk alert fires before stock-out. This is a narrow carve-out of groundwork toward Phase 3 IMMS; full inventory management remains out of scope.

## Stories

- Story 8.1: Add Garage Object Storage and Backend Integration
- Story 8.2: Manage Sparepart Material Code and Lead Time
- Story 8.3: Manage Estimated Price Entries with Currency and Kurs
- Story 8.4: Manage Sparepart Image via Garage
- Story 8.5: Configure Shift Schedule with Machine Override
- Story 8.6: Estimate Counter Rate and Shift-Aware Projections
- Story 8.7: Raise Procurement-Risk Alert Within Lead-Time Window

## Requirements & Constraints

- A sparepart may carry an optional, manually entered material code that is globally unique (not plant-scoped); installation and lifetime flows must work without it.
- A sparepart may carry an optional procurement lead time allowing fractional values (e.g. 36 hours, 7.5 days).
- A sparepart supports append-only estimated price entries with a decimal amount, ISO-4217 currency defaulting to IDR, a mandatory kurs-to-IDR snapshot when the currency is not IDR, a normalized IDR value, an actor, and a timestamp. History is retained and viewable, with a copy/reuse action when the price is unchanged.
- A sparepart has one global image stored in S3-compatible object storage; PostgreSQL holds only an object reference.
- Machine groups may define up to three daily shifts with start/end local wall-clock times permitting cross-midnight windows; a machine may override its group config (machine wins), and the UI states the inherited source when falling back.
- The system estimates counter rate as a rolling 30-day moving average of counting delta per operating hour, falls back to a full-history average when fewer than 30 days of data exist, and exposes an explicit insufficient-data state rather than guessing.
- The system shows shift-aware depletion projections (calendar time to depletion) and expected counter consumption during the lead-time window.
- When projected depletion falls within the lead-time window, the system raises a distinct, duplicate-prevented PROCUREMENT_RISK alert in addition to existing percentage-threshold alerts; missing lead time or insufficient data produces no alert and no error.
- All procurement readiness mutations require job scope LEADER or above, enforced server-side, and are written to the immutable audit log.

## Technical Decisions

- Garage is added to `infra/docker-compose.yml` with the stable name `garage` (existing stable-name pattern); backend binds endpoint, credentials, and bucket through typed properties with no hardcoded URLs, and serves objects via short-TTL presigned GET URLs. An optional health indicator follows the Epic 6 pattern.
- Data model: `spareparts` gains nullable `material_code` (global unique constraint), nullable fractional `lead_time_hours`, and nullable `image_object_key`; new append-only `sparepart_price_entries`; new machine-group shift config with shift window rows plus a parallel machine-level override table (precedence machine > group).
- Backend modules: `CounterRateEstimator` (window/staleness as typed config, backend-owned rounding) and `OperatingCalendarCalculator` (effective operating time from the resolved shift config, cross-midnight support, plant-local timezone default Asia/Jakarta used only at definition/display boundaries, computation in UTC instants).
- Alert evaluation extends the Epic 4 path with a second condition producing a distinct `PROCUREMENT_RISK` alert, duplicate-prevented per installation plus alert type, without touching existing percentage-threshold behavior.
- First server-side job-scope enforcement (LEADER or above) is added to the auth/security module, alongside the application-role baseline; this is a step in the FR-025 ABAC direction.
- Computed counter-rate statistics are derived query-time from InfluxDB telemetry history and cached in Redis with an explicit TTL (Redis stays rebuildable and never the sole source of truth).
- Money precision uses BigDecimal; all timestamps are UTC, with plant-local wall clock appearing only at shift-definition and display boundaries.
- API additions under `/api/v1`: `PATCH /spareparts/{id}` (material code, lead time), `POST|GET /spareparts/{id}/price-entries`, `POST|DELETE /spareparts/{id}/image`, `PUT /machine-groups/{id}/shift-config`, `PUT|DELETE /machines/{id}/shift-config`, with projections embedded in machine-hub/installation read responses.
- Module boundaries and backend ownership are preserved: backend owns calculation, validation, permissions, and status; the frontend renders decisions and must not reimplement domain rules.
- Testing: Testcontainers for migrations, global uniqueness, and append-only history; controlled-clock unit tests for the estimator and cross-midnight calendar math; integration tests for procurement-risk create/dedupe/non-interference/role-denial; locked DTO contracts with Orval/TanStack client regeneration.

## UX & Interaction Patterns

- New shared components: `CurrencyPriceInput`, `PriceHistoryTable`, `MaterialCodeField`, `LeadTimeInput`, `SparepartImageUpload`, `ShiftConfigEditor`, `InheritedConfigBadge`, `CounterRateProjectionCard`.
- Screen changes: sparepart management form/list extensions; machine group shift editor; Machine Hub override editor with inheritance badge and projections card; alert views render the PROCUREMENT_RISK type label and reason distinctly; LEADER+ gating with disabled-reason copy.
- Every screen supports loading, empty, error, stale, read-only, and forbidden states; status is never color-only; WCAG AA baseline applies; dense table patterns on desktop with stacked behavior on mobile.

## Cross-Story Dependencies

- 8.1 must be completed before 8.4 (image upload needs Garage).
- 8.5 must precede 8.6 (projections need a resolved shift configuration); 8.6 must precede 8.7 (procurement-risk alert needs counter-rate data).
- 8.7 also requires 8.2 (lead time).
- 8.2 and 8.5 can proceed in parallel. Recommended sequence: 8.2 ∥ 8.5 → 8.6 → 8.7, with 8.1 before 8.4.
- Builds on Epics 1–7 outputs (machine/sparepart master data, accepted telemetry, alert evaluation path); no existing story is reworked.
- Epic 8 outputs are deliberate groundwork for later roadmap phases (outside this epic's scope): the global material code becomes the identity key for future stock/inventory aggregation, the resolved shift configuration feeds calendar-based scheduling, and the Garage key-only storage pattern is the template for future evidence storage.
