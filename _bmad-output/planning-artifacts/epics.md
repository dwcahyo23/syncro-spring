---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-Syncro-2026-05-22/prd.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/planning-artifacts/ux-design-specification.md
  - _bmad-output/planning-artifacts/prds/prd-Syncro-2026-05-22/review-prd.md
  - _bmad-output/planning-artifacts/research/domain-manufacturing-machinery-maintenance-research-2026-05-25.md
  - _bmad-output/planning-artifacts/research/technical-next-js-spring-boot-openjdk25-maven-mqtt-emqx-redis-postgresql-influxdb-research-2026-05-25.md
revisedAt: 2026-05-25
revisionReason: Research reconciliation — new FRs, NFRs, UX components, and stories added
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
AR-009: Use REST JSON API under `/api/v1` and generate/maintain OpenAPI contracts.
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

### Story 2.4: Manage Sparepart Taxonomy

As a SUPER_ADMIN or MANAGE user,
I want to manage sparepart category, brand, kind, and type taxonomy,
So that spareparts are normalized and searchable.

**Acceptance Criteria:**

**Given** user has permitted role
**When** user creates taxonomy entries for category, brand, kind, or type
**Then** entries are persisted and available for sparepart creation
**And** duplicate taxonomy values within same dimension are rejected
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
**And** UI explains `MANAGE` app role is different from `MANAGER` job scope where relevant

### Story 2.8: Show Setup Completeness

As a SUPER_ADMIN or MANAGE user,
I want to see whether core setup is complete,
So that I know if Syncro is ready to receive telemetry and alerts for a machine.

**Acceptance Criteria:**

**Given** user opens setup or machine context page
**When** setup data is partially or fully configured
**Then** checklist shows plant, machine group, machine, sparepart, installation, and responsibility completion states
**And** incomplete items show clear next action
**And** checklist uses non-color-only status communication
**And** checklist supports loading, empty, error, read-only, and forbidden states
**And** setup completeness does not infer machine active state from telemetry

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

