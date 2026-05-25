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

## Current Story Boundary

Story 1.2 initializes only Docker Compose local infrastructure, local development docs, and baseline validation. Spring Boot, Next.js, migrations, MQTT consumers, telemetry schemas, and notification workers are initialized in later stories.




Changing InfluxDB first-init credentials after volumes are created requires removing the local InfluxDB volumes or recreating the stack with fresh volumes.

