# Story 1.3: Initialize Spring Boot Backend Skeleton

Status: in-progress

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an implementer,
I want a Spring Boot backend skeleton with core dependencies and health baseline,
so that Syncro can expose `/api/v1` endpoints and integrate persistence/services incrementally.

## Acceptance Criteria

1. Given repository foundation exists, when backend app is initialized in `syncro/apps/backend`, then app boots successfully with Java 25 and Maven.
2. Given backend dependencies are configured, when the Maven project is inspected, then configured dependencies include Spring Web, Validation, Security, Data JPA, PostgreSQL, Flyway, Redis, Actuator, MQTT client/integration, Scheduling support, and Testcontainers.
3. Given backend app is running locally, when a smoke test calls a backend health or readiness endpoint under `/api/v1`, then the endpoint returns a successful JSON response suitable for local verification.
4. Given database migration support is required, when backend skeleton is created, then the Flyway migration folder exists under the backend resources tree.
5. Given local infrastructure is configured through environment variables, when backend config is inspected, then service URLs, ports, usernames, passwords, tokens, MQTT client id, and topic filter are read from environment/profile values rather than hardcoded local service URLs.
6. Given baseline validation is run, when project validation executes, then backend skeleton files and local smoke-test guidance are covered without requiring committed secrets or runtime-generated files.

## Tasks / Subtasks

