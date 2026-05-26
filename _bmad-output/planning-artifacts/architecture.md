---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-Syncro-2026-05-22/prd.md
  - _bmad-output/planning-artifacts/ux-design-specification.md
  - _bmad-output/planning-artifacts/research/domain-manufacturing-machinery-maintenance-research-2026-05-25.md
  - _bmad-output/planning-artifacts/research/technical-next-js-spring-boot-openjdk25-maven-mqtt-emqx-redis-postgresql-influxdb-research-2026-05-25.md
workflowType: architecture
lastStep: 8
status: complete
completedAt: 2026-05-22
revisedAt: 2026-05-25
revisionReason: Research reconciliation — domain + technical research gaps integrated into PRD and UX spec
project_name: Syncro
user_name: Yusuf
date: 2026-05-22
---

# Architecture Decision Document

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Project Context Analysis

### Requirements Overview

**Functional Requirements:**

The PRD defines 77 functional requirements (expanded from 65 after research reconciliation) across these architectural areas:

- Authentication and role-based access: login, `SUPER_ADMIN`, `MANAGE`, `VIEWER`, menu/API guards.
- Master data: plants, plant-scoped machine groups/process lines, machines, sparepart taxonomy, spareparts.
- Machine sparepart installation: expected production-count lifetime, baseline counter, threshold percentage.
- Machine responsibility: machine-specific responsibility chain with job scopes `TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, `MANAGER`.
- MQTT telemetry ingest: topic validation, active-machine validation, payload validation, accepted telemetry persistence, latest-state cache.
- Telemetry dashboard: latest values, configured fields, freshness display.
- Alert lifecycle: `OPEN`, `ACKNOWLEDGED`, `RESOLVED`, duplicate prevention, history.
- WAHA escalation: queued WhatsApp notifications, staged escalation, delivery status, WYSIWYG text template.
- System health: PostgreSQL, InfluxDB, Redis, MQTT, WAHA, ingest worker, notification worker, latest telemetry.

Architecturally, this is not a CRUD-only system. It requires a connected event flow: registered active machine → validated MQTT telemetry → InfluxDB history + Redis latest state → sparepart lifetime calculation → alert lifecycle → notification queue → WAHA delivery → acknowledgement stops escalation.

**Non-Functional Requirements:**

NFRs that shape architecture:

- Clear datastore ownership: PostgreSQL for master/auth/config/alerts, InfluxDB for telemetry history, Redis for latest telemetry/cache, queue for notifications.
- Redis TTL enforcement and cache-only policy; never source of truth.
- Notification dispatch fully decoupled from telemetry ingest path.
- External MQTT input validation before downstream writes, including schema version, payload-topic identity match, and physically plausible range checks.
- MQTT security: TLS in production, device authentication, deny-by-default topic ACLs.
- Correlation ID across all telemetry processing stages for end-to-end traceability.
- Circuit breaker and retry with backoff for external service calls (WAHA, InfluxDB).
- Backpressure: bounded queue with delayed MQTT ack rather than silent discard or crash.
- Notification status history; no fire-and-forget WAHA. Rate limiting to prevent storms.
- Duplicate active alert prevention.
- Health visibility for telemetry and notification path failures, data quality metrics, quarantine visibility.
- OpenTelemetry-compatible structured logging.
- Frontend shall not access databases directly; all through Spring Boot APIs.
- Plant-scoped data access for non-SUPER_ADMIN users.
- Responsive UI using shadcn/ui boilerplate.
- Future compatibility with CMMS, IMMS, ABAC, reporting, KPI, and IATF.

**UX Architectural Implications:**

The UX spec adds these technical requirements:

- Responsive web app for desktop, tablet, and mobile.
- Desktop dense tables and split/detail layouts.
- Mobile alert acknowledgement with sticky primary action.
- Machine Detail as central operational context.
- Domain components: `StatusBadge`, `TelemetryCard`, `LifetimeProgress`, `AlertActionPanel`, `EscalationTimeline`, `HealthCard`, `SetupCompletenessChecklist`, `WahaTemplateEditor`, `DataQualityPanel`, `QuarantineLogTable`, `LatencyIndicator`, `AuditLogTable`, `PlantScopeSelector`, `MachineSummaryCard`, `AuditEventRow`.
- WCAG AA baseline.
- Stale/loading/error/read-only states for domain components.
- No offline support.
- Latest telemetry visible first; heavy charts lazy-loaded or avoided on mobile.
- Frontend trust requires backend-provided timestamps, status reasons, calculation evidence, and action permissions.
- Plant scope selector for multi-plant users; SUPER_ADMIN sees all.
- Audit log screen for master data change history.
- Quarantine log accessible from health dashboard.

### Scale & Complexity

- Primary domain: full-stack industrial maintenance platform foundation.
- Complexity level: medium-high internal system.
- Estimated architectural components:
  - Spring Boot API backend.
  - Next.js web frontend.
  - PostgreSQL relational database.
  - InfluxDB time-series database.
  - Redis cache/latest-state store.
  - MQTT broker integration.
  - Telemetry ingest worker/service.
  - Alert evaluation service.
  - Notification queue/worker.
  - WAHA integration.
  - Health monitoring module.

Complexity drivers:

- Real-time-ish telemetry latest-state UI.
- Multi-datastore consistency.
- MQTT device/topic validation.
- 16-bit unsigned counter wrap-around.
- Staged notification escalation.
- Alert dedupe/state lifecycle.
- Future ABAC/job-scope expansion.
- Audit/evidence needs for later KPI/IATF phases.

### Technical Constraints & Dependencies

- Backend target: Spring Boot.
- Frontend target: Next.js + shadcn/ui admin dashboard boilerplate.
- PostgreSQL required for master data, auth, config, alerts, templates, responsibility, and audit history.
- InfluxDB required for telemetry history.
- Redis required for latest telemetry and cache.
- MQTT required for machine telemetry input using `factory/{plantCode}/{machineCode}/telemetry`.
- WAHA required for WhatsApp notification delivery.
- System is online-only.
- Default validation pilot: plant `GM1`, machine group `Forming`, machine `BF-08410` / `JBF19`, sparepart `Electric PLC Wecon LX5`.

### Cross-Cutting Concerns Identified

- Authentication and application role authorization.
- Plant-scoped data access enforcement.
- Future job-scope/ABAC compatibility.
- Input validation and trust boundaries for MQTT (TLS, auth, ACLs, schema version, payload-topic match, range checks).
- Data ownership boundaries across PostgreSQL, InfluxDB, Redis, and queue.
- Redis TTL enforcement and cache-only policy.
- Alert state machine and deduplication.
- Notification reliability, retry, circuit breaker, rate limiting, status tracking, and escalation stopping.
- Notification decoupled from telemetry ingest path.
- Correlation ID / trace ID across all processing stages.
- OpenTelemetry-compatible structured logging.
- Telemetry quarantine store for rejected messages.
- Backpressure behavior with bounded queue.
- Timestamped evidence/audit trail with immutable audit log.
- Health diagnostics across dependencies, workers, data quality, and quarantine.
- Frontend security boundary (no direct database access).
- Responsive UI consistency across desktop, tablet, and mobile.
- Accessibility and non-color-only operational status.
- Future CMMS/IMMS/KPI/IATF extensibility.

### Architectural Implications

- Prefer a modular monolith backend with internal workers for Phase 1. The system already has multiple external dependencies; early microservice splitting would add avoidable failure modes.
- Model explicit domain state transitions or internal domain events for telemetry accepted, threshold crossed, alert created, alert acknowledged, alert resolved, notification queued, notification delivered, and notification failed.
- Enforce idempotency for MQTT ingest, alert creation, and notification enqueueing.
- Define queue retry behavior for WAHA failures and preserve delivery attempts.
- Use PostgreSQL migrations from the start.
- Validate DTOs at every external boundary: web API requests, MQTT payloads, and WAHA integration responses.
- Preserve operational evidence from Phase 1: actor, timestamp, source, action, target, result.
- Make the pilot scenario testable end-to-end: JBF19 live telemetry appears, sparepart threshold reaches 90%, alert opens, WAHA escalation sends, acknowledgement stops escalation.

## Starter Template Evaluation

### Primary Technology Domain

Syncro is a full-stack industrial web application with a Spring Boot backend, Next.js web frontend, and infrastructure services for PostgreSQL, InfluxDB, Redis, MQTT, and WAHA.

The architecture uses a monorepo with separate backend, web, and infrastructure areas rather than one full-stack JavaScript starter.

### Starter Options Considered

#### Backend: Spring Boot Initializr

Spring Boot Initializr should be used for the backend foundation because the PRD already selects Spring Boot and requires PostgreSQL migrations, API validation, MQTT integration, Redis, worker processes, and health checks.

Recommended backend starter setup:

- Java 25 LTS.
- Spring Boot current stable from Spring Initializr at project creation time.
- Maven by default unless the implementation team explicitly prefers Gradle Kotlin DSL.
- Dependencies:
  - Spring Web
  - Spring Validation
  - Spring Security
  - Spring Data JPA
  - PostgreSQL Driver
  - Flyway Migration
  - Spring Data Redis
  - Spring Boot Actuator
  - Spring Integration MQTT or Eclipse Paho MQTT client
  - Spring Scheduling
  - Testcontainers
- Avoid Lombok by default unless the team explicitly chooses it.
- Defer MapStruct or other mapping libraries until boilerplate mapping becomes a real implementation pain.
- Spring Boot DevTools may be added for local development only.
- Add Springdoc OpenAPI before Epic 2 data APIs so backend contracts can generate frontend types and query clients.
- Add Resilience4j before WAHA/InfluxDB external-call workflows so timeout, retry, and circuit-breaker behavior stays consistent instead of becoming scattered manual retry code.

#### Frontend: arhamkhnz/next-shadcn-admin-dashboard

The selected UX foundation is `arhamkhnz/next-shadcn-admin-dashboard`. It provides the best fit because Syncro needs dashboard shell, sidebar navigation, forms, tables, theme presets, responsive behavior, and shadcn/ui conventions.

Included stack from repo:

- Next.js 16.
- TypeScript.
- Tailwind CSS v4.
- shadcn/ui.
- Zod.
- React Hook Form.
- Zustand.
- TanStack Table.
- Biome.
- Husky.
- npm.

Caveats:

- RBAC/multi-tenant support is planned, so Syncro must implement its own role/menu/API guard behavior.
- If the frontend starter is cloned into the monorepo, remove nested `.git` metadata or copy the files instead of preserving a nested repository.
- Preserve the boilerplate theme and responsive shell; adapt it gradually rather than stripping it early.

#### Alternative: create-next-app

`create-next-app` is good for a clean Next.js base, but weaker than the selected admin dashboard boilerplate for Syncro because it lacks ready admin shell, tables, dashboard layouts, theme presets, and form/table conventions.

### Selected Starter

Use a composed monorepo foundation:

```text
syncro/
  apps/
    backend/     Spring Boot app from Spring Initializr
    web/         arhamkhnz next-shadcn-admin-dashboard adapted for Syncro
  infra/
    docker-compose.yml
  docs/
  .env.example
