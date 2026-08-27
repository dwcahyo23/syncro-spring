---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-Syncro-2026-05-22/prd.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/planning-artifacts/ux-design-specification.md
  - _bmad-output/planning-artifacts/prds/prd-Syncro-2026-05-22/review-prd.md
  - _bmad-output/planning-artifacts/research/domain-manufacturing-machinery-maintenance-research-2026-05-25.md
  - _bmad-output/planning-artifacts/research/technical-next-js-spring-boot-openjdk25-maven-mqtt-emqx-redis-postgresql-influxdb-research-2026-05-25.md
  - _bmad-output/planning-artifacts/prds/prd-Syncro-2026-08-24/prd.md
  - _bmad-output/planning-artifacts/architecture/architecture-Syncro-2026-08-24/ARCHITECTURE-SPINE.md
revisedAt: 2026-08-24
revisionReason: Phase 2 appended: Epics 9-14 (maintenance execution + OPA), 27 stories, FR-100..FR-181
---

# Syncro - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for Syncro, decomposing the requirements from the PRD, UX Design, PRD review, and Architecture requirements into implementable stories.

## Requirements Inventory

### Functional Requirements

FR-001: The system shall provide login for authenticated users.
FR-002: The system shall support application roles `SUPER_ADMIN`, `MANAGE`, and `VIEWER`.
FR-003: The system shall enforce menu and API access by application role.
FR-004: The web application shall use the selected Next.js + shadcn/ui admin dashboard boilerplate as the UI foundation.
FR-005: The backend shall use PostgreSQL migrations for schema changes.
FR-006: The system shall allow authorized users to create, read, update, and delete plants.
FR-007: The system shall allow authorized users to create, read, update, and delete plant-scoped machine groups representing process lines.
FR-008: The same machine group name may exist in different plants.
FR-009: The system shall allow authorized users to create, read, update, and delete machines.
FR-010: A machine shall require `code`, `plantId`, `machineGroupId`, and manual `status`.
FR-011: Machine `status` shall support `ACTIVE` and `INACTIVE`.
FR-012: A machine may store optional `brand`, `installedAt`, and `notes`.
FR-013: The system shall treat machine active state as manual master data, not inferred from MQTT or telemetry health.
FR-014: The system shall support normalized sparepart taxonomy for category, brand, kind, and type.
FR-015: The system shall allow authorized users to create, read, update, and delete spareparts referencing taxonomy dimensions.
FR-016: The system shall allow authorized users to install spareparts on machines.
FR-017: A machine sparepart installation shall store expected lifetime in production count.
FR-018: A machine sparepart installation shall store baseline counter value from the machine counter.
FR-019: Sparepart consumed production count shall be calculated from current machine counter relative to baseline counter, not from installation date.
FR-020: A machine sparepart installation shall default threshold percentage to 90%.
FR-021: Authorized users may override threshold percentage per machine sparepart installation.
FR-022: The system shall allow authorized users to assign users as responsible parties for specific machines.
FR-023: Machine responsibility shall support `TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, and `MANAGER` responsibility levels.
FR-024: Machine responsibility shall be machine-specific, not only plant-level or global-role-based.
FR-025: Phase 1 responsibility levels shall support alert routing and escalation, and shall be stored separately from application role so later ABAC can constrain MANAGE actions by job scope.
FR-026: The system shall subscribe to MQTT telemetry topics using `factory/{plantCode}/{machineCode}/telemetry`.
FR-027: The system shall validate `plantCode` and `machineCode` against registered active machines.
FR-028: The system shall reject telemetry for machines with `INACTIVE` status.
FR-029: The base telemetry payload shall include `running`, `runtimeHours`, and `counting`.
FR-030: `counting` shall be treated as cumulative unsigned production count.
FR-031: The system shall handle 16-bit unsigned PLC counters that wrap from maximum value back to `0`.
FR-032: The system shall reject telemetry payloads that do not satisfy the required payload contract.
FR-033: The system shall write accepted telemetry history to InfluxDB.
FR-034: The system shall store latest accepted telemetry state in Redis.
FR-035: The system shall support manually configured optional telemetry fields per machine.
FR-036: The system shall provide a telemetry dashboard showing latest telemetry for active machines.
FR-037: The dashboard shall distinguish manual machine status from telemetry online/offline or last-seen state.
FR-038: The dashboard shall render configured telemetry fields per machine.
FR-039: The dashboard shall show at minimum running state, runtime hours, production count, and last received timestamp.
FR-040: The system shall create a sparepart lifetime alert when consumed production count reaches or exceeds the configured threshold percentage.
FR-041: Alert status shall support `OPEN`, `ACKNOWLEDGED`, and `RESOLVED`.
FR-042: New threshold alerts shall start as `OPEN`.
FR-043: Authorized users shall be able to acknowledge an `OPEN` alert.
FR-044: Authorized users shall be able to resolve an acknowledged alert.
FR-045: The system shall prevent duplicate active alerts for the same machine sparepart installation and threshold crossing.
FR-046: The system shall retain alert history.
FR-047: The system shall queue WhatsApp notifications through WAHA for threshold alerts.
FR-048: The system shall resolve recipients from machine responsibility assignments.
FR-049: The system shall escalate notifications in stages instead of sending to all recipients at once.
FR-050: Escalation order shall default to `TECHNICIAN -> STAFF -> LEADER -> SPV -> MANAGER`.
FR-051: If an alert is acknowledged, the system shall stop further escalation for that alert.
FR-052: Escalation interval shall default to 15 minutes per level.
FR-053: `SUPER_ADMIN` shall be able to resolve an alert without prior acknowledgement as an administrative override.
FR-054: The system shall track notification attempt status and error detail.
FR-055: The system shall expose alert notification history to authorized users.
FR-056: The system shall provide a WYSIWYG text template editor for WAHA WhatsApp alert messages.
FR-057: The system shall provide a `SUPER_ADMIN`-only health dashboard.
FR-058: The health dashboard shall show PostgreSQL connectivity.
FR-059: The health dashboard shall show InfluxDB connectivity.
FR-060: The health dashboard shall show Redis connectivity.
FR-061: The health dashboard shall show MQTT broker connectivity.
FR-062: The health dashboard shall show WAHA availability.
FR-063: The health dashboard shall show telemetry ingest worker status.
FR-064: The health dashboard shall show notification worker status.
FR-065: The health dashboard shall show latest telemetry received timestamp.

### New FRs from Research Reconciliation (2026-05-25)

FR-029a: The system shall validate that machine identity in the payload body matches the machine identity extracted from the MQTT topic.
FR-031a: The telemetry payload shall include a `schemaVersion` field identifying the payload format version.
FR-031b: The telemetry payload shall include a unique `messageId` field for deduplication.
FR-031c: The telemetry payload shall include a `timestamp` field in UTC ISO-8601 format.
FR-035a: The system shall reject or quarantine payloads with unrecognized schema versions.
FR-035b: The system shall persist rejected telemetry messages with topic, payload, rejection reason, and received timestamp in a quarantine store accessible to SUPER_ADMIN.
FR-035c: The system shall reject telemetry values outside physically plausible ranges and record the rejection reason.
FR-041a: The system shall use `messageId` for deduplication where duplicate processing would cause incorrect behavior.
FR-061a: The system shall rate-limit WhatsApp notification sends to prevent notification storms.
FR-073a: The health dashboard shall show quarantined message count and most recent quarantine entries.
FR-073b: The health dashboard shall show telemetry data quality metrics including rejected count and anomaly count.
FR-076: The system shall record immutable audit log entries for master data mutations including plant, machine, sparepart, responsibility, and threshold changes.
FR-077: Telemetry dashboard and alert views shall be filtered by the user's plant assignment.

### New FRs from Procurement Readiness Change (2026-08-23)

FR-078: A sparepart may carry an optional manually-entered material code that is globally unique and not plant-scoped; installation and lifetime flows do not require it.
FR-079: A sparepart may carry an optional procurement lead time duration allowing fractional values.
FR-080: A sparepart may hold estimated price entries with decimal amount, ISO-4217 currency defaulting to IDR, mandatory kurs-to-IDR snapshot when foreign, normalized IDR value, timestamp, and actor.
FR-081: Price entry history shall be retained and viewable with a copy/reuse action when unchanged.
FR-082: A sparepart may have one global image stored in Garage S3-compatible object storage with PostgreSQL holding only the object reference.
FR-083: Machine groups may define shift configuration of up to three shifts per day with start/end local times permitting cross-midnight windows.
FR-084: Machines may override shift configuration; machine wins over group; UI states the inherited source when falling back.
FR-085: Counter rate estimation uses a rolling 30-day moving average per operating hour from accepted telemetry with full-history fallback and explicit insufficient-data state.
FR-086: The system displays shift-aware depletion projections and lead-time counter consumption estimates.
FR-087: Projected depletion within the lead-time window raises a duplicate-prevented PROCUREMENT_RISK alert in addition to percentage-threshold alerts.
FR-088: Procurement readiness mutations require server-side job scope LEADER or above.
FR-089: All procurement readiness mutations are recorded in the immutable audit log.

### NonFunctional Requirements

NFR-001: The system shall separate datastore ownership: PostgreSQL for master/auth/config/alerts, InfluxDB for telemetry history, Redis for latest state/cache, and queue/job storage for notification dispatch.
NFR-002: The system shall validate external MQTT input before writing to downstream stores.
NFR-003: The system shall avoid fire-and-forget notification behavior by storing notification status history.
NFR-004: The system shall avoid alert spam through duplicate active alert prevention.
NFR-005: The web UI shall be responsive and use consistent shadcn/ui components from the selected admin dashboard boilerplate.
NFR-006: Health checks shall make telemetry and notification path failures visible to `SUPER_ADMIN`.
NFR-007: Phase 1 data relationships shall preserve plant, machine, user, responsibility, and sparepart structure needed for later ABAC and CMMS expansion.

### New NFRs from Research Reconciliation (2026-05-25)

NFR-001a: All Redis keys shall have explicit TTL values. Redis shall be rebuildable from PostgreSQL and InfluxDB and never be sole source of truth.
NFR-001b: Notification dispatch shall be fully decoupled from the telemetry ingest path.
NFR-006a: When telemetry processing cannot keep pace, the system shall degrade into bounded backlog rather than crash. Queue at capacity delays MQTT ack rather than silently discarding.
NFR-009a: EMQX shall authenticate each connecting device and enforce deny-by-default topic ACLs.
NFR-009b: MQTT connections shall support TLS. Production requires TLS; dev may use plaintext.
NFR-010a: Every telemetry message shall carry a correlation identifier logged at each processing stage for end-to-end traceability.
NFR-011a: External service calls (WAHA, InfluxDB) shall use configurable timeouts, retry with backoff, and circuit-breaker behavior.
NFR-013a: Next.js shall not access PostgreSQL, InfluxDB, Redis, EMQX, or WAHA directly. All data through Spring Boot APIs.
NFR-014a: Backend shall use structured logging with consistent fields and support OpenTelemetry-compatible trace/metric export.

### Additional Requirements

AR-001: Create monorepo structure with `apps/backend`, `apps/web`, `infra`, `scripts`, `docs`, and `tests`.
AR-002: Initialize Spring Boot backend with Java 25, Maven, Spring Web, Validation, Security, Data JPA, PostgreSQL, Flyway, Redis, Actuator, MQTT client/integration, Scheduling, and Testcontainers.
AR-003: Initialize frontend from `arhamkhnz/next-shadcn-admin-dashboard` and preserve boilerplate shell, theme presets, shadcn/ui, form/table conventions, and responsive behavior.
AR-004: Pin exact dependency versions during repository initialization and record them in README/docs.
AR-005: Choose Story 0 auth mode before auth implementation: Spring Security cookie/session auth or Spring Security JWT auth.
AR-006: Run local infrastructure through `infra/docker-compose.yml` with stable service names: `postgres`, `pgadmin`, `redis`, `influxdb`, `emqx`, and `waha`.
AR-007: Keep MQTT contract broker-agnostic while using EMQX for local/dev broker implementation.
AR-008: Configure backend MQTT via environment variables, not hardcoded EMQX URLs.
AR-009: Use REST JSON API under `/api/v1`, add Springdoc OpenAPI before Epic 2 data APIs, and generate/maintain OpenAPI contracts.
AR-010: Use PostgreSQL as system of record for master data, auth, config, alerts, WAHA templates, responsibility, notification jobs, audit events, and health snapshots.
AR-011: Use InfluxDB 3 Core for accepted telemetry history, with v2 line protocol compatibility as fallback if Java support blocks implementation.
AR-012: Use Redis for latest telemetry state and lightweight cache.
AR-013: Use PostgreSQL-backed notification job/outbox table for Phase 1 WAHA dispatch and retry.
AR-014: Preserve module boundaries: controllers call application services, repositories are module-local, and cross-module access goes through service interfaces or internal events.
AR-015: Backend owns permissions, status transitions, enums, sparepart lifetime calculations, idempotency, and audit evidence.
AR-016: Frontend renders backend decisions and must not reimplement domain rules.
AR-017: Implement trace ID propagation through request/MQTT -> telemetry -> alert -> notification -> audit.
AR-018: Implement idempotency keys for telemetry, alert creation, and notification jobs.
AR-019: Implement explicit alert transition matrix and return `INVALID_STATE_TRANSITION` for invalid transitions.
AR-020: Implement pilot seed, fixtures, and scripts for GM1 / Forming / BF-08410 / JBF19 / Electric PLC Wecon LX5.
AR-021: Provide before-threshold and threshold MQTT fixture payloads to prove alert is not created before 90% and is created at threshold.
AR-022: Use pgAdmin only as local/dev evidence tool, not runtime dependency or application admin replacement.
AR-023: Create `docs/pilot-validation.md` with operator proof and technical proof sections.
AR-024: Store timestamps in UTC; frontend displays both absolute audit/evidence timestamp and relative operational freshness.
AR-025: Keep Phase 1 modular monolith ready for CMMS, IMMS, ABAC, reporting, KPI, and IATF expansion without adding premature microservices.
AR-026: Starting Epic 2, use Orval to generate TypeScript clients and TanStack Query hooks from Springdoc OpenAPI; keep Zustand for UI/session state only.
AR-027: Add Resilience4j before WAHA or InfluxDB external-call workflows so retry, timeout, and circuit-breaker behavior is standardized.

### UX Design Requirements

UX-DR-001: Implement desktop, tablet, and mobile responsive layouts for Syncro operational workflows.
UX-DR-002: Use the selected Next.js shadcn admin boilerplate theme presets and default light theme.
UX-DR-003: Provide an Operations Overview command-center home emphasizing what needs attention now.
UX-DR-004: Provide Machine Detail / Machine Hub as central operational context for machine identity, status, telemetry, sparepart lifetime, alerts, and responsibility.
UX-DR-005: Provide Alert Detail optimized for urgent response, including why the alert fired, acknowledgement action, and escalation timeline.
UX-DR-006: Provide mobile alert response with sticky acknowledge action.
UX-DR-007: Provide dense desktop admin/table patterns for master data, machine sparepart installations, responsibility, and system setup.
UX-DR-008: Provide optional dark/control-room visual preset support through boilerplate theme capability.
UX-DR-009: Implement shared `StatusBadge` component with non-color-only status communication.
UX-DR-010: Implement shared `TelemetryCard` component for latest telemetry values and freshness.
UX-DR-011: Implement shared `LifetimeProgress` component for sparepart consumed percentage, threshold, baseline, current count, and expected count evidence.
UX-DR-012: Implement shared `AlertActionPanel` component with action state, explanation, disabled reason, loading, success, and error feedback.
UX-DR-013: Implement shared `EscalationTimeline` component showing sent/pending escalation levels and timestamps.
UX-DR-014: Implement shared `AuditEventRow` component for evidence history.
UX-DR-015: Implement shared `HealthCard` component for dependency/worker status, reason, and timestamp.
UX-DR-016: Implement shared `SetupCompletenessChecklist` for setup progress across plant, machine group, machine, sparepart, installation, and responsibility.
UX-DR-017: Implement shared `MachineSummaryCard` for machine identity, group, status, telemetry freshness, and risk.
UX-DR-018: Implement `WahaTemplateEditor` supporting variables `{machineCode}`, `{machineName}`, `{plantCode}`, `{machineGroup}`, `{sparepartName}`, `{thresholdPercent}`, `{currentCount}`, and `{alertTime}`.
UX-DR-019: Every operational component shall support loading, empty, error, stale, read-only, and forbidden states.
UX-DR-020: Every critical action shall explain what happens next before or during the action.
UX-DR-021: Acknowledge action copy shall communicate that acknowledgement stops further escalation, records the action, and leaves alert visible until resolved.
UX-DR-022: Resolve action copy shall communicate that resolving closes the alert after issue handling and resolved alerts cannot be acknowledged again.
UX-DR-023: UI shall show status label, reason, severity, timestamp, and allowed actions from backend operational status contract.
UX-DR-024: UI shall distinguish manual machine status from telemetry online/offline or stale state.
UX-DR-025: UI shall show latest telemetry for JBF19 as primary pilot proof.
UX-DR-026: UI shall follow WCAG AA baseline, including visible focus states, keyboard operability, and no color-only status communication.
UX-DR-027: Mobile and touch layouts shall use practical touch targets around 44px where possible.
UX-DR-028: Heavy telemetry history/charts shall not load by default on mobile; latest state and alert actions take priority.
UX-DR-029: Frontend `app/` route files shall contain route shell/page composition only; feature logic belongs in `features/`.
UX-DR-030: `components/ui/` remains generic shadcn/ui; reusable Syncro domain visuals live in `components/syncro/`.

### New UX Design Requirements from Research Reconciliation (2026-05-25)

UX-DR-031: Implement shared `DataQualityPanel` component showing quarantine count, rejection rate, anomaly count, and dead-letter count.
UX-DR-032: Implement shared `QuarantineLogTable` component showing rejected messages with topic, payload snippet, rejection reason, timestamp, and correlationId.
UX-DR-033: Implement shared `LatencyIndicator` component showing end-to-end telemetry latency (publish to dashboard visible).
UX-DR-034: Implement shared `AuditLogTable` component for immutable master data change history with filters.
UX-DR-035: Implement shared `PlantScopeSelector` component for plant switching in header/sidebar.
UX-DR-036: Update `EscalationTimeline` to include `rate-limited` state with next available send window.
UX-DR-037: Add `audit-log/` route and feature module for master data change history viewing.
UX-DR-038: Add quarantine log accessible from system health dashboard.

### FR Coverage Map

FR-001: Epic 1 - Login
FR-002: Epic 1 - Application roles
FR-003: Epic 1 - Menu/API access
FR-004: Epic 1 - Web boilerplate foundation
FR-005: Epic 1 - PostgreSQL migrations

FR-006: Epic 2 - Plant CRUD
FR-007: Epic 2 - Machine group CRUD
FR-008: Epic 2 - Plant-scoped machine group naming
FR-009: Epic 2 - Machine CRUD
FR-010: Epic 2 - Required machine fields
FR-011: Epic 2 - ACTIVE/INACTIVE machine status
FR-012: Epic 2 - Optional machine metadata
FR-013: Epic 2 - Manual machine active state
FR-014: Epic 2 - Sparepart taxonomy
FR-015: Epic 2 - Sparepart CRUD
FR-016: Epic 2 - Install spareparts on machines
FR-017: Epic 2 - Expected production-count lifetime
FR-018: Epic 2 - Baseline counter
FR-019: Epic 2 - Counter-based consumption
FR-020: Epic 2 - Default 90% threshold
FR-021: Epic 2 - Threshold override
FR-022: Epic 2 - Machine responsibility assignment
FR-023: Epic 2 - Responsibility levels
FR-024: Epic 2 - Machine-specific responsibility
FR-025: Epic 2 - Job scope separate from app role

FR-026: Epic 3 - MQTT topic subscription
FR-027: Epic 3 - Validate plant/machine
FR-028: Epic 3 - Reject inactive machine telemetry
FR-029: Epic 3 - Base telemetry payload
FR-030: Epic 3 - Cumulative unsigned counting
FR-031: Epic 3 - 16-bit counter wrap
FR-032: Epic 3 - Reject invalid payload
FR-033: Epic 3 - InfluxDB telemetry history
FR-034: Epic 3 - Redis latest telemetry
FR-035: Epic 3 - Optional telemetry fields
FR-036: Epic 3 - Latest telemetry dashboard
FR-037: Epic 3 - Manual status vs telemetry state
FR-038: Epic 3 - Render configured fields
FR-039: Epic 3 - Running/runtime/counting/last timestamp

FR-040: Epic 4 - Threshold alert creation
FR-041: Epic 4 - Alert statuses
FR-042: Epic 4 - New alerts OPEN
FR-043: Epic 4 - Acknowledge OPEN alert
FR-044: Epic 4 - Resolve acknowledged alert
FR-045: Epic 4 - Prevent duplicate active alerts
FR-046: Epic 4 - Retain alert history
FR-053: Epic 4 - SUPER_ADMIN resolve override

FR-047: Epic 5 - Queue WAHA notifications
FR-048: Epic 5 - Resolve recipients from responsibility
FR-049: Epic 5 - Staged escalation
FR-050: Epic 5 - Default escalation order
FR-051: Epic 5 - Acknowledgement stops escalation
FR-052: Epic 5 - 15-minute escalation interval
FR-054: Epic 5 - Notification attempt status/error
FR-055: Epic 5 - Alert notification history
FR-056: Epic 5 - WYSIWYG WAHA template editor

FR-057: Epic 6 - SUPER_ADMIN health dashboard
FR-058: Epic 6 - PostgreSQL health
FR-059: Epic 6 - InfluxDB health
FR-060: Epic 6 - Redis health
FR-061: Epic 6 - MQTT broker health
FR-062: Epic 6 - WAHA health
FR-063: Epic 6 - Telemetry ingest worker status
FR-064: Epic 6 - Notification worker status
FR-065: Epic 6 - Latest telemetry timestamp health

Success Metrics and Pilot Validation: Epic 7 - End-to-end proof across FR-001 through FR-065 using GM1/Forming/BF-08410/JBF19/Electric PLC Wecon LX5.

### New FR Coverage (Research Reconciliation 2026-05-25)

FR-029a: Epic 3 - Payload-topic machine identity match
FR-031a: Epic 3 - Schema version in payload
FR-031b: Epic 3 - MessageId for deduplication
FR-031c: Epic 3 - Timestamp in payload (UTC ISO-8601)
FR-035a: Epic 3 - Reject/quarantine unrecognized schema versions
FR-035b: Epic 3 - Quarantine store for rejected messages
FR-035c: Epic 3 - Reject physically implausible values
FR-041a: Epic 3 - MessageId-based deduplication
FR-061a: Epic 5 - WAHA rate limiting
FR-073a: Epic 6 - Quarantine count and entries in health dashboard
FR-073b: Epic 6 - Data quality metrics in health dashboard
FR-076: Epic 2 - Immutable audit log for master data mutations
FR-077: Epic 1 - Plant-scoped data access filtering

## Epic List

### Epic 1: Platform Foundation, Local Infrastructure & Auth Access

Users and implementers can run Syncro locally, access the app shell, log in, and verify role-aware navigation/API protection as the base for all later workflows.

**FRs covered:** FR-001, FR-002, FR-003, FR-004, FR-005

**Additional coverage:** AR-001, AR-002, AR-003, AR-004, AR-005, AR-006, AR-009, UX-DR-001, UX-DR-002, UX-DR-009, UX-DR-029, UX-DR-030

### Epic 2: Machine Master Data & Setup Foundation

SUPER_ADMIN and MANAGE users can configure plant, machine group, machine, sparepart taxonomy, spareparts, installed spareparts, and machine responsibility so Syncro knows what exists and who owns response.

**FRs covered:** FR-006, FR-007, FR-008, FR-009, FR-010, FR-011, FR-012, FR-013, FR-014, FR-015, FR-016, FR-017, FR-018, FR-019, FR-020, FR-021, FR-022, FR-023, FR-024, FR-025

**Additional coverage:** AR-010, AR-014, AR-015, AR-016, AR-024, UX-DR-007, UX-DR-016, UX-DR-017, UX-DR-019, UX-DR-026

### Epic 3: Telemetry Ingestion & Latest Machine Visibility

Users can receive validated MQTT telemetry from active machines and view latest machine telemetry, freshness, and machine context safely.

**FRs covered:** FR-026, FR-027, FR-028, FR-029, FR-030, FR-031, FR-032, FR-033, FR-034, FR-035, FR-036, FR-037, FR-038, FR-039

**Additional coverage:** AR-007, AR-008, AR-011, AR-012, AR-017, AR-018, UX-DR-003, UX-DR-004, UX-DR-010, UX-DR-019, UX-DR-024, UX-DR-025, UX-DR-028

### Epic 4: Sparepart Lifetime Alerting

Users can detect sparepart production-count threshold risk, understand why an alert fired, acknowledge or resolve alerts, and preserve alert history.

**FRs covered:** FR-040, FR-041, FR-042, FR-043, FR-044, FR-045, FR-046, FR-053

**Additional coverage:** AR-015, AR-018, AR-019, UX-DR-005, UX-DR-006, UX-DR-011, UX-DR-012, UX-DR-020, UX-DR-021, UX-DR-022, UX-DR-023, UX-DR-026, UX-DR-027

### Epic 5: WAHA Escalation & Notification Evidence

Users can route threshold alerts through staged WAHA escalation, configure WhatsApp message templates, track notification attempts, and stop escalation through acknowledgement.

**FRs covered:** FR-047, FR-048, FR-049, FR-050, FR-051, FR-052, FR-054, FR-055, FR-056

**Additional coverage:** AR-013, AR-017, AR-018, UX-DR-013, UX-DR-014, UX-DR-018, UX-DR-021

### Epic 6: System Health & Operational Diagnostics

SUPER_ADMIN can diagnose platform dependency, telemetry, and notification path health before failures become invisible operational gaps.

**FRs covered:** FR-057, FR-058, FR-059, FR-060, FR-061, FR-062, FR-063, FR-064, FR-065

**Additional coverage:** AR-022, AR-023, UX-DR-015, UX-DR-019, UX-DR-023

### Epic 7: Pilot Validation & Operational Proof

The team can prove Phase 1 end-to-end with canonical GM1/Forming/BF-08410/JBF19/Electric PLC Wecon LX5 data, MQTT fixtures, scripts, UI evidence, pgAdmin/log evidence, and pilot documentation.

**FRs covered:** validates FR-001 through FR-065 end-to-end; primary FR ownership remains Epic 1 through Epic 6.

**Success Metrics covered:** SM-001, SM-002, SM-003, SM-004, SM-005

**Additional coverage:** AR-020, AR-021, AR-023, UX-DR-025

### Epic 8: Sparepart Procurement Readiness & Operating Calendar

Maintenance teams gain procurement readiness data (material code, lead time, priced sparepart history with kurs evidence) and shift-aware counter projections, so replacement can be planned in calendar time and procurement-risk alerts fire before stock-out. Bridge scope toward Phase 3 IMMS.

**FRs covered:** FR-078, FR-079, FR-080, FR-081, FR-082, FR-083, FR-084, FR-085, FR-086, FR-087, FR-088, FR-089

**Additional coverage:** AR-010, AR-014, AR-015, AR-016, AR-024, UX-DR-007, UX-DR-019, UX-DR-026


## Epic 1: Platform Foundation, Local Infrastructure & Auth Access

Users and implementers can run Syncro locally, access the app shell, log in, and verify role-aware navigation/API protection as the base for all later workflows.

### Story 1.1: Initialize Monorepo and Version Baseline

As an implementer,
I want the Syncro repository structure, dependency versions, and documentation baseline initialized,
So that future stories are built consistently from one known foundation.

**Acceptance Criteria:**

**Given** empty Syncro project workspace
**When** repository foundation is initialized
**Then** root structure includes `apps/backend`, `apps/web`, `infra`, `scripts`, `docs`, and `tests`
**And** README or docs records selected dependency versions
**And** `.env.example` exists at root or documented app-specific env locations
**And** implementation notes preserve architecture constraints from `architecture.md`

### Story 1.2: Start Local Infrastructure Stack

As an implementer,
I want local infrastructure services to run through Docker Compose,
So that backend, telemetry, notification, and validation stories have required dependencies.

**Acceptance Criteria:**

**Given** local development machine with Docker available
**When** local infrastructure is started from `infra/docker-compose.yml`
**Then** services start with stable names `postgres`, `pgadmin`, `redis`, `influxdb`, `emqx`, and `waha`
**And** PostgreSQL is reachable by backend configuration
**And** pgAdmin can inspect PostgreSQL during local development
**And** EMQX is available as local MQTT broker without hardcoded backend URLs
**And** WAHA service has documented local endpoint configuration
**And** infra docs state pgAdmin is local/dev only

### Story 1.3: Initialize Spring Boot Backend Skeleton

As an implementer,
I want a Spring Boot backend skeleton with core dependencies and health baseline,
So that Syncro can expose `/api/v1` endpoints and integrate persistence/services incrementally.

**Acceptance Criteria:**

**Given** repository foundation exists
**When** backend app is initialized in `apps/backend`
**Then** app boots successfully with Java 25 and Maven
**And** configured dependencies include Spring Web, Validation, Security, Data JPA, PostgreSQL, Flyway, Redis, Actuator, MQTT client/integration, Scheduling, and Testcontainers
**And** backend exposes an `/api/v1` health or readiness endpoint suitable for local smoke test
**And** Flyway migration folder exists
**And** backend config uses environment/profile values, not hardcoded local service URLs

### Story 1.4: Initialize Next.js Admin Frontend Shell

As an implementer,
I want the selected Next.js shadcn admin dashboard starter adapted into `apps/web`,
So that Syncro has a responsive app shell for future operational screens.

**Acceptance Criteria:**

**Given** repository foundation exists
**When** frontend starter is installed in `apps/web`
**Then** frontend runs locally using the starter package scripts
**And** default light theme and boilerplate theme capability remain available
**And** app shell supports desktop, tablet, and mobile layouts
**And** `app/` contains route shell/page composition only
**And** `features/`, `components/ui/`, and `components/syncro/` boundaries exist or are documented
**And** no Syncro domain rule is implemented in frontend-only code

### Story 1.5: Choose and Implement Auth Mode Baseline

As a SUPER_ADMIN,
I want to log in to Syncro with the selected Phase 1 auth mode,
So that only authenticated users can access protected screens and APIs.

**Acceptance Criteria:**

**Given** Story 0 auth decision is recorded as either Spring Security session/cookie or JWT
**When** backend auth baseline is implemented
**Then** authenticated login is available
**And** unauthenticated users cannot access protected `/api/v1` endpoints
**And** frontend login screen authenticates against backend
**And** successful login routes user into the app shell
**And** failed login shows safe error feedback without exposing sensitive detail
**And** selected auth mode is documented

### Story 1.6: Enforce Application Role Access

As a SUPER_ADMIN,
I want Syncro to enforce `SUPER_ADMIN`, `MANAGE`, and `VIEWER` role access in API and navigation,
So that users only see and perform actions allowed by their platform role.

**Acceptance Criteria:**

**Given** users exist with `SUPER_ADMIN`, `MANAGE`, or `VIEWER` roles
**When** users access protected menus or APIs
**Then** backend enforces role access server-side
**And** frontend navigation hides or disables unavailable menu actions based on backend-provided permissions or role response
**And** `VIEWER` cannot mutate protected resources
**And** `MANAGE` can access create/edit/view capability only where scope later allows
**And** denied API responses use standard error shape with code, message, timestamp, and traceId
**And** role model remains separate from job scopes `TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, and `MANAGER`

