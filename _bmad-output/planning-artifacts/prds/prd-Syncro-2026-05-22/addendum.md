# Addendum

## Technical and Future-Scope Notes

- Later phases: CMMS, IMMS, full ABAC maintenance, WYSIWYG PDF reports, project maintenance, KPI maintenance, IATF standard alignment.
- Selected frontend boilerplate: `https://github.com/dwcahyo23/next-shadcn-admin-dashboard/tree/main`.
- Boilerplate appears to provide a Next.js 16 App Router, TypeScript, Tailwind CSS v4, shadcn/ui dashboard shell with responsive layout, sidebar, themes, auth screens, prebuilt dashboards, forms, tables, Zod, React Hook Form, Zustand, TanStack Table, Biome, and Husky.
- Boilerplate caveat: RBAC/multi-tenant support appears planned rather than complete; Syncro must implement its own menu/API role guard.
- Phase 1 should not implement CMMS work orders yet, but entity naming should avoid blocking future work order relationships to machine, sparepart, user responsibility, alert, and maintenance events.
- Phase 1 telemetry schema should accommodate future predictive maintenance use cases (vibration signatures, thermal profiles, ML feature extraction) by supporting flexible/extensible parameter definitions per machine.

## MQTT and Telemetry Scaling Notes

- Expected plant-scale load: up to 500 machines per plant, each publishing one telemetry payload per second.
- Expected payload shape: up to 10 parameters per machine, such as running/off status, cumulative counter, runtime, sensors, and custom instrumentation fields.
- Recommended ingestion pattern: EMQX receives MQTT publishes; backend MQTT consumer validates topic and payload quickly; accepted messages are placed into a durable queue or stream; telemetry workers consume with bounded concurrency; Redis stores latest state; InfluxDB stores historical time-series points.
- Avoid direct synchronous database writes inside MQTT message callbacks. This makes broker message bursts depend on database latency and can crash or stall backend processes under high concurrency.
- Use buffering, retry, dead-letter handling, queue-depth monitoring, processing-lag metrics, and worker concurrency limits so overload degrades into visible backlog instead of process failure.
- Redis latest-state writes may be prioritized over historical writes so dashboards stay current even if InfluxDB history lags during bursts.
- EMQX remains the broker choice; sizing, topic ACLs, session settings, retained-message policy, QoS choice, and client limits should be handled in architecture/design work.
- When multiple backend instances consume telemetry, EMQX shared subscriptions shall be used to distribute messages across consumers without duplication. Topic subscription pattern for shared consumption: `$share/{group}/factory/{plantCode}/{machineCode}/telemetry`.

## Database Replication Notes

- Recommended PostgreSQL topology: one primary database handles all writes; one or more read replicas serve read-heavy dashboards, history lists, and reporting queries that tolerate small replication lag.
- Strongly consistent reads should stay on the primary database, especially after writes, authentication/authorization checks, alert acknowledgement/resolution, responsibility changes, and configuration changes.
- Replica lag must be measured and visible; stale reads can cause wrong operational behavior if routing is too aggressive.
- Failover design belongs in architecture work, including promotion policy, connection pooling, health checks, and application read/write routing.
- Spring Boot uses HikariCP connection pooling by default. Pool sizing shall be configured to prevent connection exhaustion under peak telemetry-to-alert load. Separate connection pools or routing may be needed for primary (writes) vs replica (reads) connections.

## InfluxDB Design Constraints

- InfluxDB 3 Core enforces hard limits: maximum 5 databases, 2000 tables across all databases, and 500 columns per table.
- Telemetry schema design must use shared tables with tag-based machine/plant discrimination rather than per-machine or per-plant tables.
- High-cardinality tags (unique IDs, timestamps as tags) degrade performance and should be avoided.
- Retention is configured per database. Phase 1 should define at minimum a short-term operational database (e.g., 30 days) for dashboard queries. Longer retention databases may be added within the 5-database limit as reporting needs emerge.

## Security Framework Notes

- Target security posture: ISO 27001 alignment for access control, encryption, incident response, and asset management.
- EMQX: TLS for MQTT transport, per-device credentials or X.509 certificates, deny-by-default topic ACLs, separate listener profiles for different device classes.
- Redis: AUTH enabled, private network only, no public exposure.
- InfluxDB: token-based authentication, private network only.
- PostgreSQL: least-privilege database users per component, TLS for connections, private network only.
- Network segmentation between OT (machine/EMQX) and IT (backend/frontend/databases) zones recommended for production.
- Zero-trust component boundaries: Next.js never gets database credentials; Spring Boot uses least-privilege database users; EMQX admin/API access stays internal.

## Edge Resilience and Machine Publisher Notes

- Machine publisher resilience: architecture should specify whether machine-side MQTT clients buffer during broker unavailability (QoS 1/2 with persistent sessions) or if an edge gateway provides store-and-forward.
- MQTT QoS 1 (at-least-once) is the recommended default for telemetry subscriptions. Consumers must be idempotent to handle duplicate deliveries from retries/reconnects.
- MQTT 5.0 is preferred protocol version (user properties, shared subscriptions, reason codes, session expiry). MQTT 3.1.1 supported as fallback for devices that cannot use 5.0.

## Observability Notes

- The system health dashboard (FR-064-075) shall be backed by Spring Boot Actuator health indicators and metrics endpoints.
- Actuator shall expose health, info, metrics, and Prometheus endpoints.
- Custom health indicators shall be implemented for EMQX, InfluxDB, WAHA, and worker status.
- OpenTelemetry is the target vendor-neutral observability standard for traces, metrics, and logs.
- Correlation IDs must flow from MQTT receipt through validation, persistence, alert evaluation, and notification dispatch.

## Implementation Strategy Notes

- Build one complete vertical slice first (single machine publishing telemetry through to dashboard visibility and alert creation) before expanding to additional machines, plants, or telemetry parameter types. This validates all architectural decisions through working behavior before scaling scope.
- Local development uses Docker Compose with EMQX, PostgreSQL, Redis, InfluxDB, WAHA, Spring Boot, and Next.js as containerized services.
- Backend integration tests shall use Testcontainers for PostgreSQL and Redis with Spring Boot @ServiceConnection for auto-configuration.

## Mobile UX Notes

- Alert acknowledgement and resolution workflows should be optimized for mobile use by field technicians, considering that primary interaction may occur via WhatsApp deep links or mobile browser.
- Consumer-grade UX is critical for technician adoption; complex dashboards should not be the only interaction path for field workers.

## WAHA Risk Notes

- WAHA WhatsApp notification is best-effort, not safety-critical machine control.
- Unofficial WhatsApp client/bot use may lead to account blocking. Official WhatsApp Business API is recommended for critical business messaging in production.
- WAHA should not sit in the machine telemetry ingestion path. Use it only for human notification workflows.