```

Infrastructure should be explicit and selected in later architecture decisions rather than hidden inside a starter.

### Initialization Commands

Backend:

```bash
# Use start.spring.io UI or API with current stable Spring Boot at implementation time.
# Suggested settings:
# Java 25, Maven, Spring Web, Validation, Security, Data JPA,
# PostgreSQL, Flyway, Data Redis, Actuator, Testcontainers.
```

Frontend:

```bash
git clone https://github.com/arhamkhnz/next-shadcn-admin-dashboard.git apps/web
cd apps/web
npm install
npm run dev
```

If cloning inside the monorepo, remove nested `.git` metadata after copying starter files.

Infrastructure should be created explicitly:

```text
infra/docker-compose.yml
- PostgreSQL
- Redis
- InfluxDB
- MQTT broker
- WAHA
```

### Architectural Decisions Provided by Starter

**Language & Runtime**

- Backend: Java 25 + Spring Boot.
- Frontend: TypeScript + Next.js 16.

**Styling Solution**

- Tailwind CSS v4 + shadcn/ui + boilerplate theme presets.

**Build Tooling**

- Backend: Maven by default.
- Frontend: npm scripts from boilerplate.

**Testing Framework**

- Backend: Spring Boot Test + Testcontainers.
- Frontend: boilerplate tooling baseline; add component/e2e test decision later if needed.

**Code Organization**

- Monorepo separates backend, web, and infra.
- Backend should be a modular monolith by domain modules.
- Frontend should keep boilerplate route/module conventions.
- Module names should support future phases: maintenance, inventory, reports, KPI, compliance.

**Development Experience**

- Backend local dev profile via Spring Boot tooling.
- Frontend dev server via `npm run dev`.
- Local dependencies through Docker Compose.

**First Implementation Story**

Project initialization should be the first implementation story and must verify that backend, frontend, and local infrastructure services can boot together.

## Core Architectural Decisions

### Decision Priority Analysis

**Critical Decisions (Block Implementation):**

- Monorepo structure: `apps/backend`, `apps/web`, `infra`.
- Backend architecture: Spring Boot modular monolith with internal workers.
- Frontend architecture: Next.js admin dashboard boilerplate adapted for Syncro.
- Datastore ownership: PostgreSQL, InfluxDB, Redis, queue.
- API pattern: REST JSON under `/api/v1`.
- Auth pattern: session/JWT-based Spring Security, with future Keycloak option deferred.
- Telemetry ingest: isolated MQTT ingest application service/worker.
- Notification execution: PostgreSQL-backed outbox/job worker with retry/status history.
- Local infrastructure: Docker Compose.

**Important Decisions (Shape Architecture):**

- PostgreSQL supported current major/minor, using Flyway.
- InfluxDB 3 Core for time-series telemetry history, with v2 line protocol compatibility as fallback if Java client support creates friction.
- Redis for latest telemetry and cache.
- MQTT broker choice deferred to infra decision, but protocol contract fixed.
- Queue implementation: start with PostgreSQL-backed outbox/job table for Phase 1; upgrade to dedicated broker later if needed.
- Frontend state: TanStack Query for client-side server state starting Epic 2 data APIs; Zustand only for local UI/session shell state.
- API docs: Springdoc OpenAPI generated from Spring backend.
- Frontend API client generation: Orval generates TypeScript client functions and TanStack Query hooks from the backend OpenAPI contract starting Epic 2.
- Audit/event table exists from Phase 1 for setup changes, alert actions, notification attempts, and significant health changes.

**Deferred Decisions (Post-MVP):**

- Full ABAC engine.
- Dedicated message broker like RabbitMQ/Kafka.
- Keycloak/SSO.
- Advanced telemetry charting.
- Multi-tenant isolation.
- Report engine for WYSIWYG PDFs.
- Kubernetes/cloud deployment.

### Data Architecture

- **PostgreSQL:** system of record for users, roles, job scopes, plants, machine groups, machines, sparepart taxonomy, spareparts, machine sparepart installations, responsibility chains, alerts, notification jobs, WAHA templates, audit events, health snapshots, and telemetry quarantine log.
- **InfluxDB 3 Core:** telemetry history for accepted machine telemetry. If Java integration support becomes a blocker, use InfluxDB v2 line protocol compatibility.
- **Redis:** latest telemetry state, freshness cache, lightweight lookup cache, WAHA rate-limit deduplication keys.
- **Flyway:** PostgreSQL schema migrations.
- **Outbox/job table:** Phase 1 queue mechanism for WAHA notification dispatch and retry.

**InfluxDB Constraints:**

- InfluxDB 3 Core enforces hard limits: maximum 5 databases, 2000 tables across all databases, 500 columns per table.
- Telemetry schema must use shared tables with tag-based machine/plant discrimination rather than per-machine or per-plant tables.
- High-cardinality tags (unique IDs, timestamps as tags) degrade performance and must be avoided.
- Retention is configured per database. Phase 1 defines at minimum a short-term operational database (e.g., 30 days) for dashboard queries.

**Redis Policy:**

- All Redis keys storing derived or cached state shall have explicit TTL values.
- Redis data shall be rebuildable from PostgreSQL and InfluxDB.
- Redis shall never be the sole source of truth for any operational data.
- WAHA rate-limit keys use short TTLs matching the configurable deduplication window.

**Quarantine Store:**

- Rejected/invalid telemetry messages are persisted in PostgreSQL `telemetry_quarantine` table with: received timestamp, MQTT topic, raw payload, rejection reason, schema version, and correlation ID.
- Quarantine entries are accessible to SUPER_ADMIN via health dashboard and dedicated quarantine log API.
- Quarantine table supports retention/cleanup policy to prevent unbounded growth.

Rationale: reduces moving parts for Phase 1 while preserving clear ownership and upgrade path.

### Authentication & Security

- Use Spring Security in backend.
- Application roles: `SUPER_ADMIN`, `MANAGE`, `VIEWER`.
- Job scopes stored separately: `TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, `MANAGER`.
- API authorization enforced server-side; frontend only hides/shows allowed actions based on API-provided permissions.
- Password/session implementation can start local/internal; SSO/Keycloak deferred unless company infrastructure requires it.
- Validate all external boundaries: REST DTOs, MQTT payloads, WAHA responses.
- REST request DTOs must use `spring-boot-starter-validation` / Jakarta Bean Validation with `@Valid` controller binding, explicit `@Size` limits on string inputs, format/range constraints where known, and custom constraints only for Syncro domain formats such as plant code, machine code, MQTT topic segment, WAHA phone, or threshold rules.
- Backend error handling must normalize validation failures and malformed JSON/type mismatches into the standard safe error shape; tests must cover blank, null, too-long, malformed, duplicate, and cross-field invalid inputs for each state-changing endpoint.
- Plant-scoped data access: non-SUPER_ADMIN users see only telemetry, alerts, and operational data for their assigned plants. Backend API enforces plant scope on all queries; frontend displays plant selector for multi-plant users.

