# Local Development

This document records the initial development baseline for Syncro. It references architecture decisions in `_bmad-output/planning-artifacts/architecture.md` and keeps implementation work under `syncro/`.

## Architecture Constraints

- All application code lives under `syncro/`.
- Backend code belongs in `syncro/apps/backend/` with Java package prefix `com.syncro`.
- Frontend code belongs in `syncro/apps/web/` with kebab-case routes and PascalCase components.
- Local infrastructure belongs in `syncro/infra/`.
- Cross-stack E2E tests and shared fixtures belong in `syncro/tests/`.
- Stable infrastructure service names are `postgres`, `pgadmin`, `redis`, `influxdb`, `emqx`, and `waha`.

## Environment Setup

1. Copy `syncro/.env.example` to `syncro/.env`.
2. Keep `.env` and all local secrets untracked.
3. Use development-safe placeholder values only in `.env.example`.

## Local Infrastructure

Start the local dependency stack from the repository root. Compose groups containers under project `syncro-spring`:

```powershell
docker compose --env-file syncro/.env -f syncro/infra/docker-compose.yml up -d
```

Stop the stack:

```powershell
docker compose --env-file syncro/.env -f syncro/infra/docker-compose.yml down
```

Inspect service status and logs:

```powershell
docker compose --env-file syncro/.env -f syncro/infra/docker-compose.yml ps
docker compose --env-file syncro/.env -f syncro/infra/docker-compose.yml logs postgres
docker compose --env-file syncro/.env -f syncro/infra/docker-compose.yml logs emqx
```

Validate Compose syntax without starting services. Use `.env.example` for baseline validation, or `.env` for your local overrides:

```powershell
docker compose --env-file syncro/.env.example -f syncro/infra/docker-compose.yml config
```

### Local Endpoints

| Service | Compose DNS | Host endpoint | Notes |
|---|---|---|---|
| PostgreSQL | `postgres:5432` | `localhost:5432` | Backend uses `POSTGRES_*` variables. |
| pgAdmin | `pgadmin:80` | `http://localhost:${PGADMIN_PORT}` | Local/dev inspection only. |
| Redis | `redis:6379` | `localhost:6379` | Cache/latest-state dependency. |
| InfluxDB | `influxdb:8086` | `http://localhost:8086` | Uses `INFLUXDB_USERNAME`, `INFLUXDB_PASSWORD`, org, bucket, and token on first init. |
| EMQX MQTT | `emqx:1883` | `localhost:1883` | Local MQTT broker. |
| EMQX Dashboard | `emqx:18083` | `http://localhost:${SYNCRO_MQTT_DASHBOARD_PORT}` | Local broker admin only. |
| WAHA | `waha:3000` | `http://localhost:3000` | WhatsApp API dependency. |

pgAdmin is local/dev only. It is not a runtime dependency, user-facing feature, production requirement, or replacement for application admin UI.

Backend and future stories must read service URLs, credentials, and ports from environment variables. Do not hardcode PostgreSQL, Redis, InfluxDB, EMQX, or WAHA URLs in source code.

## Backend

Build and test the Spring Boot backend from the repository root:

```powershell
mvn -f syncro/apps/backend/pom.xml test
```

Run the backend locally after copying `syncro/.env.example` to `syncro/.env` and starting required local infrastructure:

```powershell
mvn -f syncro/apps/backend/pom.xml spring-boot:run
```

Smoke-test the backend health endpoint:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

Backend configuration reads service hosts, ports, credentials, MQTT client identity, and topic filter from environment variables aligned with `syncro/.env.example`. Keep `syncro/.env` untracked and do not hardcode PostgreSQL, Redis, InfluxDB, EMQX, or WAHA URLs in backend source.

## Frontend

Install dependencies from the repository root:

```powershell
npm --prefix syncro/apps/web install
```

Run the Next.js development server:

```powershell
npm --prefix syncro/apps/web run dev
```

Build the frontend shell:

```powershell
npm --prefix syncro/apps/web run build
```

Run configured frontend checks:

```powershell
npm --prefix syncro/apps/web run lint
npm --prefix syncro/apps/web run check
```

