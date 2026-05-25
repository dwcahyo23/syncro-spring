---
title: Syncro Phase 1 Industrial Foundation PRD
status: final
created: 2026-05-22
updated: 2026-05-25
---
<!-- Research reconciliation applied 2026-05-25: domain + technical research gaps integrated -->

# Syncro Phase 1 Industrial Foundation PRD

## 1. Summary

Syncro Phase 1 establishes the industrial maintenance platform foundation for an internal build. The MVP connects machine master data, MQTT telemetry, sparepart production-count lifetime thresholds, machine-specific responsibility, WAHA WhatsApp escalation, and operational health visibility.

Phase 1 is not the full maintenance suite. It creates the data and event spine required for later CMMS, IMMS, ABAC maintenance governance, reporting, project maintenance, KPI tracking, and IATF alignment.

## 2. Problem

Maintenance teams need earlier visibility when installed spareparts approach end-of-life based on actual machine production output. Without a connected machine-to-alert loop, sparepart replacement risk is handled manually, alerts depend on people noticing data, and notification failures can stay hidden.

## 3. Goals

- Provide a machine-first master data foundation for plants, process lines, machines, spareparts, and responsibility assignment.
- Support plant scale up to 500 registered machine codes per plant.
- Ingest validated MQTT telemetry for active machines without overwhelming backend services during high-concurrency machine publish bursts.
- Track sparepart lifetime from cumulative production count baselines.
- Create threshold alerts when installed spareparts reach configured lifetime percentage.
- Send WhatsApp notifications through WAHA using staged escalation.
- Provide SUPER_ADMIN visibility into platform dependency and worker health.
- Keep Phase 1 data model compatible with later CMMS, IMMS, ABAC, reporting, KPI, and IATF phases.

## 4. Non-Goals

- Corrective maintenance work orders.
- Preventive maintenance scheduling.
- Inventory maintenance management.
- Full plant/machine/user ABAC enforcement.
- WYSIWYG PDF report generation.
- Maintenance project management.
- KPI scoring for users, leaders, SPVs, or managers.
- IATF compliance workflow.
- Automatic machine active/inactive state inference from heartbeat or telemetry freshness.

## 5. Users and Roles

### 5.1 Application Roles

- `SUPER_ADMIN`: full platform setup, user/role management, system health access.
- `MANAGE`: platform role that can create, edit, and view operational records within its permitted scope.
- `VIEWER`: read-only access to permitted dashboards and histories.

### 5.2 Job Permission Scopes

`TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, and `MANAGER` are job permission scopes for responsibility and future ABAC. They are separate from application roles. A user may have the `MANAGE` application role while their operational scope is still constrained by job permission scope, such as technician, staff, leader, SPV, or manager. Phase 1 stores these scope values for machine responsibility and escalation; full ABAC enforcement is deferred.

## 6. User Journeys

### UJ-1: Configure Machine Foundation

A SUPER_ADMIN logs in, creates plants, process-line machine groups, machines, sparepart taxonomy, installed machine spareparts, and machine responsibility assignments. After setup, the platform knows which sparepart is installed on which machine, what production count baseline applies, what threshold should trigger alerts, and who should receive escalation.

### UJ-2: Receive Valid Telemetry

An active machine publishes MQTT telemetry to `factory/{plantCode}/{machineCode}/telemetry`. The backend validates the topic against registered plant and machine data, validates the payload contract, stores telemetry history in InfluxDB, and updates latest telemetry state in Redis.

### UJ-3: Trigger Sparepart Threshold Alert

A machine sparepart installation has an expected production-count lifetime and baseline counter value. When incoming cumulative production count indicates consumed production reaches the configured threshold, the system creates an `OPEN` alert and begins WhatsApp escalation.

### UJ-4: Escalate Notification Through WAHA

The system sends the alert to the first responsibility level. If the alert is not acknowledged within the configured escalation interval, the system escalates to the next responsibility level. Notification attempts and delivery failures are stored for review.

### UJ-5: Diagnose Platform Health

A SUPER_ADMIN opens the health dashboard to verify PostgreSQL, InfluxDB, Redis, MQTT, WAHA, ingest worker, notification worker, and last telemetry status. If alerts stop flowing, the health page reveals which dependency or worker is failing.

## 7. Functional Requirements

### 7.1 Platform and Authentication

- FR-001: The system shall provide login for authenticated users.
- FR-002: The system shall support application roles `SUPER_ADMIN`, `MANAGE`, and `VIEWER`.
- FR-003: The system shall enforce menu and API access by application role.
- FR-004: The web application shall use the Next.js + shadcn/ui admin dashboard boilerplate at `https://github.com/dwcahyo23/next-shadcn-admin-dashboard/tree/main` as the UI foundation.
- FR-005: The backend shall use PostgreSQL migrations for schema changes.