**MQTT Security:**

- EMQX shall authenticate each connecting device using credentials or certificates.
- Each device is authorized to publish only to its own machine telemetry topic via ACL rules. Deny-by-default policy applies.
- MQTT connections shall use TLS in production. Development environments may use plaintext for convenience.
- EMQX ACL configuration lives in `infra/emqx/etc/` and maps device identity to allowed topic patterns.
- Backend MQTT consumer credentials are separate from device credentials.

**Frontend Security Boundary:**

- Next.js web application shall not access PostgreSQL, InfluxDB, Redis, EMQX, or WAHA directly.
- All data access goes through Spring Boot REST APIs or controlled Next.js server-side Route Handlers that proxy Spring Boot.
- No database credentials, WAHA API keys, or MQTT credentials in frontend environment or browser code.

### API & Communication Patterns

- Web ↔ Backend: REST JSON under `/api/v1`.
- Backend API docs: Springdoc OpenAPI.
- Epic 2 frontend data APIs use Orval-generated TypeScript clients and TanStack Query hooks from the backend OpenAPI contract; handwritten fetch remains allowed only for shell/session endpoints or until the generator is introduced.
- Standard API error shape should include machine-readable code, user-facing message, field errors where relevant, timestamp, and request trace id.
- Operational response objects should include status, status label, status reason, timestamp, and allowed actions where UX needs action safety.
- MQTT topic: `factory/{plantCode}/{machineCode}/telemetry`.
- MQTT payload contract requires: `schemaVersion`, `messageId`, `timestamp` (UTC ISO-8601), `running`, `runtimeHours`, `counting`, plus up to 10 configured parameters.
- MQTT ingest validates topic, machine status, payload contract, schema version, payload-topic machine identity match, and physically plausible value ranges before writing.
- Phase 1 assumes one active ingest worker instance. If backend scales horizontally later, MQTT shared subscriptions (`$share/{group}/factory/+/+/telemetry`), client ID coordination, or separated worker deployment is required to avoid duplicate subscriptions.
- Backend writes accepted telemetry to InfluxDB and latest state to Redis.
- Alert evaluation runs after accepted telemetry.
- WAHA notification sends from queued jobs, never directly inside user request or telemetry ingest path. Notification dispatch is fully decoupled from telemetry processing.
- All operational actions return timestamps and state reasons for UX trust.

**Circuit Breaker and Resilience:**

- External service calls (WAHA HTTP, InfluxDB HTTP writes) shall use configurable timeouts, retry with exponential backoff, and circuit-breaker behavior.
- Transient failures shall not block processing pipelines or exhaust worker threads.
- Circuit breaker state (open/half-open/closed) should be observable in health dashboard.
- WAHA rate limiting uses Redis-backed short-lived deduplication keys to prevent notification storms.

### Idempotency and Dedupe Decisions

- Telemetry ingest dedupe should use `messageId` from payload as primary key. Fallback: machine identity plus telemetry timestamp/counter payload where `messageId` is unavailable.
- Alert dedupe should use `machineSparepartInstallationId`, threshold percentage, and active alert lifecycle state.
- Notification dedupe should use `alertId`, escalation level, and recipient id.
- WAHA retry attempts should preserve each attempt result without creating duplicate logical notification records.
- WAHA rate limiting uses Redis-backed short-lived keys per `alertId` + recipient to prevent notification storms within a configurable deduplication window.

### Frontend Architecture

- Use `arhamkhnz/next-shadcn-admin-dashboard` as base.
- Keep boilerplate shell, theme presets, shadcn/ui, forms, tables, and responsive conventions.
- **Implementation reference:** See `frontend-hardening-specification.md` for detailed boilerplate audit, strip/keep/adapt decisions, semantic token definitions, component TypeScript interfaces, and build sequence.
- **Screen-level reference:** See `page-specifications.md` for detailed layout, states, microcopy, responsive behavior, and interaction specs per primary screen.
- Frontend domain modules:
  - Operations Overview
  - Telemetry
  - Alerts
  - Master Data
  - WAHA Templates
  - Audit Log
  - System Health
  - Settings
- Domain components from UX spec are frontend wrappers over backend data contracts.
- New domain components from research reconciliation: `DataQualityPanel`, `QuarantineLogTable`, `LatencyIndicator`, `AuditLogTable`, `PlantScopeSelector`.
- TanStack Query is the client-side server-state layer starting Epic 2 data APIs. Query keys must include active plant scope where data is plant-scoped, and mutations must invalidate affected query keys explicitly.
- Orval is the frontend API generation tool starting Epic 2. Generate TypeScript client functions and TanStack Query hooks from Springdoc OpenAPI; keep generated files isolated from handwritten domain components.
- Zustand only for local UI/session shell state; backend remains source of truth for permissions, calculations, and workflows.

### Infrastructure & Deployment

- Local dev via Docker Compose:
  - PostgreSQL
  - Redis
  - InfluxDB
  - MQTT broker
  - WAHA
- Backend and frontend run as separate apps.
- Environment config through `.env.example` and app-specific profiles.
- Health checks exposed through Spring Actuator plus custom dependency checks.
- Deployment target deferred; architecture stays container-friendly.

### Decision Impact Analysis

**Implementation Sequence:**

1. Initialize monorepo and local infra.
2. Initialize Spring Boot backend with PostgreSQL/Flyway/Security/Redis/Actuator.
3. Initialize Next.js web from dashboard boilerplate.
4. Implement auth/roles and API guard.
5. Implement master data schema/API/UI.
6. Implement MQTT ingest and telemetry persistence.
7. Implement Redis latest telemetry and Machine Detail/Telemetry UI.
8. Implement sparepart lifetime calculation and alerts.
9. Implement WAHA outbox worker and escalation.
10. Implement system health.

**Cross-Component Dependencies:**

