# Syncro

Syncro is an industrial maintenance and machine telemetry platform. This repository keeps all application source under `syncro/` and separates backend, frontend, infrastructure, scripts, documentation, and tests.

## Repository Structure

```text
syncro/
├── apps/
│   ├── backend/
│   └── web/
├── infra/
│   ├── postgres/init/
│   ├── pgadmin/
│   ├── redis/
│   ├── influxdb/
│   ├── emqx/etc/
│   └── waha/
├── scripts/
├── docs/
└── tests/
    ├── e2e/
    └── fixtures/
```

## Version Baseline

| Technology | Selected Version | Notes |
|-----------|------------------|-------|
| Java | 25 (Eclipse Temurin) | Backend runtime target |
| Spring Boot | 4.0.6 | Backend framework baseline |
| Maven | 3.9+ | Backend build tool |
| Next.js | 16 | Frontend baseline from selected dashboard boilerplate |
| Node.js | 22 LTS | Required by Next.js 16 baseline |
| npm | 10+ | Default package manager |
| TypeScript | 5.x | Frontend type system |
| Tailwind CSS | v4 | Frontend styling baseline |
| PostgreSQL | 16+ | Primary relational database |
| Redis | 7+ | Cache and latest-state store |
| InfluxDB | 3 Core | Telemetry time-series store |
| EMQX | 5.x | MQTT broker |
| WAHA | latest stable | WhatsApp API integration |

## Local Development

1. Copy `.env.example` to `.env`.
2. Keep secrets and local overrides out of Git.
3. Follow architecture constraints in `_bmad-output/planning-artifacts/architecture.md`.
4. Use stable infrastructure service names: `postgres`, `pgadmin`, `redis`, `influxdb`, `emqx`, `waha`.

Application implementations are added by later stories. This baseline only creates the repository foundation.