### 7.2 Plant and Machine Master Data

- FR-006: The system shall allow authorized users to create, read, update, and delete plants.
- FR-007: The system shall allow authorized users to create, read, update, and delete plant-scoped machine groups representing process lines.
- FR-008: The same machine group name may exist in different plants.
- FR-009: The system shall allow authorized users to create, read, update, and delete machines.
- FR-010: The system shall support at least 500 registered machine codes per plant.
- FR-011: A machine shall require `code`, `plantId`, `machineGroupId`, and manual `status`.
- FR-012: Machine `status` shall support `ACTIVE` and `INACTIVE`.
- FR-013: A machine may store optional `brand`, `installedAt`, and `notes`.
- FR-014: The system shall treat machine active state as manual master data, not inferred from MQTT or telemetry health.

### 7.3 Sparepart Master and Installation

- FR-015: The system shall support normalized sparepart taxonomy for category, brand, kind, and type.
- FR-016: The system shall allow authorized users to create, read, update, and delete spareparts referencing taxonomy dimensions.
- FR-017: The system shall allow authorized users to install spareparts on machines.
- FR-018: A machine sparepart installation shall store expected lifetime in production count.
- FR-019: A machine sparepart installation shall store baseline counter value from the machine counter.
- FR-020: Sparepart consumed production count shall be calculated from current machine counter relative to baseline counter, not from installation date.
- FR-021: A machine sparepart installation shall default threshold percentage to 90%.
- FR-022: Authorized users may override threshold percentage per machine sparepart installation.

### 7.4 Machine Responsibility