## Epic 2: Machine Master Data & Setup Foundation

SUPER_ADMIN and MANAGE users can configure plant, machine group, machine, sparepart taxonomy, spareparts, installed spareparts, and machine responsibility so Syncro knows what exists and who owns response.

### Story 2.1: Manage Plants

As a SUPER_ADMIN or MANAGE user,
I want to create, view, edit, and delete plants,
So that machine data can be organized by plant.

**Acceptance Criteria:**

**Given** authenticated user has permitted role
**When** user creates plant with valid data
**Then** plant is persisted in PostgreSQL
**And** plant appears in plant management list
**And** duplicate or invalid plant data returns standard validation error
**And** plant create/update request DTOs enforce `@Valid` Jakarta Bean Validation with explicit size/format constraints and malformed JSON returns the standard safe error shape
**And** VIEWER can view permitted plant list but cannot create, edit, or delete
**And** plant table supports loading, empty, error, read-only, and forbidden states

### Story 2.2: Manage Plant-Scoped Machine Groups

As a SUPER_ADMIN or MANAGE user,
I want to manage machine groups within a plant,
So that process lines like Forming can be represented per plant.

**Acceptance Criteria:**

**Given** at least one plant exists
**When** user creates machine group under a plant
**Then** machine group is linked to that plant
**And** same machine group name may exist in different plants
**And** duplicate machine group name in same plant is rejected
**And** machine group create/update request DTOs enforce required `plantId`, bounded text fields, and safe validation/malformed JSON errors
**And** machine group list can be filtered by plant
**And** VIEWER cannot mutate machine groups