- Alert evaluation depends on machine, installed sparepart, baseline counter, threshold, and telemetry ingest.
- WAHA escalation depends on alert state and machine responsibility.
- UX trust depends on backend timestamps, status reasons, allowed actions, and evidence.
- Future ABAC depends on keeping application role and job scope separate.
- Future IATF and KPI phases depend on audit/event evidence captured from Phase 1.

## Implementation Patterns & Consistency Rules

### Naming Patterns

**Database**

- Table names use `snake_case` plural names: `machines`, `spareparts`, `machine_sparepart_installations`, `alerts`, `notification_jobs`.
- Column names use `snake_case`: `machine_code`, `expected_production_count`, `last_telemetry_at`.
- Foreign keys use `{referenced_table_singular}_id`: `machine_id`, `sparepart_id`, `alert_id`.
- Indexes use `idx_<table>_<columns>`.
- Unique constraints use `uq_<table>_<columns>`.
- Enum values are persisted as uppercase strings: `OPEN`, `ACKNOWLEDGED`, `RESOLVED`.

**Java / Spring Boot**

- Package names are lowercase by bounded context: `com.syncro.machine`, `com.syncro.telemetry`, `com.syncro.alert`.
- Classes use `PascalCase`: `MachineController`, `TelemetryIngestionService`, `AlertEscalationWorker`.
- Methods and fields use `camelCase`: `calculateConsumedPercentage`, `expectedProductionCount`.
- DTO suffixes are `Request`, `Response`, or `View`.
- Entity names are singular: `Machine`, `Alert`, `NotificationJob`.

**API**

- Base path is `/api/v1`.
- Resource paths use plural nouns: `/api/v1/machines`, `/api/v1/alerts`, `/api/v1/spareparts`.
- Nested paths are used only when ownership is clear: `/api/v1/machines/{machineId}/telemetry/latest`, `/api/v1/alerts/{alertId}/acknowledge`.
- Path params and query params use `camelCase`: `{machineId}`, `{alertId}`, `plantCode`, `machineGroup`, `alertStatus`.

**Frontend**

- Routes use kebab-case: `/operations-overview`, `/machine-spareparts`, `/system-health`.
- Components use `PascalCase`: `TelemetryCard`, `LifetimeProgress`, `EscalationTimeline`.
- Hooks use `use<Domain><Action>`: `useAlerts`, `useAcknowledgeAlert`.
- Files follow the selected boilerplate convention; if no convention exists, use kebab-case.

### Structure Patterns

**Backend**

Backend modules are organized by domain:

```text
apps/backend/src/main/java/com/syncro/
  auth/
  masterdata/
  machine/
  sparepart/
  telemetry/
  alert/
  notification/
  health/
  audit/
  common/
```

Each module uses this shape when needed:

```text
api/
application/
domain/
infrastructure/
```

Cross-module boundary rules:

- Domain modules must not access another module repository directly.
- Cross-module access goes through application services or internal domain events.
- Workers orchestrate process flow; business rules stay in application/domain services.
- `common` is limited to shared primitives such as error model, audit primitives, time helpers, trace helpers, and correlation helpers.
- `common` must not contain domain logic.

Allowed/forbidden examples:

```text
telemetry -> AlertEvaluationService allowed
telemetry -> AlertRepository forbidden
notification -> AlertService read summary allowed
notification -> AlertRepository forbidden
```

**Frontend**

Keep the boilerplate structure and add Syncro modules under feature folders:

```text
apps/web/src/features/
  operations/
  machines/
  telemetry/
  alerts/
  master-data/
  waha-templates/
  system-health/
  settings/
```

Shared Syncro UI components live under:

```text
apps/web/src/components/syncro/
  status-badge.tsx
  telemetry-card.tsx
  lifetime-progress.tsx
  escalation-timeline.tsx
  health-card.tsx
  data-quality-panel.tsx
  quarantine-log-table.tsx
  latency-indicator.tsx
  audit-event-row.tsx
  audit-log-table.tsx
  plant-scope-selector.tsx
  alert-action-panel.tsx
  setup-completeness-checklist.tsx
  machine-summary-card.tsx
  waha-template-editor.tsx
```

### Format Patterns

**JSON**

- JSON fields use `camelCase`.
- Timestamps use ISO-8601 UTC: `2026-05-22T09:12:00Z`.
- Percent values are numeric, not strings.
- Status values are uppercase enum strings.

Paginated responses use:

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalItems": 0,
  "totalPages": 0
}
```

Error responses use:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "fieldErrors": {
    "machineCode": "Machine code is required"
  },
  "timestamp": "2026-05-22T09:12:00Z",
  "traceId": "..."
}
```

Operational status responses use:

```json
{
  "status": "OPEN",
  "statusLabel": "Open",
  "statusReason": "Sparepart threshold reached.",
  "statusSeverity": "CRITICAL",
  "timestamp": "2026-05-22T09:12:00Z",
  "allowedActions": ["ACKNOWLEDGE", "VIEW_DETAIL"]
}
```

Allowed severities:

```text
INFO
SUCCESS
WARNING
CRITICAL
NEUTRAL
```

Frontend maps severity to shared visual components only. Feature-specific colors are not allowed.

### Communication Patterns

Domain event names use dot-separated past tense:

```text
telemetry.accepted
telemetry.rejected
telemetry.quarantined
alert.created
alert.acknowledged
alert.resolved
notification.queued
notification.delivered
notification.failed
notification.rate_limited
health.status_changed
audit.master_data_changed
```

Event payloads include at minimum:

```json
{
  "eventName": "alert.created",
  "eventId": "...",
  "occurredAt": "2026-05-22T09:12:00Z",
  "source": "alert-service",
  "actorType": "SYSTEM",
  "entityType": "ALERT",
  "entityId": "alert-123",
  "traceId": "..."
}
```

Audit records capture:

```text
actor
timestamp
source
action
target
result
traceId
```

Every request, worker execution, telemetry message, alert transition, and notification job carries `traceId`. If source has no trace ID, backend generates one.

### Process Patterns

**Telemetry ingestion**

1. Receive MQTT message; assign correlation ID (use `messageId` from payload or generate one).
2. Validate machine exists and extract identity from topic.
3. Validate payload-topic machine identity match.
4. Reject inactive machines (quarantine with reason).
5. Validate schema version; reject/quarantine unrecognized versions.
6. Validate payload contract and physically plausible ranges; quarantine invalid payloads with reason.
7. Deduplicate using `messageId`.
8. Store accepted telemetry in InfluxDB.
9. Update latest telemetry in Redis (with TTL).
10. Evaluate sparepart lifetime from backend source of truth.
11. Create alert only when dedupe rule allows.
12. Queue notification job (decoupled from ingest path).
13. Log correlation ID at each stage for end-to-end traceability.

**Backpressure behavior:**

- When telemetry processing cannot keep pace, the system degrades into bounded backlog (queue depth limit) rather than unbounded memory growth or process crash.
- If the internal queue reaches capacity, the system delays MQTT acknowledgment (applying backpressure to the broker) rather than silently discarding messages.
- Queue depth, processing lag, and dead-letter volume are observable in health dashboard.

**Counter wrap**

- Backend owns counter delta calculation.
- Frontend never calculates consumed count from raw telemetry.
- 16-bit wrap is supported when source counter rolls back to `0`.
- Negative raw delta is treated as wrap only for configured unsigned counter sources.

**Alert lifecycle**

Alert transitions are explicit table-driven logic, not scattered conditionals:

```text
OPEN -> ACKNOWLEDGED
OPEN -> RESOLVED by SUPER_ADMIN
ACKNOWLEDGED -> RESOLVED
RESOLVED -> terminal
```

Invalid transitions return `INVALID_STATE_TRANSITION`.

**Notification retry and idempotency**

Every job-like process defines:

```text
idempotencyKey
attemptCount
nextAttemptAt
lastErrorCode
lastErrorMessage
status
traceId
```

Dedupe keys:

```text
notification: alertId + escalationLevel + recipientId
alert: machineSparepartInstallationId + thresholdPercent + active lifecycle state
telemetry: machineId + telemetryTimestamp + counter payload
```

If telemetry source has no timestamp, ingestion time may be used but duplicate protection becomes best-effort.

**Permission decisions**

Every restricted action returns an internal decision and reason:

```text
allowed: true|false
reason: ROLE_ALLOWED | JOB_SCOPE_DENIED | MACHINE_SCOPE_DENIED | ALERT_ALREADY_RESOLVED
```

Frontend may structure UI by role, but final allowed actions come from backend.

**Critical action explanation**

Every critical action explains what happens next.

Example acknowledge copy:

```text
Acknowledge alert
Stops further escalation and records your action. Alert remains visible until resolved.
```

Example resolve copy:

```text
Resolve alert
Closes this alert after the issue has been handled. Resolved alerts cannot be acknowledged again.
```

### Validation, Testing, and Demo Patterns

Backend validation levels:

- Request DTO validation for shape and required fields.
- Application validation for domain rules.
- Database constraints for uniqueness and referential integrity.
- Worker validation before external send.

Frontend validation:

- Form shape and required fields only.
- No duplicate backend domain rule implementation except UX hints.
- Backend errors remain authoritative.

Minimum Phase 1 acceptance cases:

1. Active machine telemetry is accepted and visible.
2. Inactive machine telemetry is rejected and audited.
3. 16-bit counter wrap calculates correct production delta.
4. Sparepart reaches 90% threshold and creates exactly one active alert.
5. Duplicate telemetry does not create duplicate alert.
6. TECHNICIAN notification job is queued when alert opens.
7. Duplicate notification job does not send duplicate WhatsApp.
8. No acknowledgement before interval queues STAFF escalation.
9. Acknowledgement before interval prevents STAFF escalation.
10. No acknowledgement before next interval queues LEADER escalation.
11. `SUPER_ADMIN` can resolve `OPEN` alert directly.
12. `MANAGE` role action is constrained by job scope.
13. `VIEWER` can view but cannot mutate.
14. WAHA template variables render expected alert message.
15. System health shows degraded/stale dependency with timestamp and reason.

Every Phase 1 success metric maps to a testable demo path:

```text
MQTT telemetry publish
-> telemetry accepted
-> latest telemetry visible
-> sparepart threshold evaluated
-> alert created
-> notification job queued
-> WAHA send attempted
-> audit evidence available
```

No success metric counts as complete without observable UI state, backend state, and audit/job evidence.

### Contract, Seed, and Time Patterns

All lifecycle/status enums are owned by backend domain code. Frontend may display enum values but cannot invent status strings or transition rules.

Any API response used by frontend operational screens is a contract. Contract-breaking changes require:

- frontend update in the same story/PR
- updated example response
- updated test fixture
- updated OpenAPI schema.

Development and pilot seed data must include:

```text
GM1
Forming
BF-08410
JBF19
Electric PLC Wecon LX5
TECHNICIAN / STAFF / LEADER escalation recipients
90% threshold
```

Seed data must not include fake statuses that cannot happen through backend lifecycle rules.

Backend stores and returns timestamps in UTC. Frontend displays absolute timestamp for audit/evidence and relative timestamp for operational freshness. Escalation scheduling uses backend time only; frontend countdown is display-only.

### Enforcement Rules for AI Agents

- `architecture.md`, PRD, and UX spec are source of truth before code generation.
- Backend owns permissions, status transitions, enums, and sparepart lifetime calculations.
- Frontend displays backend decisions and does not reimplement domain rules.
- New status must define enum value, API representation, UI badge variant, allowed actions, and audit behavior.
- New endpoint must define path, method, request DTO, response DTO, error codes, and permission rule.
- New background worker must define trigger, idempotency key, retry rule, failure state, and audit/log event.
- No new infrastructure component in Phase 1 unless PostgreSQL, Redis, InfluxDB, MQTT, and WAHA cannot meet the requirement.

## Project Structure & Boundaries

### Requirements Mapping

**Authentication and Roles → `apps/backend/src/main/java/com/syncro/auth/`, `apps/web/src/features/settings/`**

- `SUPER_ADMIN`, `MANAGE`, `VIEWER`.
- Backend API guards.
- Frontend allowed actions display.
- Future job-scope/ABAC compatibility.

**Master Data → `masterdata/`, `machine/`, `sparepart/`, `apps/web/src/features/master-data/`**

- Plants.
- Machine groups/process lines.
- Machines.
- Sparepart taxonomy.
- Spareparts.
- Machine sparepart installations.

**Telemetry → `telemetry/`, `infra/emqx/`, `apps/web/src/features/telemetry/`**

- MQTT topic validation.
- Active-machine validation.
- Counter payload validation.
- InfluxDB writes.
- Redis latest state.
- Telemetry freshness UI.

**Alerts → `alert/`, `apps/web/src/features/alerts/`**

- Sparepart threshold evaluation.
- Alert dedupe.
- Lifecycle: `OPEN`, `ACKNOWLEDGED`, `RESOLVED`.
- Escalation stop on acknowledgement.
- Mobile acknowledge UX.

**WAHA Notifications → `notification/`, `apps/web/src/features/waha-templates/`**

- Notification job queue.
- Escalation levels.
- Retry.
- Delivery attempts.
- WYSIWYG text template.

**System Health → `health/`, `apps/web/src/features/system-health/`**

- PostgreSQL health.
- Redis health.
- InfluxDB health.
- EMQX/MQTT health.
- WAHA health.
- Worker freshness/status.
- Data quality metrics (quarantine count, rejection rate, anomaly count, dead-letter count).
- Quarantine log (rejected telemetry messages with correlation ID).
- End-to-end telemetry latency indicator.
- Circuit breaker state visibility.

**Audit/Evidence → `audit/`, `apps/web/src/features/audit-log/`, shared across backend modules**

- Actor.
- Timestamp.
- Source.
- Action.
- Target (entity type, entity ID).
- Previous value.
- New value.
- Result.
- Trace ID.
- Immutable master data change history (FR-076).
- Filterable by entity type, actor, plant, date range.

### Complete Project Directory Structure

