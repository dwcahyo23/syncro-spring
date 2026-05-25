---
stepsCompleted: [1, 2, 3, 4, 5, 6]
inputDocuments: []
workflowType: 'research'
lastStep: 1
research_type: 'technical'
research_topic: 'Next.js, Spring Boot, OpenJDK 25, Maven, MQTT, EMQX, Redis, PostgreSQL, InfluxDB end-to-end machine data architecture'
research_goals: 'Design an end-to-end architecture to connect machines via MQTT, ingest data through Java Spring Boot, use EMQX/MQTT messaging, persist telemetry/time-series data in InfluxDB, use PostgreSQL for relational data, Redis for caching/state, and Next.js for web UI.'
user_name: 'Yusuf'
date: '2026-05-25'
web_research_enabled: true
source_verification: true
---

# Research Report: technical

**Date:** 2026-05-25
**Author:** Yusuf
**Research Type:** technical

---

## Research Overview

This technical research evaluates an end-to-end machine data platform architecture using Next.js, Spring Boot, OpenJDK 25/Eclipse Temurin, Maven, MQTT, EMQX, Redis, PostgreSQL, and InfluxDB. The research goal is to connect industrial machines through MQTT, ingest and validate telemetry through Java Spring Boot, persist time-series data into InfluxDB, maintain relational master/configuration data in PostgreSQL, use Redis for hot state/cache, and expose operator workflows through a Next.js web UI.

The strongest architectural conclusion is a hybrid event-driven and layered web architecture: EMQX handles MQTT ingress and device connectivity, Spring Boot owns validation and write orchestration, InfluxDB stores high-volume telemetry, PostgreSQL stores system-of-record relational data, Redis accelerates hot state reads, and Next.js serves UI/BFF responsibilities. Full synthesis, risks, implementation roadmap, and source documentation appear in the Research Synthesis section near the end of this document.

The research used current public documentation from Spring, EMQX, MQTT/OASIS, InfluxDB, Redis, PostgreSQL, Next.js, Maven, Adoptium, Kubernetes, OpenTelemetry, Docker, GitHub Actions, Microsoft Azure Architecture Center, and AWS Well-Architected Framework. Claims are source-cited and confidence is highest where official vendor or standards documentation was available.

---

## Technical Research Scope Confirmation

**Research Topic:** Next.js, Spring Boot, OpenJDK 25, Maven, MQTT, EMQX, Redis, PostgreSQL, InfluxDB end-to-end machine data architecture
**Research Goals:** Design an end-to-end architecture to connect machines via MQTT, ingest data through Java Spring Boot, use EMQX/MQTT messaging, persist telemetry/time-series data in InfluxDB, use PostgreSQL for relational data, Redis for caching/state, and Next.js for web UI.

**Technical Research Scope:**

- Architecture Analysis - design patterns, frameworks, system architecture
- Implementation Approaches - development methodologies, coding patterns
- Technology Stack - languages, frameworks, tools, platforms
- Integration Patterns - APIs, protocols, interoperability
- Performance Considerations - scalability, optimization, patterns

**Research Methodology:**

- Current web data with rigorous source verification
- Multi-source validation for critical technical claims
- Confidence level framework for uncertain information
- Comprehensive technical coverage with architecture-specific insights

**Scope Confirmed:** 2026-05-25

## Technology Stack Analysis

### Programming Languages

The core backend language is Java on OpenJDK 25 through Eclipse Temurin. Adoptium shows JDK 25 as an Eclipse Temurin/OpenJDK release line and marks it as **JDK 25 - LTS**, but its queried releases page also returned **No releases found** for current filter criteria. Treat OpenJDK 25 availability as source-dependent and verify exact OS/architecture artifacts before locking CI images or production base images.

The frontend language/runtime layer is TypeScript/JavaScript with React through Next.js. Next.js documentation defines Next.js as a React framework for full-stack web applications and positions App Router as the newer router with React Server Components support.

_Popular Languages: Java for Spring Boot services; TypeScript/JavaScript for Next.js UI._
_Emerging Languages: None required for this architecture; optional Python appears inside InfluxDB 3 plugins/triggers, not as main application language._
_Language Evolution: Java 25 should be handled as target runtime choice with compatibility verification across Spring Boot, Maven plugins, container images, and deployment platform._
_Performance Characteristics: Java fits long-running ingest services and database integration; TypeScript/React fits operator dashboards and browser-facing UX._
_Source: https://adoptium.net/temurin/releases/?version=25, https://nextjs.org/docs_

### Development Frameworks and Libraries

Spring Boot is the main backend framework. Spring describes it as a way to create standalone, production-grade Spring applications that can run directly, with embedded servers, starter dependencies, auto-configuration, metrics, health checks, and externalized configuration. The Spring Boot docs index shows current stable docs including 4.0.6 and 3.x maintained lines, with production-ready Actuator features such as health, metrics, tracing, Prometheus, logging, and management endpoints.

Next.js is the web framework for UI and backend-for-frontend patterns. For new UI work, App Router is the likely default because docs describe it as newer and supporting Server Components. Route Handlers, data fetching, caching, revalidation, environment variables, and rewrites are relevant for consuming Spring Boot APIs safely.

_Major Frameworks: Spring Boot for backend APIs/ingestion; Next.js App Router for web UI and BFF layer._
_Micro-frameworks: Spring Boot starters and Spring Integration/MQTT libraries can be evaluated in later integration steps._
_Evolution Trends: Spring Boot 4.x exists as current stable docs line; Spring Boot 3.5/3.4 remain relevant for ecosystem stability. Next.js App Router is current default for new applications._
_Ecosystem Maturity: Spring Boot, Maven, PostgreSQL, Redis, and Next.js all have mature docs and broad ecosystem support._
_Source: https://spring.io/projects/spring-boot, https://docs.spring.io/spring-boot/index.html, https://nextjs.org/docs_

### Database and Storage Technologies

InfluxDB 3 Core is the time-series/event telemetry store. Its docs describe it as built for event and time-series data, near real-time ingest, monitoring, dashboards, UI, and automation. It stores data as Parquet files, supports InfluxDB 1.x and 2.x write APIs, and embeds Python VM plugins/triggers. This fits high-volume machine telemetry, sensor readings, and dashboard queries.

PostgreSQL is the relational system of record for master data, users, devices, machine metadata, configuration, alarms/events requiring relational joins, audit data, and application state. PostgreSQL 18 docs cover SQL, schemas, constraints, JSON/JSONB, indexes, WAL, backup/restore, replication, auth, roles, monitoring, and concurrency.

Redis is the in-memory/cache layer. Redis docs cover cache usage, core data structures, Streams, pub/sub, JSON, CLI, Insight, Java clients such as Jedis/Lettuce, and deployment options including Redis Open Source, Redis Cloud, Redis Software, and Kubernetes Redis Enterprise. In this architecture Redis should be used for hot device state, short-lived dashboard cache, rate limits, locks, and possibly transient event buffering—not as authoritative telemetry storage.

_Relational Databases: PostgreSQL for normalized application/business data and transactional consistency._
_NoSQL Databases: Redis JSON can support specific cache/document use cases, but not primary system of record here._
_In-Memory Databases: Redis for cache, session/state, pub/sub/streams, and low-latency lookup._
_Data Warehousing/Time-Series: InfluxDB 3 Core for telemetry and time-series queries._
_Source: https://docs.influxdata.com/influxdb3/core/, https://www.postgresql.org/docs/current/, https://redis.io/docs/latest/_

### Development Tools and Platforms

Maven is the Java build and dependency management tool. Apache Maven describes it as a build tool for Java projects using a Project Object Model (POM) to manage compilation, testing, and documentation. Maven lifecycle commands such as compile, test, package, and install fit Spring Boot service builds, CI, and reproducible dependency management.

Spring Boot Maven Plugin is documented by Spring Boot for packaging executable archives, OCI images, running apps with Maven, AOT processing, integration tests, and Actuator integration. For this stack, Maven should own backend compile/test/package and container-oriented build integration.

Next.js manages frontend bundling/compiler defaults and exposes production deployment docs, self-hosting, caching, memory usage, package bundling, Turbopack, and config options.

_IDE and Editors: No tool lock-in; Java IDE + TypeScript editor support sufficient._
_Version Control: Git remains assumed standard; not researched as differentiator._
_Build Systems: Maven for backend; Next.js/npm-compatible tooling for frontend._
_Testing Frameworks: Spring Boot test ecosystem for backend; Next.js/React testing choices should be selected during implementation planning._
_Source: https://maven.apache.org/, https://docs.spring.io/spring-boot/index.html, https://nextjs.org/docs_

### Cloud Infrastructure and Deployment

EMQX is the MQTT broker layer. EMQX docs state support for MQTT 5.0 and 3.x, native distributed clustering, high availability, horizontal scaling, TLS/SSL, username/password, JWT, PSK, X.509 certificates, ACL authorization, rule engine, webhooks, and integrations with many systems. For machine connectivity, EMQX should sit at the device ingress boundary and isolate devices from backend services.

Spring Boot services can run as executable jars, traditional WARs, or container images. Spring Boot docs cover embedded servers, production Actuator, container images, Dockerfiles, buildpacks, and deployment guidance.

Next.js can be self-hosted or deployed through platforms that support its rendering and caching model. For this architecture, it should call Spring Boot APIs and avoid direct database access.

Container deployment with Docker Compose for development and Kubernetes/container orchestration for production is a natural fit, but exact platform choice needs later deployment research.

_Major Cloud Providers: Not selected yet; architecture remains provider-neutral._
_Container Technologies: Spring Boot and Next.js both support containerized deployment patterns; EMQX, Redis, PostgreSQL, and InfluxDB also commonly run as managed services or containers._
_Serverless Platforms: Not primary fit for long-lived MQTT ingestion services; possible only for frontend or auxiliary jobs._
_CDN and Edge Computing: Useful for Next.js static assets and UI delivery, not for MQTT ingestion path._
_Source: https://docs.emqx.com/en/emqx/latest/, https://docs.spring.io/spring-boot/index.html, https://nextjs.org/docs_

### Technology Adoption Trends

For this machine-data platform, trend is not one database for all data. Use purpose-specific storage: EMQX for MQTT ingress, Spring Boot for controlled domain logic, InfluxDB for telemetry/time-series, PostgreSQL for relational application data, Redis for hot state/cache, and Next.js for operator-facing UI.

The strongest architecture choice is separation of concerns: MQTT broker handles device connectivity; backend controls validation/enrichment/routing; InfluxDB stores high-volume timestamped measurements; PostgreSQL stores durable relational context; Redis accelerates read paths and transient state; Next.js renders UI and calls backend APIs.

_Migration Patterns: Move from direct machine-to-database writes toward broker-mediated ingestion and backend-controlled persistence._
_Emerging Technologies: InfluxDB 3's Parquet/object-storage architecture and embedded plugin model are notable for telemetry workloads._
_Legacy Technology: Direct polling-only architectures and single relational database telemetry storage become limiting as device count and sampling rate grow._
_Community Trends: Mature mainstream stack with strong documentation; main risk is version compatibility, especially OpenJDK 25 with chosen Spring Boot line and dependencies._
_Source: https://docs.emqx.com/en/emqx/latest/, https://docs.influxdata.com/influxdb3/core/, https://www.postgresql.org/docs/current/, https://redis.io/docs/latest/_

## Integration Patterns Analysis

### API Design Patterns