### Story 2.3: Manage Machines with Manual Active State

As a SUPER_ADMIN or MANAGE user,
I want to create and maintain machines with manual ACTIVE/INACTIVE status,
So that telemetry acceptance can depend on registered machine master data.

**Acceptance Criteria:**

**Given** plant and machine group exist
**When** user creates machine with `code`, `plantId`, `machineGroupId`, and `status`
**Then** machine is persisted and visible in machine management
**And** machine status supports only `ACTIVE` and `INACTIVE`
**And** optional `brand`, `installedAt`, and `notes` can be stored
**And** machine active state is not inferred from MQTT or telemetry freshness
**And** machine summary shows plant, group, code, name/status where available
**And** invalid or duplicate machine code returns standard validation error
**And** machine create/update request DTOs enforce required UUIDs, machine-code format/length, allowed status values, bounded optional fields, and malformed JSON/type mismatch safe errors

### Story 2.4: Manage Sparepart Taxonomy

As a SUPER_ADMIN or MANAGE user,
I want to manage sparepart category, brand, kind, and type taxonomy,
So that spareparts are normalized and searchable.

**Acceptance Criteria:**

**Given** user has permitted role
**When** user creates taxonomy entries for category, brand, kind, or type
**Then** entries are persisted and available for sparepart creation
**And** duplicate taxonomy values within same dimension are rejected
**And** taxonomy request DTOs enforce bounded names/codes and return safe validation/malformed JSON errors
**And** taxonomy lists support loading, empty, error, read-only, and forbidden states
**And** VIEWER can view taxonomy but cannot mutate it

### Story 2.5: Manage Spareparts

As a SUPER_ADMIN or MANAGE user,
I want to create and maintain spareparts using taxonomy dimensions,
So that installed spareparts can be tracked consistently.

**Acceptance Criteria:**

**Given** taxonomy entries exist
**When** user creates sparepart referencing category, brand, kind, and type
**Then** sparepart is persisted and visible in sparepart list
**And** missing required taxonomy references return validation error
**And** sparepart request DTOs enforce required taxonomy UUIDs, bounded text/code fields, and safe validation/malformed JSON errors
**And** sparepart list supports dense table display and filters
**And** VIEWER cannot create, edit, or delete spareparts

### Story 2.6: Install Spareparts on Machines with Lifetime Baseline

As a SUPER_ADMIN or MANAGE user,
I want to install spareparts on machines with expected production count and baseline counter,
So that Syncro can later calculate consumed lifetime from production output.

**Acceptance Criteria:**

**Given** machine and sparepart exist
**When** user creates machine sparepart installation
**Then** installation stores machine, sparepart, expected production count, baseline counter, and threshold percentage
**And** threshold defaults to 90% when not provided
**And** user may override threshold percentage
**And** consumption rule is recorded as counter-based, not `installedAt`-based
**And** invalid expected count, baseline, or threshold returns validation error
**And** installation request DTOs enforce positive/zero-safe numeric ranges, threshold min/max, required UUIDs, and safe validation/malformed JSON errors
**And** installation list shows baseline/current/expected/threshold evidence fields where current count is available

### Story 2.7: Assign Machine Responsibility Levels

As a SUPER_ADMIN or MANAGE user,
I want to assign responsible users to machines by job scope level,
So that alerts can route to the correct escalation recipients later.

**Acceptance Criteria:**

**Given** machine exists and users exist
**When** user assigns responsible parties to machine
**Then** assignment stores machine, user, and responsibility level
**And** responsibility levels support `TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, and `MANAGER`
**And** responsibility is machine-specific
**And** job scope is stored separately from application role
**And** invalid or duplicate assignments return validation error
**And** responsibility request DTOs enforce required machine/user UUIDs, allowed responsibility level enum values, and safe validation/malformed JSON errors
**And** UI explains `MANAGE` app role is different from `MANAGER` job scope where relevant

### Story 2.8: Show Setup Completeness (RETIRED 2026-08-27)

> Status note: RETIRED on 2026-08-27 by user decision — the Setup tab/menu was removed and Plants was consolidated into Master Data → Organization. The `features/setup` module and `setup-completeness-checklist` component are no longer referenced by any route (files remain on disk, unreferenced). Do NOT reintroduce a Setup menu or tab; setup-state concerns that survive live under Organization/Plants. If setup completeness is ever needed again, re-scope it as an Organization sub-tab, not a standalone menu.

## Epic 3: Telemetry Ingestion & Latest Machine Visibility

Users can receive validated MQTT telemetry from active machines and view latest machine telemetry, freshness, and machine context safely.

### Story 3.1: Configure MQTT Subscription and Telemetry Contract

As an implementer,
I want Syncro backend to subscribe to the configured MQTT topic contract,
So that active machines can send telemetry into the system through broker-agnostic configuration.

**Acceptance Criteria:**

**Given** EMQX is running locally and backend MQTT env values are configured
**When** backend starts
**Then** backend subscribes to `factory/{plantCode}/{machineCode}/telemetry` or configured equivalent topic filter
**And** MQTT host, port, credentials, client ID, and topic filter come from environment/profile config
**And** no EMQX URL is hardcoded in backend source
**And** incoming MQTT messages receive or generate a traceId
**And** connection/subscription status is observable for later health reporting

### Story 3.2: Validate MQTT Topic and Base Payload

As a system,
I want to validate incoming MQTT topic and payload shape before storing telemetry,
So that invalid machine data cannot pollute telemetry stores.

**Acceptance Criteria:**

**Given** backend receives MQTT message
**When** topic contains plant and machine code
**Then** backend validates plantCode and machineCode against registered machine data
**And** backend validates payload includes `running`, `runtimeHours`, and `counting`
**And** invalid topic or payload is rejected before InfluxDB/Redis writes
**And** rejection is logged/audited with reason and traceId
**And** validation errors do not crash the MQTT ingest worker

### Story 3.3: Reject Inactive Machine Telemetry

As a system,
I want telemetry for inactive machines to be rejected,
So that manual machine status remains authoritative.

**Acceptance Criteria:**

**Given** registered machine status is `INACTIVE`
**When** MQTT telemetry arrives for that machine
**Then** backend rejects the telemetry before writing to InfluxDB or Redis
**And** rejection reason identifies inactive machine status
**And** audit/log evidence includes machine identity and traceId
**And** machine status remains manual master data, not inferred from telemetry freshness

### Story 3.4: Persist Accepted Telemetry to InfluxDB and Redis

As an operator,
I want accepted telemetry to be stored as history and latest state,
So that Syncro can show current machine status and preserve telemetry history.

**Acceptance Criteria:**

**Given** active registered machine receives valid telemetry
**When** backend accepts MQTT payload
**Then** telemetry history is written to InfluxDB
**And** latest telemetry state is written to Redis
**And** telemetry write includes machine identity, timestamp, running, runtimeHours, counting, and traceId
**And** accepted telemetry does not create duplicate latest/history records for duplicate payload within idempotency rule
**And** downstream alert evaluation can consume accepted telemetry in later epic

### Story 3.5: Calculate Production Count Delta with 16-Bit Wrap Support

As a system,
I want cumulative unsigned counting to handle 16-bit PLC wrap,
So that production deltas remain correct when counters roll over to zero.

**Acceptance Criteria:**

**Given** machine telemetry source may use unsigned 16-bit counter
**When** current counting value is lower than previous counting value due to wrap
**Then** backend calculates delta using unsigned wrap rule
**And** normal increasing counts calculate direct delta
**And** invalid negative/non-numeric count payloads are rejected by payload validation
**And** test coverage includes wrap case from max range back to low value
**And** frontend does not calculate production delta from raw telemetry

### Story 3.6: Support Optional Machine Telemetry Fields

As a SUPER_ADMIN or MANAGE user,
I want configured optional telemetry fields per machine to be accepted and displayed,
So that Syncro can support future sensors without changing the base telemetry contract.

**Acceptance Criteria:**

**Given** machine has optional telemetry fields configured
**When** valid MQTT payload contains those fields
**Then** backend stores accepted optional fields with telemetry history/latest state
**And** unknown optional fields are handled according to configured machine field rules
**And** dashboard can render configured fields for that machine
**And** base fields `running`, `runtimeHours`, and `counting` remain required

### Story 3.7: Show Latest Telemetry Dashboard

As a VIEWER, MANAGE, or SUPER_ADMIN user,
I want to see latest telemetry for active machines,
So that I can know which machines are running and recently reporting data.

**Acceptance Criteria:**

**Given** accepted telemetry exists in Redis latest state
**When** user opens telemetry dashboard
**Then** dashboard shows latest telemetry for permitted active machines
**And** each machine shows running state, runtime hours, production count, and last received timestamp
**And** dashboard distinguishes manual `ACTIVE/INACTIVE` status from telemetry online/offline/stale state
**And** dashboard supports loading, empty, error, stale, read-only, and forbidden states
**And** status communication is not color-only

### Story 3.8: Show Machine Hub with Telemetry Context

As an operator,
I want a machine detail view centered on identity and latest telemetry,
So that I can understand one machine operational state before alerting is added.

**Acceptance Criteria:**

**Given** machine exists and may have latest telemetry
**When** user opens machine detail / Machine Hub
**Then** page shows plant, machine group, code, machine name, manual status, and telemetry freshness
**And** latest telemetry appears through `TelemetryCard` pattern
**And** optional configured telemetry fields render when available
**And** JBF19 pilot machine can show latest telemetry as primary proof
**And** heavy telemetry history/charts are not loaded by default on mobile
**And** page remains useful if telemetry is empty or stale

## Epic 4: Sparepart Lifetime Alerting

Users can detect sparepart production-count threshold risk, understand why an alert fired, acknowledge/resolve alerts, and preserve alert history.

### Story 4.1: Evaluate Sparepart Lifetime Threshold from Accepted Telemetry

As a system,
I want accepted telemetry to evaluate installed sparepart lifetime consumption,
So that sparepart risk can be detected from production output.

**Acceptance Criteria:**

**Given** a machine has installed sparepart with baseline counter, expected production count, and threshold percentage
**When** accepted telemetry updates current production count
**Then** backend calculates consumed production count from current counter relative to baseline counter
**And** consumed percentage uses `consumedProductionCount / expectedProductionCount * 100`
**And** calculation is backend-owned and not duplicated in frontend
**And** calculation supports counter wrap behavior from Epic 3
**And** calculation evidence is available for alert detail and machine detail display

### Story 4.2: Create Threshold Alert with Duplicate Prevention

As a maintenance operator,
I want Syncro to create one active alert when sparepart consumption reaches threshold,
So that I am notified of risk without alert spam.

**Acceptance Criteria:**

**Given** consumed percentage reaches or exceeds configured threshold
**When** alert evaluation runs
**Then** backend creates an alert with status `OPEN`
**And** alert records machine, installed sparepart, threshold, current count, consumed percentage, and traceId
**And** backend prevents duplicate active alerts for same machine sparepart installation and threshold crossing
**And** duplicate telemetry does not create duplicate alert
**And** alert creation writes audit event
**And** alert status enum is backend-owned

### Story 4.3: Show Alert List and Alert Detail

As a VIEWER, MANAGE, or SUPER_ADMIN user,
I want to see sparepart lifetime alerts with clear evidence,
So that I understand what needs attention and why.

**Acceptance Criteria:**

**Given** alerts exist
**When** user opens alert list or alert detail
**Then** UI shows alert status, machine identity, sparepart, threshold percentage, current count, consumed percentage, created timestamp, and status reason
**And** alert detail explains why alert fired
**And** `LifetimeProgress` shows baseline, current count, expected count, consumed percentage, and threshold
**And** UI uses non-color-only `StatusBadge` pattern
**And** page supports loading, empty, error, stale, read-only, and forbidden states
**And** frontend does not recalculate alert status independently

### Story 4.4: Acknowledge Open Alert

As an authorized responsible user,
I want to acknowledge an open alert,
So that the system records my response and can stop later escalation.

**Acceptance Criteria:**

**Given** alert status is `OPEN` and user is allowed to acknowledge
**When** user clicks acknowledge
**Then** backend transitions alert to `ACKNOWLEDGED`
**And** acknowledgement records actor, timestamp, source, action, target, result, and traceId
**And** frontend action panel explains: “Stops further escalation and records your action. Alert remains visible until resolved.”
**And** action button supports disabled reason, loading, success, and error feedback
**And** invalid transition returns `INVALID_STATE_TRANSITION` standard error
**And** mobile alert view provides sticky acknowledge action visible without scrolling where practical`n**And** mobile acknowledge target is touch-friendly, around 44px where layout allows

### Story 4.5: Resolve Acknowledged Alert

As an authorized user,
I want to resolve an acknowledged alert,
So that handled sparepart risk is closed while history remains available.

**Acceptance Criteria:**

**Given** alert status is `ACKNOWLEDGED` and user is allowed to resolve
**When** user resolves alert
**Then** backend transitions alert to `RESOLVED`
**And** resolved alert becomes terminal for Phase 1
**And** alert history remains visible
**And** frontend explains: “Closes this alert after the issue has been handled. Resolved alerts cannot be acknowledged again.”
**And** invalid transitions return `INVALID_STATE_TRANSITION`
**And** audit event records actor, timestamp, source, action, target, result, and traceId

### Story 4.6: Allow SUPER_ADMIN Direct Resolve Override

As a SUPER_ADMIN,
I want to resolve an open alert without prior acknowledgement,
So that I can close alerts administratively when operationally needed.

**Acceptance Criteria:**

**Given** alert status is `OPEN` and current user is `SUPER_ADMIN`
**When** SUPER_ADMIN resolves alert directly
**Then** backend transitions alert to `RESOLVED`
**And** resolution is recorded as administrative override
**And** non-SUPER_ADMIN users cannot directly resolve `OPEN` alert unless allowed by lifecycle rule
**And** audit event captures override actor and traceId
**And** frontend only shows direct resolve action when backend allowed actions include it