```text
syncro/
├── README.md
├── .env.example
├── .gitignore
├── docs/
│   ├── architecture.md
│   ├── api-contracts.md
│   ├── local-development.md
│   ├── pilot-validation.md
│   └── screenshots/
│       └── pilot/
├── scripts/
│   ├── seed-pilot.ps1
│   ├── publish-jbf19-before-threshold.ps1
│   ├── publish-jbf19-threshold.ps1
│   └── verify-pilot.ps1
├── infra/
│   ├── docker-compose.yml
│   ├── postgres/
│   │   └── init/
│   ├── pgadmin/
│   ├── redis/
│   ├── influxdb/
│   ├── emqx/
│   │   └── etc/
│   └── waha/
├── apps/
│   ├── backend/
│   │   ├── pom.xml
│   │   ├── .env.example
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── java/
│   │   │   │   │   └── com/
│   │   │   │   │       └── syncro/
│   │   │   │   │           ├── SyncroApplication.java
│   │   │   │   │           ├── auth/
│   │   │   │   │           │   ├── api/
│   │   │   │   │           │   ├── application/
│   │   │   │   │           │   ├── domain/
│   │   │   │   │           │   └── infrastructure/
│   │   │   │   │           ├── masterdata/
│   │   │   │   │           │   ├── api/
│   │   │   │   │           │   ├── application/
│   │   │   │   │           │   ├── domain/
│   │   │   │   │           │   └── infrastructure/
│   │   │   │   │           ├── machine/
│   │   │   │   │           │   ├── api/
│   │   │   │   │           │   ├── application/
│   │   │   │   │           │   ├── domain/
│   │   │   │   │           │   └── infrastructure/
│   │   │   │   │           ├── sparepart/
│   │   │   │   │           │   ├── api/
│   │   │   │   │           │   ├── application/
│   │   │   │   │           │   ├── domain/
│   │   │   │   │           │   └── infrastructure/
│   │   │   │   │           ├── telemetry/
│   │   │   │   │           │   ├── api/
│   │   │   │   │           │   ├── application/
│   │   │   │   │           │   ├── domain/
│   │   │   │   │           │   └── infrastructure/
│   │   │   │   │           ├── alert/
│   │   │   │   │           │   ├── api/
│   │   │   │   │           │   ├── application/
│   │   │   │   │           │   ├── domain/
│   │   │   │   │           │   └── infrastructure/
│   │   │   │   │           ├── notification/
│   │   │   │   │           │   ├── api/
│   │   │   │   │           │   ├── application/
│   │   │   │   │           │   ├── domain/
│   │   │   │   │           │   └── infrastructure/
│   │   │   │   │           ├── health/
│   │   │   │   │           │   ├── api/
│   │   │   │   │           │   ├── application/
│   │   │   │   │           │   ├── domain/
│   │   │   │   │           │   └── infrastructure/
│   │   │   │   │           ├── audit/
│   │   │   │   │           │   ├── application/
│   │   │   │   │           │   ├── domain/
│   │   │   │   │           │   └── infrastructure/
│   │   │   │   │           └── common/
│   │   │   │   │               ├── api/
│   │   │   │   │               ├── config/
│   │   │   │   │               ├── error/
│   │   │   │   │               ├── security/
│   │   │   │   │               ├── time/
│   │   │   │   │               └── tracing/
│   │   │   │   └── resources/
│   │   │   │       ├── application.yml
│   │   │   │       ├── application-local.yml
│   │   │   │       └── db/
│   │   │   │           ├── migration/
│   │   │   │           └── seed/
│   │   │   │               └── pilot-seed.sql
│   │   │   └── test/
│   │   │       ├── java/
│   │   │       │   └── com/
│   │   │       │       └── syncro/
│   │   │       │           ├── auth/
│   │   │       │           ├── masterdata/
│   │   │       │           ├── machine/
│   │   │       │           ├── sparepart/
│   │   │       │           ├── telemetry/
│   │   │       │           ├── alert/
│   │   │       │           ├── notification/
│   │   │       │           ├── health/
│   │   │       │           ├── audit/
│   │   │       │           └── support/
│   │   │       │               ├── IntegrationTestSupport.java
│   │   │       │               └── PilotFixture.java
│   │   │       └── resources/
│   │   │           └── fixtures/
│   │   │               └── pilot-fixture.json
│   │   └── target/
│   └── web/
│       ├── package.json
│       ├── next.config.ts
│       ├── tsconfig.json
│       ├── components.json
│       ├── biome.json
│       ├── .env.example
│       ├── public/
│       │   └── assets/
│       └── src/
│           ├── app/
│           │   ├── layout.tsx
│           │   ├── page.tsx
│           │   ├── operations-overview/
│           │   ├── machines/
│           │   ├── telemetry/
│           │   ├── alerts/
│           │   ├── master-data/
│           │   ├── waha-templates/
│           │   ├── audit-log/
│           │   ├── system-health/
│           │   └── settings/
│           ├── components/
│           │   ├── ui/
│           │   ├── layout/
│           │   └── syncro/
│           │       ├── status-badge.tsx
│           │       ├── telemetry-card.tsx
│           │       ├── lifetime-progress.tsx
│           │       ├── alert-action-panel.tsx
│           │       ├── escalation-timeline.tsx
│           │       ├── audit-event-row.tsx
│           │       ├── audit-log-table.tsx
│           │       ├── health-card.tsx
│           │       ├── data-quality-panel.tsx
│           │       ├── quarantine-log-table.tsx
│           │       ├── latency-indicator.tsx
│           │       ├── plant-scope-selector.tsx
│           │       ├── setup-completeness-checklist.tsx
│           │       ├── machine-summary-card.tsx
│           │       └── waha-template-editor.tsx
│           ├── features/
│           │   ├── operations/
│           │   ├── machines/
│           │   ├── telemetry/
│           │   ├── alerts/
│           │   ├── master-data/
│           │   ├── waha-templates/
│           │   ├── audit-log/
│           │   ├── system-health/
│           │   └── settings/
│           ├── lib/
│           │   ├── api/
│           │   ├── auth/
│           │   ├── formatting/
│           │   └── utils.ts
│           ├── stores/
│           └── types/
└── tests/
    ├── e2e/
    │   ├── pilot-threshold-alert.spec.ts
    │   └── alert-acknowledgement.spec.ts
    └── fixtures/
        ├── pilot-fixture.json
        ├── mqtt-jbf19-before-threshold-payload.json
        └── mqtt-jbf19-threshold-payload.json
```

### Architectural Boundaries

**API Boundaries**

- External web API lives only in backend `api/` packages.
- API paths remain under `/api/v1`.
- Controllers do not contain business rules.
- Controllers call application services.
- API DTOs do not leak JPA entities.

**Service Boundaries**

- Application services coordinate use cases.
- Domain objects own lifecycle and calculation rules where practical.
- Infrastructure adapters own persistence, MQTT, Redis, InfluxDB, EMQX connectivity, and WAHA details.
- Repositories are module-local and must not be injected across module boundaries.

**Frontend Boundaries**

- `app/` contains route shell and page composition only.
- `features/` contains hooks, forms, tables, page sections, and domain data composition.
- `components/syncro/` contains reusable Syncro visual components.
- `components/ui/` remains generic shadcn/ui only.
- No business logic lives in `app/` route files.
- Frontend domain components render backend-provided status, reason, timestamp, and allowed actions.

**Data Boundaries**

- PostgreSQL owns relational business state.
- InfluxDB owns accepted telemetry history.
- Redis owns latest telemetry/cache.
- PostgreSQL notification jobs own WAHA dispatch state.
- Frontend cache is display cache only, not source of truth.

**MQTT Broker Boundary**

MQTT contract stays broker-agnostic:

```text
Topic: factory/{plantCode}/{machineCode}/telemetry
Payload: Syncro telemetry JSON contract
Local broker: EMQX
```

EMQX is local/dev infrastructure choice, not domain dependency. Backend MQTT config must come from environment:

```text
SYNCRO_MQTT_HOST
SYNCRO_MQTT_PORT
SYNCRO_MQTT_USERNAME
SYNCRO_MQTT_PASSWORD
SYNCRO_MQTT_CLIENT_ID
SYNCRO_MQTT_TOPIC_FILTER
```

No hardcoded EMQX URL in backend source.

**pgAdmin Boundary**

pgAdmin is local-dev/admin evidence tool only. It supports PostgreSQL inspection, pilot debugging, alert row verification, notification job verification, and audit event verification. It is not runtime dependency, user-facing feature, production requirement, or replacement for application admin UI.

### Requirements to Structure Mapping

**Auth and App Roles**

- Backend: `auth/`.
- Frontend: `features/settings/`, route guards, navigation visibility.
- Shared: `common/security/`.

**Machine Master and Sparepart Setup**

- Backend: `masterdata/`, `machine/`, `sparepart/`.
- Frontend: `features/master-data/`, `features/machines/`.
- Tests: `masterdata/`, `machine/`, `sparepart/`.

**Telemetry Ingestion**

- Backend: `telemetry/application/TelemetryIngestionService.java`.
- MQTT adapter: `telemetry/infrastructure/`.
- EMQX local config: `infra/emqx/`.
- Redis latest state: `telemetry/infrastructure/`.
- InfluxDB writer: `telemetry/infrastructure/`.
- Frontend: `features/telemetry/`, `components/syncro/telemetry-card.tsx`.

**Alert Evaluation**

- Backend: `alert/application/AlertEvaluationService.java`.
- Lifecycle: `alert/domain/`.
- Frontend: `features/alerts/`, `components/syncro/alert-action-panel.tsx`.

**WAHA Escalation**

- Backend: `notification/application/`.
- WAHA adapter: `notification/infrastructure/`.
- Template UI: `features/waha-templates/`, `waha-template-editor.tsx`.