The frontend shell uses `NEXT_PUBLIC_API_URL` only as a browser-visible backend base URL placeholder. Do not put PostgreSQL, Redis, InfluxDB, EMQX, WAHA credentials, or private API keys in frontend environment files.

## Auth Baseline

Story 1.5 selects Spring Security JWT auth for Phase 1. Backend exposes `POST /api/v1/auth/login` and `GET /api/v1/auth/me`; all other `/api/v1/**` endpoints require a bearer token except `/api/v1/health` and `/actuator/health`.

Local development can bootstrap one SUPER_ADMIN account when `SYNCRO_AUTH_LOCAL_ADMIN_ENABLED=true`:

| Variable | Purpose |
|---|---|
| `SYNCRO_AUTH_JWT_SECRET` | HMAC secret for local JWT signing. Replace before shared/non-local use. |
| `SYNCRO_AUTH_JWT_ISSUER` | JWT issuer expected by backend validation. |
| `SYNCRO_AUTH_JWT_TTL_MINUTES` | Access token lifetime in minutes. |
| `SYNCRO_AUTH_LOCAL_ADMIN_LOGIN` | Local SUPER_ADMIN login identifier. |
| `SYNCRO_AUTH_LOCAL_ADMIN_PASSWORD` | Local bootstrap password, hashed before storage. |

Example login check:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/auth/login -Method Post -ContentType 'application/json' -Body '{"loginIdentifier":"admin@syncro.dev","password":"syncro-admin-dev"}'
```

The frontend stores the Phase 1 JWT in browser-managed cookies for route guarding and API calls. This is a local baseline only: backend JWT validation remains the authority, browser storage is not a permission source, client-managed cookies are not HttpOnly and remain exposed to XSS, and role-specific authorization is Story 1.6 scope.

## Current Story Boundary

Story 1.5 implements authenticated access baseline. Role enforcement, plant scoping, domain screens, telemetry calculations, alerts, WAHA workflows, and health data arrive in later stories.




Changing InfluxDB first-init credentials after volumes are created requires removing the local InfluxDB volumes or recreating the stack with fresh volumes.

## InfluxDB Optional-Field Type Migration (DW-114)

Since the DW-28 change, every numeric optional telemetry field is written as a Double (`InfluxTelemetryWriter` coerces via `doubleValue()`). InfluxDB rejects a field-type change within a measurement, so a bucket that already holds integer-typed samples for a field (e.g. `rpm=1200i` from before the change) rejects the first `rpm=1200.0` point.

- **Dev/CI buckets** — reset is the supported path: `docker compose down -v` for the InfluxDB service (drops the named `influxdb_data` volume) then `docker compose up -d`. The backend recreates the bucket on startup.
- **Production/preview buckets with legacy integer samples** — do NOT rely on a plain reset; rewrite the affected measurement before the new backend writes to it. In InfluxDB 3 the storage contract is per measurement, so either migrate the bucket to a fresh measurement (write the legacy series into a new measurement that only ever receives Double values) or recreate the bucket and re-import the data from the durable source (PostgreSQL `machine_counter_states` / Redis latest hash are not full telemetry history — the telemetry is only in InfluxDB, so prefer a measurement rewrite over a bucket drop).
- After migration, verify with a probe write of a float-valued sample for every configured optional field before enabling the new backend against the bucket.

## Managed PostgreSQL: btree_gist Extension Prerequisite (DW-139)

Migration `V49__repair_sessions_mttr.sql` is the first migration in the chain that installs an extension: `CREATE EXTENSION IF NOT EXISTS btree_gist` (required by the gist EXCLUDE constraint on `repair_sessions`). Local `postgres:17-alpine` applies it automatically because the container superuser owns the database.

Managed PostgreSQL (RDS, Cloud SQL, Supabase, …) does **not** grant extension-creation to the application user by default. Before the first Flyway run against a managed database:

1. Connect as a superuser (e.g. the RDS master user, or `cloudsqlsuperuser` on Cloud SQL).
2. Run `CREATE EXTENSION IF NOT EXISTS btree_gist;` in the target database.
3. Grant the application user `USAGE` on the extension if the provider requires it (check provider docs; RDS needs no extra grant after creation).

Deploying a managed database without this step fails the migration at V49 with `permission denied to create extension "btree_gist"`. Add this to the deployment runbook before the Flyway step.