### Story 4.7: Add Alert State to Machine Hub and Operations Overview

As an operator,
I want machine and operations views to show current sparepart alert state,
So that I can see risk without opening the alert module first.

**Acceptance Criteria:**

**Given** active or recent alerts exist for a machine
**When** user opens Operations Overview or Machine Hub
**Then** UI shows alert state, sparepart risk, status reason, and timestamp
**And** Machine Hub links to alert detail
**And** Operations Overview highlights what needs attention now
**And** UI uses backend-provided allowed actions and status severity
**And** status communication remains non-color-only and responsive on desktop/tablet/mobile

## Epic 5: WAHA Escalation & Notification Evidence

Users can route threshold alerts through staged WAHA escalation, configure WhatsApp message templates, track notification attempts, and stop escalation through acknowledgement.

### Story 5.1: Create WAHA Template Editor

As a SUPER_ADMIN or MANAGE user,
I want to create and edit WAHA WhatsApp alert message templates,
So that alert notifications use consistent operational text.

**Acceptance Criteria:**

**Given** user has permitted role
**When** user creates or edits a WAHA alert template
**Then** template text is persisted in PostgreSQL
**And** editor supports variables `{machineCode}`, `{machineName}`, `{plantCode}`, `{machineGroup}`, `{sparepartName}`, `{thresholdPercent}`, `{currentCount}`, and `{alertTime}`
**And** invalid or unknown variables are shown as validation errors
**And** preview renders sample alert data safely`n**And** a default starter template exists so initial notification queueing is not blocked by manual template creation`n**And** VIEWER can view template where permitted but cannot edit
**And** editor supports loading, empty, error, read-only, and forbidden states

### Story 5.2: Queue Initial WAHA Notification for Open Alert

As a system,
I want to queue a WhatsApp notification when a threshold alert opens,
So that the responsible technician is notified without blocking alert creation.

**Acceptance Criteria:**

**Given** threshold alert is created with status `OPEN`
**When** notification routing runs
**Then** backend resolves recipient from machine responsibility level `TECHNICIAN` first
**And** backend creates notification job in PostgreSQL with alertId, escalation level, recipient, status, idempotencyKey, and traceId
**And** alert creation is not blocked by WAHA send attempt
**And** duplicate alert/notification processing does not create duplicate logical notification job
**And** missing recipient is recorded as notification routing failure with error detail

### Story 5.3: Send Notification Job Through WAHA with Attempt History

As a SUPER_ADMIN or MANAGE user,
I want WAHA sends to be tracked with attempt history,
So that failed WhatsApp notification delivery is visible and diagnosable.

**Acceptance Criteria:**

**Given** pending notification job exists
**When** notification worker processes job
**Then** worker sends rendered template text to WAHA
**And** each send attempt records status, timestamp, response/error detail, attempt count, and traceId
**And** successful send marks job delivered/sent according to backend enum
**And** failed send records retry state with `nextAttemptAt` when retryable
**And** exhausted or non-retryable failure remains visible in notification history
**And** WAHA credentials or secrets are never logged

### Story 5.4: Escalate Alert Notifications by Responsibility Level

As a system,
I want unacknowledged alerts to escalate through responsibility levels,
So that higher levels are notified when no one responds.

**Acceptance Criteria:**

**Given** alert remains `OPEN` after configured escalation interval
**When** escalation worker runs
**Then** backend queues next level notification in order `TECHNICIAN -> STAFF -> LEADER -> SPV -> MANAGER`
**And** default interval is 15 minutes per level
**And** each escalation job uses dedupe key `alertId + escalationLevel + recipientId`
**And** escalation stops after highest configured level or no next recipient
**And** missing recipient or skipped level is recorded with reason
**And** schedule uses backend time, not frontend countdown

### Story 5.5: Stop Escalation When Alert Is Acknowledged

As an authorized responsible user,
I want acknowledgement to stop future escalation,
So that no unnecessary WhatsApp notifications are sent after response.

**Acceptance Criteria:**

**Given** alert has pending or scheduled escalation jobs
**When** alert is acknowledged
**Then** backend prevents any later escalation jobs from being queued or sent
**And** existing pending jobs are cancelled, skipped, or marked no longer applicable according to documented job status rule
**And** audit records acknowledgement stopping escalation
**And** escalation timeline reflects stopped/cancelled pending levels
**And** duplicate acknowledgement does not requeue notification

### Story 5.6: Show Escalation Timeline and Notification History

As a VIEWER, MANAGE, or SUPER_ADMIN user,
I want to see notification history and escalation timeline on alert detail,
So that I know who was notified, when, and what happened.

**Acceptance Criteria:**

**Given** alert has notification jobs or attempts
**When** user opens alert detail
**Then** `EscalationTimeline` shows sent, pending, skipped, failed, and stopped levels with timestamps
**And** notification history shows recipient level, recipient identity, status, attempt count, and last error where available
**And** `AuditEventRow` shows related alert/notification evidence
**And** UI does not expose WAHA credentials or secrets
**And** page supports loading, empty, error, stale, read-only, and forbidden states

### Story 5.7: Surface WAHA Notification State in Operations Views

As an operator,
I want alert cards and operations views to show notification/escalation state,
So that I know whether responsible people have been reached.

**Acceptance Criteria:**

**Given** alert has notification jobs or escalation state
**When** user opens Operations Overview, Machine Hub, or Alert List
**Then** UI shows notification state such as pending, sent, failed, or stopped
**And** state includes timestamp and reason from backend where available
**And** failed WAHA notification is visible as operational risk
**And** status communication is non-color-only
**And** link to alert detail exposes full notification history

## Epic 6: System Health & Operational Diagnostics

SUPER_ADMIN can diagnose platform dependency, telemetry, and notification path health before failures become invisible operational gaps.

### Story 6.1: Expose Dependency Health Checks

As a SUPER_ADMIN,
I want Syncro backend to check critical platform dependencies,
So that I can tell whether the system can ingest telemetry and send notifications.

**Acceptance Criteria:**

**Given** backend is running
**When** health checks execute
**Then** backend reports PostgreSQL connectivity
**And** backend reports InfluxDB connectivity
**And** backend reports Redis connectivity
**And** backend reports MQTT broker connectivity
**And** backend reports WAHA availability
**And** each health result includes status, statusLabel, statusReason, statusSeverity, timestamp, and traceId where applicable
**And** dependency failures do not crash the backend

### Story 6.2: Report Telemetry Ingest Worker Status

As a SUPER_ADMIN,
I want to see telemetry ingest worker status,
So that I can know whether machine telemetry can enter Syncro.

**Acceptance Criteria:**

**Given** telemetry ingest worker is configured
**When** health status is requested
**Then** backend reports ingest worker running/stopped/degraded state
**And** status includes MQTT subscription state where available
**And** status includes last accepted telemetry timestamp
**And** stale telemetry condition is reported with reason and timestamp
**And** worker status is visible only to authorized SUPER_ADMIN users

### Story 6.3: Report Notification Worker Status

As a SUPER_ADMIN,
I want to see notification worker status,
So that I can know whether WAHA alert escalation can run.

**Acceptance Criteria:**

**Given** notification worker is configured
**When** health status is requested
**Then** backend reports notification worker running/stopped/degraded state
**And** status includes pending job count where available
**And** status includes recent failed notification count or latest failure reason where available
**And** status includes last successful WAHA send timestamp where available
**And** worker status is visible only to authorized SUPER_ADMIN users

### Story 6.4: Build SUPER_ADMIN Health Dashboard

As a SUPER_ADMIN,
I want a health dashboard showing dependency and worker status,
So that I can diagnose why telemetry, alerts, or notifications may stop flowing.

**Acceptance Criteria:**

**Given** SUPER_ADMIN is authenticated
**When** user opens system health dashboard
**Then** dashboard shows `HealthCard` for PostgreSQL, InfluxDB, Redis, MQTT/EMQX, WAHA, telemetry ingest worker, and notification worker
**And** each card shows status label, reason, severity, and timestamp
**And** health dashboard is not accessible to non-SUPER_ADMIN roles
**And** UI supports loading, empty, error, stale, read-only, and forbidden states
**And** status communication is not color-only
**And** dashboard is responsive across desktop, tablet, and mobile

### Story 6.5: Surface Latest Telemetry Freshness in Health

As a SUPER_ADMIN,
I want health diagnostics to show latest telemetry received timestamp,
So that I can detect telemetry path failures even when dependencies appear reachable.

**Acceptance Criteria:**

**Given** accepted telemetry history exists or no telemetry has been accepted yet
**When** health dashboard loads
**Then** dashboard shows latest telemetry received timestamp
**And** if telemetry is stale, dashboard shows stale reason and elapsed freshness
**And** if no telemetry exists, dashboard shows clear empty state rather than healthy false-positive
**And** value is based on backend latest accepted telemetry record, not frontend time inference
**And** timestamp is displayed with absolute UTC and relative freshness where practical

### Story 6.6: Link Health Failures to Operational Evidence

As a SUPER_ADMIN,
I want health failures to point to evidence or next diagnostic action,
So that I can investigate failure without guessing.

**Acceptance Criteria:**

**Given** a dependency or worker is degraded/unhealthy/stale
**When** health dashboard renders failure state
**Then** UI shows status reason and suggested evidence location or next action
**And** WAHA failure references notification history where available
**And** telemetry stale condition references latest telemetry/machine context where available
**And** local/dev docs may mention pgAdmin for PostgreSQL evidence without making pgAdmin a product feature
**And** no secrets or credentials are displayed in health UI

## Epic 7: Pilot Validation & Operational Proof

The team can prove Phase 1 end-to-end with canonical GM1/Forming/BF-08410/JBF19/Electric PLC Wecon LX5 data, MQTT fixtures, scripts, UI evidence, pgAdmin/log evidence, and pilot documentation.

### Story 7.1: Create Canonical Pilot Seed Data

As an implementer,
I want canonical pilot seed data for GM1/Forming/BF-08410/JBF19,
So that Phase 1 can be validated against a repeatable industrial scenario.

**Acceptance Criteria:**

**Given** local PostgreSQL is running
**When** pilot seed is applied
**Then** database contains plant `GM1`
**And** machine group `Forming` exists under `GM1`
**And** machine `BF-08410` / `JBF19` exists with `ACTIVE` status
**And** sparepart `Electric PLC Wecon LX5` exists and is installed on JBF19
**And** installation has expected production count, baseline counter, and 90% threshold suitable for validation
**And** responsibility assignments include `TECHNICIAN`, `STAFF`, and `LEADER` recipients
**And** seed data uses only lifecycle states possible through backend rules

### Story 7.2: Provide Pilot MQTT Payload Fixtures

As an implementer,
I want before-threshold and threshold MQTT payload fixtures for JBF19,
So that telemetry-to-alert behavior can be tested repeatably.

**Acceptance Criteria:**

**Given** pilot seed data exists
**When** fixtures are created under `tests/fixtures`
**Then** `mqtt-jbf19-before-threshold-payload.json` represents accepted telemetry below threshold
**And** `mqtt-jbf19-threshold-payload.json` represents accepted telemetry reaching 90% threshold
**And** payloads include `running`, `runtimeHours`, `counting`, and timestamp
**And** payload values align with pilot baseline and expected count
**And** fixtures do not rely on frontend calculation

### Story 7.3: Create Pilot Scripts for Seed, Publish, and Verify

As an implementer,
I want repeatable pilot scripts,
So that the end-to-end validation can be run without manual guesswork.

**Acceptance Criteria:**

**Given** local infra and apps are available
**When** pilot scripts are created under `scripts/`
**Then** `seed-pilot.ps1` applies or documents pilot seed flow
**And** `publish-jbf19-before-threshold.ps1` publishes before-threshold payload to EMQX topic `factory/GM1/BF-08410/telemetry`
**And** `publish-jbf19-threshold.ps1` publishes threshold payload to EMQX topic `factory/GM1/BF-08410/telemetry`
**And** `verify-pilot.ps1` checks or documents verification for telemetry, alert, notification job, and acknowledgement result
**And** scripts use environment variables/config, not hardcoded secrets
**And** scripts are documented for Windows PowerShell

### Story 7.4: Validate Telemetry Before Threshold Does Not Create Alert

As a SUPER_ADMIN or implementer,
I want to prove below-threshold telemetry is accepted without creating alert,
So that threshold logic is trusted.

**Acceptance Criteria:**

**Given** pilot seed data exists and backend is subscribed to EMQX
**When** before-threshold JBF19 payload is published
**Then** telemetry is accepted and latest JBF19 telemetry is visible
**And** no threshold alert is created for the installed sparepart
**And** audit/log evidence records accepted telemetry with traceId
**And** technical proof can be inspected through logs, pgAdmin, or documented query
**And** UI proof can be captured from Machine Hub or telemetry dashboard

### Story 7.5: Validate Threshold Alert and WAHA Notification Job

As a SUPER_ADMIN or implementer,
I want to prove threshold telemetry creates alert and queues WAHA notification,
So that Phase 1 success metric is demonstrated.

**Acceptance Criteria:**

**Given** before-threshold validation has passed
**When** threshold JBF19 payload is published
**Then** telemetry is accepted
**And** installed sparepart reaches 90% consumed production count
**And** exactly one active `OPEN` alert is created
**And** TECHNICIAN WAHA notification job is queued or sent
**And** alert detail shows why the alert fired
**And** traceId connects telemetry, alert, notification job, and audit evidence
**And** duplicate publish does not create duplicate active alert or duplicate logical notification job

### Story 7.6: Validate Acknowledgement Stops Escalation

As a SUPER_ADMIN or authorized responsible user,
I want to prove acknowledging the pilot alert stops later escalation,
So that WAHA escalation behavior is trusted.

**Acceptance Criteria:**

**Given** pilot alert is `OPEN` with escalation pending
**When** authorized user acknowledges alert before next escalation interval
**Then** alert transitions to `ACKNOWLEDGED`
**And** STAFF and LEADER notification jobs are not sent after acknowledgement
**And** escalation timeline shows acknowledgement stopped future escalation
**And** audit records actor, timestamp, action, result, and traceId
**And** mobile alert view supports acknowledge path

### Story 7.7: Document Pilot Validation Proof

As a project stakeholder,
I want pilot validation documentation with operator proof and technical proof,
So that Phase 1 readiness can be demonstrated and repeated.

**Acceptance Criteria:**

**Given** pilot flow can be executed
**When** `docs/pilot-validation.md` is written
**Then** document includes prerequisites, seed instructions, publish instructions, and expected results
**And** operator proof section covers UI evidence: JBF19 latest telemetry, 90% lifetime, open alert, escalation timeline, acknowledge action, health dashboard
**And** technical proof section covers pgAdmin/log/job-table/audit evidence
**And** docs reference `docs/screenshots/pilot/` for optional screenshots
**And** docs clearly distinguish pgAdmin as local/dev evidence tool, not product feature
**And** docs explain success metrics SM-001 through SM-005


## Epic 8: Sparepart Procurement Readiness & Operating Calendar

Maintenance teams gain procurement readiness data (material code, lead time, priced sparepart history with kurs evidence) and shift-aware counter projections, so replacement can be planned in calendar time and procurement-risk alerts fire before stock-out. Bridge scope toward Phase 3 IMMS.

### Story 8.1: Add Garage Object Storage and Backend Integration

As an implementer,
I want Garage (S3-compatible object storage) running in local infrastructure with typed backend integration,
So that sparepart images have a durable, maintained home and PostgreSQL only stores object references.

**Acceptance Criteria:**

**Given** local infrastructure is managed through `infra/docker-compose.yml`
**When** the stack is started
**Then** a `garage` service runs alongside existing stable-name services
**And** backend binds Garage endpoint, credentials, and bucket through typed properties/environment without hardcoded URLs
**And** an S3-compatible client bean can upload an object and generate a short-TTL presigned GET URL
**And** uploaded objects survive service restart
**And** no image bytes are persisted in PostgreSQL

