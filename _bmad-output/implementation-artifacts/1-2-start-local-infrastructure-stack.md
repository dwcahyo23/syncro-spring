# Story 1.2: Start Local Infrastructure Stack

Status: done

## Story

As an implementer,
I want local infrastructure services to run through Docker Compose,
so that backend, telemetry, notification, and validation stories have required dependencies.

## Acceptance Criteria

1. Given local development machine with Docker available, when local infrastructure is started from `syncro/infra/docker-compose.yml`, then services start with stable names `postgres`, `pgadmin`, `redis`, `influxdb`, `emqx`, and `waha`.
2. Given PostgreSQL service is running, when backend configuration uses `.env.example` values, then PostgreSQL is reachable at the documented host, port, database, user, and password.
3. Given pgAdmin service is running, when a developer opens pgAdmin locally, then PostgreSQL can be inspected during local development.
4. Given EMQX service is running, when backend stories configure MQTT later, then EMQX is available as a local MQTT broker without hardcoded backend URLs.
5. Given WAHA service is running, when notification stories configure WhatsApp later, then WAHA local endpoint and API key are documented.
6. Given infrastructure docs are updated, when a developer reads local development instructions, then docs state pgAdmin is local/dev only and not a runtime dependency or production requirement.

## Tasks / Subtasks