- FR-023: The system shall allow authorized users to assign users as responsible parties for specific machines.
- FR-024: Machine responsibility shall support responsibility levels needed for escalation, including `TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, and `MANAGER`.
- FR-025: Machine responsibility shall be machine-specific, not only plant-level or global-role-based.
- FR-026: Phase 1 responsibility levels shall support alert routing and escalation, and shall be stored separately from application role so later ABAC can constrain MANAGE actions by job scope.

### 7.5 MQTT Telemetry Ingest

- FR-027: The system shall use EMQX as the MQTT broker for machine telemetry.
- FR-028: The system shall subscribe to MQTT telemetry topics using `factory/{plantCode}/{machineCode}/telemetry`.
- FR-029: The system shall validate `plantCode` and `machineCode` against registered active machines.
- FR-029a: The system shall validate that machine identity in the payload body matches the machine identity extracted from the MQTT topic. Mismatches shall be rejected.
- FR-030: The system shall reject telemetry for machines with `INACTIVE` status.
- FR-031: The base telemetry payload shall include `running`, `runtimeHours`, and `counting`.
- FR-031a: The telemetry payload shall include a `schemaVersion` field identifying the payload format version.
- FR-031b: The telemetry payload shall include a unique `messageId` field for deduplication.
- FR-031c: The telemetry payload shall include a `timestamp` field in UTC ISO-8601 format.
- FR-032: The telemetry payload shall support up to 10 configured parameters per machine, including status, counter, sensor, and custom instrumentation values.
- FR-033: `counting` shall be treated as cumulative unsigned production count.
- FR-034: The system shall handle 16-bit unsigned PLC counters that wrap from maximum value back to `0`.
- FR-035: The system shall reject telemetry payloads that do not satisfy the required payload contract.
- FR-035a: The system shall reject or quarantine payloads with unrecognized schema versions.
- FR-035b: The system shall persist rejected telemetry messages with topic, payload, rejection reason, and received timestamp in a quarantine store accessible to SUPER_ADMIN.
- FR-035c: The system shall reject telemetry values outside physically plausible ranges (negative runtime, counter decrease outside wrap-around) and record the rejection reason.
- FR-036: The system shall write accepted telemetry history to InfluxDB.
- FR-037: The system shall store latest accepted telemetry state in Redis.
- FR-038: The system shall support manually configured optional telemetry fields per machine for instrumentation such as power analyzer, vibration sensor, quality sensor, or custom sensor fields.
- FR-039: The system shall support sustained telemetry ingestion from 500 machines per plant publishing once per second.
- FR-040: The system shall decouple MQTT message receipt from database writes using a durable queue or stream so temporary database slowdown does not crash the MQTT ingest path.
- FR-041: The telemetry ingest path shall apply backpressure, bounded concurrency, and retry/dead-letter handling for failed telemetry writes.
- FR-041a: The system shall use `messageId` for deduplication where duplicate processing would cause incorrect behavior such as duplicate alert creation or duplicate notification triggers.
- FR-042: The system shall preserve latest telemetry state even when historical telemetry writes are delayed.

### 7.6 Telemetry Dashboard

- FR-043: The system shall provide a telemetry dashboard showing latest telemetry for active machines.
- FR-044: The dashboard shall distinguish manual machine status from telemetry online/offline or last-seen state.
- FR-045: The dashboard shall render configured telemetry fields per machine.
- FR-046: The dashboard shall show at minimum running state, runtime hours, production count, and last received timestamp.

### 7.7 Alert Lifecycle

- FR-047: The system shall create a sparepart lifetime alert when consumed production count reaches or exceeds the configured threshold percentage.
- FR-048: Alert status shall support `OPEN`, `ACKNOWLEDGED`, and `RESOLVED`.
- FR-049: New threshold alerts shall start as `OPEN`.
- FR-050: Authorized users shall be able to acknowledge an `OPEN` alert.
- FR-051: Authorized users shall be able to resolve an acknowledged alert.
- FR-052: The system shall prevent duplicate active alerts for the same machine sparepart installation and threshold crossing.
- FR-053: The system shall retain alert history.

### 7.8 WAHA Notification and Escalation

- FR-054: The system shall queue WhatsApp notifications through WAHA for threshold alerts.
- FR-055: The system shall resolve recipients from machine responsibility assignments.
- FR-056: The system shall escalate notifications in stages instead of sending to all recipients at once.
- FR-057: Escalation order shall default to TECHNICIAN → STAFF → LEADER → SPV → MANAGER.
- FR-058: If an alert is acknowledged, the system shall stop further escalation for that alert.
- FR-059: Escalation interval shall default to 15 minutes per level.
- FR-060: SUPER_ADMIN shall be able to resolve an alert without prior acknowledgement as an administrative override.
- FR-061: The system shall track notification attempt status and error detail.
- FR-061a: The system shall rate-limit WhatsApp notification sends to prevent notification storms. Rate limiting shall use short-lived deduplication keys with configurable window to avoid repeated sends for the same alert.
- FR-062: The system shall expose alert notification history to authorized users.
- FR-063: The system shall provide a WYSIWYG text template editor for WAHA WhatsApp alert messages.

### 7.9 System Health

- FR-064: The system shall provide a SUPER_ADMIN-only health dashboard.
- FR-065: The health dashboard shall show PostgreSQL connectivity.
- FR-066: The health dashboard shall show InfluxDB connectivity.
- FR-067: The health dashboard shall show Redis connectivity.
- FR-068: The health dashboard shall show EMQX MQTT broker connectivity.
- FR-069: The health dashboard shall show WAHA availability.
- FR-070: The health dashboard shall show telemetry ingest worker status.
- FR-071: The health dashboard shall show notification worker status.
- FR-072: The health dashboard shall show latest telemetry received timestamp.
- FR-073: The health dashboard shall show telemetry queue depth, failed telemetry write count, and dead-letter count.
- FR-073a: The health dashboard shall show quarantined message count and most recent quarantine entries.
- FR-073b: The health dashboard shall show telemetry data quality metrics including rejected count and anomaly count.
- FR-074: The health dashboard shall show database primary and replica connectivity status.
- FR-075: The health dashboard shall show database replication lag when replicas are configured.

### 7.10 Audit and Access Control

- FR-076: The system shall record immutable audit log entries for master data mutations including plant, machine, sparepart, responsibility, and threshold changes. Each entry shall capture user, action, entity, previous value, new value, and timestamp.
- FR-077: Telemetry dashboard and alert views shall be filtered by the user's plant assignment. A user with access to Plant A shall not see telemetry or alerts from Plant B unless granted SUPER_ADMIN role.

## 8. Minimum Screen Set

- Login.
- Plant management.
- Machine group / process line management.
- Machine management.
- Sparepart taxonomy management.
- Sparepart management.
- Machine sparepart installation.
- Machine responsibility assignment.
- Telemetry dashboard.
- Alert history and alert detail.
- Audit log.
- System health dashboard (including data quality panel and quarantine log).
- WAHA template editor.

## 9. Data and Calculation Rules

### 9.1 Production Count

`counting` is a cumulative unsigned production counter. Some PLC sources may expose this as a 16-bit unsigned counter, causing automatic wrap-around to `0` after the maximum value. The system must calculate deltas correctly across wrap-around.

### 9.2 Sparepart Baseline

Sparepart lifetime consumption starts from the stored counter baseline at installation or reset time. Installation date does not determine consumption.

### 9.3 Threshold Formula

Consumed percentage is calculated as:

```text
consumedProductionCount / expectedProductionCount * 100
```

An alert is triggered when consumed percentage is greater than or equal to the configured threshold percentage. Default threshold percentage is `90`.

### 9.4 Telemetry Timestamp

Each telemetry payload shall include a `timestamp` field in UTC ISO-8601 format. The system shall normalize timestamps to UTC before persistence. If a payload lacks a timestamp, the system shall use server receipt time and flag the record accordingly.

### 9.5 WAHA Notification Identifiers

WhatsApp recipient identifiers (chatId) shall use WAHA format: international phone number without leading `+`, suffixed with `@c.us` for individual users or `@g.us` for groups. Example: `6281234567890@c.us`. The WAHA session name shall default to `default`.

## 10. Non-Functional Requirements

- NFR-001: The system shall separate datastore ownership: PostgreSQL for master/auth/config/alerts, InfluxDB for telemetry history, Redis for latest state/cache, queue or stream for telemetry buffering, and queue for notification dispatch.
- NFR-001a: All Redis keys storing derived or cached state shall have explicit TTL values. Redis data shall be rebuildable from PostgreSQL and InfluxDB. Redis shall never be the sole source of truth for any operational data.
- NFR-001b: Notification dispatch (WAHA sends) shall be fully decoupled from the telemetry ingest path. Alert creation may trigger notification queuing, but notification delivery shall not block or slow telemetry processing.
- NFR-002: PostgreSQL shall support primary-replica deployment so writes go to the primary database and eligible read-heavy queries can use read replicas.
- NFR-003: Database read routing shall keep strongly consistent operations on the primary when stale replica reads could cause incorrect behavior.
- NFR-004: Database replication lag, replica health, and failover state shall be observable to SUPER_ADMIN or operations users.
- NFR-005: The system shall validate external MQTT input before writing to downstream stores.
- NFR-006: The MQTT ingest path shall survive 500 machines per plant publishing one payload per second with up to 10 parameters per payload without crashing the backend process.
- NFR-006a: When telemetry processing cannot keep pace with incoming messages, the system shall degrade into bounded backlog (queue depth limit) rather than unbounded memory growth or process crash. If the queue reaches capacity, the system shall delay MQTT acknowledgment (applying backpressure to the broker) rather than silently discarding messages.
- NFR-007: Telemetry processing shall use bounded worker concurrency so burst traffic cannot exhaust CPU, memory, database connections, or event loop capacity.
- NFR-008: Telemetry ingestion shall prefer loss-controlled buffering over direct synchronous writes from MQTT callbacks to PostgreSQL or InfluxDB.
- NFR-009: EMQX broker configuration shall support per-topic routing and client limits appropriate for plant-scale machine publishers.
- NFR-009a: EMQX shall authenticate each connecting device using credentials or certificates. Each device shall be authorized to publish only to its own machine telemetry topic(s) via ACL rules. Deny-by-default policy shall apply.
- NFR-009b: MQTT connections from devices to EMQX shall support TLS encryption. Production deployments shall require TLS; development environments may use plaintext for convenience.
- NFR-010: The system shall make telemetry backlog, processing lag, failed writes, and dead-letter volume observable in health or operations views.
- NFR-010a: Every telemetry message processed through the ingest path shall carry a correlation identifier that is logged at each processing stage (MQTT receipt, validation, InfluxDB write, Redis update, alert evaluation) to enable end-to-end traceability.
- NFR-011: The system shall avoid fire-and-forget notification behavior by storing notification status history.
- NFR-011a: External service calls (WAHA, InfluxDB HTTP writes) shall use configurable timeouts, retry with backoff, and circuit-breaker behavior so transient failures do not block processing pipelines or exhaust worker threads.
- NFR-012: The system shall avoid alert spam through duplicate active alert prevention.
- NFR-013: The web UI shall be responsive and use consistent shadcn/ui components from the selected admin dashboard boilerplate.
- NFR-013a: The Next.js web application shall not access PostgreSQL, InfluxDB, Redis, EMQX, or WAHA directly. All data access shall go through Spring Boot REST APIs or controlled Next.js server-side Route Handlers that proxy Spring Boot.
- NFR-014: Health checks shall make telemetry and notification path failures visible to SUPER_ADMIN.
- NFR-014a: The backend shall use structured logging with consistent fields (correlationId, machineCode, plantCode, component) and shall support OpenTelemetry-compatible trace/metric export for future observability integration.
- NFR-015: Phase 1 data relationships shall preserve plant, machine, user, responsibility, and sparepart structure needed for later ABAC and CMMS expansion.

## 11. Success Metrics

- SM-001: Telemetry is accepted, stored, and visible for at least one pilot active machine.
- SM-002: A plant-scale load test with 500 simulated active machines publishing one payload per second for 15 minutes completes without backend process crash.
- SM-003: During the plant-scale load test, latest telemetry remains visible for active machines while historical telemetry writes may lag within defined operational tolerance.
- SM-004: A sparepart lifetime alert is created when production count reaches the configured threshold.
- SM-005: A WhatsApp notification is sent through WAHA for the threshold alert.
- SM-006: Alert escalation stops when the alert is acknowledged.
- SM-007: SUPER_ADMIN can identify dependency, worker, queue backlog, dead-letter, database primary/replica health, and replication lag failures from the health dashboard.
- SM-008: During normal operation (not burst), telemetry published by a machine shall be visible on the dashboard within 5 seconds of MQTT publish.
- SM-009: During the plant-scale load test, telemetry quarantine/rejection rate shall remain below 1% for well-formed payloads from registered active machines.


## 12. Pilot Validation Scenario

Phase 1 validation shall use this pilot scenario:

- Plant: `GM1`.
- Machine group / process line: `Forming`.
- Machine code: `BF-08410`.
- Machine name: `JBF19`.
- Sparepart: `Electric PLC Wecon LX5`.
- Scenario: send MQTT telemetry with `counting` until the installed sparepart reaches 90% consumed production count, then verify the system creates an alert and sends WAHA WhatsApp escalation to `TECHNICIAN`, `STAFF`, and `LEADER` according to configured escalation rules.
## 13. Product Roadmap

### Phase 1 — Industrial Foundation

Machine master, telemetry, sparepart production-count threshold alert, staged WAHA notification, and system health.

### Phase 2 — CMMS

Computerized Maintenance Management System covering Corrective Maintenance Work Orders and Preventive Maintenance. Includes safety procedure enforcement (LOTO/energy isolation) integrated into work order execution.

### Phase 3 — IMMS

Inventory Maintenance Management System with degradation-driven procurement triggers linked to sparepart lifetime data from Phase 1. ERP/MES integration capability for closed-loop supply chain automation.

### Phase 4 — Governance and Reporting

Full ABAC maintenance and WYSIWYG PDF reports for Work Order Maintenance and Preventive Maintenance.

### Phase 5 — Project Maintenance

Maintenance project planning, execution, and tracking.

### Phase 6 — Maintenance KPI

Equipment effectiveness KPIs (OEE, MTBF, MTTR) and personnel maintenance KPIs for User, Leader, SPV, and Manager levels.

### Phase 7 — IATF Standard

IATF 16949 standard alignment and compliance support, including clause 8.5.1.5 TPM documentation requirements for OEE, MTBF, and MTTR metrics.