### Story 8.2: Manage Sparepart Material Code and Lead Time

As a SUPER_ADMIN or MANAGE user with job scope LEADER or above,
I want to record an optional globally unique material code and optional procurement lead time on a sparepart,
So that identical spareparts across machines aggregate for inventory recap and procurement planning has timing input.

**Acceptance Criteria:**

**Given** a sparepart exists
**When** an authorized user sets material code or lead time
**Then** values persist and appear on sparepart list/detail
**And** material code uniqueness is enforced globally at database level and returns standard validation error on duplicates
**And** installation and lifetime flows succeed without material code present
**And** lead time accepts fractional durations (for example 36 hours or 7.5 days)
**And** users below LEADER job scope receive server-side denial with required-role explanation
**And** both mutations write immutable audit entries

### Story 8.3: Manage Estimated Price Entries with Currency and Kurs

As a SUPER_ADMIN or MANAGE user with job scope LEADER or above,
I want to append estimated price entries per sparepart with currency and kurs evidence,
So that cost history remains auditable as prices and exchange rates change.

**Acceptance Criteria:**

**Given** a sparepart exists
**When** a price entry is created
**Then** entry stores decimal amount, ISO-4217 currency defaulting to IDR, entered-by, entered-at timestamp
**And** non-IDR currency requires a kurs-to-IDR snapshot value before save succeeds
**And** normalized IDR amount is derived by the backend as `amount × kursToIdr` (kurs 1 for IDR)
**And** entries are append-only; history renders in PriceHistoryTable with original currency, kurs, IDR value, and timestamp
**And** an unchanged-price flow copies the previous entry values into a new entry
**And** mutations require LEADER job scope server-side and are audit logged

### Story 8.4: Manage Sparepart Image via Garage

As a SUPER_ADMIN or MANAGE user with job scope LEADER or above,
I want to upload, replace, and remove one global image per sparepart,
So that identical spareparts share a single visual reference across machines.

**Acceptance Criteria:**

**Given** Garage integration exists from Story 8.1
**When** an authorized user uploads an image within size limits
**Then** the file is stored in Garage and only the object key/reference persists in PostgreSQL
**And** the UI previews the image via backend-generated presigned URL that expires
**And** replacing removes the previous object so orphans do not accumulate
**And** removing the image clears the reference and deletes the object
**And** users below LEADER job scope are denied server-side and mutations are audit logged

### Story 8.5: Configure Shift Schedule with Machine Override

As a SUPER_ADMIN or MANAGE user with job scope LEADER or above,
I want to define up to three daily shifts per machine group and optionally override them per machine,
So that effective operating calendars reflect how each machine actually runs.

**Acceptance Criteria:**

**Given** machine group and machines exist
**When** shift config is set on a machine group
**Then** it stores up to three shift windows with start/end local wall-clock times
**And** windows crossing midnight are accepted and validated (end after start modulo midnight)
**And** a machine may store its own override which takes precedence over its group config
**And** clearing the machine override falls back to the group config
**And** read responses expose the resolved source (`MACHINE` or `MACHINE_GROUP`)
**And** Machine Hub shows InheritedConfigBadge stating group inheritance when applicable
**And** ShiftConfigEditor enforces max three shifts and cross-midnight validation client-side while backend validation remains authoritative
**And** mutations require LEADER job scope server-side and are audit logged

### Story 8.6: Estimate Counter Rate and Shift-Aware Projections

As an operator,
I want to see counter rate estimates and calendar-time depletion projections for installed spareparts,
So that I know roughly when each sparepart must be replaced in days, not just in counters.

**Acceptance Criteria:**

**Given** accepted telemetry history and a resolved shift configuration exist
**When** projections are requested for an installed sparepart
**Then** backend computes counter rate as rolling 30-day moving average of counting delta per operating hour using OperatingCalendarCalculator
**And** fewer than 30 days of data falls back to full-history average transparently with window evidence shown
**And** insufficient data yields an explicit unavailable state rather than a guessed value
**And** projection shows estimated time to depletion and expected counter consumption during the configured lead-time window when lead time exists
**And** computed rates are cached in Redis with explicit TTL and invalidated on relevant writes
**And** CounterRateProjectionCard displays rate, freshness/window timestamps, projections, and insufficient-data state
**And** calculation is backend-owned with controlled-clock unit test coverage

### Story 8.7: Raise Procurement-Risk Alert Within Lead-Time Window

As a maintenance planner,
I want a distinct alert when projected depletion occurs inside the procurement lead-time window,
So that ordering starts before stock-out rather than only at the percentage threshold.

**Acceptance Criteria:**

**Given** an installed sparepart has lead time and sufficient counter-rate data
**When** evaluation finds projected depletion within the lead-time window
**Then** backend creates a PROCUREMENT_RISK alert with distinct type/reason evidence including rate, projection basis, and lead time used
**And** duplicate prevention applies per installation and alert type without interfering with existing percentage-threshold alerts
**And** alert list/detail render the procurement-risk type label and reason distinctly
**And** missing lead time or insufficient data produces no alert and no error
**And** creation is audit logged with traceId and role-denial paths return standard errors
**And** pilot seed optionally extends with material code, lead time, and IDR price example for validation


## Final Validation Results

### FR Coverage Validation

All functional requirements FR-001 through FR-065 are covered by stories:

- FR-001 through FR-005: Epic 1.
- FR-006 through FR-025: Epic 2.
- FR-026 through FR-039: Epic 3.
- FR-040 through FR-046 and FR-053: Epic 4.
- FR-047 through FR-052 and FR-054 through FR-056: Epic 5.
- FR-057 through FR-065: Epic 6.
- Success metrics and pilot scenario: Epic 7.

### New FR Coverage (Procurement Readiness 2026-08-23)

- FR-078, FR-079: Epic 8 — Story 8.2
- FR-080, FR-081: Epic 8 — Story 8.3
- FR-082: Epic 8 — Stories 8.1 and 8.4
- FR-083, FR-084: Epic 8 — Story 8.5
- FR-085, FR-086: Epic 8 — Story 8.6
- FR-087: Epic 8 — Story 8.7
- FR-088, FR-089: Epic 8 — cross-cutting across all mutation stories

### Architecture Implementation Validation

Starter and architecture setup are covered:

- Story 1.1 initializes monorepo and version baseline.
- Story 1.2 starts local infrastructure with PostgreSQL, pgAdmin, Redis, InfluxDB, EMQX, and WAHA.
- Story 1.3 initializes Spring Boot backend.
- Story 1.4 initializes Next.js admin frontend shell.
- Story 1.5 records and implements auth mode baseline.

Database/entity creation follows story need:

- Epic 1 creates only foundation/auth/migration needs.
- Epic 2 creates master/setup entities as those capabilities appear.
- Epic 3 adds telemetry storage/integration needs.
- Epic 4 adds alert lifecycle needs.
- Epic 5 adds notification/template/job needs.
- Epic 6 adds health status needs.
- Epic 7 adds seed, fixtures, scripts, and docs only.

### Story Quality Validation

Stories are ready for development:

- Each story has user value and clear acceptance criteria.
- Each story is sized for one dev agent session.
- Acceptance criteria use Given/When/Then format with testable outcomes.
- Boundary and negative cases are included where relevant.
- Role/access behavior is included where relevant.
- UX requirements are embedded into value stories rather than isolated as a component-only epic.
- Every story should produce a demonstrable result: backend test, UI smoke path, integration check, or pilot evidence.

### Epic Structure Validation

Epic structure is user-value oriented:

- Epic 1: run/access platform.
- Epic 2: configure machine and sparepart foundation.
- Epic 3: receive and view telemetry.
- Epic 4: turn telemetry into actionable alert.
- Epic 5: notify and escalate through WAHA.
- Epic 6: diagnose platform health.
- Epic 7: prove Phase 1 end-to-end.

File overlap is intentional and value-driven. Shared frontend/backend modules evolve across user-value flows, but each epic has a distinct outcome and feedback boundary.

### Dependency Validation

Dependency flow is valid:

- Epic 1 is standalone foundation.
- Epic 2 works without telemetry by configuring required master data.
- Epic 3 uses Epic 1 and Epic 2 outputs to accept and display telemetry, without needing alerts.
- Epic 4 uses telemetry and setup data to create/handle alerts, without needing WAHA.
- Epic 5 uses alerts and responsibility to send/escalate notifications.
- Epic 6 uses existing dependency/worker states for diagnostics.
- Epic 7 intentionally depends on Epic 1 through Epic 6 as final proof.

Within each epic, stories build only on prior stories or prior epics. No story requires a future story inside the same epic to function.

### Final Readiness

Status: READY FOR DEVELOPMENT

Recommended implementation priority:

1. Complete Epic 1.
2. Complete Epic 2 for pilot data needs.
3. Complete Epic 3 telemetry path.
4. Complete Epic 4 threshold alert path.
5. Complete Epic 5 WAHA notification and escalation path.
6. Complete Epic 6 health diagnostics.
7. Complete Epic 7 pilot proof.

Vertical slice target:

```text
local stack
-> login
-> pilot setup
-> JBF19 MQTT telemetry
-> latest telemetry visible
-> 90% threshold alert
-> TECHNICIAN WAHA job
-> mobile acknowledge
-> escalation stopped
-> health/pilot evidence visible
```

## Backend baseline update

- Story 1.3 backend baseline is revised from Java 21/Spring Boot 3.3.5 to Java 25/Spring Boot 3.5.14.
- Validation: `mvn -q test` passes from `syncro/apps/backend` with OpenJDK 25.0.3.

## Research Reconciliation Stories (2026-05-25)

The following stories address gaps identified during full reconciliation of domain and technical research documents against the PRD, UX spec, and architecture.

### Story 1.7: Implement Plant-Scoped Data Access

As a MANAGE or VIEWER user,
I want to see only telemetry, alerts, and operational data for plants I am assigned to,
So that multi-plant deployments maintain data privacy between plants.

**Acceptance Criteria:**

**Given** user has plant assignments stored in their profile
**When** user accesses Operations Overview, Telemetry Dashboard, Alerts, or Machine lists
**Then** only data from assigned plants is returned by backend APIs
**And** SUPER_ADMIN sees all plants without restriction
**And** frontend shows `PlantScopeSelector` in header for multi-plant users
**And** direct link to out-of-scope resource returns permission-denied response
**And** user with no plant assignment sees empty state with admin contact message

**FRs covered:** FR-077
**NFRs covered:** —
**UX-DRs covered:** UX-DR-035

### Story 2.9: Implement Immutable Audit Log for Master Data

As a SUPER_ADMIN,
I want to review who changed what master data, when, and what the previous value was,
So that operational accountability and compliance readiness are maintained from Phase 1.

**Acceptance Criteria:**

**Given** any master data mutation occurs (plant, machine, machine group, sparepart, installed sparepart, responsibility, threshold)
**When** the change is persisted
**Then** an immutable audit log entry is created with: actor, action, entity type, entity ID, previous value, new value, and timestamp
**And** audit log is accessible via `/api/v1/audit-log` with filters for entity type, actor, plant, and date range
**And** frontend `AuditLogTable` shows entries with expandable before/after detail
**And** audit entries cannot be edited or deleted
**And** desktop uses dense table; mobile uses stacked cards grouped by date

**FRs covered:** FR-076
**UX-DRs covered:** UX-DR-034, UX-DR-037

### Story 3.9: Enforce Telemetry Payload Contract with Schema Version, MessageId, and Timestamp

As a system,
I want to require `schemaVersion`, `messageId`, and `timestamp` in every telemetry payload,
So that payload evolution, deduplication, and time-series precision are supported from day one.

**Acceptance Criteria:**

**Given** backend receives MQTT telemetry message
**When** payload is validated
**Then** `schemaVersion`, `messageId`, and `timestamp` (UTC ISO-8601) are required fields
**And** missing any of these fields results in rejection with specific reason
**And** unrecognized `schemaVersion` values are quarantined (not silently dropped)
**And** `messageId` is used for deduplication: duplicate `messageId` does not create duplicate alerts or notifications
**And** `timestamp` is normalized to UTC before InfluxDB write; if absent, server receipt time is used and record is flagged

**FRs covered:** FR-031a, FR-031b, FR-031c, FR-035a, FR-041a
**NFRs covered:** NFR-010a

### Story 3.10: Validate Payload-Topic Machine Identity Match

As a system,
I want to reject telemetry where the payload machine identity does not match the MQTT topic,
So that spoofed or misconfigured device data cannot corrupt machine telemetry.

**Acceptance Criteria:**

**Given** MQTT message arrives on topic `factory/{plantCode}/{machineCode}/telemetry`
**When** payload body contains machine identity fields
**Then** backend validates payload machine identity matches topic-extracted identity
**And** mismatches are rejected and quarantined with reason "payload-topic identity mismatch"
**And** rejection includes correlationId for tracing

**FRs covered:** FR-029a

### Story 3.11: Implement Telemetry Quarantine Store and Data Quality Validation

As a SUPER_ADMIN,
I want rejected telemetry messages to be persisted in a quarantine store with visibility in the health dashboard,
So that debugging device issues and firmware problems is possible without silent data loss.

**Acceptance Criteria:**

**Given** telemetry is rejected for any reason (invalid payload, inactive machine, unrecognized schema, range violation, identity mismatch)
**When** rejection occurs
**Then** rejected message is persisted in `telemetry_quarantine` table with: received timestamp, MQTT topic, raw payload, rejection reason, schema version, and correlationId
**And** physically implausible values (negative runtime, counter decrease outside wrap-around) are rejected with specific reason
**And** health dashboard shows quarantined message count and most recent entries via `DataQualityPanel`
**And** `QuarantineLogTable` shows entries with expandable full payload detail
**And** correlationId is copyable for cross-system tracing
**And** quarantine table supports retention/cleanup policy configuration

**FRs covered:** FR-035b, FR-035c, FR-073a, FR-073b
**UX-DRs covered:** UX-DR-031, UX-DR-032, UX-DR-038

### Story 3.12: Implement Backpressure and Bounded Queue Behavior

As a system,
I want telemetry processing to degrade into bounded backlog rather than crash or silently discard messages,
So that the platform survives burst traffic without data loss or process failure.

**Acceptance Criteria:**

**Given** telemetry processing cannot keep pace with incoming MQTT messages
**When** internal queue reaches configured capacity
**Then** system delays MQTT acknowledgment (backpressure to broker) rather than discarding messages
**And** queue depth, processing lag, and dead-letter volume are observable in health dashboard
**And** system does not crash or grow memory unboundedly
**And** when capacity recovers, normal processing resumes without manual intervention

**NFRs covered:** NFR-006a

### Story 3.13: Configure MQTT Security (TLS, Device Auth, ACLs)

As a platform operator,
I want EMQX to authenticate devices and enforce topic ACLs,
So that only authorized machines can publish telemetry to their own topics.

**Acceptance Criteria:**

**Given** EMQX is configured in `infra/emqx/etc/`
**When** a device connects to EMQX
**Then** device must authenticate using credentials or certificates
**And** each device is authorized to publish only to its own `factory/{plantCode}/{machineCode}/telemetry` topic
**And** deny-by-default ACL policy applies (unauthorized topic publish is rejected)
**And** production configuration requires TLS; development may use plaintext
**And** backend MQTT consumer uses separate credentials from device credentials
**And** ACL configuration is documented in infra docs

**NFRs covered:** NFR-009a, NFR-009b

### Story 5.7: Implement WAHA Rate Limiting

As a system,
I want WhatsApp notification sends to be rate-limited,
So that notification storms do not cause WAHA account blocking or recipient fatigue.

**Acceptance Criteria:**

