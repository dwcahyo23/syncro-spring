# Epic 6 Context: System Health & Operational Diagnostics

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Give SUPER_ADMIN a health dashboard that reveals platform dependency, telemetry ingest, notification worker, and data quality failures before they become invisible operational gaps. SUPER_ADMIN can identify whether a telemetry or notification problem comes from PostgreSQL, InfluxDB, Redis, MQTT/EMQX, WAHA, an ingest/notification worker, stale telemetry, or a data quality/quarantine backlog.

## Stories

- Story 6.1: Expose Dependency Health Checks
- Story 6.2: Report Telemetry Ingest Worker Status
- Story 6.3: Report Notification Worker Status
- Story 6.4: Build SUPER_ADMIN Health Dashboard
- Story 6.5: Surface Latest Telemetry Freshness in Health
- Story 6.6: Link Health Failures to Operational Evidence
- Story 6.7: Implement Data Quality Panel and Latency Indicator

## Requirements & Constraints

- Health dashboard is SUPER_ADMIN-only; non-SUPER_ADMIN users must not access it (API and navigation guarded).
- Report connectivity for PostgreSQL, InfluxDB, Redis, MQTT/EMQX, and WAHA availability; dependency failures must never crash the backend.
- Report telemetry ingest worker and notification worker status (running/stopped/degraded), including MQTT subscription state, last accepted telemetry timestamp, pending job counts, and last WAHA send.
- Surface latest telemetry received timestamp with absolute UTC plus relative freshness; show a stale reason when stale, and a clear empty state when no telemetry has ever been received (never a healthy false-positive).
- Show quarantine and data quality visibility: quarantined message count and recent entries, rejection rate, anomaly count, dead-letter count, telemetry queue depth, failed write count, and circuit-breaker state.
- Health checks must make telemetry and notification path failures visible to SUPER_ADMIN (NFR-006).
- Health responses carry the standard operational status contract: status, statusLabel, statusReason, statusSeverity, timestamp, traceId where applicable.
- No secrets or credentials may ever be displayed in the health UI or logged in health output.
- Telemetry freshness is computed from backend latest-accepted-telemetry records, never inferred on the frontend.
- pgAdmin may be referenced in local/dev evidence docs for PostgreSQL proof but is not a product feature.
- End-to-end telemetry latency (MQTT publish to dashboard visible) targets normal < 5s, elevated 5–15s, critical > 15s.

## Technical Decisions

- Health dashboard backed by Spring Boot Actuator health indicators and metrics endpoints; custom health indicators for EMQX, InfluxDB, WAHA, and worker status.
- Backend health module owns health computation and snapshots; frontend only renders backend-provided status.
- Rejected telemetry is persisted in the PostgreSQL `telemetry_quarantine` table (received timestamp, MQTT topic, raw payload, rejection reason, schema version, correlationId) with a retention/cleanup policy to prevent unbounded growth.
- Quarantine and data quality metrics originate from the Epic 3 ingest pipeline (validation, queue depth, dead-letter) and are exposed through the health module.
- Queue depth, processing lag, and dead-letter volume are observable in the health dashboard; backpressure delays MQTT ack instead of discarding.
- WAHA circuit-breaker state (closed/open/half-open) is exposed in health; notification sends stay fully decoupled from the telemetry ingest path.
- Frontend never accesses infrastructure directly; all health data comes through Spring Boot REST APIs (e.g. `/api/v1/health/summary`).

## UX & Interaction Patterns

- Route `/system-health`, reachable from navigation and the Operations Overview health summary; non-SUPER_ADMIN access redirects to Operations Overview.
- One `HealthCard` per dependency/worker showing name, StatusBadge, last checked timestamp, last error, and impacted area.
- Page sections: Dependencies, Workers, Data Quality, Telemetry Freshness, Database Replication, Queue Status.
- `DataQualityPanel` shows quarantine count, rejection rate %, anomaly count, dead-letter count, and time window, with warning/critical thresholds; links to the quarantine log.
- `QuarantineLogTable` lists rejected messages (received timestamp, topic, reason, schema, copyable correlationId, expandable full payload) with filters and pagination.
- `LatencyIndicator` renders current end-to-end latency with distinct normal/elevated/critical states.
- Status communication is never color-only; visible labels always present. Loading uses skeletons; failed sections show "last known status at {time}" with retry.
- Health card/timestamp states: all-healthy summary banner, partial-failure critical styling on failed cards only, and section-level inline error with retry.
- Refresh: auto every 30s plus manual refresh button and per-section retry.

## Cross-Story Dependencies

- 6.1 dependency checks are the foundation for 6.4's dashboard and 6.6's evidence links; 6.2 and 6.3 feed the Workers section.
- 6.5 freshness depends on accepted telemetry from Epic 3 (Redis latest state / backend latest record).
- 6.7 data quality and latency depend on the quarantine store and queue/dead-letter metrics from Epic 3 (Stories 3.11–3.12) and the WAHA circuit breaker/rate-limit state from Epic 5 (Stories 5.7–5.8).
- 6.6 links failures to notification history (Epic 5) and machine/telemetry context (Epics 2–3).
- Health endpoints reuse the SUPER_ADMIN role model and error/status contracts established in Epics 1–3.