Use REST/HTTP APIs between Next.js and Spring Boot for operator UI, admin screens, reports, configuration, device registry, alarm views, and dashboard queries. Next.js Route Handlers support `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, and `OPTIONS`, and use Web `Request`/`Response` APIs. This makes them suitable as a thin backend-for-frontend layer when secrets, cookies, or same-origin browser calls need to be handled in Next.js.

Spring Boot should expose the authoritative application API. Next.js should not talk directly to PostgreSQL, InfluxDB, Redis, or EMQX management APIs unless there is a deliberate admin integration. Direct UI-to-database access increases security and coupling risk.

_RESTful APIs: Primary UI/backend integration style for this architecture._
_GraphQL APIs: Not required for initial platform; consider only if UI query flexibility becomes painful._
_RPC and gRPC: Not needed for browser-facing UI; possible later for internal high-throughput service-to-service calls._
_Webhook Patterns: EMQX supports webhook/HTTP-style integrations through data integration routes; use carefully for coarse integration, not primary telemetry persistence if Spring Boot owns validation._
_Source: https://nextjs.org/docs/app/building-your-application/routing/route-handlers, https://docs.spring.io/spring-boot/index.html, https://docs.emqx.com/en/emqx/latest/data-integration/rules.html_

### Communication Protocols

MQTT is the machine-to-platform protocol. MQTT 5.0 is the current OASIS Standard, with MQTT 3.1.1 also standardized. EMQX docs state support for MQTT 5.0 and 3.x, plus clustering, security, rule engine, and integrations. For new deployments, MQTT 5.0 is preferred when client libraries/devices support it; MQTT 3.1.1 remains compatibility fallback.

Spring Integration MQTT provides inbound and outbound adapters for MQTT v3 and MQTT v5 using Eclipse Paho clients. Inbound adapters subscribe to topics and emit Spring Integration messages; outbound adapters publish Spring Integration messages to MQTT. It supports topic lists, QoS settings, raw byte payloads, manual acknowledgments, reconnect behavior, shared client support, and MQTT v5 header/property mapping.

_HTTP/HTTPS Protocols: Use for Next.js ↔ Spring Boot APIs and Spring Boot → InfluxDB HTTP write API._
_WebSocket Protocols: Optional for live dashboards; use only if polling/server rendering cannot meet UX latency needs._
_Message Queue Protocols: MQTT via EMQX is primary device messaging protocol._
_gRPC and Protocol Buffers: Optional future internal optimization, not baseline._
_Source: https://mqtt.org/mqtt-specification/, https://docs.emqx.com/en/emqx/latest/, https://docs.spring.io/spring-integration/reference/mqtt.html, https://docs.influxdata.com/influxdb3/core/write-data/_

### Data Formats and Standards

Machine payloads should use a strict JSON schema or binary schema. JSON is easier for early integration and EMQX rule processing; binary formats reduce bandwidth but add tooling complexity. For InfluxDB writes, Spring Boot should convert validated telemetry to line protocol and write using InfluxDB 3 `/api/v3/write_lp` or compatible client libraries. InfluxDB line protocol needs table, optional tags, field set, and optional timestamp; timestamp precision must be set correctly when not nanoseconds.

PostgreSQL should store relational metadata and JSONB only where flexible metadata is needed. Redis can cache JSON-like state and expose pub/sub/streams patterns, but Redis should not become source of truth for telemetry or configuration.

_JSON and XML: JSON preferred for MQTT payloads and REST APIs; XML not recommended unless machines require it._
_Protobuf and MessagePack: Consider later for constrained bandwidth or strict binary contracts._
_CSV and Flat Files: Useful for import/export only, not live ingestion._
_Custom Data Formats: Avoid until device constraints force them; every custom payload needs versioning and decoder tests._
_Source: https://docs.influxdata.com/influxdb3/core/write-data/, https://www.postgresql.org/docs/current/, https://redis.io/docs/latest/_

### System Interoperability Approaches

Recommended flow: machines publish MQTT messages to EMQX; Spring Boot subscribes to validated topic filters; backend validates and enriches payloads; telemetry writes to InfluxDB; relational updates/events write to PostgreSQL; hot state and dashboard cache update Redis; Next.js calls Spring Boot or Next.js Route Handlers proxy Spring Boot.

EMQX Rule Engine can extract, filter, enrich, transform, and route MQTT messages. It can republish messages, output logs, and forward to sinks such as MQTT services, Kafka, PostgreSQL, and HTTP/Webhook integrations. Still, for business-critical persistence, Spring Boot should own validation, idempotency, and write orchestration unless EMQX rules are deliberately used as low-code routing layer.

_Point-to-Point Integration: Avoid machine-to-backend direct TCP/HTTP where MQTT broker gives better decoupling._
_API Gateway Patterns: Use Next.js Route Handlers or reverse proxy for UI-facing API boundary; EMQX remains device ingress gateway._
_Service Mesh: Not required initially; add only if Kubernetes service-to-service observability/security demands it._
_Enterprise Service Bus: Not needed; EMQX + Spring Boot integration is simpler._
_Source: https://docs.emqx.com/en/emqx/latest/data-integration/rules.html, https://docs.spring.io/spring-integration/reference/mqtt.html, https://nextjs.org/docs/app/building-your-application/routing/route-handlers_

### Microservices Integration Patterns

Start with a small set of services, not many microservices. A practical baseline is one Spring Boot ingestion/API service, one Next.js web app, EMQX broker, Redis, PostgreSQL, and InfluxDB. Split services later when load, ownership, or deployment cadence forces it.

If separated later, likely split points are: ingestion service, device registry/config service, alerting/rules service, reporting/query service, and notification service. Use REST internally first unless high-throughput streaming between backend services justifies message bus or gRPC.

_API Gateway Pattern: Next.js/BFF or edge reverse proxy for browser APIs; EMQX for device MQTT ingress._
_Service Discovery: Docker Compose DNS in dev; Kubernetes service discovery if deployed to Kubernetes._
_Circuit Breaker Pattern: Needed around database writes, EMQX connectivity, and external integrations once implementation begins._
_Saga Pattern: Not central for telemetry ingest; use for cross-system commands like remote machine config changes if they become transactional workflows._
_Source: https://docs.spring.io/spring-boot/index.html, https://docs.emqx.com/en/emqx/latest/, https://nextjs.org/docs_

### Event-Driven Integration

The platform is event-driven at the ingress edge: devices publish events/measurements; EMQX distributes them; Spring Boot consumes subscribed topics. Use topic hierarchy to separate tenant/site/line/machine/data-type, and keep command/control topics separate from telemetry topics.

Redis can support cache invalidation, hot state, and lightweight pub/sub/streams patterns. However, EMQX should remain source for MQTT device messaging, and InfluxDB/PostgreSQL should remain durable stores. Redis use should be bounded by TTL, memory policy, and recovery behavior.

_Publish-Subscribe Patterns: MQTT topics provide device publish/subscribe decoupling._
_Event Sourcing: Not required for raw telemetry; InfluxDB stores time-series facts, not domain event replay by default._
_Message Broker Patterns: EMQX primary broker; add Kafka only if replay, long retention stream processing, or multi-consumer analytics outgrow MQTT + DB writes._
_CQRS Patterns: Useful read/write split: ingestion writes InfluxDB/PostgreSQL/Redis; UI reads optimized APIs and caches._
_Source: https://mqtt.org/mqtt-specification/, https://docs.emqx.com/en/emqx/latest/, https://redis.io/docs/latest/_

### Integration Security Patterns

Secure device ingress with EMQX TLS/SSL, username/password or stronger authentication, JWT where appropriate, X.509 certificates for stronger device identity, and ACL-based publish/subscribe authorization. EMQX docs also mention integrations with LDAP, HTTP, SQL, and NoSQL systems for authorization.

For UI/backend, use HTTPS, session/JWT auth as product requires, CSRF/CORS controls, and keep database credentials only in server-side components. Next.js Route Handlers can read cookies/headers and proxy backend calls, but secrets must stay server-side.

For service-to-service and database connections, prefer TLS where supported, least-privilege credentials, separate accounts per component, and rotation. Do not allow machines to publish arbitrary topics; topic ACLs must enforce identity-to-topic mapping.

_OAuth 2.0 and JWT: Good for human/API auth; EMQX also supports JWT auth patterns._
_API Key Management: Useful for service integrations, not ideal as sole machine identity at scale._
_Mutual TLS: Strong option for machine identity and service-to-service trust._
_Data Encryption: TLS for MQTT and HTTP; encrypted database connections/storage where deployment platform supports it._
_Source: https://docs.emqx.com/en/emqx/latest/, https://nextjs.org/docs/app/building-your-application/routing/route-handlers, https://www.postgresql.org/docs/current/_

## Architectural Patterns and Design

### System Architecture Patterns

Recommended baseline is a **hybrid event-driven + layered web architecture**. MQTT telemetry ingress is event-driven: machines publish events, EMQX brokers them, Spring Boot consumes them. Human/operator workflows remain request/response: Next.js calls Spring Boot APIs for dashboards, configuration, reports, and admin actions.

This follows event-driven architecture guidance: event producers generate streams, event consumers listen, and event channels/brokers transfer events. Event-driven systems decouple producers and consumers, support near-real-time processing, and fit high-volume IoT data. Main tradeoffs are eventual consistency, error handling complexity, ordering/exactly-once challenges, data loss risk, and harder observability across decoupled components.

Avoid over-splitting into microservices at start. Use a modular Spring Boot backend first, with clear internal modules for ingestion, device registry, telemetry persistence, alarm/rule evaluation, and query APIs. Split services only after load, team ownership, or deployment cadence proves need.

_Source: https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven, https://docs.emqx.com/en/emqx/latest/, https://docs.spring.io/spring-boot/index.html_

### Design Principles and Best Practices

Use separation of concerns as core design rule:

- EMQX owns MQTT connection management, subscriptions, broker clustering, topic routing, and device ingress security.
- Spring Boot owns validation, enrichment, idempotency, database writes, business rules, and APIs.
- InfluxDB owns telemetry/time-series storage and time-window queries.
- PostgreSQL owns relational system-of-record data.
- Redis owns hot state, cache, short-lived coordination, and transient read acceleration.
- Next.js owns UI rendering and optional backend-for-frontend routes.

Use cloud design patterns selectively. Relevant patterns from Azure Architecture Center include Backends for Frontends, Cache-Aside, Circuit Breaker, Competing Consumers, CQRS, Health Endpoint Monitoring, Publisher-Subscriber, Queue-Based Load Leveling, Retry, and Throttling. These patterns matter because distributed systems assumptions fail: network is not always reliable, latency is not zero, bandwidth is not infinite, topology changes, and observability cannot be delayed.

For this platform, design every message and API around correlation IDs, schema versions, timestamps, machine identity, and tenant/site/line context. This makes troubleshooting and future multi-tenant isolation possible.

_Source: https://learn.microsoft.com/en-us/azure/architecture/patterns/, https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven_

### Scalability and Performance Patterns

Scale each layer independently:

- EMQX scales MQTT connections horizontally via cluster nodes.
- Spring Boot scales consumers/API replicas horizontally, but topic partitioning and idempotency must avoid duplicate/ordered-processing bugs.
- InfluxDB scales telemetry writes/queries through schema design, retention, and database/table limits.
- PostgreSQL scales relational workload through indexes, connection pooling, query design, replication, and partitioning if needed.
- Redis scales hot reads and transient state, but memory and eviction policy become architectural constraints.
- Next.js scales UI traffic separately from ingest traffic.

Event-driven architecture fits high volume/high velocity IoT workloads, but delivery guarantees, order, and exactly-once semantics are hard. Design consumers idempotently. Store processed message identifiers or deterministic measurement keys where duplicate writes matter. Add retry/backoff around database writes. Use dead-letter or quarantine flow for invalid payloads.

EMQX clustering supports horizontal scale and HA; docs describe clusters as multiple nodes acting as one system sharing client sessions, subscriptions, and routing state. EMQX cluster network latency should stay under 10 ms, with cluster unavailable above 100 ms. Keep EMQX core nodes in same private network.

_Source: https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven, https://docs.emqx.com/en/emqx/latest/deploy/cluster/introduction.html, https://docs.influxdata.com/influxdb3/core/admin/databases/_

### Integration and Communication Patterns

Use broker topology for telemetry ingress: devices do not know backend consumers; Spring Boot subscribes to topic filters. Use mediator/orchestration only for controlled workflows such as remote commands, machine configuration updates, acknowledgments, or safety-critical state changes.

Topic design should encode routing context, not database schema. Example shape:

```text
site/{siteId}/line/{lineId}/machine/{machineId}/telemetry/{metricGroup}
site/{siteId}/line/{lineId}/machine/{machineId}/event/{eventType}
site/{siteId}/line/{lineId}/machine/{machineId}/command/{commandType}
site/{siteId}/line/{lineId}/machine/{machineId}/command/{commandId}/ack
```

Keep telemetry topics separate from command/control topics. Apply EMQX ACLs so each machine can publish/subscribe only allowed topics. Spring Boot should normalize payloads into internal DTOs before database writes.

Next.js should integrate through HTTP APIs. For live dashboards, choose polling first if latency tolerance allows; add WebSocket/SSE only when required.

_Source: https://mqtt.org/mqtt-specification/, https://docs.emqx.com/en/emqx/latest/, https://docs.spring.io/spring-integration/reference/mqtt.html, https://nextjs.org/docs/app/building-your-application/routing/route-handlers_

### Security Architecture Patterns

Security boundary starts at EMQX. Use TLS for MQTT, per-device credentials or certificates, ACLs for topic authorization, and separate listener profiles when device classes differ. EMQX supports TLS/SSL, username/password, JWT, PSK, X.509 certificates, ACL-based publish/subscribe authorization, and integration with LDAP/HTTP/SQL/NoSQL auth sources.

Use zero-trust-ish component boundaries: Next.js never gets database credentials; Spring Boot uses least-privilege database users; EMQX admin/API access stays internal; Redis is not exposed publicly; PostgreSQL and InfluxDB are private network resources.

Avoid leaking sensitive payload data into broadly visible events. Event-driven architecture docs warn events may be visible to multiple components even if not all should consume them. Treat MQTT payloads as potentially shared inside platform and keep secrets out of telemetry messages.

_Source: https://docs.emqx.com/en/emqx/latest/, https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven, https://www.postgresql.org/docs/current/_

### Data Architecture Patterns

Use purpose-built stores:

- InfluxDB: raw and processed time-series measurements.
- PostgreSQL: device registry, users, roles, sites/lines/machines, configuration, alarm definitions, audit logs, relational events.
- Redis: latest machine state, dashboard summary cache, short-lived locks, rate limits, transient command status.

InfluxDB 3 database design matters. Docs describe a database as named time-series storage; retention is part of the database concept, default retention has no expiration, minimum practical retention is 1h, Core limit is 5 databases, max 2000 tables across all databases, and max 500 columns per table. Many focused tables can improve query pruning/performance but increase object-store PUT cost; wide schemas above column limit hurt performance/resource use.

Recommended data split: use measurement/table per coherent metric group, tags for dimensions with bounded cardinality, fields for numeric/string values, and retention by database where lifecycle differs. Do not put every unique serial/event ID as a high-cardinality tag unless query patterns require it.

_Source: https://docs.influxdata.com/influxdb3/core/admin/databases/, https://docs.influxdata.com/influxdb3/core/write-data/, https://www.postgresql.org/docs/current/, https://redis.io/docs/latest/_

### Deployment and Operations Architecture

For development, Docker Compose can run EMQX, PostgreSQL, Redis, InfluxDB, Spring Boot, and Next.js. For production, use containerized deployment or managed services depending team operations capacity.

Kubernetes is appropriate when production needs declarative deployment, self-healing, service discovery, load balancing, automated rollouts/rollbacks, horizontal scaling, secret/config management, and storage orchestration. Kubernetes docs define it as a platform for managing containerized workloads and services, and say it handles scaling, failover, deployment patterns, service discovery, load balancing, self-healing, and configuration/secret management.

Use Spring Boot Actuator for health, metrics, tracing, Prometheus endpoint, logging, and management endpoints. The architecture should be evaluated against well-architected pillars: operational excellence, security, reliability, performance efficiency, cost optimization, and sustainability.

_Source: https://kubernetes.io/docs/concepts/overview/, https://docs.spring.io/spring-boot/index.html, https://docs.aws.amazon.com/wellarchitected/latest/framework/welcome.html_

## Implementation Approaches and Technology Adoption

### Technology Adoption Strategies

Adopt this stack incrementally, not as a big-bang platform. Start with one vertical telemetry path: one machine type, one MQTT topic family, one Spring Boot consumer, one InfluxDB measurement/table group, one PostgreSQL device registry slice, one Redis latest-state cache, and one Next.js dashboard page. Expand only after payload contract, timestamp handling, idempotency, and observability are proven.

Recommended adoption order:

1. Local Docker Compose platform: EMQX, InfluxDB, PostgreSQL, Redis, Spring Boot, Next.js.
2. MQTT topic and payload contract for one machine class.
3. Spring Boot ingestion service with MQTT subscribe, validation, and structured errors.
4. InfluxDB write path using Java client or HTTP line protocol.
5. PostgreSQL registry/config schema.
6. Redis latest-state/cache.
7. Next.js dashboard through Spring Boot API.
8. Observability and CI hardening.
9. Production deployment design.

_Source: https://docs.docker.com/compose/, https://docs.spring.io/spring-integration/reference/mqtt.html, https://docs.influxdata.com/influxdb3/core/write-data/client-libraries/_

### Development Workflows and Tooling

Use Maven as backend build system and keep all backend dependencies explicit in `pom.xml`. Required implementation candidates include Spring Boot web/API dependencies, Spring Integration MQTT, InfluxDB 3 Java client, PostgreSQL driver/data access, Redis client/starter, Actuator, and testing dependencies.

InfluxDB docs show Java client dependency:

```xml
<dependency>
  <groupId>com.influxdb</groupId>
  <artifactId>influxdb3-java</artifactId>
  <version>1.1.0</version>