- [x] Task 1: Create Spring Boot Maven backend skeleton (AC: #1, #2)
  - [x] Replace placeholder-only backend with a Maven Spring Boot project rooted at `syncro/apps/backend`.
  - [x] Configure Java 25 and Spring Boot 4.0.x stable baseline.
  - [x] Add Maven wrapper or clearly documented Maven invocation only if project pattern requires it; do not add unrelated build systems.
  - [x] Configure dependencies for Spring Web, Validation, Security, Data JPA, PostgreSQL, Flyway, Spring Data Redis, Actuator, MQTT client/integration, and Testcontainers.
  - [x] Add main application class under `com.syncro` package namespace.
- [x] Task 2: Add `/api/v1` health/readiness smoke endpoint (AC: #3)
  - [x] Add minimal API package structure under `com.syncro` that follows architecture package rules.
  - [x] Expose a backend-owned JSON endpoint under `/api/v1` for local smoke testing.
  - [x] Keep endpoint separate from direct infrastructure access; no frontend or external service calls are required for this story.
  - [x] Ensure Spring Security configuration allows this smoke endpoint and actuator health as appropriate for local baseline.
- [x] Task 3: Add environment/profile-based backend configuration (AC: #5)
  - [x] Add `application.yml` and local/profile configuration under `syncro/apps/backend/src/main/resources`.
  - [x] Configure datasource, Redis, Flyway, MQTT, server port, and actuator values from environment variables with safe defaults only where already documented.
  - [x] Use existing variable names from `syncro/.env.example`; do not invent duplicate names for the same services.
  - [x] Do not hardcode local service URLs for PostgreSQL, Redis, InfluxDB, EMQX, or WAHA.
- [x] Task 4: Add Flyway folder and baseline test structure (AC: #4, #6)
  - [x] Create Flyway migration folder under `syncro/apps/backend/src/main/resources/db/migration`.
  - [x] Create backend test resources and fixtures folders required by architecture if not present.
  - [x] Add tests proving application context loads and `/api/v1` smoke endpoint responds.
  - [x] Use Testcontainers configuration only where tests need infrastructure; do not require Docker for fast unit tests unless unavoidable.
- [x] Task 5: Update local development docs and validation coverage (AC: #1, #3, #5, #6)
  - [x] Update `syncro/docs/local-development.md` with backend build/run/test/smoke commands.
  - [x] Extend `syncro/scripts/validate-syncro-baseline.ps1` to verify backend skeleton essentials without depending on caller working directory.
  - [x] Document that backend config uses `.env`/environment values and keeps `.env` untracked.
  - [x] Verify validation passes from repository root.

### Review Findings

- [ ] [Review][Patch] Default profile embeds local endpoints and dev credentials [syncro/apps/backend/src/main/resources/application.yml:1]
- [ ] [Review][Patch] Backend context test depends on live local PostgreSQL [syncro/apps/backend/src/test/java/com/syncro/SyncroBackendApplicationTests.java:1]
- [ ] [Review][Patch] CSRF is globally disabled before auth mode is decided [syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java:1]
- [x] [Review][Defer] MQTT credentials are configured in backend but local EMQX MQTT auth is not enforced [syncro/infra/docker-compose.yml:71] — deferred, pre-existing/local infra auth story scope

## Dev Notes

### Scope Boundary

This story initializes backend skeleton only. Do not implement domain modules, authentication decisions, role authorization, persistence schemas, telemetry ingestion, InfluxDB writes, Redis key patterns, MQTT consumers, WAHA notification workers, Next.js frontend, production deployment manifests, or seed data beyond folders/config needed for skeleton readiness.

### Current State From Previous Stories

Story 1.1 established the monorepo baseline under `syncro/`, `.env.example`, local development docs, and cwd-independent baseline validation.

Story 1.2 added local Docker Compose infrastructure with stable service names and environment-driven configuration:

- `postgres`
- `pgadmin`
- `redis`
- `influxdb`
- `emqx`
- `waha`

Story 1.2 also added Compose project grouping with top-level `name: syncro-spring`, so local containers use the `syncro-spring-*` project prefix while preserving service DNS names inside Compose.

Backend currently contains only the placeholder path `syncro/apps/backend/.gitkeep`. Replace or remove placeholder only if real source files make it unnecessary.

### Required Backend Baseline

Use these baseline choices from project documentation and architecture:

| Area                  | Requirement                                               |
| --------------------- | --------------------------------------------------------- |
| Runtime               | Java 25 (Eclipse Temurin target)                          |
| Framework             | Spring Boot 4.0.x stable                                  |
| Build                 | Maven 3.9+                                                |
| Package prefix        | `com.syncro`                                              |
| API base path         | `/api/v1`                                                 |
| Backend root          | `syncro/apps/backend`                                     |
| Config files          | `syncro/apps/backend/src/main/resources/application*.yml` |
| Migrations            | `syncro/apps/backend/src/main/resources/db/migration`     |
| Backend test fixtures | `syncro/apps/backend/src/test/resources/fixtures`         |

### Required Dependencies

Configure dependencies that satisfy architecture and acceptance criteria:

- Spring Web
- Spring Validation
- Spring Security
- Spring Data JPA
- PostgreSQL Driver
- Flyway Migration
- Spring Data Redis
- Spring Boot Actuator
- MQTT client/integration: prefer Spring Integration MQTT if compatible with Spring Boot 3.5.x; Eclipse Paho is acceptable if needed by integration starter
- Scheduling support via Spring Framework scheduling annotation support; do not add a separate scheduling framework unless required
- Testcontainers for integration testing
- Spring Boot test support

Do not add unrelated libraries, code generators, or frontend tooling in this story.

### Environment Variables

Use existing `.env.example` variable names as backend config surface:

```env
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=syncro
POSTGRES_USER=syncro
POSTGRES_PASSWORD=syncro_dev
REDIS_HOST=localhost
REDIS_PORT=6379
INFLUXDB_HOST=localhost
INFLUXDB_PORT=8086
INFLUXDB_USERNAME=syncro
INFLUXDB_PASSWORD=syncro_dev_password
INFLUXDB_TOKEN=syncro-dev-token
INFLUXDB_ORG=syncro
INFLUXDB_BUCKET=syncro_telemetry
SYNCRO_MQTT_HOST=localhost
SYNCRO_MQTT_PORT=1883
SYNCRO_MQTT_USERNAME=syncro_backend
SYNCRO_MQTT_PASSWORD=syncro_mqtt_dev
SYNCRO_MQTT_CLIENT_ID=syncro-backend-1
SYNCRO_MQTT_TOPIC_FILTER=factory/+/+/telemetry
WAHA_HOST=localhost
WAHA_PORT=3000
WAHA_API_KEY=syncro-waha-dev-key
SPRING_PROFILES_ACTIVE=local
SERVER_PORT=8080
```

For Spring config, prefer property placeholders like `${POSTGRES_HOST}` and `${SERVER_PORT}`. Safe defaults may mirror `.env.example` only for local profile convenience, but secrets/tokens must remain overrideable and no real secret values may be introduced.

### Architecture Compliance

- Backend is the only application layer allowed to access PostgreSQL, Redis, InfluxDB, EMQX, and WAHA.
- Next.js must not access infrastructure services directly in later stories.
- External web API endpoints belong in backend API packages and must use REST JSON conventions under `/api/v1`.
- Infrastructure details belong behind adapters/configuration, not inside controllers.
- PostgreSQL owns relational data; InfluxDB owns accepted telemetry history; Redis owns hot/latest state and cache only.
- EMQX broker details must come from MQTT environment variables, never hardcoded backend URLs.
- WAHA endpoint/API key config can be surfaced for future notification stories, but do not implement sends here.
- pgAdmin remains local/dev only and must not become backend dependency.

### Suggested Source Layout

Minimal skeleton can use this structure or equivalent package-consistent layout:

```text
syncro/apps/backend/
├── pom.xml
├── src/main/java/com/syncro/SyncroBackendApplication.java
├── src/main/java/com/syncro/api/HealthController.java
├── src/main/java/com/syncro/config/SecurityConfig.java
├── src/main/java/com/syncro/config/MqttProperties.java
├── src/main/resources/application.yml
├── src/main/resources/application-local.yml
├── src/main/resources/db/migration/.gitkeep
├── src/test/java/com/syncro/SyncroBackendApplicationTests.java
├── src/test/java/com/syncro/api/HealthControllerTest.java
└── src/test/resources/fixtures/.gitkeep
```

If Spring Boot generated naming differs, keep package prefix and architecture intent intact. Avoid creating domain-specific package trees before domain stories exist.

### Validation Requirements

Before marking implementation complete, dev agent should run commands from repository root:

```powershell
pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1
mvn -f syncro/apps/backend/pom.xml test
mvn -f syncro/apps/backend/pom.xml spring-boot:run
```

For runtime smoke test after app starts:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

If local infrastructure is needed for context-load tests, start it with:

```powershell
docker compose --env-file syncro/.env.example -f syncro/infra/docker-compose.yml up -d
```

Do not claim boot or smoke-test success unless commands actually run successfully. If Docker or Maven is unavailable, record exact blocker in Dev Agent Record and still run all possible static validation.

### Testing Requirements

- Application context test must prove backend starts with test profile/config.
- Controller test must prove `/api/v1` smoke endpoint returns successful JSON.
- Tests must not require committed `.env` or real secrets.
- If Testcontainers dependency is present but not used in this skeleton, keep future integration test setup minimal and non-invasive.
- Validation script must remain callable from repository root, `syncro/`, and `syncro/scripts/` if modified.

### Previous Story Intelligence

- `validate-syncro-baseline.ps1` already derives repository root from `$PSScriptRoot`; preserve that behavior.
- Story 1.2 validation checks Compose file and pgAdmin boundary docs; do not weaken those checks while adding backend checks.
- `.env.example` is the authoritative documented local variable surface.
- Compose config uses `${VAR:?VAR is required}` guards; backend docs should steer developers to copy `.env.example` to `.env` for local runs.
- Local infrastructure may already be running under Compose project `syncro-spring`; avoid commands that delete volumes unless user explicitly asks.

### Project Structure Notes

No project-structure conflict detected. Story 1.3 fills `syncro/apps/backend/`, which is already reserved by the repository baseline.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.3: Initialize Spring Boot Backend Skeleton]
- [Source: _bmad-output/planning-artifacts/architecture.md#Backend Application]
- [Source: _bmad-output/planning-artifacts/architecture.md#Complete Project Directory Structure]
- [Source: _bmad-output/planning-artifacts/architecture.md#REST API Style]
- [Source: _bmad-output/planning-artifacts/architecture.md#External API Conventions]
- [Source: _bmad-output/planning-artifacts/architecture.md#MQTT Broker Boundary]
- [Source: _bmad-output/planning-artifacts/research/technical-next-js-spring-boot-openjdk25-maven-mqtt-emqx-redis-postgresql-influxdb-research-2026-05-25.md]
- [Source: syncro/README.md#Version Baseline]
- [Source: syncro/.env.example]
- [Source: syncro/docs/local-development.md]
- [Source: _bmad-output/implementation-artifacts/1-2-start-local-infrastructure-stack.md]

## Dev Agent Record

### Agent Model Used

cx/gpt-5.5

### Debug Log References

- RED: Initial Maven test run failed because Testcontainers dependency versions were not resolved by the first handwritten Spring Boot 4 POM.
- FIX: Regenerated skeleton baseline from Spring Initializr for Spring Boot 4.0.6, then added MQTT dependency and project-specific API/config files.
- GREEN: `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1` passed.
- GREEN: `mvn -f syncro/apps/backend/pom.xml test` passed with 2 tests.
- GREEN: `mvn -f syncro/apps/backend/pom.xml spring-boot:run` booted successfully and `Invoke-RestMethod http://localhost:8080/api/v1/health` returned status `UP`.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.
- Initialized Spring Boot 4.0.6 backend skeleton from Spring Initializr with Java 25, Maven, wrapper files, core dependencies, scheduling, and `com.syncro` package baseline.
- Added `/api/v1/health` smoke endpoint plus Spring Security configuration permitting the smoke endpoint and actuator health.
- Added environment-driven `application.yml` and `application-local.yml` for datasource, Redis, Flyway, MQTT, InfluxDB, WAHA, actuator, and server port values.
- Added Flyway migration folder, backend fixtures folder, context-load test, and controller smoke endpoint test.
- Updated local development docs and baseline validation coverage for backend commands, smoke endpoint, dependencies, config env keys, and required skeleton files.

### File List

- _bmad-output/implementation-artifacts/1-3-initialize-spring-boot-backend-skeleton.md
- _bmad-output/implementation-artifacts/sprint-status.yaml
- syncro/apps/backend/.gitattributes
- syncro/apps/backend/.gitkeep
- syncro/apps/backend/.mvn/wrapper/maven-wrapper.properties
- syncro/apps/backend/mvnw
- syncro/apps/backend/mvnw.cmd
- syncro/apps/backend/pom.xml
- syncro/apps/backend/src/main/java/com/syncro/SyncroBackendApplication.java
- syncro/apps/backend/src/main/java/com/syncro/api/HealthController.java
- syncro/apps/backend/src/main/java/com/syncro/config/MqttProperties.java
- syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java
- syncro/apps/backend/src/main/resources/application-local.yml
- syncro/apps/backend/src/main/resources/application.yml
- syncro/apps/backend/src/main/resources/db/migration/.gitkeep
- syncro/apps/backend/src/test/java/com/syncro/SyncroBackendApplicationTests.java
- syncro/apps/backend/src/test/java/com/syncro/api/HealthControllerTest.java
- syncro/apps/backend/src/test/resources/fixtures/.gitkeep
- syncro/docs/local-development.md
- syncro/scripts/validate-syncro-baseline.ps1

### Change Log

- 2026-05-25: Implemented Story 1.3 Spring Boot 4.0.6 backend skeleton and moved story to review.