- [x] Task 1: Create Docker Compose local infrastructure stack (AC: #1)
  - [x] Add `syncro/infra/docker-compose.yml`.
  - [x] Define services with exact names: `postgres`, `pgadmin`, `redis`, `influxdb`, `emqx`, `waha`.
  - [x] Load configuration from `../.env` or `.env.example`-compatible variables without committing secrets.
  - [x] Use deterministic container names only if they do not conflict with service DNS names.
- [x] Task 2: Configure PostgreSQL and pgAdmin for local development (AC: #2, #3, #6)
  - [x] Configure PostgreSQL using `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `POSTGRES_PORT` from `syncro/.env.example`.
  - [x] Mount local PostgreSQL init directory from `syncro/infra/postgres/init/`.
  - [x] Configure pgAdmin using `PGADMIN_EMAIL` and `PGADMIN_PASSWORD`.
  - [x] Ensure pgAdmin can reach PostgreSQL by service DNS name `postgres` inside Compose network.
- [x] Task 3: Configure Redis, InfluxDB, EMQX, and WAHA baseline services (AC: #1, #4, #5)
  - [x] Expose Redis on documented `REDIS_PORT`.
  - [x] Configure InfluxDB with documented org, bucket, token, and port variables.
  - [x] Configure EMQX as local MQTT broker with MQTT listener on `SYNCRO_MQTT_PORT` and dashboard/API only for local dev if exposed.
  - [x] Configure WAHA with documented `WAHA_PORT` and `WAHA_API_KEY`.
- [x] Task 4: Document local infrastructure usage (AC: #2-#6)
  - [x] Update `syncro/docs/local-development.md` with start, stop, logs, and health-check commands.
  - [x] Document local endpoints for PostgreSQL, pgAdmin, Redis, InfluxDB, EMQX, and WAHA.
  - [x] Explicitly state pgAdmin is local/dev only and not a production requirement.
  - [x] State backend and future stories must use environment variables, not hardcoded service URLs.
- [x] Task 5: Add validation script coverage for infrastructure baseline (AC: #1-#6)
  - [x] Extend `syncro/scripts/validate-syncro-baseline.ps1` to verify `syncro/infra/docker-compose.yml` exists.
  - [x] Validate the Compose file contains the six required service keys.
  - [x] Validate local development docs mention pgAdmin local/dev-only boundary.


### Review Findings

- [x] [Review][Patch] Compose variables do not fail fast when required env values are missing [syncro/infra/docker-compose.yml:8]
- [x] [Review][Patch] pgAdmin host port is hardcoded instead of environment-configured [syncro/infra/docker-compose.yml:28]
- [x] [Review][Patch] EMQX dashboard host port is hardcoded instead of environment-configured [syncro/infra/docker-compose.yml:78]
- [x] [Review][Patch] InfluxDB init username/password bypass documented env surface [syncro/infra/docker-compose.yml:55]

## Dev Notes

### Scope Boundary

This story creates local infrastructure configuration only. Do not initialize Spring Boot, Next.js, Flyway migrations, application code, MQTT consumers, Redis key patterns, InfluxDB schemas, WAHA notification workers, or production deployment manifests. Those belong to later stories.

### Current State From Previous Story

Story 1.1 created the required skeleton and baseline files:

- `syncro/infra/postgres/init/.gitkeep`
- `syncro/infra/pgadmin/.gitkeep`
- `syncro/infra/redis/.gitkeep`
- `syncro/infra/influxdb/.gitkeep`
- `syncro/infra/emqx/etc/.gitkeep`
- `syncro/infra/waha/.gitkeep`
- `syncro/.env.example`
- `syncro/docs/local-development.md`
- `syncro/scripts/validate-syncro-baseline.ps1`

Review patch from Story 1.1 made `validate-syncro-baseline.ps1` cwd-independent by deriving repository root from `$PSScriptRoot`. Preserve that behavior when extending validation.

### Required Environment Variables

Use names already documented in `syncro/.env.example`; do not invent duplicate names.

```env
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=syncro
POSTGRES_USER=syncro
POSTGRES_PASSWORD=syncro_dev
PGADMIN_EMAIL=admin@syncro.dev
PGADMIN_PASSWORD=admin
REDIS_HOST=localhost
REDIS_PORT=6379
INFLUXDB_HOST=localhost
INFLUXDB_PORT=8086
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
```

If Compose needs service-internal URLs, use service DNS names inside Compose docs, e.g. `postgres`, `redis`, `influxdb`, `emqx`, `waha`. Keep host-facing examples as `localhost` for developer machines.

### Architecture Compliance

- Local dev infrastructure must be through Docker Compose at `syncro/infra/docker-compose.yml`.
- Stable service names are mandatory: `postgres`, `pgadmin`, `redis`, `influxdb`, `emqx`, `waha`.
- PostgreSQL owns master/auth/config/alerts/audit/notification job data.
- InfluxDB owns accepted telemetry history.
- Redis owns latest telemetry/cache/rate-limit keys and must remain rebuildable, not source of truth.
- EMQX is local/dev MQTT broker infrastructure; backend MQTT config must come from environment, never hardcoded backend URLs.
- WAHA is external WhatsApp API dependency reached through configuration; notification sends must remain later queued-job work.
- pgAdmin is local-dev/admin evidence tool only. It is not runtime dependency, user-facing feature, production requirement, or replacement for application admin UI.
- Next.js must not access PostgreSQL, InfluxDB, Redis, EMQX, or WAHA directly in later stories.

### Suggested Service Version Baseline

Use compatible versions aligned with existing README and architecture unless current image tags require patch adjustment during implementation:

| Service | Baseline |
|---|---|
| PostgreSQL | 16+ |
| pgAdmin | latest stable local/dev image |
| Redis | 7+ |
| InfluxDB | 3 Core; use v2-compatible setup only if image support requires |
| EMQX | 5.x |
| WAHA | latest stable |

Do not use `latest` blindly for core datastores if stable major tags are available. Pin major versions where practical.

### File Structure Requirements

Expected files to create or update:

```text
syncro/infra/docker-compose.yml
syncro/docs/local-development.md
syncro/scripts/validate-syncro-baseline.ps1
_bmad-output/implementation-artifacts/1-2-start-local-infrastructure-stack.md
_bmad-output/implementation-artifacts/sprint-status.yaml
```

Do not move infra outside `syncro/infra/`. Do not create root-level `docker-compose.yml`.

### Validation Requirements

Before marking implementation complete, dev agent should run:

```powershell
pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1
docker compose --env-file syncro/.env.example -f syncro/infra/docker-compose.yml config
docker compose --env-file syncro/.env.example -f syncro/infra/docker-compose.yml up -d
docker compose --env-file syncro/.env.example -f syncro/infra/docker-compose.yml ps
docker compose --env-file syncro/.env.example -f syncro/infra/docker-compose.yml down
```

If Docker is unavailable in implementation environment, dev agent must still run `docker compose ... config` if possible and record exact blocker. Do not claim services started unless verified.

### Testing Requirements

- Validate Compose syntax with `docker compose config`.
- Validate required services appear in Compose output: `postgres`, `pgadmin`, `redis`, `influxdb`, `emqx`, `waha`.
- If Docker daemon is available, start stack and verify `docker compose ps` shows all services created/running or healthy according to image behavior.
- Verify pgAdmin docs explain local/dev-only boundary.
- Verify no `.env`, credentials, runtime volumes, logs, or generated data are committed.

### Previous Story Intelligence

- Story 1.1 established project root convention: all application source lives under `syncro/`.
- Existing validation script must remain callable from repository root, `syncro/`, and `syncro/scripts/`.
- Empty infra subdirectories already exist and should be reused, not recreated elsewhere.
- `.env.example` is the source for documented local dev variables.

### Git Intelligence Summary

Recent commits show Story 1.1 added baseline files and then a review patch for cwd-independent validation. Preserve exact-path staging discipline and keep BMad status updates scoped to the current story.

### Latest Technical Information

Technical research supports Docker Compose for local development and purpose-specific services:

- EMQX owns MQTT connection management, subscriptions, broker clustering, topic routing, and device ingress security.
- PostgreSQL and Redis should stay private service resources; Redis is cache/hot state only.
- InfluxDB 3 Core is preferred for time-series telemetry; schema and write path are later-story concerns.
- WAHA calls need timeout/retry/circuit breaker later, but this story only provides local endpoint configuration.

### Project Structure Notes

No conflicts detected. Story 1.2 fills the `syncro/infra/` placeholder created by Story 1.1.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.2: Start Local Infrastructure Stack]
- [Source: _bmad-output/planning-artifacts/architecture.md#Complete Project Directory Structure]
- [Source: _bmad-output/planning-artifacts/architecture.md#MQTT Broker Boundary]
- [Source: _bmad-output/planning-artifacts/architecture.md#pgAdmin Boundary]
- [Source: _bmad-output/planning-artifacts/architecture.md#Stable Local Infra Service Names]
- [Source: _bmad-output/planning-artifacts/research/technical-next-js-spring-boot-openjdk25-maven-mqtt-emqx-redis-postgresql-influxdb-research-2026-05-25.md#Research Overview]
- [Source: syncro/.env.example]
- [Source: syncro/docs/local-development.md]
- [Source: _bmad-output/implementation-artifacts/1-1-initialize-monorepo-and-version-baseline.md]

## Dev Agent Record

### Agent Model Used

cx/gpt-5.5

### Debug Log References

- RED: baseline validation initially passed before Story 1.2 infra checks existed, exposing missing validation coverage.
- GREEN: `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1` passed after Compose and doc checks were added.
- GREEN: `docker compose --env-file syncro/.env.example -f syncro/infra/docker-compose.yml config` passed.
- GREEN: `docker compose --env-file syncro/.env.example -f syncro/infra/docker-compose.yml up -d` started all services; `ps` verified `postgres`, `redis`, `influxdb`, and `emqx` healthy, with `pgadmin` and `waha` up.
- FIX: pgAdmin rejected `admin@syncro.local`; `.env.example` placeholder changed to `admin@syncro.dev` and pgAdmin recreated successfully.
- CLEANUP: `docker compose --env-file syncro/.env.example -f syncro/infra/docker-compose.yml down` stopped and removed local containers/network.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.
- Added Docker Compose local infrastructure stack with stable service names and environment-driven configuration.
- Updated local development docs with start/stop/config/status/log commands, service endpoints, and pgAdmin local/dev-only boundary.
- Extended baseline validation to require `syncro/infra/docker-compose.yml`, required service keys, and pgAdmin boundary documentation.
- Verified Compose config and runtime startup; stopped stack after validation.`r`n- Resolved code review patches for required Compose env guards, configurable admin ports, documented InfluxDB init credentials, and `.env`-based docs commands.

### File List

- syncro/.env.example
- syncro/docs/local-development.md
- syncro/infra/docker-compose.yml
- syncro/scripts/validate-syncro-baseline.ps1
- _bmad-output/implementation-artifacts/1-2-start-local-infrastructure-stack.md
- _bmad-output/implementation-artifacts/sprint-status.yaml

### Change Log

- 2026-05-25: Implemented Story 1.2 local infrastructure stack and moved story to review.`r`n- 2026-05-25: Addressed code review patches and moved story to done.