</dependency>
```

Spring Integration MQTT docs show dependency:

```xml
<dependency>
  <groupId>org.springframework.integration</groupId>
  <artifactId>spring-integration-mqtt</artifactId>
  <version>7.0.4</version>
</dependency>
```

For MQTT v3/v5, Spring Integration requires explicit Eclipse Paho dependencies. For MQTT v5, use MQTT v5 adapters and configure reconnect behavior, QoS, manual acknowledgments where needed, and error channels.

Use GitHub Actions or similar CI to run Maven verification. GitHub docs describe Java with Maven workflows using `actions/checkout`, `actions/setup-java`, Maven cache, and `mvn --batch-mode --update-snapshots verify`.

_Source: https://maven.apache.org/, https://docs.spring.io/spring-integration/reference/mqtt.html, https://docs.influxdata.com/influxdb3/core/write-data/client-libraries/, https://docs.github.com/en/actions/use-cases-and-examples/building-and-testing/building-and-testing-java-with-maven_

### Testing and Quality Assurance

Backend test layers:

- Unit tests for payload parsing, validation, timestamp normalization, line protocol mapping, and topic parsing.
- Integration tests for PostgreSQL/Redis/InfluxDB writes using containerized services where possible.
- MQTT integration tests against EMQX or a test broker.
- Idempotency tests for duplicate MQTT messages.
- Failure tests for database outage, invalid payload, malformed timestamp, authorization failure, and reconnect behavior.

Spring Boot docs state `spring-boot-starter-test` imports Spring Boot test modules, JUnit Jupiter, AssertJ, Hamcrest, and useful focused test modules. Testcontainers support appears in Spring Boot testing navigation and should be used when real PostgreSQL/Redis/broker behavior matters.

Next.js docs define unit, component, integration, E2E, and snapshot testing. They recommend E2E over unit testing for async Server Components where tool support is incomplete. Use Playwright or Cypress for dashboard golden paths.

_Source: https://docs.spring.io/spring-boot/reference/testing/index.html, https://nextjs.org/docs/app/guides/testing_

### Deployment and Operations Practices

Spring Boot Actuator should be included from day one. Spring docs state Actuator adds production-ready monitoring/management through HTTP endpoints or JMX, including health, metrics, observability, tracing, and Prometheus endpoint support.

Common dependency:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

Common exposure shape:

```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
```

EMQX observability should be enabled for broker health: dashboard, HTTP API metrics, system topics, alarms, Prometheus integration, Datadog integration, topic metrics, slow subscriptions, and log trace.

OpenTelemetry should be used as vendor-neutral telemetry model for traces, metrics, and logs. OpenTelemetry is not storage or visualization; send data to Prometheus/Grafana, Jaeger/Tempo, ELK/OpenSearch, or managed backend later.

_Source: https://docs.spring.io/spring-boot/reference/actuator/index.html, https://docs.emqx.com/en/emqx/latest/observability/overview.html, https://opentelemetry.io/docs/what-is-opentelemetry/_

### Team Organization and Skills

Minimum skill areas:

- MQTT/EMQX operations: topic design, ACLs, TLS, session behavior, clustering, monitoring.
- Java/Spring Boot: integration flows, async/error handling, Actuator, data access, testing.
- Time-series modeling: InfluxDB line protocol, tags/fields, retention, query design.
- Relational modeling: PostgreSQL constraints, indexes, migrations, roles, backups.
- Redis operations: cache TTLs, memory policy, hot state design.
- Frontend: Next.js App Router, Route Handlers, dashboard UX, E2E testing.
- DevOps: Docker Compose, CI, secrets, observability, deployment rollback.

Keep ownership simple at first: one backend owner, one frontend owner, one platform/infra owner. Avoid separate microservice teams until service boundaries prove stable.

_Source: https://docs.emqx.com/en/emqx/latest/, https://docs.spring.io/spring-boot/index.html, https://docs.influxdata.com/influxdb3/core/, https://nextjs.org/docs_

### Cost Optimization and Resource Management

Avoid premature production complexity. Use Docker Compose locally, then choose managed services or Kubernetes based on operations capacity. Managed PostgreSQL/Redis/InfluxDB/EMQX can reduce operational load; self-hosted containers reduce direct service cost but increase maintenance burden.

Cost hotspots:

- MQTT connection count and message throughput.
- InfluxDB write volume, retention period, table/schema design, object-store PUT count if object storage used.
- PostgreSQL indexes and query load.
- Redis memory usage and eviction behavior.
- Next.js SSR/server compute and dashboard polling frequency.
- Observability cardinality from labels/tags.

InfluxDB docs warn many focused tables may help query pruning/performance but can raise object-store PUT cost; wide schemas above column limit hurt performance/resource use. Retention should match business need.

_Source: https://docs.influxdata.com/influxdb3/core/admin/databases/, https://kubernetes.io/docs/concepts/overview/, https://docs.aws.amazon.com/wellarchitected/latest/framework/welcome.html_

### Risk Assessment and Mitigation

Primary risks and mitigations:

- **OpenJDK 25 compatibility risk:** verify Adoptium artifact availability, Spring Boot line compatibility, Maven compiler config, container image support, and dependency test results before production lock.
- **Duplicate telemetry writes:** use idempotent processing and deterministic keys where required.
- **Payload schema drift:** include schema version in MQTT payloads and reject/route unknown versions.
- **Timestamp bugs:** normalize timezone/precision and test line protocol output.
- **High-cardinality Influx tags:** model tags carefully and load-test query/write patterns.
- **Redis becoming source of truth:** enforce TTL/cache-only policy for Redis data.
- **EMQX ACL gaps:** test topic authorization per device identity.
- **Observability gaps:** require correlation ID across MQTT message, Spring logs/traces, DB writes, and UI request.
- **Async failure invisibility:** implement invalid-message quarantine/dead-letter pattern and alerting.

_Source: https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven, https://docs.emqx.com/en/emqx/latest/, https://opentelemetry.io/docs/what-is-opentelemetry/_

## Technical Research Recommendations

### Implementation Roadmap

Phase 1: local platform skeleton with Docker Compose, EMQX, PostgreSQL, Redis, InfluxDB, Spring Boot, Next.js.

Phase 2: single machine telemetry ingestion path with MQTT topic contract, payload schema, Spring MQTT consumer, validation, and InfluxDB write.

Phase 3: PostgreSQL device registry and Redis latest-state cache.

Phase 4: Next.js dashboard through Spring Boot APIs.

Phase 5: observability baseline with Actuator, EMQX metrics, OpenTelemetry, structured logs, alerts.

Phase 6: production hardening: TLS, ACLs, secrets, backups, retention, load tests, CI/CD, deployment rollback.

### Technology Stack Recommendations

Use this baseline:

- Next.js App Router for UI.
- Spring Boot backend with Maven.
- Eclipse Temurin/OpenJDK 25 only after compatibility validation; otherwise use latest supported LTS that Spring Boot/dependencies validate in CI.
- EMQX MQTT 5.0 broker with MQTT 3.1.1 fallback if device support requires.
- InfluxDB 3 Core for telemetry.
- PostgreSQL for relational/system-of-record data.
- Redis for cache/hot state only.
- Docker Compose for local dev; Kubernetes or managed services for production depending ops maturity.

### Skill Development Requirements

Prioritize:

1. MQTT topic/ACL/session/QoS fundamentals.
2. Spring Integration MQTT and error handling.
3. InfluxDB line protocol/schema/retention.
4. PostgreSQL relational design and migrations.
5. Redis cache correctness and TTL design.
6. Next.js App Router + Route Handlers.
7. Observability with Actuator, EMQX metrics, OpenTelemetry.
8. CI/CD with Maven and frontend tests.

### Success Metrics and KPIs

Track technical success through:

- MQTT messages ingested per second.
- End-to-end latency: machine publish → dashboard visible.
- Ingestion success/error/quarantine rate.
- Duplicate processing rate.
- InfluxDB write latency and query latency.
- PostgreSQL slow queries and connection pool saturation.
- Redis hit ratio and memory usage.
- EMQX connected clients, publish/subscribe rates, dropped/disconnected clients.
- Spring Boot health, JVM metrics, error rate, trace latency.
- Dashboard load time and E2E test pass rate.

# From Machine Signals to Actionable Operations: Comprehensive Next.js, Spring Boot, OpenJDK 25, MQTT/EMQX, Redis, PostgreSQL, and InfluxDB Technical Research

## Executive Summary

This research concludes that the target platform should be built as a **hybrid event-driven + layered web architecture**. Machine data should enter through MQTT into EMQX, be consumed and validated by Spring Boot, persisted into InfluxDB for telemetry and PostgreSQL for relational system-of-record data, cached in Redis for latest state and low-latency dashboard reads, and presented through Next.js. This separation gives each technology a clear role and avoids turning PostgreSQL, Redis, EMQX rules, or Next.js into inappropriate catch-all layers.

The most important design decision is where business authority lives. EMQX should broker and secure MQTT traffic, but Spring Boot should own validation, idempotency, enrichment, persistence orchestration, and API contracts. InfluxDB should store high-volume timestamped measurements; PostgreSQL should store devices, sites, users, configuration, alarm definitions, audit records, and transactional relational data; Redis should be treated as cache/hot state only. This pattern reduces coupling and gives the platform a path from local prototype to production deployment.

OpenJDK 25 should be treated as a deliberate compatibility decision. Adoptium shows JDK 25 as a Temurin/OpenJDK line and marks it as LTS, but queried release filtering returned no matching release artifacts. Before locking production runtime images, verify OS/architecture artifacts, Spring Boot compatibility, Maven compiler configuration, container base images, and all runtime dependencies in CI.

**Key Technical Findings:**

- Event-driven MQTT ingress is the right pattern for machine telemetry, but requires idempotency, dead-letter/quarantine handling, correlation IDs, schema versioning, and observability from day one.
- EMQX fits device connectivity, MQTT 5.0/3.x support, clustering, ACLs, TLS, rule engine, and observability, but business-critical persistence should remain in Spring Boot unless deliberately delegated.
- InfluxDB 3 Core fits time-series telemetry, but schema design, retention, table/column limits, and tag cardinality are core architecture decisions, not implementation details.
- PostgreSQL and Redis should have strict roles: PostgreSQL as relational source of truth; Redis as cache/latest-state/transient accelerator.
- Next.js should consume backend APIs and optionally provide BFF Route Handlers; it should not access databases directly.
- Production readiness depends more on operations discipline—testing, monitoring, auth, TLS, backups, retention, CI/CD—than on framework selection.

**Technical Recommendations:**

1. Build one complete vertical slice before broad platform expansion.
2. Use EMQX + Spring Integration MQTT for broker-to-backend ingestion.
3. Use InfluxDB Java client or HTTP line protocol for telemetry writes.
4. Keep PostgreSQL schema normalized for master/configuration data.
5. Use Redis only for cached/latest/transient data with TTLs.
6. Add Actuator, EMQX metrics, and OpenTelemetry early.
7. Validate OpenJDK 25 artifact/runtime compatibility before production commitment.

## Table of Contents

1. Technical Research Introduction and Methodology
2. Technical Landscape and Architecture Analysis
3. Implementation Approaches and Best Practices
4. Technology Stack Evolution and Current Trends
5. Integration and Interoperability Patterns
6. Performance and Scalability Analysis
7. Security and Compliance Considerations
8. Strategic Technical Recommendations
9. Implementation Roadmap and Risk Assessment
10. Future Technical Outlook and Innovation Opportunities
11. Technical Research Methodology and Source Verification
12. Technical Appendices and Reference Materials

## 1. Technical Research Introduction and Methodology

### Technical Research Significance

Industrial machine data platforms fail when all data flows are treated the same. Telemetry, configuration, user actions, alarms, command acknowledgments, dashboard summaries, and audit records have different consistency, latency, retention, and query requirements. A robust architecture separates these concerns while preserving traceability from physical machine event to UI decision.

The chosen stack is technically coherent: MQTT/EMQX handles machine ingress, Spring Boot handles domain logic and integration, InfluxDB handles telemetry/time-series storage, PostgreSQL handles relational system-of-record data, Redis handles low-latency hot state, and Next.js handles operator UI. This creates a practical path from prototype to production without prematurely adopting a large microservices platform.

_Technical Importance: Current machine telemetry systems need high-volume ingest, low-latency visibility, durable relational context, secure device identity, and operational observability._
_Business Impact: Correct architecture enables faster troubleshooting, machine monitoring, historical analysis, operator dashboards, and future alerting/automation._
_Source: https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven, https://docs.emqx.com/en/emqx/latest/, https://docs.influxdata.com/influxdb3/core/_

### Technical Research Methodology

This research used official documentation and architecture references as primary sources. Vendor docs were preferred for product capabilities, standards docs for MQTT protocol facts, and cloud architecture references for system design patterns.

- **Technical Scope:** End-to-end machine data architecture, technology stack, integration patterns, architecture patterns, implementation workflow, testing, deployment, observability, cost/risk.
- **Data Sources:** Spring Boot, Spring Integration MQTT, EMQX, MQTT/OASIS, InfluxDB, PostgreSQL, Redis, Next.js, Maven, Adoptium, Kubernetes, OpenTelemetry, Docker, GitHub Actions, Azure Architecture Center, AWS Well-Architected.
- **Analysis Framework:** Separation of concerns, event-driven architecture tradeoffs, source-of-truth boundaries, operational readiness, scalability, security, and implementation risk.
- **Time Period:** Current as of 2026-05-25.
- **Technical Depth:** Architecture-level decisions plus implementation-ready dependency and workflow guidance.

### Technical Research Goals and Objectives

**Original Technical Goals:** Design an end-to-end architecture to connect machines via MQTT, ingest data through Java Spring Boot, use EMQX/MQTT messaging, persist telemetry/time-series data in InfluxDB, use PostgreSQL for relational data, Redis for caching/state, and Next.js for web UI.

**Achieved Technical Objectives:**

- Defined component responsibilities across EMQX, Spring Boot, InfluxDB, PostgreSQL, Redis, and Next.js.
- Identified MQTT integration path through Spring Integration MQTT.
- Defined storage split between telemetry, relational data, and hot state.
- Identified operational requirements: metrics, health, tracing, logging, topic metrics, CI, testing, and local Docker Compose.
- Produced phased roadmap and risk register.

## 2. Technical Landscape and Architecture Analysis

### Current Technical Architecture Patterns

Recommended system shape:

```text
Machines/PLC/Edge Devices
  -> MQTT over TLS
  -> EMQX broker/cluster
  -> Spring Boot MQTT consumers
  -> validation/enrichment/idempotency
  -> InfluxDB telemetry writes
  -> PostgreSQL relational writes
  -> Redis latest-state/cache updates
  -> Spring Boot REST APIs
  -> Next.js UI/BFF