**Given** multiple alerts fire simultaneously or in rapid succession
**When** notification jobs are processed
**Then** Redis-backed rate-limit keys prevent repeated sends for the same alert within configurable deduplication window
**And** rate-limited notifications show `rate-limited` state in `EscalationTimeline` with next available send window
**And** rate limiting does not permanently suppress notifications — they are sent after the window expires
**And** rate-limit behavior is observable in health/notification metrics

**FRs covered:** FR-061a
**UX-DRs covered:** UX-DR-036

### Story 5.8: Implement Circuit Breaker for WAHA Calls

As a system,
I want WAHA HTTP calls to use timeout, retry, and circuit-breaker patterns,
So that WAHA unavailability does not block notification workers or cascade into alert delivery failures.

**Acceptance Criteria:**

**Given** WAHA service is slow or unavailable
**When** notification worker attempts to send
**Then** configurable timeout prevents indefinite blocking
**And** retry with exponential backoff is applied for transient failures
**And** circuit breaker opens after configured failure threshold, preventing further calls until half-open probe succeeds
**And** circuit breaker state (open/half-open/closed) is observable in health dashboard
**And** failed attempts are recorded with error detail in notification attempt history

**NFRs covered:** NFR-011a

### Story 6.7: Implement Data Quality Panel and Latency Indicator

As a SUPER_ADMIN,
I want the health dashboard to show telemetry data quality metrics and end-to-end latency,
So that I can detect data quality degradation and processing delays before they impact operations.

**Acceptance Criteria:**

**Given** SUPER_ADMIN opens System Health
**When** health dashboard loads
**Then** `DataQualityPanel` shows: quarantined message count, rejection rate %, anomaly count, dead-letter count, and time window
**And** `LatencyIndicator` shows current end-to-end latency (MQTT publish to dashboard visible) with status: normal (<5s), elevated (5-15s), critical (>15s)
**And** elevated/critical states are visually distinct
**And** `DataQualityPanel` links to `QuarantineLogTable` for detail drill-down

**FRs covered:** FR-073a, FR-073b
**NFRs covered:** —
**UX-DRs covered:** UX-DR-031, UX-DR-033
**Success Metrics covered:** SM-008, SM-009


---

## Phase 2 Requirements Inventory (Maintenance Execution & OPA) — 2026-08-24

### Functional Requirements (FR-100 .. FR-181)

**Org Structure & Sections**

FR-100: Manage sections (MACHINERY|UTILITY|WORKSHOP) per plant; deactivation guarded; audit-logged.
FR-101: Assign each machine group to exactly one section.
FR-102: Derive section leadership from machine responsibilities level LEADER (or above) on a machine group.
FR-103: Scope section leaders to their own machine groups only (no sibling-group/section visibility).
FR-104: Monitor sparepart lifetime within derived scope.
FR-105: Manage expiry-dated cross-plant teams; membership grants scoped extra access.

**Workorder**

FR-110: Create internal workorders (INTERNAL, WO-YYMMxxxx, safe under transaction+lock); PRODUCTION_LEADER may create breakdown for own line.
FR-111: Import synced workorders (SYNCED, id = external sheet_no; idempotent upsert).
FR-112: Manage WO categories (code+label, e.g. 01 Breakdown, 02 Preventive); leader+ can add.
FR-113: Assign and delegate workorders; section leader cannot execute their own.
FR-114: Transition workorder status (DRAFT->OPEN->ASSIGNED->IN_PROGRESS->ON_PROCUREMENT->IN_PROGRESS->DONE->CLOSED, +CANCELLED); invalid -> INVALID_STATE_TRANSITION.
FR-115: Record multi repair sessions; cumulative MTTR.
FR-116: Upload evidence before/after + technical drawings (JPEG/PNG/WebP/PDF <=10MB) to Garage.
FR-117: Optional CP/CPK values + PDF on any category report.
FR-118: Tag workorders with FMEA failure type.
FR-119: Manage workorder todos / kanban view.
FR-120: Parent cannot CLOSED while children not CLOSED/CANCELLED; override by SUPER_ADMIN/MANAGER with note.
FR-121: Rate technicians (by section leader) with configurable dimensions 1-5 stars.
FR-122: Capture stop-time reason on breakdown workorders.
FR-123: Record SLA response time per category.
FR-124: Rate maintenance workorder (by PRODUCTION_LEADER), configurable dimensions 1-5 stars.

**Preventive**

FR-130: Define preventive programs per machine (mechanical/electrical; MONTHLY/ANNUAL).
FR-131: Generate and track schedules (calendar/shift based, floating interval).
FR-132: Complete checklist with assessment; leader approves with signature.
FR-133: Print preventive report (WYSIWYG tabular, logo, signature).
FR-134: Auto-create preventive workorders from due schedules.

**Sparepart Request & Inventory**

FR-140: Create requests (SPAREPART/CONSUMABLE/SERVICE_EXTERNAL) with qty, purchase URL, optional new-item.
FR-141: Manage request state machine (REQUESTED->ACKED->PROCESSING->[READY|PURCHASE_REQUESTED->PART_RECEIVED->READY]->PICKED_UP->CLOSED); timeline events + audit.
FR-142: Approve with separation of duty (approver != requester); threshold by estimated cost (qty x est price).
FR-143: Record purchase reference URL.
FR-144: Complete new-item requests (PENDING_COMPLETION) with material code, image, est price.
FR-145: Record manual MRE code (no auto-generation).
FR-146: Track stock by material code per plant with OP/OQ reorder warning.
FR-147: Escalate requests per configurable escalation_configs; WAHA per step.

**Sync Module (hardened)**

FR-150: Scheduled sync from external PostgreSQL (typed config, watermark, lock, ordered by sheet_no, batch transaction).
FR-151: Upsert idempotently by external id; duplicates to quarantine.
FR-152: Resolve conflicts: external master wins, local operational preserved; deterministic + audited.
FR-153: Quarantine failed records; observability on health dashboard.
FR-154: Normalize Asia/Jakarta -> UTC; notification hygiene (dedupe, no storm).

**OPA Authorization & Allowed Actions**

FR-160: Enforce OPA default-deny on every authorization-sensitive request; decision_id correlated to audit.
FR-161: Provide allowed-actions endpoint for frontend rendering; frontend never calls OPA directly.
FR-162: Deploy policy as code with CI (opa test); versioned bundles; decision-log masking + retention.
FR-163: Derive operational scope from org data (roles, plants, sections, machine groups, active teams) passed as input.
FR-164: Audit policy decisions; AUDITOR can trace allow/deny with policy revision + decision_id.

**Dashboards & Reports**

FR-170: Machine dashboard (status, telemetry freshness, open WO, alerts, lifetime) within scope.
FR-171: Workorder dashboard (counts by status/category) within scope.
FR-172: Preventive dashboard (due/overdue) within scope.
FR-173: MTBF (woStopAt-ordered) / MTTR (cumulative sessions) dashboards; units/window/freshness defined.
FR-174: Technician KPI dashboard (configurable ratings + objective KPIs).
FR-175: WYSIWYG print workorder & preventive reports (tabular, logo, signature block).

**WAHA Notifications**

FR-180: Notify workorder lifecycle events to section leaders+ and inventory roles with phone.
FR-181: Send 4-hour acknowledgment request with auto-login link bound to registered WA number; task list (ack/rating status).

### NonFunctional Requirements (Phase 2 additions)

NFR-P2-1: OPA deployed as sidecar HTTP; default-deny; degraded-mode allowlist for health/read endpoints.
NFR-P2-2: Backend owns MTBF/MTTR/state-machine/stock calculations; frontend renders only.
NFR-P2-3: All new schema via Flyway V41+; ddl-auto=validate; additive migrations.
NFR-P2-4: Scope dims exactly {plantIds, machineGroupIds, activeTeamIds}; single org scope service for OPA input AND SQL filter.
NFR-P2-5: Separation of duty (approver != requester) enforced server-side before OPA.
NFR-P2-6: Stock mutations use optimistic lock + atomic conditional update; no negative stock.
NFR-P2-7: Decision logs masked (no WAHA secrets/phones); retention 30 days configurable.
NFR-P2-8: Sync module writes workorders only via maintenance WorkorderImportService (module boundary).
NFR-P2-9: Terminal states (DONE/CLOSED) protected from sync regression (TERMINAL_STATE_PROTECTED quarantine).
NFR-P2-10: Frontend uses TanStack Table v9 for tabular main views.

### Additional Requirements (Architecture)

- Role taxonomy migration: PRD ten roles via additive V41 CHECK; MANAGE->MANAGER_MAINTENANCE, VIEWER->read-only; OPA subject.roles = application role + derived scope (AD-15).
- OPA input assembly via single authz.PolicyDecisionPoint service (Addendum A3 authoritative).
- Workorder id VARCHAR(50) UTF-8; internal WO-YYMM-XXXXX; idempotencyKey on creation.
- notification_jobs polymorphic target (target_type/target_id nullable; alert_id nullable; dedupe key target_type+target_id+template+recipient).
- Sync field classification via sync_field_mappings config (MASTER vs OPERATIONAL).
- Parent-child close uses SELECT FOR UPDATE on children; sync child upsert rejects when parent CLOSED.
- Garage for evidence; material code reused for stock OP/OQ.

---

## Phase 2 Epic List (approved 2026-08-24)

### Epic 9: Org Structure & OPA Authorization Foundation

Users can configure org structure (sections, teams) and every access decision is enforced by OPA, establishing the authorization backbone for all maintenance features.

**FRs covered:** FR-100, FR-101, FR-102, FR-103, FR-104, FR-105, FR-160, FR-161, FR-162, FR-163, FR-164

### Epic 10: Workorder Execution

Maintenance leaders and technicians can run breakdown/preventive workorders end-to-end: create, assign, delegate, status lifecycle (with ON_PROCUREMENT), multi repair sessions, evidence upload, parent-child chains, technician & maintenance ratings.

**FRs covered:** FR-110, FR-111, FR-112, FR-113, FR-114, FR-115, FR-116, FR-117, FR-118, FR-119, FR-120, FR-121, FR-122, FR-123, FR-124

### Epic 11: Preventive Maintenance

Leaders and technicians can run monthly/annual preventive programs: define programs, generate schedules, complete checklists with assessment, signature approval, and WYSIWYG report printing.

**FRs covered:** FR-130, FR-131, FR-132, FR-133, FR-134

### Epic 12: Sparepart Request & Inventory

Leaders request parts; storekeeper/inventory manage the request state machine, stock OP/OQ by material code, MRE recording, and escalation with WAHA notifications.

**FRs covered:** FR-140, FR-141, FR-142, FR-143, FR-144, FR-145, FR-146, FR-147

### Epic 13: External Sync & Hardening

Synced workorders from the internal system arrive reliably and safely: transactional batches, watermark resume, quarantine, conflict resolution, and notification hygiene.

**FRs covered:** FR-150, FR-151, FR-152, FR-153, FR-154

### Epic 14: Dashboards, Reports & Notifications

Leaders see machine/workorder/preventive dashboards, MTBF/MTTR, technician KPI; print WYSIWYG reports with logo & signature; receive WAHA notifications per lifecycle step and the 4-hour ack with auto-login link.

**FRs covered:** FR-170, FR-171, FR-172, FR-173, FR-174, FR-175, FR-180, FR-181

## Phase 2 FR Coverage Map

FR-100: Epic 9 - Manage sections
FR-101: Epic 9 - Assign machine groups to sections
FR-102: Epic 9 - Derive section leadership from responsibilities
FR-103: Epic 9 - Scope leaders to own machine groups
FR-104: Epic 9 - Monitor lifetime within scope
FR-105: Epic 9 - Cross-plant teams
FR-110: Epic 10 - Create internal workorders
FR-111: Epic 10 - Import synced workorders
FR-112: Epic 10 - Manage WO categories
FR-113: Epic 10 - Assign/delegate workorders
FR-114: Epic 10 - Transition WO status
FR-115: Epic 10 - Multi repair sessions
FR-116: Epic 10 - Evidence upload
FR-117: Epic 10 - Optional CP/CPK
FR-118: Epic 10 - FMEA tag
FR-119: Epic 10 - Todos/kanban
FR-120: Epic 10 - Parent-child close rule
FR-121: Epic 10 - Rate technicians
FR-122: Epic 10 - Stop-time reason
FR-123: Epic 10 - SLA response time
FR-124: Epic 10 - Rate maintenance WO
FR-130: Epic 11 - Preventive programs
FR-131: Epic 11 - Schedules
FR-132: Epic 11 - Checklist/assessment
FR-133: Epic 11 - Print preventive report
FR-134: Epic 11 - Auto-create preventive WO
FR-140: Epic 12 - Create requests
FR-141: Epic 12 - Request state machine
FR-142: Epic 12 - Approval SoD
FR-143: Epic 12 - Purchase URL
FR-144: Epic 12 - New-item completion
FR-145: Epic 12 - MRE manual
FR-146: Epic 12 - Stock OP/OQ
FR-147: Epic 12 - Escalation + WAHA
FR-150: Epic 13 - Scheduled sync
FR-151: Epic 13 - Idempotent upsert
FR-152: Epic 13 - Conflict resolution
FR-153: Epic 13 - Quarantine + observability
FR-154: Epic 13 - Timezone + notification hygiene
FR-160: Epic 9 - OPA enforcement
FR-161: Epic 9 - Allowed-actions endpoint
FR-162: Epic 9 - Policy as code CI
FR-163: Epic 9 - Derive scope from org data
FR-164: Epic 9 - Audit decisions
FR-170: Epic 14 - Machine dashboard
FR-171: Epic 14 - Workorder dashboard
FR-172: Epic 14 - Preventive dashboard
FR-173: Epic 14 - MTBF/MTTR dashboards
FR-174: Epic 14 - Technician KPI
FR-175: Epic 14 - WYSIWYG print reports
FR-180: Epic 14 - Notify lifecycle events
FR-181: Epic 14 - 4-hour ack auto-login

---

## Epic 9: Org Structure & OPA Authorization Foundation

Users can configure org structure (sections, teams) and every access decision is enforced by OPA, establishing the authorization backbone for all maintenance features.

### Story 9.1: Sections Foundation

As a SUPER_ADMIN or MANAGER_MAINTENANCE,
I want to define sections (MACHINERY/UTILITY/WORKSHOP) per plant and assign each machine group to one section,
So that operational scope can be derived from the org structure instead of manual role assignment.

**Acceptance Criteria:**

**Given** a plant exists with machine groups
**When** I create a section and assign machine groups to it
**Then** sections are persisted in PostgreSQL via migration V41+
**And** a machine group belongs to exactly one section; assigning to another section is rejected with a validation error
**And** deactivating a section with active machine groups is rejected with a machine-readable code
**And** section mutations are audit-logged with actor and traceId

**Given** a user holds responsibility level LEADER (or above) on a machine group
**When** their operational scope is derived
**Then** they become a section leader for that machine group without any additional role assignment
**And** demoting/removing the responsibility immediately removes the scope server-side
**And** list/detail endpoints for workorders and sparepart lifetime filter rows by derived scope (machineGroupId), excluding sibling-group data within the same section

**Given** (2026-08-27 org consolidation) a user opens Master Data → Organization
**When** they need plant master data
**Then** Plants is a tab inside Organization (tab value `plants`, renders PlantManagement)
**And** the legacy `/master-data/plants` and `/master-data/setup` routes redirect to `/master-data/organization?tab=plants`
**And** the sidebar shows no standalone Plants sub-item

**FRs covered:** FR-100, FR-101, FR-102, FR-103, FR-104
**NFRs covered:** NFR-P2-3, NFR-P2-4

### Story 9.2: Cross-Plant Teams

As a SUPER_ADMIN or MANAGER_MAINTENANCE,
I want to create expiry-dated cross-plant teams and add members,
So that shared repair work across plants is possible without permanent access leaks.

