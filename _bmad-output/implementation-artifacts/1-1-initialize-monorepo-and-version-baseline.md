# Story 1.1: Initialize Monorepo and Version Baseline

Status: review

## Story

As an implementer,
I want the Syncro repository structure, dependency versions, and documentation baseline initialized,
so that future stories are built consistently from one known foundation.

## Acceptance Criteria

1. Given empty Syncro project workspace, when repository foundation is initialized, then root structure includes `syncro/apps/backend`, `syncro/apps/web`, `syncro/infra`, `syncro/scripts`, `syncro/docs`, and `syncro/tests`.
2. Given repository structure exists, when dependency versions are selected, then README or docs records all selected dependency versions (Java, Spring Boot, Next.js, Node, npm, PostgreSQL, Redis, InfluxDB, EMQX, WAHA).
3. Given repository structure exists, when environment configuration is created, then `.env.example` exists at root with documented variables for all infrastructure services.
4. Given repository structure exists, when architecture constraints are documented, then implementation notes reference architecture decisions from `architecture.md`.
5. Given repository structure exists, when `.gitignore` is created, then it excludes `node_modules/`, `target/`, `.next/`, `.env`, `*.log`, OS files, IDE files, and local database volumes.

## Tasks / Subtasks

- [x] Task 1: Create monorepo root structure (AC: #1)
  - [x] Create `syncro/` root directory
  - [x] Create `syncro/apps/backend/` placeholder (pom.xml created in Story 1.3)
  - [x] Create `syncro/apps/web/` placeholder (initialized in Story 1.4)
  - [x] Create `syncro/infra/` with subdirectories: `postgres/init/`, `pgadmin/`, `redis/`, `influxdb/`, `emqx/etc/`, `waha/`
  - [x] Create `syncro/scripts/` directory
  - [x] Create `syncro/docs/` directory
  - [x] Create `syncro/tests/e2e/` and `syncro/tests/fixtures/` directories
- [x] Task 2: Create root configuration files (AC: #3, #5)
  - [x] Create `syncro/.env.example` with all infrastructure service variables
  - [x] Create `syncro/.gitignore` with comprehensive exclusions
- [x] Task 3: Document version baseline (AC: #2, #4)
  - [x] Create `syncro/README.md` with project overview, tech stack versions, and local dev instructions placeholder
  - [x] Create `syncro/docs/local-development.md` placeholder referencing architecture decisions
- [x] Task 4: Validate structure (AC: #1)
  - [x] Verify all directories exist and are not empty (use `.gitkeep` where needed)
  - [x] Verify `.env.example` contains all required variables

## Dev Notes

### Architecture Compliance

This story creates ONLY the directory skeleton. It does NOT:
- Initialize Spring Boot (Story 1.3)
- Initialize Next.js frontend (Story 1.4)
- Create Docker Compose (Story 1.2)
- Implement any application code

The directory structure MUST match the architecture document exactly. Do not add extra directories or rename modules.

### Technology Versions to Document

Pin these in README.md (verify latest stable at implementation time):

| Technology | Target Version | Notes |
|-----------|---------------|-------|
| Java | 25 (Eclipse Temurin) | LTS — verify Adoptium artifact availability |
| Spring Boot | 4.x or 3.5.x stable | Pin at implementation time |
| Maven | 3.9+ | Backend build tool |
| Next.js | 16 | From boilerplate |
| Node.js | 22 LTS | Required by Next.js 16 |
| npm | 10+ | Package manager (from boilerplate) |
| TypeScript | 5.x | From boilerplate |
| Tailwind CSS | v4 | From boilerplate |
| PostgreSQL | 16+ | Primary relational store |
| Redis | 7+ | Cache/latest state |
| InfluxDB | 3 Core | Telemetry time-series |
| EMQX | 5.x | MQTT broker |
| WAHA | latest | WhatsApp API |

### Environment Variables (.env.example)

```env
# PostgreSQL
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=syncro
POSTGRES_USER=syncro
POSTGRES_PASSWORD=syncro_dev

# pgAdmin (local/dev only)
PGADMIN_EMAIL=admin@syncro.local
PGADMIN_PASSWORD=admin

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# InfluxDB
INFLUXDB_HOST=localhost
INFLUXDB_PORT=8086
INFLUXDB_TOKEN=syncro-dev-token
INFLUXDB_ORG=syncro
INFLUXDB_BUCKET=syncro_telemetry

# EMQX MQTT
SYNCRO_MQTT_HOST=localhost
SYNCRO_MQTT_PORT=1883
SYNCRO_MQTT_USERNAME=syncro_backend
SYNCRO_MQTT_PASSWORD=syncro_mqtt_dev
SYNCRO_MQTT_CLIENT_ID=syncro-backend-1
SYNCRO_MQTT_TOPIC_FILTER=factory/+/+/telemetry

# WAHA
WAHA_HOST=localhost
WAHA_PORT=3000
WAHA_API_KEY=syncro-waha-dev-key

# Backend
SPRING_PROFILES_ACTIVE=local
SERVER_PORT=8080

# Frontend
NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1
```

### Naming Conventions (from Architecture)

- **Database:** `snake_case` plural tables, `snake_case` columns, `{table_singular}_id` foreign keys
- **Java:** `com.syncro.{module}` packages, `PascalCase` classes, `camelCase` methods/fields
- **API:** `/api/v1/{plural-resource}`, `camelCase` JSON fields
- **Frontend:** kebab-case routes, `PascalCase` components, `use<Domain><Action>` hooks

### Infrastructure Service Names (Stable)

These names are used in Docker Compose and must remain stable across all stories:

```text
postgres
pgadmin
redis
influxdb
emqx
waha
```

### Project Structure Notes

- All application code lives under `syncro/` (not project root)
- BMad artifacts stay at project root under `_bmad-output/`
- Claude/agent config stays at project root under `.claude/`, `.agent/`, `.agents/`
- `syncro/infra/` owns all Docker Compose and service config
- `syncro/apps/backend/` and `syncro/apps/web/` are separate applications
- `syncro/tests/` holds cross-stack E2E tests and shared fixtures
- `syncro/scripts/` holds pilot validation and dev utility scripts

### References

- [Source: _bmad-output/planning-artifacts/architecture.md#Complete Project Directory Structure]
- [Source: _bmad-output/planning-artifacts/architecture.md#Starter Template Evaluation]
- [Source: _bmad-output/planning-artifacts/architecture.md#Naming Patterns]
- [Source: _bmad-output/planning-artifacts/architecture.md#MQTT Broker Boundary]
- [Source: _bmad-output/planning-artifacts/architecture.md#Stable Local Infra Service Names]
- [Source: _bmad-output/planning-artifacts/frontend-hardening-specification.md#Route Structure After Initialization]

## Dev Agent Record

### Agent Model Used

cx/gpt-5.5

### Debug Log References

- RED: temporary `validate-syncro-baseline.ps1` failed before skeleton existed, reporting missing required directories and files.
- GREEN: `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1` passed.

### Completion Notes List

- Created Syncro monorepo baseline directories under `syncro/` only.
- Added `.gitkeep` placeholders for empty implementation directories.
- Added `syncro/.env.example` with required infrastructure, backend, and frontend variables.
- Added `syncro/.gitignore` covering Node, Next.js, Java, Maven, logs, local infra data, secrets, and temp files.
- Added `syncro/README.md` with selected version baseline and repository structure.
- Added `syncro/docs/local-development.md` referencing architecture constraints and story boundary.
- Added `syncro/scripts/validate-syncro-baseline.ps1` to verify required structure and environment keys.

### File List

- syncro/.env.example
- syncro/.gitignore
- syncro/README.md
- syncro/apps/backend/.gitkeep
- syncro/apps/web/.gitkeep
- syncro/docs/local-development.md
- syncro/infra/emqx/etc/.gitkeep
- syncro/infra/influxdb/.gitkeep
- syncro/infra/pgadmin/.gitkeep
- syncro/infra/postgres/init/.gitkeep
- syncro/infra/redis/.gitkeep
- syncro/infra/waha/.gitkeep
- syncro/scripts/validate-syncro-baseline.ps1
- syncro/tests/e2e/.gitkeep
- syncro/tests/fixtures/.gitkeep
- _bmad-output/implementation-artifacts/1-1-initialize-monorepo-and-version-baseline.md
- _bmad-output/implementation-artifacts/sprint-status.yaml

### Change Log

- 2026-05-25: Implemented Story 1.1 monorepo baseline and moved story to review.