```

This is a hybrid architecture. Machine telemetry uses event-driven publish/subscribe. Human workflows use request/response HTTP APIs. This avoids forcing every interaction through asynchronous messaging and avoids using synchronous APIs for high-volume telemetry.

_Dominant Patterns: Event-driven ingress, layered backend, purpose-built persistence, BFF-capable frontend._
_Architectural Evolution: Start modular monolith/backend-first; split services only when operational need proves it._
_Architectural Trade-offs: Event-driven architecture improves decoupling and scalability but adds eventual consistency, ordering, and observability complexity._
_Source: https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven, https://docs.emqx.com/en/emqx/latest/deploy/cluster/introduction.html_

### System Design Principles and Best Practices

Core principles:

- Each component has one primary responsibility.
- Spring Boot remains business authority.
- Databases are selected by data shape and query pattern.
- MQTT topic contracts are versioned and secured.
- All telemetry events carry timestamp, schema version, machine identity, and correlation context.
- All persistence writes are observable and failure-aware.

Azure Cloud Design Patterns identify relevant patterns: Backends for Frontends, Cache-Aside, Circuit Breaker, Competing Consumers, CQRS, Health Endpoint Monitoring, Publisher-Subscriber, Queue-Based Load Leveling, Retry, and Throttling. These map directly to this architecture.

_Source: https://learn.microsoft.com/en-us/azure/architecture/patterns/_

## 3. Implementation Approaches and Best Practices

### Current Implementation Methodologies

Implement as a vertical slice:

1. One machine payload schema.
2. One MQTT topic family.
3. One Spring Boot MQTT inbound flow.
4. One InfluxDB telemetry write path.
5. One PostgreSQL device registry table set.
6. One Redis latest-state key pattern.
7. One Next.js dashboard page.
8. One CI workflow.
9. One observability baseline.

This reduces risk because every architectural decision is validated through working behavior.

_Development Approaches: Vertical-slice delivery, modular backend, source-cited contracts._
_Code Organization Patterns: Separate modules/packages for MQTT ingress, validation, persistence, API, and observability._
_Quality Assurance Practices: Unit tests for mapping/validation; integration tests for broker/database/cache; E2E tests for dashboard._
_Deployment Strategies: Docker Compose locally; managed services or Kubernetes later._
_Source: https://docs.spring.io/spring-integration/reference/mqtt.html, https://docs.influxdata.com/influxdb3/core/write-data/client-libraries/, https://docs.docker.com/compose/_

### Implementation Framework and Tooling

Backend:

- Java/OpenJDK through Eclipse Temurin after runtime validation.
- Spring Boot for API/application runtime.
- Spring Integration MQTT for MQTT inbound/outbound adapters.
- Maven for build/dependency lifecycle.
- InfluxDB 3 Java client for telemetry writes.
- PostgreSQL driver/data access.
- Redis client/starter.
- Spring Boot Actuator for production monitoring.

Frontend:

- Next.js App Router.
- Route Handlers for BFF/proxy use cases.
- Playwright/Cypress for E2E.
- Jest/Vitest for unit/component tests where appropriate.

_Source: https://maven.apache.org/, https://docs.spring.io/spring-boot/index.html, https://docs.spring.io/spring-integration/reference/mqtt.html, https://nextjs.org/docs_

## 4. Technology Stack Evolution and Current Trends

### Current Technology Stack Landscape

The stack is mainstream and mature, but OpenJDK 25 is the version-sensitive element. Spring Boot documentation shows 4.0.6 and maintained 3.x lines. Maven remains a standard Java build tool. Next.js provides modern React full-stack features. EMQX provides MQTT broker capabilities and clustering. InfluxDB 3 Core provides time-series/event storage. Redis and PostgreSQL remain stable infrastructure foundations.

_Programming Languages: Java backend, TypeScript/JavaScript frontend._
_Frameworks and Libraries: Spring Boot, Spring Integration MQTT, Next.js, InfluxDB Java client._
_Database and Storage Technologies: InfluxDB for telemetry, PostgreSQL for relational source of truth, Redis for cache/hot state._
_API and Communication Technologies: MQTT for machines, HTTP/REST for UI/backend, optional WebSocket/SSE later._
_Source: https://spring.io/projects/spring-boot, https://docs.spring.io/spring-boot/index.html, https://nextjs.org/docs, https://docs.emqx.com/en/emqx/latest/, https://docs.influxdata.com/influxdb3/core/_

### Technology Adoption Patterns

Adoption should move from local reproducible environment to vertical slice to hardening. Do not begin with large-scale clustering or Kubernetes unless deployment constraints require it. Validate contracts, schemas, idempotency, and observability first.

_Adoption Trends: Purpose-built databases and broker-mediated ingestion are favored over direct machine-to-database writes._
_Migration Patterns: Move from polling/direct writes toward MQTT broker + backend-controlled persistence._
_Emerging Technologies: InfluxDB 3 Parquet/object-storage architecture and OpenTelemetry standardization are relevant future-facing choices._
_Source: https://docs.influxdata.com/influxdb3/core/, https://opentelemetry.io/docs/what-is-opentelemetry/_

## 5. Integration and Interoperability Patterns

### Current Integration Approaches

MQTT is the device protocol. Spring Boot consumes MQTT using Spring Integration MQTT inbound adapters. Spring Boot writes InfluxDB line protocol through Java client or HTTP API. Spring Boot exposes HTTP APIs to Next.js. Redis and PostgreSQL remain private backend resources.

_API Design Patterns: REST/HTTP APIs for UI/backend; MQTT topic contracts for devices._
_Service Integration: Keep broker/backend/database integrations server-side._
_Data Integration: Transform device payloads into domain DTOs, InfluxDB points/line protocol, PostgreSQL rows, and Redis latest-state keys._
_Source: https://mqtt.org/mqtt-specification/, https://docs.spring.io/spring-integration/reference/mqtt.html, https://docs.influxdata.com/influxdb3/core/write-data/_

### Interoperability Standards and Protocols

Use MQTT 5.0 where device support exists; MQTT 3.1.1 as compatibility fallback. Use JSON payloads initially for inspectability. Add binary formats only after bandwidth, latency, or device constraints justify complexity.

_Standards Compliance: MQTT 5.0 current OASIS Standard; MQTT 3.1.1 older ISO/OASIS Standard._
_Protocol Selection: MQTT for device telemetry; HTTP/HTTPS for APIs and InfluxDB writes; database-native protocols only backend-side._
_Integration Challenges: Payload versioning, topic ACLs, timestamp precision, duplicate processing, reconnect behavior._
_Source: https://mqtt.org/mqtt-specification/, https://docs.emqx.com/en/emqx/latest/, https://docs.influxdata.com/influxdb3/core/write-data/_

## 6. Performance and Scalability Analysis

### Performance Characteristics and Optimization

Performance bottlenecks likely appear at:

- Machine publish rate and payload size.
- EMQX connection/message throughput.
- Spring Boot consumer concurrency and backpressure.
- InfluxDB write batching/schema design.
- PostgreSQL transaction/index/query behavior.
- Redis memory and key cardinality.
- Dashboard polling/rendering frequency.

InfluxDB writes require careful timestamp precision and line protocol design. EMQX cluster design requires low-latency private network. Event-driven consumers must be idempotent because retries and reconnects can produce duplicate processing.

_Performance Benchmarks: Product docs provide capability claims, but project-specific load tests are required before sizing._
_Optimization Strategies: Batch telemetry writes, keep tags bounded, cache dashboard summaries, index PostgreSQL query paths, avoid excessive UI polling._
_Monitoring and Measurement: Actuator metrics, EMQX metrics/topic metrics, Redis metrics, PostgreSQL slow queries, InfluxDB write/query latency._
_Source: https://docs.emqx.com/en/emqx/latest/deploy/cluster/introduction.html, https://docs.influxdata.com/influxdb3/core/admin/databases/, https://docs.spring.io/spring-boot/reference/actuator/index.html_

### Scalability Patterns and Approaches

Scale by layer, not as one monolith:

- EMQX cluster for device connections/message throughput.
- Multiple Spring Boot consumers/API replicas with idempotent handling.
- InfluxDB retention/schema tuned per telemetry workload.
- PostgreSQL indexes/connection pooling/replication where needed.
- Redis memory sizing and TTL policy.
- Next.js horizontal scale/CDN for UI assets.

_Scalability Patterns: Publisher-subscriber, competing consumers, cache-aside, queue-based load leveling, CQRS read/write separation._
_Capacity Planning: Estimate devices × messages/sec × payload size × retention × query patterns._
_Elasticity and Auto-scaling: Kubernetes can help scale services, but only after metrics and limits are known._
_Source: https://learn.microsoft.com/en-us/azure/architecture/patterns/, https://kubernetes.io/docs/concepts/overview/_

## 7. Security and Compliance Considerations

### Security Best Practices and Frameworks

Secure device ingress first:

- MQTT over TLS.
- Per-device credentials or X.509 certificates.
- EMQX ACLs mapping identity to allowed topics.
- Separate command/control topics from telemetry topics.
- No database credentials in devices or Next.js browser code.
- Internal-only EMQX admin/API, Redis, PostgreSQL, and InfluxDB.

EMQX supports TLS/SSL, username/password, JWT, PSK, X.509 certificates, ACL-based authorization, and external auth integrations.

_Security Frameworks: Use least privilege, encrypted transport, secure defaults, and well-architected security review._
_Threat Landscape: Unauthorized publish/subscribe, credential leakage, payload spoofing, command topic abuse, exposed databases, excessive CORS._
_Secure Development Practices: Validate all external payloads, reject unknown schema versions, protect secrets, add auth tests._
_Source: https://docs.emqx.com/en/emqx/latest/, https://docs.aws.amazon.com/wellarchitected/latest/framework/welcome.html_

### Compliance and Regulatory Considerations

No specific regulatory regime was selected in this workflow. If industrial or automotive compliance becomes relevant, architecture should add auditability, traceability, data retention policies, operator action logs, role-based access, backup evidence, and change management controls.

_Industry Standards: MQTT/OASIS protocol compliance; database backup/restore and security docs from PostgreSQL; cloud well-architected evaluation._
_Regulatory Compliance: To be determined by domain requirements._
_Audit and Governance: Store configuration changes, user actions, machine command requests, command acknowledgments, and security-relevant events in PostgreSQL._
_Source: https://mqtt.org/mqtt-specification/, https://www.postgresql.org/docs/current/, https://docs.aws.amazon.com/wellarchitected/latest/framework/welcome.html_

## 8. Strategic Technical Recommendations

### Technical Strategy and Decision Framework

Use this decision framework:

- If data is high-volume timestamped machine measurement, store in InfluxDB.
- If data describes business/entity relationships or durable configuration, store in PostgreSQL.
- If data is derived/hot/temporary, store in Redis with TTL.
- If data crosses machine boundary, use MQTT through EMQX.
- If data crosses UI boundary, use Spring Boot HTTP APIs or Next.js Route Handlers as BFF.
- If processing is business-critical, implement in Spring Boot rather than EMQX rules.

_Architecture Recommendations: Hybrid event-driven + layered web architecture._
_Technology Selection: Keep current selected stack, with OpenJDK 25 compatibility gate._
_Implementation Strategy: Vertical slice, then hardening, then scale._
_Source: https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven, https://docs.emqx.com/en/emqx/latest/data-integration/rules.html_

### Competitive Technical Advantage

Advantage comes from operational clarity: reliable data capture, fast dashboards, clean domain modeling, and traceable machine events. The stack is not exotic; value comes from disciplined boundaries, strong topic/schema design, and observability.

_Technology Differentiation: Purpose-built telemetry architecture instead of generic CRUD-only platform._
_Innovation Opportunities: Real-time alerting, predictive maintenance, anomaly detection, machine command workflows, OEE dashboards._
_Strategic Technology Investments: Telemetry schema discipline, observability, test automation, and secure device identity._
_Source: https://docs.influxdata.com/influxdb3/core/, https://opentelemetry.io/docs/what-is-opentelemetry/_

## 9. Implementation Roadmap and Risk Assessment

### Technical Implementation Framework

**Phase 1: Local foundation**

- Docker Compose for EMQX, InfluxDB, PostgreSQL, Redis, backend, web.
- Maven Spring Boot app and Next.js app.
- Health checks and basic CI.

**Phase 2: Ingestion vertical slice**

- MQTT topic contract.
- One machine payload schema.
- Spring Integration MQTT inbound flow.
- Validation, structured logging, quarantine path.
- InfluxDB write path.

**Phase 3: Application state**

- PostgreSQL device/site/line/machine registry.
- Redis latest-state cache.
- API endpoints for dashboard.

**Phase 4: UI and operator workflow**

- Next.js dashboard.
- Device list/detail pages.
- Telemetry query views.
- E2E test for golden path.

**Phase 5: Production hardening**

- TLS, auth, ACLs.
- Actuator, EMQX metrics, OpenTelemetry.
- Backup/restore.
- Load tests.
- Deployment rollback.

_Implementation Phases: Local → vertical slice → state/API/UI → observability → production hardening._
_Technology Migration Strategy: No big bang; expand topic families and machine classes after first slice._
_Resource Planning: Backend, frontend, infrastructure/operations, and domain machine protocol knowledge all needed._
_Source: https://docs.docker.com/compose/, https://docs.github.com/en/actions/use-cases-and-examples/building-and-testing/building-and-testing-java-with-maven_

### Technical Risk Management

Top risks:

- OpenJDK 25 dependency/runtime mismatch.
- Incorrect MQTT topic/ACL design.
- Duplicate or out-of-order message processing.
- InfluxDB high-cardinality schema mistakes.
- Redis misuse as system of record.
- Missing observability across async flow.
- Under-tested database/broker integration.
- Exposed internal services or secrets.

Mitigations:

- CI matrix for target Java/runtime image.
- Topic contract review and ACL tests.
- Idempotency keys and duplicate tests.
- InfluxDB schema review before load growth.
- Redis TTL policy and data ownership rules.
- Correlation IDs and OpenTelemetry from start.
- Testcontainers/containerized integration tests.
- Private networks and least-privilege credentials.

_Source: https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven, https://docs.influxdata.com/influxdb3/core/admin/databases/, https://docs.emqx.com/en/emqx/latest/_

## 10. Future Technical Outlook and Innovation Opportunities

### Emerging Technology Trends

Near-term evolution should focus on making ingestion reliable and visible, not adding advanced analytics too early. Once telemetry is trustworthy, the platform can add alerting, anomaly detection, predictive maintenance, machine command workflows, and more advanced dashboards.

_Near-term Technical Evolution: Robust telemetry ingestion, dashboards, alarms, retention, CI/CD, observability._
_Medium-term Technology Trends: Stream processing, rule evaluation, alerting, predictive models, edge preprocessing._
_Long-term Technical Vision: Closed-loop machine operations with secure commands, audit trails, and AI-assisted diagnostics._
_Source: https://docs.influxdata.com/influxdb3/core/, https://docs.emqx.com/en/emqx/latest/_

### Innovation and Research Opportunities

Research opportunities:

- Topic taxonomy and schema version strategy for many machine types.
- Retention strategy per telemetry class.
- Edge buffering during broker/network outage.
- Command/ack workflow reliability and safety controls.
- OEE and machine health metrics derived from raw telemetry.
- Cost model for managed vs self-hosted EMQX/InfluxDB/PostgreSQL/Redis.

_Research Opportunities: Edge reliability, alerting logic, telemetry aggregation, predictive maintenance._
_Emerging Technology Adoption: Add stream processing or ML only after telemetry baseline is stable._
_Innovation Framework: Treat telemetry platform as foundation; add intelligence in later layers._
_Source: https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven, https://opentelemetry.io/docs/what-is-opentelemetry/_

## 11. Technical Research Methodology and Source Verification

### Comprehensive Technical Source Documentation

_Primary Technical Sources:_

- Spring Boot: https://spring.io/projects/spring-boot
- Spring Boot Docs: https://docs.spring.io/spring-boot/index.html
- Spring Boot Actuator: https://docs.spring.io/spring-boot/reference/actuator/index.html
- Spring Boot Testing: https://docs.spring.io/spring-boot/reference/testing/index.html
- Spring Integration MQTT: https://docs.spring.io/spring-integration/reference/mqtt.html
- MQTT Specification: https://mqtt.org/mqtt-specification/
- EMQX Docs: https://docs.emqx.com/en/emqx/latest/
- EMQX Rule Engine: https://docs.emqx.com/en/emqx/latest/data-integration/rules.html
- EMQX Cluster: https://docs.emqx.com/en/emqx/latest/deploy/cluster/introduction.html
- EMQX Observability: https://docs.emqx.com/en/emqx/latest/observability/overview.html
- InfluxDB 3 Core: https://docs.influxdata.com/influxdb3/core/
- InfluxDB Write Data: https://docs.influxdata.com/influxdb3/core/write-data/
- InfluxDB Java Client Libraries: https://docs.influxdata.com/influxdb3/core/write-data/client-libraries/
- InfluxDB Databases: https://docs.influxdata.com/influxdb3/core/admin/databases/
- PostgreSQL Docs: https://www.postgresql.org/docs/current/
- Redis Docs: https://redis.io/docs/latest/
- Next.js Docs: https://nextjs.org/docs
- Next.js Route Handlers: https://nextjs.org/docs/app/building-your-application/routing/route-handlers
- Next.js Testing: https://nextjs.org/docs/app/guides/testing
- Maven: https://maven.apache.org/
- Adoptium Temurin Releases: https://adoptium.net/temurin/releases/?version=25
- Kubernetes Overview: https://kubernetes.io/docs/concepts/overview/
- OpenTelemetry: https://opentelemetry.io/docs/what-is-opentelemetry/
- Docker Compose: https://docs.docker.com/compose/
- GitHub Actions Maven CI: https://docs.github.com/en/actions/use-cases-and-examples/building-and-testing/building-and-testing-java-with-maven
- Azure Event-Driven Architecture: https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven
- Azure Cloud Design Patterns: https://learn.microsoft.com/en-us/azure/architecture/patterns/
- AWS Well-Architected Framework: https://docs.aws.amazon.com/wellarchitected/latest/framework/welcome.html

_Technical Web Search Queries Used:_

- `2026 Spring Boot Java 25 Maven MQTT EMQX Next.js Redis PostgreSQL InfluxDB architecture programming languages frameworks`
- `2026 Spring Boot MQTT EMQX Redis PostgreSQL InfluxDB development tools platforms Maven Next.js`
- `2026 MQTT EMQX InfluxDB PostgreSQL Redis time series database IoT architecture best practices`
- `2026 Next.js Spring Boot Docker Kubernetes MQTT EMQX Redis PostgreSQL InfluxDB deployment architecture`
- `2026 IoT MQTT time series database architecture EMQX InfluxDB Spring Boot Next.js technical significance`

### Technical Research Quality Assurance

_Technical Source Verification: Official vendor/standards documentation used wherever possible._
_Technical Confidence Levels:_

- High: MQTT standard status, EMQX capabilities, Spring Boot/Integration docs, InfluxDB write/database docs, Next.js docs, PostgreSQL/Redis/Maven docs.
- Medium: Deployment strategy choices, because exact production platform has not been selected.
- Requires follow-up: OpenJDK 25 production runtime artifact availability/compatibility for target OS/architecture and chosen Spring Boot/dependency line.

_Technical Limitations:_

- No live benchmark run was performed.
- No actual machine payload schema was provided.
- No production infrastructure provider was selected.
- No regulatory/compliance framework was specified.
- OpenJDK 25 verification was limited to Adoptium page fetch and should be repeated during implementation.

_Methodology Transparency: All conclusions derive from cited sources plus architecture synthesis against stated project goals._

## 12. Technical Appendices and Reference Materials

### Detailed Technical Data Tables

| Component | Primary Responsibility | Do Not Use For |
| --- | --- | --- |
| EMQX | MQTT broker, device ingress, auth/ACL, topic routing | Business persistence authority |
| Spring Boot | Validation, enrichment, APIs, orchestration, persistence writes | Raw broker replacement |
| InfluxDB | Time-series telemetry and event measurements | Relational master/config data |
| PostgreSQL | Relational system of record | High-rate raw telemetry by default |
| Redis | Hot state/cache/transient coordination | Durable source of truth |
| Next.js | UI and optional BFF routes | Direct database access |

| Risk | Mitigation |
| --- | --- |
| Duplicate MQTT processing | Idempotency tests, deterministic keys, duplicate detection |
| InfluxDB cardinality explosion | Tag governance, schema review, load tests |
| EMQX ACL misconfiguration | Per-device ACL tests and deny-by-default policy |
| Redis source-of-truth drift | TTLs, rebuildable cache, PostgreSQL/InfluxDB authority |
| OpenJDK 25 incompatibility | CI/runtime validation before production lock |
| Observability gaps | Correlation IDs, Actuator, EMQX metrics, OpenTelemetry |

### Technical Resources and References

_Technical Standards:_ MQTT 5.0 and MQTT 3.1.1 via OASIS/ISO references.
_Open Source Projects:_ Spring Boot, Spring Integration, EMQX, InfluxDB 3 Core, PostgreSQL, Redis, Next.js, OpenTelemetry, Kubernetes.
_Technical Communities:_ Spring, EMQX, InfluxData, PostgreSQL, Redis, Next.js/Vercel, CNCF/OpenTelemetry.

---

## Technical Research Conclusion

### Summary of Key Technical Findings

The recommended architecture is clear: use EMQX for MQTT ingress, Spring Boot for controlled processing and API ownership, InfluxDB for telemetry, PostgreSQL for relational truth, Redis for hot/cache state, and Next.js for UI. This gives clean boundaries, supports high-volume telemetry, and remains implementable by a small team.

### Strategic Technical Impact Assessment

This architecture can become a foundation for machine monitoring, operational dashboards, alerting, command/ack workflows, and future predictive maintenance. Success depends on disciplined contracts, security, observability, and incremental implementation more than on adding more services.

### Next Steps Technical Recommendations

1. Define one machine payload schema and MQTT topic taxonomy.
2. Build Docker Compose local stack.
3. Implement Spring Boot MQTT consumer and InfluxDB write path.
4. Add PostgreSQL device registry and Redis latest-state cache.
5. Build first Next.js dashboard.
6. Add Actuator, EMQX metrics, OpenTelemetry, CI, and integration tests.
7. Validate OpenJDK 25 runtime compatibility before production lock.
8. Add WAHA only as an outbound/inbound WhatsApp integration adapter, not as core telemetry path.

## WhatsApp API Integration Addendum: WAHA

WAHA can be added as a notification and operator communication integration layer. Its documentation describes WAHA as a self-hosted WhatsApp HTTP API that can run on your own server. It supports send/receive guides, sessions, events, Swagger/Postman/OpenAPI tooling, and production topics such as install/update, engines, storages, security, and WAHA Plus.

Recommended role in this architecture:

```text
Machine telemetry -> EMQX -> Spring Boot -> InfluxDB/PostgreSQL/Redis
                                      -> Alert/Notification service
                                      -> WAHA HTTP API
                                      -> WhatsApp users/groups