**System Health**

- Backend: `health/`.
- Frontend: `features/system-health/`, `health-card.tsx`.

**Audit and Evidence**

- Backend: `audit/`.
- Frontend display: `audit-event-row.tsx`.
- Used by alerts, notifications, health, setup changes.

### Integration Points

**Internal Communication**

```text
REST request
-> Controller
-> Application Service
-> Domain
-> Repository/Adapter
-> Audit Event
```

```text
MQTT message
-> Telemetry Adapter
-> TelemetryIngestionService
-> InfluxDB + Redis
-> AlertEvaluationService
-> NotificationJobService
-> Audit Event
```

```text
Notification worker
-> NotificationJobRepository
-> WahaClient
-> NotificationAttempt recorded
-> Audit Event
```

**External Integrations**

- EMQX receives MQTT machine telemetry in local/dev.
- pgAdmin supports PostgreSQL inspection/admin in local/dev.
- WAHA sends WhatsApp messages.
- InfluxDB stores telemetry history.
- Redis stores latest telemetry.
- PostgreSQL stores business state and job state.

**Stable Local Infra Service Names**

`infra/docker-compose.yml` service names must remain stable because docs and scripts depend on them:

```text
postgres
pgadmin
redis
influxdb
emqx
waha
```

**Data Flow**

```text
Machine telemetry
-> MQTT topic factory/{plantCode}/{machineCode}/telemetry
-> EMQX local broker
-> backend validation
-> InfluxDB history
-> Redis latest
-> sparepart lifetime calculation
-> alert
-> notification job
-> WAHA
-> audit evidence
-> frontend operational screens
```

### File Organization Patterns

**Configuration**

- Backend config: `apps/backend/src/main/resources/application*.yml`.
- Frontend config: `apps/web/.env.example`.
- Infra config: `infra/`.
- Root environment sample: `.env.example`.

**Source**

- Backend source by domain module.
- Frontend source by route, feature, shared component, and API lib.
- No domain logic in `common`.
- No backend calculation duplicated in frontend.

**Seed, Fixtures, and Tests**

- `apps/backend/src/main/resources/db/seed/` contains local dev and pilot demo seed data.
- `apps/backend/src/test/resources/fixtures/` contains backend automated test fixtures.
- `tests/fixtures/` contains cross-stack E2E fixtures.
- `tests/fixtures/mqtt-jbf19-before-threshold-payload.json` proves alert is not created before 90%.
- `tests/fixtures/mqtt-jbf19-threshold-payload.json` proves alert is created at threshold.
- Backend unit/integration tests mirror backend package structure.
- E2E tests live in root `tests/e2e/`.
- Seed data must match possible backend lifecycle state. No impossible fake statuses.

**Assets and Screenshots**

- Static web assets live in `apps/web/public/assets/`.
- Pilot screenshots for documentation live in `docs/screenshots/pilot/`.
- No user-upload or report storage required in Phase 1.

### Pilot Validation Structure

`docs/pilot-validation.md` splits proof into two layers.

**Operator proof via UI**

- JBF19 latest telemetry visible.
- Sparepart lifetime reaches 90%.
- Alert appears as `OPEN`.
- Acknowledge action visible for allowed user.
- Escalation timeline visible.
- System health visible.

**Technical proof via pgAdmin / logs / job table**

- Telemetry accepted event/audit row exists.
- Alert row created once.
- Notification job queued.
- WAHA attempt recorded.
- Acknowledgement stops next escalation job.
- Trace ID connects telemetry → alert → notification → audit.

### Development Workflow Integration

**Local Development**

- `infra/docker-compose.yml` starts PostgreSQL, pgAdmin, Redis, InfluxDB, EMQX, and WAHA.
- Backend runs from `apps/backend`.
- Frontend runs from `apps/web`.
- Pilot fixture seeds canonical validation data.

**Dev Script Expectations**

Root `scripts/` supports repeatable pilot actions:

```text
start local infra
seed pilot data
publish JBF19 MQTT before-threshold payload to EMQX
publish JBF19 MQTT threshold payload to EMQX
verify telemetry accepted
verify alert created
verify WAHA notification job queued
```

**Build**

- Backend build via Maven in `apps/backend`.
- Frontend build via npm scripts in `apps/web`.
- Infrastructure remains container config, not compiled artifact.

**Deployment**

- Backend and frontend deploy as separate apps.
- Infrastructure dependencies are externalized by environment variables.
- pgAdmin remains local/dev only unless explicitly provisioned by ops.
- EMQX can be replaced by another MQTT broker in production if it preserves the MQTT contract.
- Structure remains container-friendly for later cloud/Kubernetes work.

## Architecture Validation Results

### Coherence Validation

**Decision Compatibility: ✅ Pass**

Architecture choices fit together:

- Spring Boot modular monolith supports domain modules, internal workers, REST API, Flyway, Redis, PostgreSQL, MQTT, and WAHA.
- Next.js + shadcn admin boilerplate supports dashboard shell, dense admin screens, mobile alert response, and reusable domain components.
- PostgreSQL + InfluxDB + Redis ownership is clear:
  - PostgreSQL = business state, config, alerts, jobs, audit.
  - InfluxDB = accepted telemetry history.
  - Redis = latest telemetry/cache.
- EMQX fits local MQTT broker need while MQTT contract stays broker-agnostic.
- pgAdmin is scoped as local/dev evidence tool only, not app dependency.
- PostgreSQL-backed notification jobs avoid extra broker complexity for Phase 1.

**Pattern Consistency: ✅ Pass**

Patterns support decisions:

- Naming rules align across database, Java, API, JSON, frontend files, and routes.
- Backend-owned enum/status rules support lifecycle safety.
- Operational status contract supports UX trust needs.
- Trace ID and audit rules support future KPI/IATF evidence.
- Idempotency rules cover telemetry, alert, and notification duplicate risks.
- Route/feature/component boundaries fit selected Next.js boilerplate.

**Structure Alignment: ✅ Pass**

Project structure supports architecture:

- `apps/backend` and `apps/web` keep backend/frontend concerns separate.
- `infra/` owns PostgreSQL, pgAdmin, Redis, InfluxDB, EMQX, WAHA.
- Backend domain modules map to PRD requirement categories.
- Frontend routes, features, and Syncro components map to UX spec.
- Root `tests/fixtures` and `scripts/` support cross-stack pilot validation.

### Requirements Coverage Validation

**Feature Coverage: ✅ Pass**

All Phase 1 feature areas have architectural support:

- Auth and roles → `auth`, `common/security`, frontend settings/navigation guards.
- Master data → `masterdata`, `machine`, `sparepart`.
- Machine sparepart installation → `sparepart`, `machine`, PostgreSQL.
- Machine responsibility and job scopes → auth/job-scope model, notification escalation.
- MQTT telemetry → `telemetry`, EMQX, InfluxDB, Redis.
- Alert lifecycle → `alert`.
- WAHA escalation → `notification`, WAHA adapter, PostgreSQL job table.
- WYSIWYG WAHA template → frontend `waha-templates`, backend notification/template persistence.
- System health → `health`, Actuator/custom checks.
- Audit/evidence → `audit`.

**Functional Requirements Coverage: ✅ Pass**

77 PRD FRs (expanded from 65 after research reconciliation) are covered by module, pattern, or integration decision. New FRs covered:

- FR-029a (payload-topic identity match) → telemetry ingestion process step 3.
- FR-031a/b/c (schemaVersion, messageId, timestamp) → MQTT payload contract.
- FR-035a/b/c (schema quarantine, quarantine store, range validation) → quarantine store, telemetry process steps 5-6.
- FR-041a (messageId deduplication) → idempotency decisions.
- FR-061a (WAHA rate limiting) → Redis rate-limit keys, notification process.
- FR-073a/b (quarantine visibility, data quality metrics) → health module, frontend DataQualityPanel/QuarantineLogTable.
- FR-076 (audit log) → audit module, AuditLogTable component.
- FR-077 (plant-scoped access) → auth/security, PlantScopeSelector component.

