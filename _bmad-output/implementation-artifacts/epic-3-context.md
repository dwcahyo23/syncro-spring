# Epic 3 Context: Telemetry Ingestion & Latest Machine Visibility

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Users receive validated MQTT telemetry from registered active machines and can safely view latest machine telemetry, freshness, and machine context. Incoming telemetry is validated against master data and a fixed payload contract before being written as history to InfluxDB and latest state to Redis, then surfaced on a telemetry dashboard and per-machine hub without conflating manual machine status with telemetry online/offline/stale state.

## Stories

- Story 3.1: Configure MQTT Subscription and Telemetry Contract
- Story 3.2: Validate MQTT Topic and Base Payload
- Story 3.3: Reject Inactive Machine Telemetry
- Story 3.4: Persist Accepted Telemetry to InfluxDB and Redis
- Story 3.5: Calculate Production Count Delta with 16-Bit Wrap Support
- Story 3.6: Support Optional Machine Telemetry Fields
- Story 3.7: Show Latest Telemetry Dashboard
- Story 3.8: Show Machine Hub with Telemetry Context

## Requirements & Constraints

**Ingest contract.** Backend subscribes to `factory/{plantCode}/{machineCode}/telemetry` (broker-agnostic contract, EMQX for local/dev). Topic identity must match a registered machine and the machine identity embedded in the payload body. Telemetry for `INACTIVE` machines is rejected before any store write. Base payload requires `running`, `runtimeHours`, and `counting`; `counting` is a cumulative unsigned production count that must tolerate 16-bit PLC wrap from max back to `0`. Payloads that fail the contract are rejected, and validation errors must not crash the ingest worker.

**Message integrity.** Payloads carry `schemaVersion` (format version), unique `messageId` (dedup), and a UTC ISO-8601 `timestamp`. Unrecognized schema versions are rejected or quarantined. `messageId` is used for deduplication to prevent duplicate latest/history records and downstream double-processing. Values outside physically plausible ranges (negative runtime, counter decrease that is not wrap) are rejected with a recorded reason.

**Persistence.** Accepted telemetry history goes to InfluxDB; latest accepted state goes to Redis. Both writes carry machine identity, timestamp, `running`, `runtimeHours`, `counting`, and traceId. Accepted telemetry must be consumable by later sparepart threshold evaluation.

**Dashboard.** Show latest telemetry for permitted active machines — at minimum running state, runtime hours, production count, and last received timestamp — rendering per-machine configured optional fields. Distinguish manual `ACTIVE/INACTIVE` status from telemetry online/offline/stale state; communication must not be color-only.

**Robustness NFRs.** Validate external MQTT input before downstream writes; degrade to bounded backlog (queue depth limit) with delayed MQTT ack rather than crash or silent discard; EMQX authenticates devices and enforces deny-by-default topic ACLs; TLS in production, plaintext allowed in dev; every telemetry message carries a correlation identifier logged at each processing stage; external calls (InfluxDB, WAHA) use configurable timeouts, retry with backoff, and circuit-breaker behavior.

**Architectural ARs.** MQTT contract stays broker-agnostic; backend MQTT is configured via environment variables, never hardcoded EMQX URLs; InfluxDB 3 Core with v2 line protocol fallback; Redis for latest telemetry and lightweight cache; trace ID propagation through request/MQTT → telemetry → alert → notification → audit; idempotency keys for telemetry, alert creation, and notification jobs.

## Technical Decisions

- **MQTT contract:** fixed topic `factory/{plantCode}/{machineCode}/telemetry`; payload requires `schemaVersion`, `messageId`, `timestamp`, `running`, `runtimeHours`, `counting`, plus up to 10 configured optional parameters. Phase 1 runs one ingest worker; horizontal scale later requires shared subscriptions (`$share/{group}/factory/+/+/telemetry`).
- **InfluxDB 3 Core:** shared tables with tag-based machine/plant discrimination (not per-machine tables) due to hard limits (5 databases, 2000 tables, 500 columns); retention ~30 days for dashboard queries; v2 line protocol as fallback if Java client blocks.
- **Redis:** all keys have explicit TTL; data rebuildable from PostgreSQL/InfluxDB; never the sole source of truth.
- **traceId/correlation:** every telemetry message carries a trace/correlation ID (reuse payload `messageId` or generate) logged at MQTT receipt, validation, InfluxDB write, Redis update.
- **Idempotency:** telemetry dedupe uses `messageId` as primary key, with machine identity + timestamp + counter payload as fallback.
- **Quarantine:** rejected/invalid messages persist to PostgreSQL `telemetry_quarantine` (received timestamp, topic, raw payload, rejection reason, schema version, correlation ID), SUPER_ADMIN-accessible, with retention/cleanup policy.
- **Validation order:** receive → machine exists from topic → payload-topic identity match → reject inactive → validate schema version → validate contract + plausible ranges → dedupe → write InfluxDB → update Redis → log correlation.
- **Backpressure:** bounded internal queue; delay MQTT ack when the queue is at capacity instead of dropping or crashing; queue depth, lag, and dead-letter observable for health.
- **Counter wrap:** backend owns delta calculation; frontend never computes production deltas from raw telemetry; negative raw delta treated as wrap only for configured unsigned counter sources.

## UX & Interaction Patterns

- **`TelemetryCard`** (shared component) for latest telemetry values and freshness: machine code/name, running state, `counting`, `runtimeHours`, `lastSeen`, freshness state, optional end-to-end latency indicator. States: live, stale, inactive, loading, error, empty, read-only.
- **Status separation:** manual machine active state, telemetry freshness, alert lifecycle, and delivery must never be visually conflated; telemetry freshness uses distinct semantics (e.g., stale = no data in 5 min) with non-color-only labels.
- **Operations Overview** acts as a command-center home emphasizing what needs attention now, including live telemetry; **Machine Hub / Machine Detail** is the central operational context (identity, manual status, telemetry, spareparts, alerts).
- **JBF19** pilot machine must show latest telemetry as primary proof; telemetry auto-refresh ~30s.
- **States:** every operational component supports loading, empty, error, stale, read-only, and forbidden states; stale states explain what failed and where to investigate.
- **Mobile:** heavy telemetry history/charts do not load by default; latest state and alert actions take priority (stacked cards, links to detail).
- Views are plant-scoped: users see only telemetry for their assigned plants.

## Cross-Story Dependencies

- Epic 1 (Stories 1.2–1.3): EMQX, InfluxDB, Redis running locally via `infra/docker-compose.yml`; backend skeleton with MQTT client, Redis, Actuator dependencies.
- Epic 2 (Stories 2.1–2.3): registered active machines, plant codes, and machine master data required for topic/machine validation and optional-field configuration.
- Story 3.1 → 3.2 → 3.3: subscription precedes validation; inactive-machine rejection depends on validated machine identity. Story 3.2/3.3 feed quarantine of rejected messages.
- Stories 3.2–3.4: validation must pass before any InfluxDB/Redis write; persistence depends on the full validation gate.
- Story 3.5 depends on Story 3.4's accepted telemetry (previous counter values) and validation rejecting non-numeric/negative counts; its wrap-safe delta feeds later sparepart consumption (Epic 4).
- Story 3.6 extends 3.4 persistence and 3.7/3.8 rendering with configured optional fields; base fields stay required.
- Stories 3.7/3.8 consume Redis latest state from Story 3.4 and Epic 2 machine master data; Machine Hub is the Epic 2 machine detail extended with telemetry context.
- Epic 4 consumes accepted telemetry from Story 3.4; Epic 6 surfaces ingest worker health, quarantine count, and telemetry freshness.