```

WAHA should not sit in the machine telemetry ingestion path. Use it only for human notification workflows such as alarm notifications, maintenance alerts, operator acknowledgments, daily summaries, or escalation messages.

### WAHA Send Message Pattern

Spring Boot should call WAHA HTTP endpoints server-side. WAHA send-message docs list endpoints including:

```http
POST /api/sendText
POST /api/sendSeen
POST /api/sendImage
POST /api/sendVoice
POST /api/sendVideo
POST /api/sendFile
POST /api/sendLocation
POST /api/reaction
POST /api/sendContactVcard
```

Text message example from WAHA docs:

```json
{
  "session": "default",
  "chatId": "12132132130@c.us",
  "text": "Hi there!"
}
```

WAHA uses `session` as sender session name. Core supports only `default`; Plus supports multiple sessions. User `chatId` format is international phone number without `+`, followed by `@c.us`, for example `12132132131@c.us`. Groups use `@g.us`.

### WAHA Integration Recommendations

- Keep WAHA behind Spring Boot; never expose WAHA API directly to browser clients.
- Store WAHA API key/session secrets in server-side configuration only.
- Add notification table in PostgreSQL for alert delivery attempts, status, target, and failure reason.
- Use Redis for short-lived rate limiting/deduplication of repeated alerts.
- Add retry with backoff, but avoid notification storms.
- Model WhatsApp delivery as best-effort external communication, not safety-critical machine control.
- For critical business messaging, note WAHA docs warn unofficial client/bot use may lead to blocking and suggest official WhatsApp API for critical business use.

_Source: https://waha.devlike.pro/docs/overview/introduction/, https://waha.devlike.pro/docs/how-to/send-messages/_

## Frontend Boilerplate Addendum: Next.js shadcn/ui Tailwind Admin Dashboard

Candidate boilerplate: `arhamkhnz/next-shadcn-admin-dashboard`.

Repository URL: https://github.com/arhamkhnz/next-shadcn-admin-dashboard

The repository describes itself as a modern admin dashboard template built with Shadcn UI and Next.js 16. It is a good candidate for accelerating the Syncro operator/admin dashboard because it already includes dashboard layouts, auth screens, theming, responsive UI, sidebar/layout controls, and common frontend tooling.

### Verified Stack

- Next.js 16 App Router
- TypeScript
- Tailwind CSS v4
- shadcn/ui
- Zod
- React Hook Form
- Zustand
- TanStack Table
- Biome
- Husky
- MIT license

### Relevant Features for Syncro

Use this boilerplate for:

- machine dashboard shell
- operator/admin navigation
- light/dark theme
- responsive mobile/tablet UI
- table-heavy screens such as machines, lines, sites, alarms, users, notifications
- form-heavy screens such as device registration, alarm rules, WhatsApp notification targets, MQTT config views
- future analytics pages for telemetry summaries

Suggested mapping:

| Boilerplate Capability | Syncro Use |
| --- | --- |
| Dashboard layouts | overview, machine health, alarm center |
| Auth screens | login/session UI, later connected to backend auth |
| TanStack Table | machine registry, alert logs, notification history |
| React Hook Form + Zod | config forms with validation |
| Zustand | UI/session/local dashboard state |
| shadcn/ui + Tailwind | consistent design system foundation |
| Theme presets | operator-friendly UI themes |

### Integration Pattern

The boilerplate should remain frontend-only and communicate with Spring Boot through API calls or Next.js Route Handlers/BFF routes.

Recommended frontend flow:

```text
Next.js dashboard
  -> Route Handler or direct server-side fetch
  -> Spring Boot API
  -> PostgreSQL / InfluxDB / Redis
```

Do not connect the boilerplate directly to PostgreSQL, InfluxDB, Redis, EMQX, or WAHA. All secrets and infrastructure access should stay server-side behind Spring Boot or controlled Next.js server routes.

### Adoption Notes

Install/run instructions from repo:

```bash
git clone https://github.com/arhamkhnz/next-shadcn-admin-dashboard.git
cd next-shadcn-admin-dashboard
npm install
npm run dev
```

Local URL:

```text
http://localhost:3000
```

Formatting/linting command shown by repo:

```bash
npx @biomejs/biome check --write
```

### Cautions

- README warns project updates may include breaking changes.
- RBAC and multi-tenant support are planned, not completed; do not rely on them as implemented features.
- Older Next.js 15 and Next.js 14/Tailwind v3 versions exist on archive branches; current template targets Next.js 16 and Tailwind CSS v4.
- Before adopting, review package versions, license, routing structure, auth assumptions, and theming compatibility with Syncro backend/API needs.

_Source: https://github.com/arhamkhnz/next-shadcn-admin-dashboard_

## Frontend Boilerplate Deep-Dive: Dashboard Information Architecture and Integration Plan

This section expands the frontend boilerplate decision for `arhamkhnz/next-shadcn-admin-dashboard` into a Syncro-specific dashboard plan. The boilerplate is useful because it already combines Next.js 16 App Router, TypeScript, Tailwind CSS v4, shadcn/ui, Zod, React Hook Form, Zustand, TanStack Table, Biome, and Husky.

### Frontend Architecture Role

The frontend should be an **operator/admin dashboard**, not a data processing layer. It should render machine state, telemetry summaries, alarms, registry/config forms, and notification history. All infrastructure access remains behind Spring Boot or controlled Next.js server routes.

Recommended boundary:

```text
Browser UI
  -> Next.js page/server component/client component
  -> Next.js Route Handler when BFF/proxy needed
  -> Spring Boot API
  -> PostgreSQL / InfluxDB / Redis / WAHA / EMQX internal integrations