**Acceptance Criteria:**

**Given** a cross-plant team with an expiry date and members
**When** the team is active
**Then** members gain scoped access to the specified machines/workorders across plants
**And** the scope is merged into the machineGroupIds set used for SQL filtering (AD-2, AD-13)

**Given** a cross-plant team has expired
**When** a member requests access to the previously shared resource
**Then** access is denied server-side (OPA input excludes the expired team)
**And** no policy redeploy is required to revoke the extra scope

**Given** a SUPER_ADMIN or MANAGER_MAINTENANCE creates/updates/deletes a team
**When** the mutation is submitted
**Then** team mutations are audit-logged with actor, action, target, and traceId

**FRs covered:** FR-105
**NFRs covered:** NFR-P2-4

### Story 9.3: OPA Infrastructure

As an implementer,
I want OPA deployed as a sidecar and a PolicyDecisionPoint service in the backend,
So that every authorization decision is evaluated by policy rather than scattered service checks.

**Acceptance Criteria:**

**Given** the local infra stack is started
**Then** an `opa` service runs in docker-compose (v1.19.1) with a stable name
**And** OPA input assembly is centralized in a single `authz.PolicyDecisionPoint` service (RestClient + Resilience4j timeout/retry/circuit-breaker)
**And** the input schema (subject/resource/action/context) follows Addendum A3

**Given** a request reaches an authorization-sensitive endpoint
**When** the interceptor builds OPA input and calls `POST /v1/data/syncro/authz/allow`
**Then** a denied decision returns the standard permission-denied error shape before any business logic runs
**And** a failing OPA call (sidecar down) defaults to deny except a configurable degraded-mode allowlist for health/read endpoints

**Given** a decision is returned
**When** the request proceeds
**Then** the `decision_id` is stored alongside the audit record for correlation (FR-164)

**Given** the frontend needs to render menus/buttons
**When** it calls `/api/v1/authz/allowed-actions`
**Then** the endpoint returns the allowed-actions set from OPA, and the frontend never calls OPA directly (NFR-013a)

**FRs covered:** FR-160, FR-161, FR-164
**NFRs covered:** NFR-P2-1, NFR-P2-7

### Story 9.4: Role Taxonomy Migration

As an implementer,
I want the PRD role taxonomy to replace the Phase 1 application-role model via additive migration,
So that maintenance roles (MANAGER_MAINTENANCE, SECTION_LEADER, INVENTORY_MAINTENANCE, etc.) are enforceable without breaking existing users.

**Acceptance Criteria:**

**Given** the Phase 1 `auth_users.application_role` CHECK constraint allows only SUPER_ADMIN/MANAGE/VIEWER
**When** migration V41+ runs
**Then** the CHECK is extended to the PRD roles; existing SUPER_ADMIN rows remain valid
**And** MANAGE maps to MANAGER_MAINTENANCE; VIEWER maps to a read-only role; mapping is documented
**And** the migration is additive (no existing data dropped)
**And** the mapping is explicit that MANAGE (a capability) does NOT imply MANAGER_MAINTENANCE global scope — MANAGER_MAINTENANCE still requires plant assignments, so no user is silently promoted to global access (scope is enforced per AD-2 via plant assignments, never inferred from the role name)

**Given** OPA input is assembled for a user
**When** their roles are resolved
**Then** `subject.roles` = the application role (extended enum) plus the derived scope (plantIds/machineGroupIds/activeTeamIds) per AD-2/AD-15
**And** PRODUCTION_LEADER scope is derived from plant/line assignments; SUPER_ADMIN bypasses checks (Phase 1 pattern)

**Given** a Phase 1 service switches on ApplicationRole
**When** the module is touched
**Then** in-service checks are migrated to the new role names without changing semantics

**FRs covered:** FR-160, FR-163
**NFRs covered:** NFR-P2-3
**Additional:** role taxonomy migration (AD-15)

### Story 9.5: OPA Enforcement on Maintenance Endpoints

As an implementer,
I want every authorization-sensitive maintenance/org/sync endpoint to be enforced by OPA,
So that server-side authorization is uniform and audit traceable.

**Acceptance Criteria:**

**Given** an authorization-sensitive endpoint in maintenance/org/sync
**When** the request is processed
**Then** OPA is evaluated (default-deny) before business logic, using the single PolicyDecisionPoint
**And** row-level scoping is applied in queries using the derived scope set identical to the OPA input (AD-2)
**And** out-of-scope resource access returns a permission-denied code

**Given** a decision is logged
**When** an AUDITOR reviews it
**Then** they can trace which policy allowed/denied the action, with policy revision and decision_id (FR-164)
**And** decision logs mask sensitive fields (no WAHA secrets, no full phone numbers) and retain 30 days configurable

**FRs covered:** FR-160, FR-162, FR-164
**NFRs covered:** NFR-P2-7

---

## Epic 10: Workorder Execution

Maintenance leaders and technicians can run breakdown/preventive workorders end-to-end: create, assign, delegate, status lifecycle (with ON_PROCUREMENT), multi repair sessions, evidence upload, parent-child chains, technician & maintenance ratings.

### Story 10.1: Workorder Schema & Categories

As a maintenance team member,
I want workorders stored with a dual-source ID scheme and configurable categories,
So that synced and internal workorders coexist without collision and categories reflect the maintenance workflow.

**Acceptance Criteria:**

**Given** migration V41+ creates the workorder schema
**Then** `work_orders` has id VARCHAR(50) UTF-8, source ENUM (SYNCED/INTERNAL), parent_id self-FK, sync_version, and lifecycle fields
**And** `work_order_categories` stores code+label (e.g. 01 Breakdown, 02 Preventive) with unique codes
**And** `work_order_status_history` records every transition with actor, from/to, source (MANUAL/DERIVED/SYNC), and traceId

**Given** a section leader (or above) creates/updates a WO category
**When** the mutation is submitted
**Then** the category is persisted and audit-logged
**And** users below section leader receive a server-side permission-denied error

**Given** an internal workorder ID is generated
**When** it is created
**Then** the format is WO-YYMM-XXXXX (5-digit, monthly reset) generated under transaction + row lock
**And** concurrent creation does not produce duplicate IDs

**FRs covered:** FR-110, FR-111, FR-112
**NFRs covered:** NFR-P2-3
**Additional:** workorder id VARCHAR(50), parent-child chains (AD-3)

### Story 10.2: Create & Assign Workorders

As a section leader or staff maintenance,
I want to create internal workorders and delegate them to technicians,
So that breakdowns and planned work have an owner without the leader executing the repair themselves.

**Acceptance Criteria:**

**Given** an authorized user creates an internal workorder
**When** the POST `/api/v1/workorders` request is submitted
**Then** a WO-YYMM-XXXXX id is returned with source INTERNAL
**And** an optional idempotencyKey header dedupes double-submission within a 5-minute window (frontend sends a UUID)
**And** creating a child workorder requires access to the parent section scope

**Given** a PRODUCTION_LEADER creates a workorder
**When** the request is submitted
**Then** only breakdown category is allowed and only for machines on their own production lines (server-side, FR-110)

**Given** a section leader assigns a workorder to a technician
**When** the assignment is submitted
**Then** the workorder transitions OPEN → ASSIGNED
**And** the system rejects assigning the section leader as executing technician of their own workorders with a machine-readable code (FR-113)

**FRs covered:** FR-110, FR-113
**NFRs covered:** NFR-P2-3
**Additional:** idempotencyKey on creation (AD-3)

### Story 10.3: Status Lifecycle & ON_PROCUREMENT

As a maintenance team,
I want workorders to follow an explicit status lifecycle with ON_PROCUREMENT derived from sparepart requests,
So that waiting-for-part time is tracked correctly and invalid transitions are prevented.

**Acceptance Criteria:**

**Given** a workorder exists
**When** a status transition is requested
**Then** the transition must be valid per `DRAFT → OPEN → ASSIGNED → IN_PROGRESS → ON_PROCUREMENT → IN_PROGRESS → DONE → CLOSED` (+ CANCELLED from OPEN/ASSIGNED)
**And** invalid transitions return `INVALID_STATE_TRANSITION`

**Given** a sparepart request on the workorder becomes non-READY
**When** the request state changes
**Then** the workorder enters ON_PROCUREMENT and a status-history row is written with source=DERIVED, actor=SYSTEM
**And** it resumes IN_PROGRESS when all requests are READY (recomputed on transition events, not a poller)
**And** the recompute is guarded by a lock (row/advisory) so concurrent request transitions cannot produce conflicting derived transitions or duplicate history rows
**And** manual leader placement to ON_PROCUREMENT is allowed only when no live request exists

**Given** a parent workorder has children
**When** a leader tries to CLOSE the parent
**Then** closing is blocked (machine-readable code) while any child is not CLOSED/CANCELLED
**And** SUPER_ADMIN/MANAGER may override with an audit-logged reason
**And** closing a parent takes `SELECT ... FOR UPDATE` on child statuses within the same transaction (AD-3)

**FRs covered:** FR-114, FR-120
**NFRs covered:** NFR-P2-9
**Additional:** AD-4, AD-5

### Story 10.4: Repair Sessions & MTTR

As a technician,
I want to log multiple repair sessions on a workorder,
So that interrupted repairs (e.g. waiting for parts) accumulate correct working time and MTTR.

**Acceptance Criteria:**

**Given** a technician is assigned a workorder
**When** they start and stop a repair session
**Then** the session (start/end, description, technicians) is persisted
**And** a workorder may have multiple sessions without overlap
**And** cumulative session duration equals the workorder MTTR (backend-computed, FR-115)

**Given** a workorder is being closed as DONE
**When** it has no completed session
**Then** closing is blocked unless a documented reason is provided

**Given** a category defines a target response time
**When** the first IN_PROGRESS session starts
**Then** response time (OPEN → first session start) is recorded and surfaced on dashboards (FR-123)

**FRs covered:** FR-115, FR-123
**NFRs covered:** NFR-P2-2
**Additional:** AD-6

### Story 10.5: Evidence & Technical Drawings

As a technician or leader,
I want to upload before/after photos and technical drawings to a workorder,
So that repair evidence is durable and auditable.

**Acceptance Criteria:**

**Given** an authorized user uploads a file to a workorder
**When** the upload is submitted
**Then** accepted types are JPEG/PNG/WebP/PDF with a 10 MB configurable limit
**And** the object is stored in Garage via ObjectStorageService; PostgreSQL stores only the object key
**And** replacing/deleting an attachment removes the previous object

**Given** an out-of-scope or over-limit upload
**When** it is submitted
**Then** a standard validation/permission error is returned; nothing is persisted

**FRs covered:** FR-116
**NFRs covered:** NFR-P2-2
**Additional:** AD-10 (reuse SparepartImageService pattern)

### Story 10.6: Reports, CP/CPK, FMEA & Stop-Time

As a section leader or staff maintenance,
I want to complete workorder reports with optional CP/CPK, FMEA tagging, and stop-time reasons,
So that the closed workorder carries full evidence and future MTBF/FMEA analysis is possible.

**Acceptance Criteria:**

**Given** a workorder report is written
**Then** the narrative (chronological/analyze/corrective/preventive) is persisted
**And** any category may record optional CP/CPK values and an optional CP/CPK PDF; never mandatory (FR-117)
**And** a workorder can close without CP/CPK

**Given** a user tags a workorder with an FMEA failure type
**When** the tag is submitted
**Then** it is stored and appears in report and machine history (FR-118)

**Given** a breakdown workorder is being completed
**When** no stop-time reason code is provided
**Then** DONE is blocked; the reason (electric/mechanical/pneumatic/hydraulic/etc.) is required (FR-122)

**FRs covered:** FR-117, FR-118, FR-122
**NFRs covered:** NFR-P2-2

### Story 10.7: Todos & Kanban

As a section leader,
I want to create workorder todos and see work in a kanban view,
So that tasks within a repair are tracked and the team sees what is pending.

**Acceptance Criteria:**

**Given** a section leader creates todos on a workorder (own group)
**When** todos are assigned to technicians
**Then** todo create/assign/complete is scoped to the workorder group and audit-logged
**And** a kanban view shows workorders/todos grouped by backend-provided status
**And** kanban respects derived scope and backend statuses (no client-side state invention)

**FRs covered:** FR-119
**NFRs covered:** NFR-P2-10 (TanStack Table tabular)

### Story 10.8: Ratings

As a section leader and production leader,
I want to rate technicians and maintenance workorders with configurable dimensions,
So that KPI dashboards reflect team performance and maintenance quality.

**Acceptance Criteria:**

**Given** a workorder is closed
**When** its section leader rates the executing technicians
**Then** ratings use configurable dimensions rendered as 1–5 stars; dimensions are data configured by SUPER_ADMIN (AD-14)
**And** only the workorder group's section leader can rate; ratings are immutable after submission (FR-121)

**Given** a maintenance workorder is closed
**When** the PRODUCTION_LEADER of the affected line rates it
**Then** the rating is bound to the workorder (not a technician) with configurable dimensions 1–5 stars (FR-124)
**And** one rating per workorder; immutable; feeds the maintenance-quality view

**FRs covered:** FR-121, FR-124
**NFRs covered:** NFR-P2-2

### Story 10.9: Workorder List Table, Month Picker & Quick Actions (IMPLEMENTED 2026-08-27)

As a maintenance leader or technician,
I want a server-paginated workorder table as the default Work Orders tab with a month quick picker, category/status/machine/search filters, and per-row quick actions,
So that daily triage of workorders happens in one list without calendar date clicking or opening the kanban.

> Status note: this story was implemented directly by user request on 2026-08-27 (commit 86b5b97). It is recorded here as the authoritative contract so future planning does not revert it.

**Acceptance Criteria:**

**Given** the user opens Work Orders
**When** the page loads
**Then** the Table tab is the default (kanban/ratings/categories remain secondary tabs)
**And** the table is server-paginated (TanStack Table, page size 20) from `GET /api/v1/workorders`

**Given** the user filters by period
**When** they use the month control
**Then** the control is a month quick picker with prev/next arrows and a month-year label — no calendar date clicking, no 7d/30d/90d/This-month presets
**And** the selected month maps to `from`=start-of-month and `to`=end-of-month UTC bounds sent to the list endpoint

**Given** the user filters the list
**When** they select status, category, machine, or type a search term
**Then** status/category/machine are non-native shadcn Selects; search is 300ms-debounced
**And** category options come from `GET /api/v1/work-order-categories` (master data), rendered `code · label`
**And** the list endpoint accepts `categoryCode` (added to controller, service and both scoped JPQL queries)

**Given** a row is visible
**When** the user uses the Actions column
**Then** each row offers quick actions that open dialogs without leaving the table: Request part (reuses the sparepart-request dialog) and Report (GET/PUT `/{id}/report` with the four narrative fields)
**And** actions never render raw UUIDs

**FRs covered:** FR-110, FR-112, FR-171
**NFRs covered:** NFR-P2-10 (TanStack Table)

### Story 10.10: Work Order Category Master Data UI (IMPLEMENTED 2026-08-27)

As a section leader or above,
I want a Categories tab on Work Orders to view and create work-order categories,
So that category reference data is manageable in the UI, not only via API.

> Status note: implemented 2026-08-27 (commit 86b5b97). Backend CRUD (`GET/POST /api/v1/work-order-categories`, `PUT /{code}`) predates this story (FR-112, Story 10.1). The UI lists code/label/target-response and creates categories with a dialog; only the four leader+ roles may mutate (gate mirrors OPA `category_mutation_paths`).

**FRs covered:** FR-112
**NFRs covered:** NFR-P2-10

---

## Epic 11: Preventive Maintenance

Leaders and technicians can run monthly/annual preventive programs: define programs, generate schedules, complete checklists with assessment, signature approval, and WYSIWYG report printing.