Critical path covered:

```text
registered active machine
-> MQTT telemetry accepted
-> InfluxDB history + Redis latest
-> sparepart threshold calculation
-> alert dedupe/lifecycle
-> notification job
-> WAHA send attempt
-> acknowledgement stops escalation
-> audit evidence
```

Pilot path covered:

```text
GM1 / Forming / BF-08410 / JBF19 / Electric PLC Wecon LX5
-> before-threshold MQTT fixture
-> threshold MQTT fixture
-> alert created once
-> WAHA job queued
-> UI + pgAdmin/log proof
```

**Non-Functional Requirements Coverage: ✅ Pass**

- Security: Spring Security, backend guards, backend-owned permissions, MQTT TLS/auth/ACLs, frontend security boundary (no direct DB access), plant-scoped data access.
- Reliability: idempotency via messageId, retry with circuit breaker, job state, delivery attempts, backpressure with bounded queue, WAHA rate limiting.
- Observability: health module, audit, correlation ID/trace ID across all ingest stages, OpenTelemetry-compatible structured logging, data quality metrics, quarantine visibility, latency indicator.
- Data correctness: backend-owned calculations, 16-bit wrap pattern, validation layers, schema version enforcement, payload-topic cross-validation, physically plausible range checks.
- Data policy: Redis TTL enforcement, InfluxDB retention per database, quarantine table cleanup, Redis never source of truth.
- UX trust: status reason, timestamp, severity, allowed actions.
- Performance: Redis latest telemetry path with TTL, backpressure over crash, no heavy telemetry history load by default, single active MQTT ingest worker assumption, idempotent processing, notification decoupled from ingest.
- Future readiness: modular monolith boundaries preserve CMMS, IMMS, ABAC, reports, KPI, IATF expansion.

### Implementation Readiness Validation

**Decision Completeness: ✅ Pass**

Critical decisions are documented:

- Monorepo layout.
- Backend modular monolith.
- Frontend starter.
- Datastore ownership.
- REST `/api/v1`.
- MQTT contract.
- EMQX local broker.
- WAHA notification worker.
- PostgreSQL job queue.
- Auth/role model.
- Status/lifecycle ownership.
- Implementation patterns.
- Project structure.

Exact dependency versions should be pinned during repository initialization and recorded in README/docs. This is not a blocker because selected stack boundaries are already fixed.

**Structure Completeness: ✅ Pass**

Structure is specific enough for AI agents:

- Backend package tree defined.
- Frontend route/feature/component boundaries defined.
- Infra services defined.
- Docs/scripts/tests/fixtures locations defined.
- Seed and pilot validation assets defined.

**Pattern Completeness: ✅ Pass**

Conflict points covered:

- Database names.
- Java names.
- API paths.
- JSON shape.
- Error shape.
- Status contract.
- Events.
- Audit fields.
- Trace ID.
- Idempotency.
- Lifecycle transitions.
- Validation timing.
- Frontend state handling.
- Seed/fixture separation.
- Time handling.
- Critical action explanation.

### Gap Analysis Results

**Critical Gaps: None**

No architecture blocker found.

**Important Gaps: Minor / first implementation decisions**

- Exact authentication mechanism still needs Story 0 decision: cookie session vs JWT.
  - Not blocking architecture because backend ownership and Spring Security boundary already decided.
- Exact dependency versions must be pinned during repository initialization.
  - Not blocking architecture because stack families and boundaries are fixed.
- Springdoc OpenAPI, Orval, TanStack Query provider, and query-key conventions start in Epic 2.
  - Not blocking Story 1.x because Phase 1 shell/auth state uses typed API fetch plus local UI/session state only.
- Exact E2E test runner not selected.
  - Not blocking architecture; can be selected during implementation.

**Nice-to-Have Gaps**

- Add `docs/api-contracts.md` examples after backend DTOs exist.
- Add `docs/screenshots/pilot/` during pilot demo.
- Add script command docs after actual infra files exist.
- Add OpenAPI generation config during backend initialization.

### Validation Issues Addressed

Issues from review already addressed:

- EMQX added as local MQTT broker.
- pgAdmin added as local PostgreSQL inspection tool.
- MQTT contract remains broker-agnostic.
- pgAdmin scoped local/dev only.
- Root duplicate `docker-compose.yml` removed from final structure.
- `scripts/` added at root for cross-stack pilot actions.
- Before-threshold and threshold MQTT payload fixtures added.
- `docs/pilot-validation.md` split into operator proof and technical proof.
- `app/` vs `features/` frontend boundary clarified.
- Stable infra service names documented.
- Story 0 auth decision noted.
- Implementation-time version pinning noted.
- Performance rationale made explicit.
- Mobile acknowledge path included in first vertical slice.

### Architecture Completeness Checklist

**Requirements Analysis**

- [x] Project context thoroughly analyzed
- [x] Scale and complexity assessed
- [x] Technical constraints identified
- [x] Cross-cutting concerns mapped

**Architectural Decisions**

- [x] Critical decisions documented with versions
- [x] Technology stack fully specified
- [x] Integration patterns defined
- [x] Performance considerations addressed

**Implementation Patterns**

- [x] Naming conventions established
- [x] Structure patterns defined
- [x] Communication patterns specified
- [x] Process patterns documented

**Project Structure**

- [x] Complete directory structure defined
- [x] Component boundaries established
- [x] Integration points mapped
- [x] Requirements to structure mapping complete

### Architecture Readiness Assessment

**Overall Status:** READY FOR IMPLEMENTATION

**Confidence Level:** high

**Key Strengths**

- Strong Phase 1 scope boundary.
- Clear backend/frontend/infra separation.
- Good industrial telemetry-to-alert traceability.
- Explicit datastore ownership.
- EMQX and WAHA integration boundaries clear.
- Pilot validation path testable end-to-end.
- Patterns protect against AI-agent inconsistency.
- Audit and traceability support future KPI/IATF phases.
- Performance approach is appropriate for Phase 1 latest telemetry and alert response.

**Areas for Future Enhancement**

- Select final auth mode: session cookie or JWT.
- Pin exact dependency versions during repository initialization.
- Add Springdoc OpenAPI, Orval generation, TanStack Query provider, query-key factory, and mutation invalidation conventions in the first Epic 2 frontend data story.
- Add Resilience4j before WAHA/InfluxDB external-call implementation.
- Add report storage architecture before Phase 4.
- Add ABAC policy model before full ABAC rollout.
- Revisit dedicated message broker if notification workload grows.

### Implementation Handoff

**AI Agent Guidelines**

- Follow `architecture.md`, PRD, and UX spec exactly.
- Respect backend module boundaries.
- Do not access repositories across modules.
- Keep backend source of truth for permissions, statuses, transitions, and calculations.
- Keep frontend focused on rendering backend decisions.
- Use shared Syncro UI components for operational states.
- Preserve trace ID and audit evidence through telemetry → alert → notification.
- Use pilot fixtures to prove Phase 1 success.

**Story 0 Decision**

Before auth implementation, choose:

```text
Spring Security cookie/session auth
or
Spring Security JWT auth
```

Record selected auth mode and exact dependency versions in README/docs during repository initialization.

**First Implementation Priority**

Initialize repository foundation:

```text
syncro/
├── apps/backend
├── apps/web
├── infra
├── scripts
├── docs
└── tests
```

Then boot local stack:

```text
PostgreSQL + pgAdmin + Redis + InfluxDB + EMQX + WAHA
```

Then implement first vertical slice:

```text
pilot seed
-> MQTT JBF19 telemetry accepted
-> latest telemetry visible
-> threshold alert created
-> WAHA notification job queued
-> mobile acknowledge stops escalation
```


## Backend Runtime Update

- Backend runtime baseline updated to Java 25.
- Backend framework baseline updated to Spring Boot 3.5.14, pinned in `syncro/apps/backend/pom.xml`.
- Current validated local runtime: OpenJDK 25.0.3 (Eclipse Adoptium).