```

Next.js documentation supports App Router organization with `app`, route folders, `layout`, `page`, `loading`, `error`, and `route` files. Route groups such as `(dashboard)` can organize routes without changing URL paths. Private folders like `_components` or `_lib` can colocate implementation details without making them routable.

_Source: https://nextjs.org/docs/app/getting-started/project-structure, https://nextjs.org/docs/app/building-your-application/routing/route-handlers_

### Proposed Dashboard Routes

Use the boilerplate sidebar/layout as the base admin shell.

```text
/dashboard
/dashboard/machines
/dashboard/machines/[machineId]
/dashboard/sites
/dashboard/lines
/dashboard/telemetry
/dashboard/alarms
/dashboard/notifications/whatsapp
/dashboard/mqtt
/dashboard/users
/dashboard/settings
```

Recommended route grouping:

```text
app/
  (auth)/
    login/page.tsx
  (dashboard)/
    layout.tsx
    dashboard/page.tsx
    machines/page.tsx
    machines/[machineId]/page.tsx
    telemetry/page.tsx
    alarms/page.tsx
    notifications/whatsapp/page.tsx
    mqtt/page.tsx
    settings/page.tsx
  api/
    backend/[...path]/route.ts
```

`(auth)` and `(dashboard)` allow separate layouts without changing URL shape. `api/backend/[...path]/route.ts` can proxy Spring Boot when same-origin browser calls, cookie handling, or secret hiding is needed.

### Page-to-Feature Mapping

| Page | Purpose | Primary Backend Data |
| --- | --- | --- |
| `/dashboard` | overview KPIs, current machine health, alarm summary | Redis latest state, PostgreSQL registry, InfluxDB aggregates |
| `/dashboard/machines` | machine registry table | PostgreSQL |
| `/dashboard/machines/[machineId]` | detail, live state, recent telemetry, alarms | PostgreSQL + Redis + InfluxDB |
| `/dashboard/sites` | site/factory hierarchy | PostgreSQL |
| `/dashboard/lines` | production line grouping | PostgreSQL |
| `/dashboard/telemetry` | query telemetry by machine/time/metric | InfluxDB through Spring Boot API |
| `/dashboard/alarms` | alarm definitions and active alarm log | PostgreSQL + Redis |
| `/dashboard/notifications/whatsapp` | WAHA targets, delivery attempts, templates | PostgreSQL + Spring Boot WAHA integration |
| `/dashboard/mqtt` | topic status, connected clients summary, broker health | Spring Boot API backed by EMQX internal API/metrics |
| `/dashboard/users` | users/roles | PostgreSQL/auth provider |
| `/dashboard/settings` | platform config | PostgreSQL/server config |

### Component Strategy with shadcn/ui

shadcn/ui is not a packaged component library; docs state it provides app-owned component source. This fits Syncro because dashboard components will need customization for machine telemetry, alarm severity, table filters, and industrial UI density.

Recommended shadcn/ui components:

- `Sidebar` for operator/admin navigation.
- `Card` for KPIs and machine summaries.
- `Table` / `Data Table` for machines, alarms, notification logs.
- `Chart` for telemetry trends.
- `Dialog` / `Sheet` for create/edit flows.
- `Form`, `Input`, `Select`, `Textarea`, `Checkbox` for configuration forms.
- `Tabs` for machine detail sections.
- `Toast` for action feedback.
- `Tooltip` for metric explanations.
- `Badge` for machine/alarm status.

_Source: https://ui.shadcn.com/docs_

### Data Table Strategy with TanStack Table

TanStack Table is headless: it provides table logic, state, processing, and APIs but no markup/styles. This works well with shadcn/ui because Syncro can keep visual design consistent while using robust table state.

Recommended uses:

- Machine registry table.
- Alarm history table.
- WhatsApp notification delivery table.
- MQTT topic/client summary table.
- Device configuration table.

For large datasets, prefer server-side pagination/filtering through Spring Boot APIs rather than loading all rows into the browser.

_Source: https://tanstack.com/table/latest/docs/introduction_

### Form and Validation Strategy

The boilerplate includes Zod and React Hook Form. Zod is TypeScript-first schema validation with static type inference and supports React Hook Form through ecosystem resolvers. Use Zod schemas for frontend form validation and mirror/align them with backend DTO constraints where practical.

Recommended forms:

- register machine
- edit machine metadata
- create alarm rule
- configure WhatsApp notification target
- configure MQTT topic mapping
- create/edit site and line

Example validation concept:

```ts
const machineFormSchema = z.object({
  machineCode: z.string().min(1),
  siteId: z.string().min(1),
  lineId: z.string().min(1),
  mqttTopicPrefix: z.string().min(1),
})
```

_Source: https://zod.dev_

### Tailwind CSS and Design System Strategy

Tailwind CSS v4 docs describe a utility-first workflow that scans HTML, JavaScript components, and templates for class names, then generates static CSS. It is zero-runtime and fits component-heavy dashboards.

Use Tailwind for layout density, responsive breakpoints, status colors, spacing, and operator-friendly UI states. Keep domain styling semantic through wrapper components such as `MachineStatusBadge`, `AlarmSeverityBadge`, and `TelemetryCard` rather than scattering raw status color logic everywhere.

_Source: https://tailwindcss.com/docs/installation/using-vite_

### State Management Strategy

Use state sparingly:

- Server state: fetched from Spring Boot APIs, cached by Next.js/fetch strategy or a data fetching library if adopted later.
- UI state: sidebar collapsed, selected table filters, local dashboard preferences.
- Real-time state: prefer polling first; add WebSocket/SSE later only if latency needs demand it.

Zustand in the boilerplate should be limited to UI/session/local preferences, not authoritative machine state. Authoritative latest state comes from Redis through Spring Boot API.

### API Contract Strategy

Initial API groups:

```text
GET    /api/machines
POST   /api/machines
GET    /api/machines/{id}
GET    /api/machines/{id}/latest-state
GET    /api/machines/{id}/telemetry?metric=&from=&to=
GET    /api/alarms
POST   /api/alarms/rules
GET    /api/notifications/whatsapp
POST   /api/notifications/whatsapp/test
GET    /api/mqtt/health
GET    /api/mqtt/topics
```

Frontend should depend on stable typed API clients. Keep API response models separate from UI view models.

### Boilerplate Adoption Checklist

Before copying/adopting repo into Syncro:

1. Verify license compatibility: MIT observed.
2. Review package versions and lockfile.
3. Remove demo dashboards not needed.
4. Keep layout/sidebar/theme primitives.
5. Replace fake data with typed API client calls.
6. Define route groups `(auth)` and `(dashboard)`.
7. Add Syncro domain components: machine cards, alarm badges, telemetry charts.
8. Align lint/format tooling with project standards.
9. Confirm Tailwind v4 + shadcn/ui setup works with selected Next.js version.
10. Add E2E golden path: login -> dashboard -> machine detail -> telemetry view.

### Frontend Risks

| Risk | Mitigation |
| --- | --- |
| Boilerplate churn/breaking changes | Pin commit/version before adoption |
| Demo code pollutes domain model | Remove unused pages/components early |
| RBAC/multi-tenant not implemented | Treat as future work; implement backend-driven authz |
| Too much client state | Keep server data authoritative |
| Direct infra access from UI | Enforce Spring Boot/Route Handler API boundary |
| Large telemetry responses | Add time range limits, aggregation, pagination |

### Recommended Frontend MVP

MVP frontend should include:

- Login shell placeholder or real auth if backend ready.
- Dashboard overview with mock-to-real API transition.
- Machines table.
- Machine detail page.
- Latest state card from Redis-backed API.
- Recent telemetry chart from InfluxDB-backed API.
- Alarm list.
- WhatsApp notification history page.
- MQTT broker health page.

This gives Syncro a usable operator surface without waiting for every backend feature.

_Source: https://github.com/arhamkhnz/next-shadcn-admin-dashboard, https://nextjs.org/docs/app/getting-started/project-structure, https://ui.shadcn.com/docs, https://tanstack.com/table/latest/docs/introduction, https://zod.dev, https://tailwindcss.com/docs/installation/using-vite_

## Data Model Deep-Dive: MQTT Payloads, PostgreSQL, InfluxDB, Redis

This section defines first-pass data boundaries for Syncro. Goal: avoid mixing telemetry, relational master data, and transient dashboard state in one store.

### Data Ownership Model

| Data Type | Owner Store | Reason |
| --- | --- | --- |
| Raw/high-frequency telemetry | InfluxDB | time-series write/query workload |
| Machine/site/line registry | PostgreSQL | relational constraints and joins |
| Alarm definitions | PostgreSQL | durable rules/configuration |
| Alarm occurrences/history | PostgreSQL + optional InfluxDB event table | relational workflow + time analytics |
| Latest machine state | Redis | low-latency dashboard reads |
| Notification attempts | PostgreSQL | auditability and retry history |
| MQTT payload transit | EMQX | brokered pub/sub delivery |
| WhatsApp send action | WAHA through Spring Boot | external notification adapter |

### MQTT Topic Taxonomy

EMQX messaging docs confirm MQTT publish/subscribe, topic-based filtering, wildcards, QoS, retained messages, shared subscriptions, will messages, MQTT 5 user-property filters, and durable message queue features.

Recommended initial topic scheme:

```text
syncro/{tenantId}/{siteId}/{lineId}/{machineId}/telemetry/{metricGroup}
syncro/{tenantId}/{siteId}/{lineId}/{machineId}/event/{eventType}
syncro/{tenantId}/{siteId}/{lineId}/{machineId}/state
syncro/{tenantId}/{siteId}/{lineId}/{machineId}/command/{commandType}
syncro/{tenantId}/{siteId}/{lineId}/{machineId}/command/{commandId}/ack
syncro/{tenantId}/{siteId}/{lineId}/{machineId}/will
```

Rules:

- Put routing identity in topic: tenant, site, line, machine.
- Put schema/version/timestamps in payload, not only topic.
- Keep telemetry and command topics separate.
- Use wildcard subscriptions carefully, for example Spring Boot subscribes to `syncro/+/+/+/+/telemetry/+`.
- Use ACLs so each machine can publish only its own telemetry/state/will and subscribe only allowed command topics.
- Use retained messages only for latest state where appropriate, not high-frequency telemetry.
- Use shared subscriptions when multiple Spring Boot consumers need load-balanced processing.

_Source: https://docs.emqx.com/en/emqx/latest/messaging/introduction.html_

### MQTT Payload Contract

Recommended telemetry payload JSON:

```json
{
  "schemaVersion": "1.0",
  "messageId": "01J...",
  "timestamp": "2026-05-25T10:15:30.123Z",
  "machineId": "MCH-001",
  "metricGroup": "production",
  "sequence": 10234,
  "values": {
    "cycleCount": 1842,
    "temperatureC": 62.4,
    "pressureBar": 5.8,
    "rpm": 1410,
    "running": true
  },
  "quality": {
    "source": "plc",
    "status": "good"
  }
}
```

Recommended event payload:

```json
{
  "schemaVersion": "1.0",
  "messageId": "01J...",
  "timestamp": "2026-05-25T10:15:30.123Z",
  "machineId": "MCH-001",
  "eventType": "alarm_raised",
  "severity": "high",
  "code": "TEMP_HIGH",
  "message": "Temperature exceeded limit",
  "attributes": {
    "temperatureC": 92.1,
    "limitC": 90
  }
}
```

Payload requirements:

- `schemaVersion` required for evolution.
- `messageId` required for idempotency/deduplication.
- `timestamp` required, UTC ISO-8601 at source if possible.
- `machineId` in payload must match topic machine segment.
- Numeric values must stay numeric, not strings.
- Unknown schema versions should go to quarantine/error path.

### InfluxDB Measurement/Table Design

InfluxDB 3 docs state measurements are now tables and buckets are databases. Tables should group similar time-series data and keep homogeneous tag/field keys. Tags store metadata/context and are strings only; fields store measured values and can be integer, unsigned integer, float, string, or boolean. Timestamp is UTC nanosecond Unix time and never null.

Recommended database split:

```text
syncro_telemetry_30d
syncro_telemetry_1y
syncro_events_1y
```

Recommended initial tables:

```text
machine_production
machine_environment
machine_energy
machine_state_event
```

Example `machine_production` design:

Tags:

```text
tenant_id
site_id
line_id
machine_id
metric_group
source
```

Fields:

```text
cycle_count: integer
rpm: float
pressure_bar: float
temperature_c: float
running: boolean
quality_status: string
sequence: integer
```

Example line protocol:

```text
machine_production,tenant_id=t1,site_id=s1,line_id=l1,machine_id=MCH-001,metric_group=production,source=plc cycle_count=1842i,rpm=1410,pressure_bar=5.8,temperature_c=62.4,running=true,quality_status="good",sequence=10234i 1779704130123000000
```

InfluxDB rules:

- Do not create one table per machine.
- Do not put high-cardinality random IDs as tags unless query requires them.
- Put commonly filtered tags first in first write because first write fixes physical column order.
- Avoid wide sparse tables; split metric groups when field sets differ.
- Use fields for measured values.
- Use retention by database for lifecycle.

Line protocol syntax:

```text
<table>[,<tag_key>=<tag_value>[,<tag_key>=<tag_value>]] <field_key>=<field_value>[,<field_key>=<field_value>] [<timestamp>]
```

Line protocol notes:

- Table is required and maps from older InfluxDB measurement concept.
- Fields are required; at least one field per point.
- Tags are optional and comma-delimited.
- Timestamp is optional; if absent, InfluxDB uses host system UTC time.
- Default timestamp precision is nanoseconds unless write precision specifies otherwise.
- Integer values require `i` suffix, unsigned integers require `u`, strings need double quotes, booleans are unquoted.
- First unescaped space separates table/tag set from fields; second unescaped space separates fields from timestamp.
- Duplicate point identity is table + tag set + timestamp; same identity with different fields merges fields, conflicts use new field value.

_Source: https://docs.influxdata.com/influxdb3/core/write-data/best-practices/schema-design/, https://docs.influxdata.com/influxdb3/core/reference/line-protocol/_

### PostgreSQL Relational Schema Draft

PostgreSQL docs emphasize primary keys, foreign keys, unique constraints, check constraints, not-null constraints, and indexing. Primary keys are unique/not null and create unique B-tree indexes. Foreign keys maintain referential integrity but do not automatically create indexes on referencing columns. Indexes improve read performance but add write/storage overhead, so use sensibly.

Core tables:

```sql
CREATE TABLE tenants (
  id uuid PRIMARY KEY,
  code text UNIQUE NOT NULL,
  name text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sites (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  code text NOT NULL,
  name text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, code)
);

CREATE TABLE lines (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  site_id uuid NOT NULL REFERENCES sites(id),
  code text NOT NULL,
  name text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (site_id, code)
);

CREATE TABLE machines (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  site_id uuid NOT NULL REFERENCES sites(id),
  line_id uuid NOT NULL REFERENCES lines(id),
  machine_code text NOT NULL,
  display_name text NOT NULL,
  mqtt_machine_id text NOT NULL,
  status text NOT NULL DEFAULT 'unknown',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, machine_code),
  UNIQUE (tenant_id, mqtt_machine_id),
  CHECK (status IN ('unknown', 'running', 'idle', 'stopped', 'alarm', 'offline'))
);
```

Alarm and notification tables:

```sql
CREATE TABLE alarm_rules (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  machine_id uuid REFERENCES machines(id),
  code text NOT NULL,
  name text NOT NULL,
  severity text NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  condition jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, code),
  CHECK (severity IN ('info', 'warning', 'high', 'critical'))
);

