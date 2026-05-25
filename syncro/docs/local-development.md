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

## Current Story Boundary

Story 1.1 initializes only directories, baseline configuration, and documentation. Spring Boot, Next.js, Docker Compose, and application code are initialized in later stories.