### Story 11.1: Preventive Programs & Schedules

As a staff maintenance or leader,
I want to define preventive programs per machine and generate monthly/annual schedules,
So that routine maintenance runs on the calendar without requiring telemetry.

**Acceptance Criteria:**

**Given** an authorized user creates a preventive program for a machine
**When** the program is submitted
**Then** it stores category (mechanical/electrical) and schedule type limited to MONTHLY or ANNUAL (FR-130)
**And** the program is scoped to the machine's section/group
**And** (2026-08-27) preventive categories are surfaced as a read-only Categories tab on the Preventive page (PreventiveCategoryManagement); the backend enum MECHANICAL/ELECTRICAL remains the source of truth — convert to a CRUD master table only when preventive categories need to be configurable

**Given** a program is active
**When** schedules are due
**Then** schedules are generated on the calendar/shift basis (using shift config V40) and work without telemetry (FR-131)
**And** the next due date rolls forward from completion (floating interval)
**And** a calendar view lists due/overdue items computed from the server clock
**And** due/overdue items surface on the preventive dashboard (FR-172)

**FRs covered:** FR-130, FR-131
**NFRs covered:** NFR-P2-3
**Additional:** AD-12 (calendar/shift-based, floating interval)

### Story 11.2: Checklist, Assessment & Signature

As a technician and leader,
I want to complete preventive checklists with a leader assessment and signature approval,
So that performed checks are evidenced and approved.

**Acceptance Criteria:**

**Given** a due preventive schedule exists
**When** a technician/staff completes the checklist
**Then** the result is persisted with performed-by, timestamp, notes, and evidence (FR-132)
**And** checklist items with assessment values (including LSL/USL bounds where applicable) are stored

**Given** a leader assesses the result
**When** they approve
**Then** approval is a leader action with signature capture (image + signer identity + timestamp), audit-logged
**And** the schedule is marked performed and the next due date rolls forward

**FRs covered:** FR-132
**NFRs covered:** NFR-P2-3
**Additional:** signature mechanism pending OQ-5

### Story 11.3: Preventive Report & Auto-Workorder

As a leader,
I want to print preventive reports via WYSIWYG and have due schedules generate preventive workorders,
So that reports are presentable and preventive tasks enter the workorder flow.

**Acceptance Criteria:**

**Given** a completed preventive schedule
**When** the report is printed
**Then** the WYSIWYG report renders checklist, results, logo, and signature block (FR-133)
**And** print works for workorder reports and preventive reports (tabular view)

**Given** a due schedule is configured to generate workorders
**When** the schedule period arrives
**Then** an internal preventive workorder (category 02 Preventive) is created, linked back to the schedule (FR-134)
**And** duplicate generation is prevented per schedule period (idempotent)

**FRs covered:** FR-133, FR-134
**NFRs covered:** NFR-P2-10
**Additional:** AD-10, AD-12

---

## Epic 12: Sparepart Request & Inventory

Leaders request parts; storekeeper/inventory manage the request state machine, stock OP/OQ by material code, MRE recording, and escalation with WAHA notifications.

### Story 12.1: Request Creation & Types

As a leader/staff/technician with workorder scope,
I want to create sparepart requests of different types with a purchase reference URL and optional new-item details,
So that needed parts are requested precisely even when the material code is unknown.

**Acceptance Criteria:**

**Given** an authorized user creates a request on a workorder
**When** the request is submitted
**Then** type rules are enforced: SERVICE_EXTERNAL requires a workorder; CONSUMABLE does not require a machine; SPAREPART uses electric/mechanic taxonomy (FR-140)
**And** a purchase reference URL (http/https) may be stored and rendered for the storekeeper (FR-143)
**And** a new-item request without a material code starts in PENDING_COMPLETION (FR-144)
**And** request creation is audit-logged

**FRs covered:** FR-140, FR-143, FR-144
**NFRs covered:** NFR-P2-3

> **2026-08-28 update:** The request-part dialog was upgraded with BOM search
> (`useListSpareparts`), a local draft cart (add/remove items without submitting),
> and a single "Send to warehouse" submission. A server-paginated list page
> (`GET /api/v1/sparepart-requests`) was added as the Sparepart Requests route,
> replacing the earlier placeholder. Backend scope filter mirrors the workorder
> list pattern (plant/machine-group). All changes in commit `f6ac419`.

### Story 12.2: Request State Machine

As inventory/storekeeper and leaders,
I want requests to flow through an explicit state machine with a recorded timeline,
So that the procurement and pickup status of every part is known and auditable.

**Acceptance Criteria:**

**Given** a request exists
**When** a transition is requested
**Then** valid transitions are `REQUESTED → ACKED → PROCESSING → [READY | PURCHASE_REQUESTED → PART_RECEIVED → READY] → PICKED_UP → CLOSED`
**And** invalid transitions return `INVALID_STATE_TRANSITION`; each transition records a timeline event + audit (FR-141)

**Given** a request is not READY on a workorder
**When** its state changes
**Then** the workorder enters ON_PROCUREMENT (derived, AD-5) and resumes when all requests are READY

**Given** INVENTORY_MAINTENANCE records an MRE code
**When** a request is in PURCHASE_REQUESTED
**Then** the MRE code is manual (no auto-generation), format-free (e.g. MRE26023xxxx), and stored on the timeline (FR-145)

**Given** the workorder section leader picks up / closes
**When** the request is READY
**Then** PICKED_UP and CLOSED are performed by the workorder section leader (FR-141)

**FRs covered:** FR-141, FR-145
**NFRs covered:** NFR-P2-3
**Additional:** AD-4, AD-5

### Story 12.3: Approval, Separation of Duty & Escalation

As a maintenance organization,
I want request approvals with separation of duty and configurable escalation,
So that no one approves their own request and overdue requests escalate with WhatsApp notifications.

**Acceptance Criteria:**

**Given** a request requires approval
**When** the requester submits their own request for approval
**Then** requester != approver is enforced server-side before OPA/state machine (FR-142, AD-16)
**And** threshold tiering by estimated cost (qty × est. price) is computed in the application layer
**And** when est. price is absent, the request requires section-leader approval regardless of quantity
**And** threshold tiers are configurable via escalation_configs (v1 defaults ≤5M / 5M–50M / >50M IDR)

**Given** a request is unacknowledged or overdue
**When** the escalation worker runs
**Then** it escalates per configurable escalation_configs durations (not hardcoded) (FR-147)
**And** WAHA notifications fire per step (request part → escalation) with idempotency (dedupe key target_type+target_id+template+recipient) and rate-limit/circuit-breaker (Epic 5 patterns)

**FRs covered:** FR-142, FR-147
**NFRs covered:** NFR-P2-5
**Additional:** AD-9, AD-16

### Story 12.4: Stock OP/OQ & New-Item Completion

As inventory/storekeeper,
I want to manage stock by material code with order-point/order-quantity and complete new-item requests,
So that reorder warnings fire at the right time and unknown parts get properly registered.

**Acceptance Criteria:**

**Given** a sparepart has a material code
**When** stock is recorded per plant
**Then** `sparepart_stock(material_code, plant_id, stock_on_hand, order_point, order_qty)` is unique per (material_code, plant_id) (FR-146)
**And** a reorder warning fires when stock_on_hand <= order_point, recommending a purchase request of order_qty (business-rule signal; the PR action is OPA-authorized)
**And** stock mutations use version optimistic lock + atomic conditional update; negative stock is rejected server-side (AD-11)

**Given** INVENTORY_MAINTENANCE/STOREKEEPER completes a PENDING_COMPLETION request
**When** they provide material code, image, and estimated price
**Then** the material code links to the existing sparepart/material-code identity (global uniqueness enforced); duplicate rejected (FR-144)
**And** the sparepart record is created/updated via the masterdata module application service (not a direct cross-module write)
**And** completion is audit-logged

**FRs covered:** FR-144, FR-146
**NFRs covered:** NFR-P2-6
**Additional:** AD-11

---

## Epic 13: External Sync & Hardening

Synced workorders from the internal system arrive reliably and safely: transactional batches, watermark resume, quarantine, conflict resolution, and notification hygiene.

### Story 13.1: Sync Pipeline Foundation

As an implementer,
I want a hardened scheduled sync job that imports workorders from the internal PostgreSQL,
So that synced workorders arrive reliably without duplicate workorders or lost batches.

**Acceptance Criteria:**

**Given** the external datasource is configured via typed config (SyncProperties)
**When** the scheduled job runs
**Then** it processes rows in batches inside a transaction, ordered by sheet_no ASC (FR-150)
**And** upserts are idempotent by external id (re-sync updates, never duplicates) (FR-151)
**And** processing resumes from a watermark (last_synced_sheet_no) after restart
**And** a distributed lock (Redis/DB advisory) prevents concurrent runs
**And** the sync module writes workorders only via maintenance.workorder.application.WorkorderImportService (AD-7) — never via JPA directly
**And** retry with backoff on external DB outage; no crash

**FRs covered:** FR-150, FR-151
**NFRs covered:** NFR-P2-8
**Additional:** AD-7

### Story 13.2: Conflict Resolution & Field Mapping

As an implementer,
I want deterministic conflict resolution between external master fields and local operational fields,
So that local evidence is never overwritten and terminal workorders are never regressed.

**Acceptance Criteria:**

**Given** a synced workorder is upserted
**When** external master fields (status, timestamps, machine, category) arrive
**Then** master fields take external values; local operational fields (report, evidence, ratings) are preserved (FR-152)
**And** the field classification is configuration via sync_field_mappings (MASTER vs OPERATIONAL); unmapped fields default to MASTER (AD-8)

**Given** the external system sends a regressed status for a DONE/CLOSED workorder
**When** the sync processes it
**Then** the row is quarantined with TERMINAL_STATE_PROTECTED; the workorder is not reopened (NFR-P2-9)
**And** external status does not override a locally-derived ON_PROCUREMENT state
**And** sync upsert of a child workorder rejects (quarantine) when the parent is CLOSED

**Given** a batch completes
**When** its results are recorded
**Then** resolution is deterministic and audit-logged per run (FR-152)

**FRs covered:** FR-152
**NFRs covered:** NFR-P2-9
**Additional:** AD-8

### Story 13.3: Quarantine & Observability

As a SUPER_ADMIN,
I want failed sync records quarantined and sync run status observable,
So that sync failures are diagnosable without silent data loss.

**Acceptance Criteria:**

**Given** a row fails mapping or conflicts terminally
**When** the sync processes it
**Then** it is persisted in sync_quarantine with reason, raw payload, and traceId (FR-153)
**And** sync_runs records status, counts (created/updated/failed), timestamps, and error detail
**And** the health dashboard shows last run, counts, and last run timestamp (FR-153)

**Given** external timestamps are processed
**When** they are persisted
**Then** Asia/Jakarta timestamps are normalized to UTC (FR-154)

**Given** new workorders or important transitions are synced
**When** notifications are sent
**Then** they are deduplicated and limited (no notification storm); the sync module does not notify independently (AD-7/AD-8)

**FRs covered:** FR-153, FR-154
**NFRs covered:** NFR-P2-8
**Additional:** AD-7, AD-8

---

## Epic 14: Dashboards, Reports & Notifications

Leaders see machine/workorder/preventive dashboards, MTBF/MTTR, technician KPI; print WYSIWYG reports with logo & signature; receive WAHA notifications per lifecycle step and the 4-hour ack with auto-login link.

### Story 14.1: Machine, Workorder & Preventive Dashboards

As a maintenance leader,
I want dashboards for machines, workorders, and preventive schedules within my scope,
So that I can see what needs attention at a glance.

**Acceptance Criteria:**

**Given** an authorized user opens the machine dashboard
**Then** it shows machines with status, telemetry freshness (when available), open workorders, alerts, and lifetime risk within scope (FR-170)
**And** it respects derived scope (plant/machineGroup/team) and renders loading/empty/error/stale/forbidden states (UX-DR-019)

**Given** a user opens the workorder dashboard
**Then** it shows workorder counts by status/category within scope, backend-computed (FR-171)
**And** filtering by plant/section/status/category is available

**Given** a user opens the preventive dashboard
**Then** it shows due/overdue preventive schedules within scope from the server clock (FR-172)
**And** overdue items are visibly distinct without color-only status

**FRs covered:** FR-170, FR-171, FR-172
**NFRs covered:** NFR-P2-2, NFR-P2-10 (TanStack Table)

### Story 14.2: MTBF/MTTR & Technician KPI Dashboards

As a maintenance leader,
I want MTBF/MTTR and technician KPI dashboards,
So that reliability and team performance are measurable.

**Acceptance Criteria:**

**Given** breakdown workorder data exists
**When** MTBF/MTTR is computed
**Then** MTBF is ordered by woStopAt (not id) between consecutive breakdown workorders; MTTR = cumulative session durations (FR-173, AD-6)
**And** units (hours), window (monthly rolling), and freshness (30-min Redis TTL with stale indicator) are documented
**And** insufficient-data state is explicit when fewer than 2 breakdown workorders exist

**Given** a leader opens the technician KPI dashboard
**Then** it shows configurable ratings (1–5 stars) and objective KPIs (completed count, average MTTR, on-time %) per technician (FR-174)
**And** leader sees own-group technicians; manager sees plant; manager-global sees all

**FRs covered:** FR-173, FR-174
**NFRs covered:** NFR-P2-2
**Additional:** AD-6

### Story 14.3: WYSIWYG Print Reports with Signature

As a leader or SPV,
I want to print workorder and preventive reports in WYSIWYG format with logo and signature,
So that reports are presentable and approvals are evidenced.

**Acceptance Criteria:**

**Given** a completed workorder or preventive report
**When** it is printed
**Then** the WYSIWYG report renders tabular view with configurable logo (FR-175)
**And** it includes WO fields, sessions, parts, report narrative, CP/CPK (if present), evidence references
**And** a signature block captures leader/SPV input (image + signer identity + timestamp) for approval and close
**And** signature renders correctly in print output (AD-10, pending OQ-5 mechanism)

**FRs covered:** FR-175
**NFRs covered:** NFR-P2-10
**Additional:** AD-10, OQ-5

### Story 14.4: WAHA Notifications & 4-Hour Acknowledgment

As a maintenance and production leader,
I want WhatsApp notifications for workorder lifecycle events and a 4-hour acknowledgment with auto-login,
So that the right people are reached in time without notification fatigue.

**Acceptance Criteria:**

**Given** a workorder lifecycle event occurs (new breakdown, ON_PROCUREMENT, part READY, DONE/CLOSED)
**When** notification routing runs
**Then** WAHA messages are sent to section leaders and above plus inventory roles with phone numbers (FR-180)
**And** messages use templates; each event is idempotent (dedupe key target_type+target_id+template+recipient) (AD-9)
**And** rate limit + circuit breaker apply (Epic 5 patterns)

**Given** a workorder has been in IN_PROGRESS (net of ON_PROCUREMENT) for more than 4 hours (configurable)
**When** the escalation worker runs
**Then** a WAHA message with a direct link is sent to the PRODUCTION_LEADER (FR-181)
**And** the link carries a short-lived token (bound to the recipient's registered WA number) that auto-authenticates the leader; the token expires after first use or after a configurable TTL (default 15 minutes), whichever comes first — revoke on expiry, mismatch, or login reuse
**And** if the token is expired or the phone number mismatch, the fallback is a normal login (no residual access via the stale link)
**And** the landing task list shows acknowledged vs pending acks and rated vs unrated closed workorders
**And** acknowledging records the action in the timeline + audit and stops further escalation
**And** the 4-hour clock excludes ON_PROCUREMENT time (AD-5, computed from status-history rows)

**FRs covered:** FR-180, FR-181
**NFRs covered:** NFR-P2-7
**Additional:** AD-5, AD-9