CREATE TABLE alarm_events (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  machine_id uuid NOT NULL REFERENCES machines(id),
  rule_id uuid REFERENCES alarm_rules(id),
  event_type text NOT NULL,
  severity text NOT NULL,
  code text NOT NULL,
  message text NOT NULL,
  source_message_id text,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, source_message_id),
  CHECK (severity IN ('info', 'warning', 'high', 'critical'))
);

CREATE TABLE whatsapp_notification_targets (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  name text NOT NULL,
  chat_id text NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, chat_id)
);

CREATE TABLE notification_attempts (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  alarm_event_id uuid REFERENCES alarm_events(id),
  target_id uuid REFERENCES whatsapp_notification_targets(id),
  channel text NOT NULL,
  status text NOT NULL,
  payload jsonb NOT NULL,
  error_message text,
  attempted_at timestamptz NOT NULL DEFAULT now(),
  CHECK (channel IN ('whatsapp')),
  CHECK (status IN ('pending', 'sent', 'failed', 'skipped'))
);
```

Recommended indexes:

```sql
CREATE INDEX idx_sites_tenant_id ON sites (tenant_id);
CREATE INDEX idx_lines_site_id ON lines (site_id);
CREATE INDEX idx_machines_tenant_line ON machines (tenant_id, line_id);
CREATE INDEX idx_machines_mqtt_id ON machines (tenant_id, mqtt_machine_id);
CREATE INDEX idx_alarm_events_machine_time ON alarm_events (machine_id, occurred_at DESC);
CREATE INDEX idx_alarm_events_tenant_time ON alarm_events (tenant_id, occurred_at DESC);
CREATE INDEX idx_notification_attempts_alarm ON notification_attempts (alarm_event_id);
CREATE INDEX idx_notification_attempts_status_time ON notification_attempts (status, attempted_at DESC);
```

_Source: https://www.postgresql.org/docs/current/ddl-constraints.html, https://www.postgresql.org/docs/current/indexes.html_

### Redis Key Design

Redis docs describe strings, hashes, sorted sets, streams, and lists. For Syncro, use hashes for latest machine state, strings for simple flags, sorted sets for ordered active alarms, and streams only if a short-lived event buffer is needed.

Recommended keys:

```text
syncro:{tenantId}:machine:{machineId}:latest
syncro:{tenantId}:machine:{machineId}:heartbeat
syncro:{tenantId}:line:{lineId}:machines:status
syncro:{tenantId}:alarms:active
syncro:{tenantId}:notifications:dedupe:{alarmEventId}:{targetId}
```

Example latest state hash:

```text
HSET syncro:t1:machine:MCH-001:latest \
  status running \
  temperature_c 62.4 \
  pressure_bar 5.8 \
  rpm 1410 \
  updated_at 2026-05-25T10:15:30.123Z \
  message_id 01J...
```

Redis rules:

- Every derived/latest key should have TTL unless intentionally persistent.
- Redis data must be rebuildable from PostgreSQL/InfluxDB where possible.
- Do not store authoritative config only in Redis.
- Use notification dedupe keys with TTL to prevent WhatsApp alert storms.

_Source: https://redis.io/docs/latest/develop/data-types/_

### Cross-Store Consistency Rules

- PostgreSQL `machines.mqtt_machine_id` must match MQTT topic/payload machine identity.
- InfluxDB tags use stable IDs/codes from PostgreSQL.
- Redis latest state is overwritten by newest accepted telemetry only.
- `messageId` should be recorded or deduped where duplicate processing matters.
- Alarm event creation should happen once per source message/alarm condition.
- Notification attempts must reference alarm event rows where possible.

### Data Model MVP

MVP should implement only:

1. `tenants`, `sites`, `lines`, `machines`.
2. `alarm_events` minimal table.
3. `notification_attempts` only if WAHA notifications are in MVP.
4. InfluxDB `machine_production` table.
5. Redis latest machine state hash.
6. MQTT telemetry topic and JSON payload v1.

Everything else can grow after first working vertical slice.

## Backend Implementation Deep-Dive: Spring Boot, MQTT, PostgreSQL, InfluxDB, Redis, WAHA

This section turns the architecture into an implementation-ready backend plan. Scope: one Spring Boot service owns MQTT ingestion, validation, persistence writes, Redis latest-state updates, REST APIs, and WAHA notification orchestration.

### Backend Project Setup

Use Maven with Spring Boot dependency management. Spring Boot docs recommend Maven or Gradle and state Spring Boot manages dependency versions through curated dependencies/BOM, so managed dependencies should not specify versions unless override is intentional.

Recommended Spring Boot 4.x starter set:

```xml
<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.integration</groupId>
    <artifactId>spring-integration-mqtt</artifactId>
  </dependency>
  <dependency>
    <groupId>org.eclipse.paho</groupId>
    <artifactId>org.eclipse.paho.mqttv5.client</artifactId>
  </dependency>
  <dependency>
    <groupId>com.influxdb</groupId>
    <artifactId>influxdb3-java</artifactId>
    <version>1.1.0</version>
  </dependency>
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>com.redis</groupId>
    <artifactId>testcontainers-redis</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Notes:

- Spring Boot 4 docs use `spring-boot-starter-webmvc`; older `spring-boot-starter-web` is deprecated in favor of webmvc.
- Spring Integration MQTT version should normally be managed/aligned by dependency management if available; only pin when necessary.
- MQTT v5 requires explicit Paho v5 dependency.
- InfluxDB Java client version came from InfluxDB docs and may not be Spring-managed.
- OpenJDK 25 requires CI validation before lock.

_Source: https://docs.spring.io/spring-boot/reference/using/build-systems.html, https://docs.spring.io/spring-integration/reference/mqtt.html, https://docs.influxdata.com/influxdb3/core/write-data/client-libraries/_

### Package/Module Structure

Recommended package layout:

```text
com.syncro.backend
  config
    MqttConfig
    InfluxConfig
    RedisConfig
    WahaConfig
  mqtt
    MqttInboundFlowConfig
    MqttMessageHandler
    MqttTopicParser
    MqttHeadersMapper
  telemetry
    TelemetryPayload
    TelemetryValidator
    TelemetryIngestionService
    InfluxTelemetryWriter
    RedisLatestStateWriter
  registry
    TenantEntity
    SiteEntity
    LineEntity
    MachineEntity
    MachineRepository
    MachineService
  alarms
    AlarmRuleEntity
    AlarmEventEntity
    AlarmEvaluationService
    AlarmRepository
  notifications
    NotificationAttemptEntity
    WhatsappTargetEntity
    WahaClient
    NotificationService
  api
    MachineController
    TelemetryQueryController
    AlarmController
    NotificationController
    MqttHealthController
  common
    CorrelationId
    ClockProvider
    JsonMapper
    ErrorResponse
  quarantine
    InvalidMqttMessageEntity
    QuarantineService
```

Design rule: MQTT integration parses and routes; telemetry service validates and persists; API layer never contains persistence orchestration logic.

### Configuration Properties

Suggested `application.yml` shape:

```yaml
syncro:
  mqtt:
    broker-url: tcp://localhost:1883
    client-id: syncro-backend-ingest
    username: syncro
    password: change-me
    topics:
      telemetry: syncro/+/+/+/+/telemetry/+
      event: syncro/+/+/+/+/event/+
    automatic-reconnect: true
    qos: 1
  influx:
    url: http://localhost:8181
    token: change-me
    database: syncro_telemetry_30d
  waha:
    base-url: http://localhost:3000
    api-key: change-me
    session: default

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/syncro
    username: syncro
    password: change-me
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  data:
    redis:
      host: localhost
      port: 6379
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

Spring Boot SQL docs state PostgreSQL JDBC config uses `spring.datasource.*`; driver class is usually deduced from URL; HikariCP is default when JDBC/JPA starter is used. Disable `spring.jpa.open-in-view` for cleaner API/service transaction boundaries.

Spring Boot Redis docs state Redis auto-configuration uses Lettuce by default and provides `RedisConnectionFactory`, `StringRedisTemplate`, and `RedisTemplate`. Configure via `spring.data.redis.*` or `spring.data.redis.url`.

_Source: https://docs.spring.io/spring-boot/4.0.6/reference/data/sql.html, https://docs.spring.io/spring-boot/4.0.6/reference/data/nosql.html#data.nosql.redis_

### MQTT Consumer Flow

Recommended MQTT v5 flow using Spring Integration:

```text
EMQX topic publish
  -> Mqttv5PahoMessageDrivenChannelAdapter
  -> queue/direct channel
  -> handler parses topic + payload
  -> validate schemaVersion, messageId, timestamp, machineId match
  -> lookup machine in PostgreSQL
  -> write telemetry to InfluxDB
  -> update Redis latest state
  -> evaluate alarms
  -> create alarm event in PostgreSQL if needed
  -> send WAHA notification if rule target configured
  -> ack message after successful critical processing
```

Spring Integration MQTT facts:

- MQTT v5 inbound adapter: `Mqttv5PahoMessageDrivenChannelAdapter`.
- MQTT v5 outbound handler: `Mqttv5PahoMessageHandler`.
- v5 requires `org.eclipse.paho:org.eclipse.paho.mqttv5.client`.
- v5 inbound supports payload type, manual acks, header mapping, MQTT properties, and received topic headers.
- `MqttConnectionOptions#setAutomaticReconnect(true)` recommended for v5 reconnect.
- Manual acknowledgments add `IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK`; call `acknowledge()` to complete delivery.
- Error channel receives `ErrorMessage` for downstream exceptions when configured.
- Shared client support exists through `ClientManager` when multiple adapters need one client lifecycle.

Implementation recommendation:

- Use `qos=1` first.
- Use manual ack only if processing is fast and deterministic enough; otherwise first persist raw/quarantine state, then ack, then async downstream.
- Always log topic, messageId, machineId, schemaVersion, correlationId.
- Route validation errors to quarantine, not silent discard.

_Source: https://docs.spring.io/spring-integration/reference/mqtt.html_

### InfluxDB Writer

Use InfluxDB 3 Java client or direct HTTP line protocol. Java client docs show dependency `com.influxdb:influxdb3-java:1.1.0`, client config with URL, database, authorization token, and writes using line protocol or `Point` objects.

Recommended writer responsibilities:

- Convert validated telemetry DTO to InfluxDB `Point` or line protocol.
- Ensure timestamp precision is correct.
- Use fields for numeric values and booleans.
- Use tags for tenant/site/line/machine/source/metric group.
- Avoid one table per machine.
- Keep metric-group tables homogeneous.

Pseudo-interface:

```java
public interface TelemetryWriter {
    void write(TelemetryRecord record);
}
```

Failure policy:

- If InfluxDB write fails, do not pretend ingestion succeeded.
- For MVP, send to quarantine/error table and alert logs.
- Later, add retry queue/outbox if write failures must be recoverable.

_Source: https://docs.influxdata.com/influxdb3/core/write-data/client-libraries/, https://docs.influxdata.com/influxdb3/core/reference/line-protocol/_

### PostgreSQL Persistence

Use JPA or JDBC. For this domain, JPA is acceptable for registry/config/alarm/notification tables because they are relational entities with constraints. Telemetry should not be stored primarily in PostgreSQL.

Spring Boot SQL docs:

- `spring-boot-starter-data-jpa` provides Hibernate, Spring Data JPA, and Spring ORM.
- PostgreSQL driver should be runtime dependency.
- Entity scanning supports `@Entity`, `@Embeddable`, `@MappedSuperclass`.
- Repositories are auto-scanned.
- `spring.jpa.hibernate.ddl-auto=validate` is appropriate when migrations own schema.

Recommended persistence rules:

- Use migrations later for actual schema management.
- Keep foreign keys and unique constraints in database.
- Use `jsonb` only for flexible rule conditions/payload snapshots, not as default modeling shortcut.
- Add indexes matching UI query patterns.

_Source: https://docs.spring.io/spring-boot/4.0.6/reference/data/sql.html, https://www.postgresql.org/docs/current/ddl-constraints.html, https://www.postgresql.org/docs/current/indexes.html_

### Redis Latest-State Writer

Use `StringRedisTemplate` for simple hashes/strings first. Spring Boot Redis auto-configures Lettuce and `StringRedisTemplate`.

Recommended operations:

```text
HSET syncro:{tenantId}:machine:{machineId}:latest ...fields
EXPIRE syncro:{tenantId}:machine:{machineId}:latest 300
ZADD syncro:{tenantId}:alarms:active {occurredAtEpochMs} {alarmEventId}
SET syncro:{tenantId}:notifications:dedupe:{alarmEventId}:{targetId} 1 EX 3600 NX
```

Rules:

- Redis latest state is derived and rebuildable.
- TTL all machine latest-state keys unless operationally persistent.
- Use `SET NX EX` for WAHA notification dedupe.
- Do not store durable configuration only in Redis.

_Source: https://docs.spring.io/spring-boot/4.0.6/reference/data/nosql.html#data.nosql.redis, https://redis.io/docs/latest/develop/data-types/_

### WAHA Notification Service

WAHA is called from Spring Boot only when an alarm/notification policy triggers.

Flow:

```text
AlarmEvent created
  -> NotificationService finds WhatsApp targets
  -> Redis dedupe key prevents repeat storm
  -> create notification_attempts row pending
  -> POST WAHA /api/sendText
  -> update attempt sent/failed
```

WAHA send body:

```json
{
  "session": "default",
  "chatId": "12132132130@c.us",
  "text": "Machine MCH-001 alarm TEMP_HIGH: Temperature exceeded limit"
}
```

Implementation rules:

- Use server-side API key only.
- Time out WAHA calls.
- Retry with backoff for transient failures.
- Do not block MQTT consumer for long WAHA calls; after alarm event creation, notification can be async.
- Treat WAHA as best-effort notification, not safety-critical control.

_Source: https://waha.devlike.pro/docs/overview/introduction/, https://waha.devlike.pro/docs/how-to/send-messages/_

### Error, Quarantine, and Idempotency

Create quarantine path for invalid MQTT messages:

```sql
CREATE TABLE invalid_mqtt_messages (
  id uuid PRIMARY KEY,
  topic text NOT NULL,
  payload text NOT NULL,
  reason text NOT NULL,
  source_message_id text,
  received_at timestamptz NOT NULL DEFAULT now()
);
```

Idempotency:

- Require `messageId` in payload.
- Store processed message IDs only if duplicate risk is significant and duplicates cannot be tolerated.
- For telemetry, InfluxDB duplicate point behavior can merge/overwrite fields when table + tag set + timestamp match, but do not rely on that as only idempotency for business events.
- For alarm events, use `(tenant_id, source_message_id)` uniqueness if source message maps to one alarm event.

### REST API Surface

MVP APIs:

```text
GET /api/machines
GET /api/machines/{id}
GET /api/machines/{id}/latest-state
GET /api/machines/{id}/telemetry?metricGroup=&from=&to=
GET /api/alarms/events
GET /api/notifications/whatsapp/attempts
POST /api/notifications/whatsapp/test
GET /api/mqtt/health
```

Guidelines:

- API returns UI-ready summaries but not raw infrastructure credentials.
- Query telemetry through backend, with time range limits.
- Validate all input with Bean Validation.
- Use pagination for lists.

### Testing Plan

Backend test layers:

1. Unit tests:
   - topic parser
   - payload parser
   - validation
   - Influx line/point mapper
   - Redis key builder
   - WAHA request builder

2. Integration tests:
   - PostgreSQL repositories with Testcontainers.
   - Redis latest-state writes with Redis Testcontainer.
   - MQTT consumer against EMQX/test broker if practical.
   - API controller tests.

Spring Boot Testcontainers docs state Testcontainers manages real services in Docker containers for integration tests. `@ServiceConnection` lets Spring Boot auto-create connection details for containers and override normal connection properties. PostgreSQL and Redis service connections are supported.

Example PostgreSQL pattern:

```java
@Testcontainers
@SpringBootTest
class PostgresIntegrationTests {
  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
}
```

Example Redis pattern:

```java
@Testcontainers
@SpringBootTest
class RedisIntegrationTests {
  @Container
  @ServiceConnection
  static RedisContainer redis = new RedisContainer("redis:7");
}
```

_Source: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html_

### Backend MVP Build Order

1. Create Spring Boot Maven project.
2. Add config properties and Actuator.
3. Add PostgreSQL entities/repositories for tenants/sites/lines/machines.
4. Add MQTT topic parser + payload DTO + validation.
5. Add Spring Integration MQTT inbound flow.
6. Add InfluxDB writer for `machine_production`.
7. Add Redis latest-state writer.
8. Add minimal machine/telemetry REST APIs.
9. Add alarm event creation.
10. Add WAHA notification attempt flow.
11. Add integration tests.
12. Add Docker Compose local stack.

This order keeps the first vertical slice small and testable.

## Deployment Deep-Dive: Docker Compose, Local Stack, Production Topology

This section defines a deployment baseline for local development and a production evolution path. Goal: developers can run the full machine-data platform locally while production remains secure, observable, and replaceable with managed services later.

### Local Development Stack

Use Docker Compose for local development. Docker Compose configures application services, networks, volumes, and related resources in one file. It is appropriate for running EMQX, PostgreSQL, Redis, InfluxDB, WAHA, Spring Boot, and Next.js together.

Local services:

```text
emqx       MQTT broker and dashboard
postgres   relational DB
redis      latest-state cache
influxdb   telemetry/time-series DB
waha       WhatsApp HTTP API adapter
backend    Spring Boot API + MQTT consumer
web        Next.js dashboard
```

Recommended local ports:

| Service | Internal | Host | Purpose |
| --- | ---: | ---: | --- |
| EMQX MQTT | 1883 | 1883 | MQTT TCP dev |
| EMQX MQTT TLS | 8883 | 8883 | MQTT TLS later |
| EMQX WS | 8083 | 8083 | MQTT over WebSocket |
| EMQX WSS | 8084 | 8084 | MQTT over WSS |
| EMQX Dashboard | 18083 | 18083 | broker admin UI |
| PostgreSQL | 5432 | 5432 | DB dev access |
| Redis | 6379 | 6379 | cache dev access |
| InfluxDB 3 Core | 8181 | 8181 | telemetry API |
| WAHA | 3000 | 3001 | avoid conflict with Next.js |
| Spring Boot | 8080 | 8080 | backend API |
| Next.js | 3000 | 3000 | web UI |

### Docker Compose Skeleton

```yaml
services:
  emqx:
    image: emqx/emqx-enterprise:6.2.0
    hostname: node1.emqx.local
    environment:
      EMQX_NODE_NAME: emqx@node1.emqx.local
    ports:
      - "1883:1883"
      - "8083:8083"
      - "8084:8084"
      - "8883:8883"
      - "18083:18083"
    volumes:
      - emqx-data:/opt/emqx/data
      - emqx-log:/opt/emqx/log
    healthcheck:
      test: ["CMD", "/opt/emqx/bin/emqx", "ctl", "status"]
      interval: 10s
      timeout: 5s
      retries: 12

  postgres:
    image: postgres:18
    environment:
      POSTGRES_DB: syncro
      POSTGRES_USER: syncro
      POSTGRES_PASSWORD: syncro_dev_password
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql

  redis:
    image: redis:8
    command: ["redis-server", "--save", "60", "1", "--loglevel", "warning"]
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data

  influxdb:
    image: influxdb:3-core
    ports:
      - "8181:8181"
    volumes:
      - influxdb-data:/var/lib/influxdb3/data
      - influxdb-plugins:/var/lib/influxdb3/plugins

  waha:
    image: devlikeapro/waha
    restart: unless-stopped
    ports:
      - "3001:3000"
    volumes:
      - waha-sessions:/app/.sessions

  backend:
    build:
      context: ./syncro/apps/backend
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/syncro
      SPRING_DATASOURCE_USERNAME: syncro
      SPRING_DATASOURCE_PASSWORD: syncro_dev_password
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      SYNCRO_MQTT_BROKER_URL: tcp://emqx:1883
      SYNCRO_INFLUX_URL: http://influxdb:8181
      SYNCRO_WAHA_BASE_URL: http://waha:3000
    ports:
      - "8080:8080"
    depends_on:
      - emqx
      - postgres
      - redis
      - influxdb
      - waha

  web:
    build:
      context: ./syncro/apps/web
    environment:
      API_BASE_URL: http://backend:8080
    ports:
      - "3000:3000"
    depends_on:
      - backend

volumes:
  emqx-data:
  emqx-log:
  postgres-data:
  redis-data:
  influxdb-data:
  influxdb-plugins:
  waha-sessions:
```

Note: exact backend/web paths depend final repo layout. Compose service names become DNS hostnames inside Compose network, so backend should use `postgres`, `redis`, `emqx`, `influxdb`, and `waha`, not `localhost`.

_Source: https://docs.docker.com/compose/compose-file/, https://docs.emqx.com/en/emqx/latest/deploy/install-docker.html, https://docs.influxdata.com/influxdb3/core/install/, https://hub.docker.com/_/postgres, https://hub.docker.com/_/redis, https://waha.devlike.pro/docs/how-to/install/_

### Service-Specific Deployment Notes

#### EMQX

EMQX Docker docs show image `emqx/emqx-enterprise:6.2.0`, ports `1883`, `8083`, `8084`, `8883`, `18083`, and persistent paths `/opt/emqx/data` and `/opt/emqx/log`. Compose healthcheck can use `/opt/emqx/bin/emqx ctl status`. Docker host access note: `localhost` inside container is container-local, not host.

Production notes:

- Use stable node names.
- Persist `/opt/emqx/data` and logs.
- Enable TLS listener for devices.
- Configure authentication and ACLs before exposing MQTT.
- For cluster, use static/DNS/Kubernetes discovery depending platform.

_Source: https://docs.emqx.com/en/emqx/latest/deploy/install-docker.html_

#### InfluxDB 3 Core

InfluxDB 3 Core Docker image is `influxdb:3-core`. Default Core URL shown in docs is `localhost:8181`. It stores time-series data as Apache Parquet and supports local filesystem or object storage such as S3/Azure/GCS/MinIO. Local filesystem likely faster; object storage avoids local disk capacity limits and enables network access patterns.

Production notes:

- Use explicit data directory/volume.
- Decide local disk vs object storage early.
- Define retention periods by database.
- Monitor disk/object-store growth.

_Source: https://docs.influxdata.com/influxdb3/core/install/_

#### PostgreSQL

Official PostgreSQL image is `postgres`. Required env is `POSTGRES_PASSWORD`; optional env includes `POSTGRES_USER`, `POSTGRES_DB`, `POSTGRES_INITDB_ARGS`, `POSTGRES_INITDB_WALDIR`, `POSTGRES_HOST_AUTH_METHOD`, and `PGDATA`. Init scripts can be mounted under `/docker-entrypoint-initdb.d` and run only when data directory is empty. `_FILE` variants support Docker secrets for several env vars.

Production notes:

- Use managed PostgreSQL where possible.
- If self-hosted, configure backups, WAL, monitoring, storage, and upgrade path.
- Never use dev password in production.
- Use migrations, not ad-hoc init scripts, after first schema baseline.

_Source: https://hub.docker.com/_/postgres_

#### Redis

Official Redis image is `redis`. Local run can use `docker run --name some-redis -d redis`. Persistence example uses `redis-server --save 60 1 --loglevel warning`. Data path is `/data`. Redis docs warn if port is exposed with `-p`, Redis may be reachable without password; configure password/security before internet exposure.

Production notes:

- Keep Redis private network only.
- Set memory policy and persistence intentionally.
- Use password/TLS where platform requires.
- Treat Redis data as rebuildable cache/hot state.

_Source: https://hub.docker.com/_/redis_

#### WAHA

WAHA install docs support Docker/Docker Compose. Example image is `devlikeapro/waha`, port mapping `3000:3000`, sessions volume `./.sessions:/app/.sessions`, and restart policy `always`. Docs state quick-start `docker run` is good only for dev and production needs more config for security, reliability, and management.

Production notes:

- Persist sessions.
- Protect API key and session storage.
- Monitor uptime and failed sends.
- Back up session data if self-hosted.
- Treat WAHA as external notification adapter, not critical control layer.

_Source: https://waha.devlike.pro/docs/how-to/install/_

### Environment Variable Matrix

| Variable | Service | Dev Example | Secret? |
| --- | --- | --- | --- |
| `SPRING_DATASOURCE_URL` | backend | `jdbc:postgresql://postgres:5432/syncro` | no |
| `SPRING_DATASOURCE_USERNAME` | backend | `syncro` | yes-ish |
| `SPRING_DATASOURCE_PASSWORD` | backend | `syncro_dev_password` | yes |
| `SPRING_DATA_REDIS_HOST` | backend | `redis` | no |
| `SPRING_DATA_REDIS_PORT` | backend | `6379` | no |
| `SYNCRO_MQTT_BROKER_URL` | backend | `tcp://emqx:1883` | no |
| `SYNCRO_MQTT_USERNAME` | backend | `syncro` | yes |
| `SYNCRO_MQTT_PASSWORD` | backend | `change-me` | yes |
| `SYNCRO_INFLUX_URL` | backend | `http://influxdb:8181` | no |
| `SYNCRO_INFLUX_TOKEN` | backend | `change-me` | yes |
| `SYNCRO_INFLUX_DATABASE` | backend | `syncro_telemetry_30d` | no |
| `SYNCRO_WAHA_BASE_URL` | backend | `http://waha:3000` | no |
| `SYNCRO_WAHA_API_KEY` | backend | `change-me` | yes |
| `API_BASE_URL` | web | `http://backend:8080` | no if internal |

Never commit real `.env` or secrets. Use `.env.example` only for placeholders.

### Health Checks and Readiness

Local minimum:

- EMQX: `emqx ctl status` healthcheck.
- Spring Boot: `/actuator/health`.
- Next.js: HTTP check on `/` or `/dashboard`.
- PostgreSQL: `pg_isready` if available in image.
- Redis: `redis-cli ping` if CLI available.
- InfluxDB: HTTP health/readiness endpoint should be verified during implementation.
- WAHA: HTTP status endpoint should be verified during implementation.

Do not assume `depends_on` means service is ready for app use unless health conditions are configured and supported by chosen Compose version.

### Production Topology Options

#### Option A: Managed Data Services + Containers

```text
Managed PostgreSQL
Managed Redis
Managed/hosted InfluxDB or VM/container InfluxDB
EMQX managed/self-hosted cluster
Spring Boot containers
Next.js container/platform
WAHA container/VM
```

Best when team wants less database ops burden.

#### Option B: Kubernetes Self-Hosted

```text
Kubernetes namespace
  EMQX Stateful/cluster deployment
  PostgreSQL managed outside cluster preferred
  Redis managed outside cluster preferred
  InfluxDB persistent/object storage
  Spring Boot deployment + HPA
  Next.js deployment + ingress
  WAHA deployment + persistent session volume
```

Best when team already operates Kubernetes well.

#### Option C: Single VM / Docker Compose Production

Only acceptable for early pilot/internal demo. Needs backups, restart policies, firewall, TLS, monitoring, and restore testing. Avoid for production machine-critical workloads.

### Backup and Retention

| Component | Backup/Retention Need |
| --- | --- |
| PostgreSQL | regular backups + restore tests |
| InfluxDB | retention policy + data directory/object storage backup plan |
| Redis | optional persistence; cache should be rebuildable |
| EMQX | config, ACL/auth data, retained/session data if used |
| WAHA | session volume backup if continuity needed |
| Next.js | rebuildable from source |
| Spring Boot | rebuildable from source + config/secrets |

### Deployment Risks

| Risk | Mitigation |
| --- | --- |
| Exposed Redis/PostgreSQL/InfluxDB | private network/firewall only |
| Dev passwords reach prod | `.env.example`, secret manager, review before deploy |
| WAHA session loss | persistent volume + backup |
| EMQX node name changes | stable hostname/node name |
| InfluxDB disk growth | retention + monitoring |
| Compose `localhost` confusion | use service DNS names in containers |
| App starts before dependencies ready | healthchecks + retry/backoff |
| No restore testing | schedule restore drills |

### Recommended Deployment MVP

Start with Docker Compose local stack. For first real pilot, prefer:

- Spring Boot + Next.js as containers.
- Managed PostgreSQL if available.
- Managed Redis if available.
- EMQX single node or small cluster depending device count.
- InfluxDB with explicit retention and storage plan.
- WAHA isolated with persistent sessions and API key.
- TLS termination and private networking.
- Actuator + EMQX metrics + logs collected centrally.

---

**Technical Research Completion Date:** 2026-05-25
**Research Period:** Current comprehensive technical analysis
**Source Verification:** All technical facts cited with current sources where available
**Technical Confidence Level:** High for architecture and stack role decisions; medium for deployment platform choice; follow-up required for OpenJDK 25 production artifact compatibility

_This comprehensive technical research document serves as an authoritative technical reference for the target machine data platform and provides strategic technical guidance for implementation planning._

