---
project_name: 'Syncro'
user_name: 'Yusuf'
date: '2026-08-24'
status: 'complete'
rule_count: 236
optimized_for_llm: true
sections_completed: ['discovery', 'technology_stack', 'documentation_mcp_rules', 'language_specific_rules', 'framework_specific_rules', 'testing_rules', 'code_quality_style_rules', 'development_workflow_rules', 'critical_dont_miss_rules', 'phase2_maintenance_opa']
existing_patterns_found: 31
source_artifacts:
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/planning-artifacts/epics.md
  - _bmad-output/planning-artifacts/ux-design-specification.md
  - _bmad-output/planning-artifacts/frontend-hardening-specification.md
  - _bmad-output/planning-artifacts/page-specifications.md
  - _bmad-output/planning-artifacts/prds/prd-Syncro-2026-08-24/prd.md
  - _bmad-output/planning-artifacts/architecture/architecture-Syncro-2026-08-24/ARCHITECTURE-SPINE.md
  - opencode.json
  - syncro/scripts/postgres-mcp.ps1
  - syncro/scripts/chrome-devtools-mcp.ps1
---

# Project Context for AI Agents

_This file contains critical rules and patterns that AI agents must follow when implementing code in this project. Focus on unobvious details that agents might otherwise miss._

---

## Technology Stack & Versions

- Source manifests are authoritative: backend versions in `syncro/apps/backend/pom.xml`; frontend declared versions in `syncro/apps/web/package.json`; frontend resolved versions in `syncro/apps/web/package-lock.json`; TypeScript rules in `syncro/apps/web/tsconfig.json`; Next config in `syncro/apps/web/next.config.mjs`.
- Backend uses Java `25`, Spring Boot `4.0.6`, Maven; do not downgrade Java or copy Spring Boot 3 examples without checking Jakarta/Spring Boot 4 compatibility.
- Maven parent/BOM in `pom.xml` controls Spring-managed backend dependency versions; do not pin or override Spring-managed dependencies without a concrete compatibility reason.
- Spring Boot 4 requires Jakarta namespace and current Spring Security style; do not add legacy `javax.*` imports or old security configuration examples without verification.
- Backend integrates Spring WebMVC, Security, Validation, JPA, Redis, Flyway, Actuator, MQTT, PostgreSQL, and Testcontainers.
- Flyway owns schema evolution; never use JPA auto-DDL such as `ddl-auto=update`; migrations are forward-only and applied migrations must not be edited.
- Frontend uses Next.js `^16.2.6`, React `^19.2.6`, TypeScript `^5.9.3`, Tailwind CSS `^4.1.5`; caret ranges are declared constraints, while `package-lock.json` is the resolved install authority.
- Frontend package manager is `npm` with `syncro/apps/web/package-lock.json`; do not introduce pnpm, yarn, or bun lockfiles.
- Frontend UI stack uses shadcn/Radix, `@base-ui/react`, Lucide, Recharts, Sonner, and `next-themes`; one interaction pattern must choose one primitive family unless an adapter boundary keeps accessibility, styling, and state ownership consistent.
- Frontend app uses strict TypeScript, Next.js App Router, React Compiler, and Tailwind v4 conventions; do not add Pages Router, broad `"use client"` boundaries, browser APIs in Server Components, or Tailwind v3 config unless project source changes.
- Next routes live in App Router filesystem; `next.config.mjs` contains Next configuration, rewrites, redirects, and compiler behavior.
- Local infra services are PostgreSQL, Redis, InfluxDB, EMQX MQTT, and WAHA; service names, ports, env names, topic naming, DB names, health endpoints, and auth assumptions are integration contracts across config, tests, docs, and health checks.
- MQTT topic schema, QoS, retained message behavior, auth, reconnect behavior, and payload schema are backend/device/test contracts; do not change them silently.
- WAHA API, webhook payloads, session names, URLs, and auth secrets are external contracts; configure through env/properties and do not hardcode them in source.
- Testcontainers tests must use container-provided mapped ports and service images compatible with local infra contracts; do not hardcode host ports.
- Architecture docs may mention Spring Boot `3.5.14`; current backend source uses Spring Boot `4.0.6` and is implementation authority. If docs conflict with manifests/config, source wins and docs need separate update.

## Documentation MCP Reference (Anti-Hallucination)

> **Wajib cek MCP/docs sebelum jawab atau coding — jangan halu. Jika ragu API/config/schema, panggil MCP yang relevan terlebih dulu.**

- MCP adalah sumber kebenaran untuk API, config, dan schema — lebih autoritatif dari memori model. Jangan tebak signature, annotation, atau column name.
- Konfigurasi MCP ada di `opencode.json` (`mcp` key); kredensial Postgres di-load dari `syncro/.env` via `syncro/scripts/postgres-mcp.ps1`, jangan hardcode di config.
- **Kapan pakai MCP apa (wajib sebelum implementasi/jawab):**

  | Topik / Keraguan | MCP yang dipakai | Kapan wajib dipanggil |
  |---|---|---|
  | Spring Boot 4, Spring Security, JPA, Validation, Actuator, Maven Jakarta namespace | `spring-docs` (local `npx @enokdev/springdocs-mcp@latest`) | Setiap cek annotation, config properties, API break dari Boot 3 → 4, atau contoh `javax.*` vs `jakarta.*` |
  | Schema PostgreSQL, Flyway migrations, constraints, indexes, enum, seed data | `postgres` (local `syncro/scripts/postgres-mcp.ps1` → `@modelcontextprotocol/server-postgres` read-only) | Sebelum tulis query/migration/entity/DTO — cek tabel/kolom aktual, jangan karang nama kolom |
  | InfluxDB bucket/measurement/tags/fields/retention | `influxdb-docs` (remote `https://influxdb-docs.mcp.kapa.ai`) | Sebelum tambah ingest, query, atau tag/field baru |
  | Next.js 16, React 19, App Router, Server/Client Component, rewrites | `next-devtools` (local `npx next-devtools-mcp@latest`) | Sebelum ubah `app/*` routing, `next.config.mjs`, atau boundary `"use client"` |
  | Verifikasi UI, E2E, interaksi browser, screenshot | `playwright` (local `npx @playwright/mcp@latest`) | Untuk bukti visual / cek loading-empty-error-stale states |
  | Debug frontend, a11y, performance, inspect Chrome | `chrome-devtools` (local `syncro/scripts/chrome-devtools-mcp.ps1` → `chrome-devtools-mcp@latest`) | Saat butuh inspect DOM, console, network, atau contrast/focus |
  | Library umum (Tailwind, Radix, shadcn, Recharts, Zustand, RHF+Zod) | `context7` (remote `https://mcp.context7.com/mcp`, currently `enabled:false`) | Enable (`enabled:true` di `opencode.json`) lalu query jika butuh versi/API terbaru — jangan tebak props |

- Alur anti-halu: `1) identifikasi domain keraguan → 2) panggil MCP terkait → 3) kutip hasil MCP di jawaban/code → 4) baru implement`. Jika MCP tidak menjawab, fallback ke `Read` source manifests (`pom.xml`, `package.json`, `syncro/.env.example`, `app/*`), bukan mengarang.
- Untuk Spring Boot 4 / Jakarta: selalu verifikasi via `spring-docs` — jangan copy contoh Boot 3 / `javax.*` dari internet tanpa cek MCP.
- Untuk DB/Influx: `postgres` MCP itu read-only — gunakan untuk `list_tables`, `describe_table`, `query` selektif; jangan asumsikan `ddl-auto` atau kolom ada.
- Jika butuh enable `context7`, ubah `enabled:false→true` di `opencode.json` dan restart opencode, lalu query dengan `library + version` eksplisit (mis. `tailwind 4.1.5`).
- Simpan bukti: sebutkan MCP/tools yang dipakai di commit/PR notes dan di pemetaan `AC -> evidence` saat claim selesai.

## Critical Implementation Rules

### Language-Specific Rules

- Java packages are lowercase and grouped by bounded context: `com.syncro.<context>.<layer>`, not `com.syncro.<layer>.<context>`.
- Use bounded-context layers intentionally: `api`, `application`, `domain`, `infrastructure`/`persistence`, `integration`, and `config`; avoid generic cross-domain `common`, `shared`, or `utils` packages unless they are true technical contracts.
- Do not import across bounded contexts except through public contracts, application ports, or explicit integration adapters.
- Layer direction matters: `domain` must not import Spring, JPA, Jackson, or web DTOs; `application` must not depend on controllers or raw JPA entities; `api` must not call repositories directly.
- Java API DTO names end with `Request`, `Response`, or `View`; `View` means read model/API projection, not domain model. Event/message/integration contracts may use `Event`, `Command`, `Message`, or `Payload`.
- Prefer Java records for immutable API DTOs; do not use records for JPA entities or framework-proxied mutable types.
- Use `jakarta.*` imports for Spring Boot 4, especially `jakarta.validation.*`, `jakarta.persistence.*`, and `jakarta.annotation.*`; do not mix in `javax.validation`, `javax.persistence`, or `javax.servlet`.
- Do not introduce Lombok, MapStruct, or reflection-heavy mapping for domain/application flow without approval. Generated OpenAPI/client types are allowed only from committed specs and must stay isolated from handwritten domain code.
- JPA entities use singular domain nouns and must not be returned from controllers, serialized as API DTOs, published as events, or reused as WAHA/MQTT payloads.
- Keep API DTOs, domain/application models, persistence entities, and external WAHA/MQTT payload classes separate when lifecycle or validation rules differ.
- WAHA/MQTT payload classes belong in infrastructure integration packages and should use provider-specific names such as `WahaWebhookPayload` or `MqttDeviceEvent`.
- Mapping between DTOs, domain objects, entities, and integration payloads must be explicit; small local mapper helpers are fine, generic cross-domain mappers are not.
- Domain objects should not depend on Spring annotations unless they are infrastructure-facing.
- Do not use `Optional` in DTO fields, JPA entity fields, or request bodies.
- Request/response nullability must be explicit through Bean Validation/OpenAPI schema; do not rely on implicit nullable fields for business-critical data.
- Validate input at system boundaries: REST DTOs, WAHA webhooks, MQTT payloads, localStorage, and other unknown data. Keep business invariants in domain/application code, not only annotations.
- `@Transactional` belongs at application service/use-case boundaries; do not put transactions in controllers, DTO mappers, or domain objects.
- Spring config must bind through typed `*Properties` classes with validation; avoid scattered `@Value`, direct environment reads in business code, or hardcoded URLs, ports, credentials, topic prefixes, and webhook routes.
- Topic prefixes, routing keys, queue names, webhook URLs, and frontend/backend endpoint config must come from typed config/env; never compose externally visible topics or routes from raw user input without whitelist/sanitization.
- JSON fields use `camelCase`; timestamps use ISO-8601 UTC; status values use uppercase enum strings.
- Java absolute timestamps should use `Instant`; avoid `LocalDateTime` for API/event timestamps unless value is truly timezone-independent.
- Monetary, quantity, and precision-critical values must not use floating point; use `BigDecimal` in Java and contract-defined string/decimal handling in TypeScript.
- Backend error responses must expose stable machine-readable codes; frontend must branch on `code`/status, not human message text or Java exception names.
- Backend enum strings are contract values; frontend must handle unknown enum/status values safely when data comes from API, WAHA, MQTT, or persisted async payloads.
- Async event payloads that are persisted or consumed by another process must be append-only/backward-compatible unless a migration/compatibility plan exists.
- TypeScript stays strict; avoid `any`, `as any`, `// @ts-ignore`, and double assertions unless local, commented, and not exported.
- Treat network responses, localStorage, postMessage, and other external data as `unknown` until validated or narrowed.
- Shared TypeScript API types should come from generated/OpenAPI/shared contracts when available; if manual shapes are temporary, keep them near the API client and do not scatter duplicates through UI code.
- Do not manually edit generated contract/client files; generation must be reproducible from committed specs.
- TypeScript API timestamp fields remain ISO strings in shared/API types; convert to `Date` only at UI/application edges.
- TypeScript discriminated unions should use backend contract status/code fields, not UI labels or translated strings. UI-only state may use UI discriminants, but must not leak into API contracts.
- TypeScript client state must preserve backend identifiers as opaque strings; do not parse IDs for business meaning. External provider IDs stay separate from internal IDs.
- Preserve backend `null`/optional semantics in frontend state; do not convert `null`, `undefined`, and empty strings interchangeably.
### Framework-Specific Rules

- Backend API base path is `/api/v1`; use plural resources and camelCase query/body fields, with explicit exceptions for singleton/control endpoints such as health, auth session, or machine commands.
- Controllers bind and validate request DTOs, delegate to application services, map responses/errors, and must not contain business rules, transactions, repository access, or permission decisions.
- Application services own use cases, transaction boundaries, server-side permission checks, status transitions, command timestamps, and emitted domain/application events.
- Distinguish timestamp ownership: telemetry uses source timestamp plus `receivedAt`; status workflows use server-side clock; audit/database timestamps may be infrastructure-managed.
- Backend must enforce authorization server-side with role/scope/resource ownership rules; frontend visibility never counts as permission enforcement.
- Only health/readiness endpoints may be unauthenticated; actuator exposure must be minimal, profile/environment-specific, and must not expose metrics/env/beans publicly.
- Auth, CORS, credential mode, CSRF/session/JWT strategy, and allowed origins must be explicit before adding authenticated frontend/backend flows.
- New endpoints must define path, method, request/response DTOs, error codes, permission rule, idempotency need, and audit/log expectation before implementation.
- REST errors use one stable shape with machine-readable `code`, human-safe `message`, optional `details`, and `correlationId`/`traceId`; do not expose Java exception names or raw messages.
- Every request, ingest event, job, outbox dispatch, quarantine record, and audit entry should propagate correlation ID where available.
- PostgreSQL is source of truth for transactional state; all schema changes go through Flyway migrations, never manual schema edits or runtime auto-DDL.
- Redis is cache-only; cache keys must be namespaced, have explicit TTL, and define invalidation rules tied to write use cases before caching permissions/status.
- InfluxDB stores telemetry/time-series data; define bucket, measurement, tags, fields, retention/downsampling policy before adding new ingest, and do not use it as source of truth for master data, users, permissions, or status workflows.
- MQTT ingest must validate topic, active machine, schema version, payload-topic identity, plausible ranges, timestamp/sequence ordering, duplicate message identity, and correlation ID before application processing.
- Invalid MQTT payloads that can be parsed enough for identity/correlation must be quarantined/audited with reason code, topic, sanitized payload/reference, correlation ID, and `receivedAt`; malformed or abusive garbage may be rate-limited/dropped with metrics.
- MQTT topic format and schema version are contracts; changes must be backward-compatible or have a migration/deprecation path.
- WAHA notification dispatch must never run inline with request or ingest path; use PostgreSQL outbox/job table with idempotency key, max retries, backoff, dead-letter/failure state, and operator-visible failure reason.
- WAHA provider failure must not roll back domain transactions; record dispatch failure separately through outbox/job state.
- Frontend uses Next.js App Router; `app/*` route files compose route/layout/loading/error/not-found wiring and delegate feature logic to `src/features/*`.
- Cross-feature reusable domain UI belongs under `src/components/syncro/*`; feature-specific UI stays under `src/features/<feature>/components`; generated shadcn primitives under `src/components/ui` stay low-level and free of domain logic.
- Server Components are default; add `"use client"` only for interaction, browser APIs, client state, realtime indicators, charts, tables, forms, or client-only libraries.
- Do not access `window`, `localStorage`, browser-only auth state, random values, or locale/date formatting in Server Components when it can cause hydration mismatch.
- Client components must not reimplement backend permissions, status transitions, or domain calculations; display backend-provided allowed actions and states.
- Permission UX should hide/disable actions from backend-provided allowed actions, but security remains server-side.
- All backend calls should go through one typed API client/contract boundary; avoid ad-hoc `fetch` response parsing inside components.
- Server Actions must not bypass backend authorization or duplicate backend use cases; domain mutations still go through backend API/service contracts.
- Environment variables must split public/private correctly; only `NEXT_PUBLIC_*` values may reach browser code.
- Zustand is for client/UI state, not server cache replacement; server data should remain tied to API contract, refresh behavior, and mutation revalidation strategy.
- Mutations must define revalidation behavior (`router.refresh`, tag/path revalidate, or cache invalidation), loading state, double-submit prevention, success/error feedback, and reversible optimistic update rules if optimistic UI is used.
- Complex client-facing forms should use React Hook Form + Zod with schema-aligned validation; backend validation remains authoritative and backend field/global errors must map consistently.
- TanStack Table server-side mode must serialize sorting/filtering/pagination to backend query names/defaults and must not mix hidden client-side filtering with server pagination.
- Dashboard pages, tables, forms, and telemetry views must include loading, empty, error, unauthorized, and long-content states.
- Interactive UI must preserve keyboard support, visible focus states, labels, ARIA state, and WCAG AA contrast.
- New UI should use existing shadcn/Tailwind tokens/theme values; avoid hardcoded color, spacing, radius, shadow, or one-off visual styles.
- Telemetry/realtime UI needs stale indicator, last-updated timestamp, reconnect/loading state, and fallback when stream/API fails.
- Next rewrites/redirects in `next.config.mjs` are route contracts; preserve public URL behavior and `/dashboard/*` target routes when adding pages, and do not use rewrites to hide backend API contract changes.
- React Compiler is enabled; render logic must stay pure and avoid mutable module state, object/array mutation during render, side effects in render helpers, and memoization as behavior dependency.
### Testing Rules

- Prefer few high-signal tests over broad low-value coverage.
- Each story acceptance criterion must map to at least one test or explicit manual evidence item; unmapped criteria block story completion.
- Unit tests cover pure validation, calculations, and domain decisions without infrastructure.
- Backend tests follow owned module/package boundaries; colocate tests with module code where tooling allows.
- Use Spring Boot Test + Testcontainers for integration tests covering PostgreSQL, Redis, scheduling, messaging, migration, repository, or transaction behavior.
- Never mock persistence for migration, repository, transaction, locking, uniqueness, constraints, indexes, or query correctness; use real database/Testcontainers.
- Migrations must apply cleanly from empty database and from previous schema state in Testcontainers; migration evidence must cover required constraints, indexes, enum/default behavior, and rollback/repair notes when rollback is not supported.
- Cross-module tests must use public API, application service, or documented event contracts; never reach into another module's private repository/infrastructure.
- API contract tests must lock DTO shape, validation error shape, status codes, error codes, and backend-provided state enums consumed by frontend.
- Test external-boundary validation for REST DTOs, MQTT payloads, WAHA responses, and scheduled/imported data.
- Use controlled clock/time providers for threshold, stale state, escalation, retry, scheduled job, and audit timestamp tests.
- Telemetry tests must cover machine identity, schema version, payload-topic mismatch, implausible values, duplicate `messageId`, 16-bit counter wrap, accepted/rejected/quarantined outcomes, and correlation/request ID evidence.
- Alert tests must prove backend-owned threshold calculation, duplicate active-alert prevention, valid/invalid state transitions, `INVALID_STATE_TRANSITION`, role permissions, explicit `SUPER_ADMIN` direct resolve override, and audit trail.
- Notification tests must prove idempotency by `alertId + escalationLevel + recipientId`, retry/attempt history, acknowledgement stopping future escalation, channel failure behavior, and WAHA secret/token/phone redaction from logs, errors, retries, and audit metadata.
- Role tests must prove forbidden actions are rejected server-side, not only hidden/disabled in UI.
- State-changing action tests must prove audit trail captures actor, role, timestamp, previous state, new state, and reason when required.
- Frontend tests may mock API responses only from shared type/schema/contract fixtures; do not use ad-hoc backend shape objects.
- Frontend tests must not re-implement domain rules; assert display and interaction from backend-provided status, reason, severity, timestamps, permissions, and allowed actions.
- UI tests for operational components must cover reachable loading, empty, error, stale, read-only, forbidden, pending, and success states.
- Pilot/E2E fixtures must use canonical data: `GM1`, `Forming`, `BF-08410`, `JBF19`, `Electric PLC Wecon LX5`, `TECHNICIAN/STAFF/LEADER`, and 90% threshold.
- Critical acceptance tests must state expected evidence: API response/state, database/audit record, queued job, emitted event, or visible UI state.
- Phase 1 proof must include observable UI state, backend persisted state, and audit/job evidence.
- Do not add new testing frameworks or heavy E2E suites unless current stack cannot cover required behavior.

### Code Quality & Style Rules

- Keep code boring, explicit, observable, and easy to change.
- When rules conflict, prefer security, data correctness, observability, then developer convenience.
- Follow existing file/folder patterns first; add new abstractions or layers only when current repetition, ownership, and story scope justify them.
- Small duplication is acceptable until a pattern is stable in 2-3 places; shared abstractions should reduce risk, not only line count.
- Keep `common` small, domain-neutral, and dependency-light: shared primitives/contracts only, no domain logic, no dependency on domain modules.
- Use domain noun + purpose for file/module names; avoid vague names like `manager`, `helper`, `utils`, `service2`, `new`, or `common` without domain context.
- Keep domain rules authoritative in backend/shared contracts; frontend may adapt display copy but must not reimplement business decisions.
- Backend owns API contracts; frontend consumes contract/generated/shared types where available and must not redefine status, enum, error code, or response shape.
- API contracts must stay stable; breaking request/response changes need explicit migration or versioning path.
- Backend validation must live at DTO/schema and domain/service boundaries; frontend validation is UX guidance only.
- Generated/build outputs must not be hand-edited or committed; source, Flyway migrations, fixtures, and config drive generated artifacts.
- Schema migrations must be backwards-aware; no rename/drop without explicit migration path and data impact.
- Database names use `snake_case`; tables plural; columns `snake_case`; foreign keys `{singular}_id`; indexes `idx_<table>_<columns>`; unique constraints `uq_<table>_<columns>`.
- Enum values persisted or exchanged over API use uppercase strings.
- Cross-boundary time, status, severity, enum, and error code values should use shared constants/types.
- API error shape must include stable `code`, safe `message`, optional `fieldErrors`, `timestamp`, and `traceId`; never expose stack traces, SQL errors, secrets, or internal service details.
- When UX branches on operational state, backend response must include stable status, reason, severity, timestamp, and allowed actions.
- Persist and compare timestamps in UTC. Convert timezone only at display/input boundaries.
- Use structured logging with trace/correlation IDs and stable queryable fields such as `requestId`, `deviceId`, `jobId`, `operation`, `durationMs`, `status`, and `errorCode`.
- Do not log secrets, WAHA tokens, passwords, MQTT credentials, phone identifiers, auth headers, cookies, connection strings, raw sensitive payloads, or secret environment values.
- Config must use environment/schema validation with safe defaults; never hardcode secrets.
- Redis keys must have explicit TTL unless explicitly persistent and documented; Redis must never be sole source of truth.
- InfluxDB telemetry schema must avoid per-machine/per-plant tables. Tags must be low-cardinality and bounded; store UUIDs, message IDs, phone numbers, free text, message bodies, or user-provided values as fields unless explicitly approved.
- Webhooks, queue workers, sync jobs, and external-side-effect operations must be idempotent or have explicit dedupe keys.
- Async jobs must have retry policy, terminal failure state, and enough diagnostic context without exposing secrets or sensitive payloads.
- Tests should use domain vocabulary and avoid brittle snapshots, magic strings, implementation details, or duplicated business rules.
- Comments explain intent, constraints, non-obvious tradeoffs, or invariants; do not restate code behavior.
- UI status/severity must not rely on color alone; text and interactive states must meet WCAG AA unless exception is documented.
- UI must support keyboard navigation, visible focus states, semantic labels, sufficient contrast, and screen-reader-friendly form errors.
- Frontend lint/format uses Biome only; do not add Prettier/ESLint config or dependencies without explicit project decision.
- Let Biome own formatting and basic lint style; do not debate formatting in reviews unless Biome cannot express the rule.

### Development Workflow Rules

- Source code lives under `syncro/`. Planning/workflow artifacts stay under `_bmad/`, `_bmad-output/`, `.claude/`, `.agent/`, and `.agents/`.
- Do not create or use worktrees unless user explicitly asks.
- Use `main`. Do not create, switch, rename, or delete branches unless user explicitly asks.
- Do not rewrite history, amend, squash, rebase, reset, force-push, or discard changes unless user explicitly asks.
- Edit permission is not commit permission. Commit permission is not push permission.
- Before commits, inspect `git status --short`, `git diff --stat`, `git diff`, `git diff --cached`, `git log -5 --oneline`, and current branch.
- If inspection shows unrelated files, secrets, wrong branch, generated output, unexpected deletes, or unexpected changes, stop and ask.
- Stage exact intended paths only. Avoid `git add -A`, `git add .`, and broad wildcards unless user explicitly asks after scope review.
- Never commit secrets, env files except `.env.example`, runtime data, caches, dependencies, build output, logs, local Claude settings, local agent sessions, or large binaries without explicit confirmation.
- Never bypass hooks. Fix the issue or stop if fix is outside scope. Do not amend unless user asks.
- After a commit, run `git status --short` and `git log -1 --oneline`; report branch, commit hash, and remaining changes.
- Use concise imperative commit subject; body explains why; Claude-created commits must end with `Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>`.
- A story is ready only when goal, acceptance criteria, scope in/out, affected paths, verification method, data/migration impact, and dependencies/risks are clear.
- Do not start implementation when acceptance criteria are ambiguous or unverifiable; ask first.
- Completion requires evidence, not assertion. Map each acceptance criterion to evidence: `AC1 -> verified by <test/check/manual step>`.
- Evidence hierarchy: automated tests > build/typecheck/lint > browser/manual verification > screenshot/log support.
- For UI/frontend behavior changes, verify actual route and interaction in browser before reporting done; screenshot alone is not enough. If not possible, state blocker and fallback checks run.
- For backend endpoint/service changes, verify through automated tests or real request/response behavior; record status code, payload shape, and error path where relevant.
- Run smallest relevant checks for changed scope: backend Maven tests; frontend Biome/type/build checks when available. State exact checks run.
- If verification is skipped or partial, state why, what remains unverified, and risk.
- Do not mark story complete while tests fail, checks are skipped without rationale, acceptance criteria are unmapped, or applicable runtime behavior is unverified.
- Schema-changing stories must state migration expectation, seed/test data impact, and rollback/dev reset risk. Never reset DB without explicit confirmation.
- Use documented infra service names exactly; do not invent aliases.
- Do not add new services, package managers, frameworks, linters, formatters, databases, queues, auth providers, UI kits, or test stacks without explicit project decision.
- Do not create docs/reports/summaries unless explicitly requested.
- Plans, stories, and checklists are not implementation proof. Verify behavior with code, tests, app run, request/response, or inspection.
- Do not treat planning artifact edits as implementation proof. Update planning only when story/user asks.
### Critical Don't-Miss Rules

- Do not treat frontend visibility, disabled buttons, or route hiding as authorization; backend must enforce every permission-sensitive action.
- Do not bypass typed config/env for external contracts: WAHA URLs/secrets, MQTT topics/auth, DB/Redis/Influx settings, CORS/auth settings, and public/private frontend env split.
- Do not hardcode service ports, container ports, topic prefixes, webhook routes, credentials, backend URLs, frontend public URLs, or environment-specific values in source, tests, Docker files, or CI config.
- Do not change API DTO shapes, enum strings, error codes, MQTT payloads, WAHA payloads, WAHA message templates, exported reports, public APIs, or operator-facing status semantics without compatibility, versioning, or migration impact review.
- Do not serialize JPA entities through controllers or reuse persistence entities as API/event/integration payloads.
- Do not add `ddl-auto=update`, manually edit schemas, edit applied Flyway migrations, resequence migrations, or reset databases without explicit confirmation and migration impact review.
- Do not change migration order, seed data assumptions, enum persistence strategy, identifier formats, or identifier immutability without checking existing data compatibility and audit impact.
- Do not mock PostgreSQL/Redis/messaging behavior for tests that prove migrations, repositories, transactions, locks, uniqueness, retries, or outbox behavior.
- Do not run WAHA notifications, MQTT publishes, webhooks, or other external side effects inline with domain transactions; use durable outbox/job state with retry and idempotency keys.
- Do not publish MQTT/WAHA events before database commit; dispatch only from committed outbox/job records.
- Do not assume idempotency, message ordering, exactly-once delivery, single consumer execution, or single-node workers; webhook, MQTT, job, retry, scheduler, and replay handlers must tolerate duplicates, stale events, late delivery, and concurrent execution.
- Do not rely on Redis, browser storage, memory, scheduled job state, dashboards, time-series metrics, or external chat state as source of truth for permissions, workflow state, counters, locks, audit data, or business facts; durable business state belongs in PostgreSQL unless explicitly ephemeral.
- Do not treat InfluxDB metrics/time-series data as transactional business state, audit source, or master data.
- Do not add Spring `@Async`, scheduled jobs, message consumers, webhook handlers, polling, realtime subscriptions, MQTT listeners, or dashboard refresh loops without timeout, cancellation, backoff, retry, idempotency, observability, and lifecycle ownership.
- Do not skip optimistic/pessimistic locking, uniqueness constraints, transaction boundaries, or documented status transitions for inventory, production counts, maintenance state, alerts, approvals, or other business-critical state changes.
- Do not use wall-clock local time, client time, or frontend-generated timestamps for business logic, scheduling, retention, audit, SLA, traceability, or test assertions; use server-side UTC timestamps and injected clocks.
- Store event occurrence time, ingestion time, processing time, and user-visible business date separately when they differ; never infer one from another silently.
- Do not use floating-point numbers for money, quantities requiring precision, counters, KPI inputs, or persisted measurements where exactness matters.
- Do not expose stack traces, Java class names, SQL errors, constraint names, validation internals, provider internals, infrastructure details, or raw exception messages through API responses, frontend messages, webhook replies, or user-visible logs.
- Do not log secrets, auth headers, cookies, connection strings, WAHA tokens, MQTT credentials, phone identifiers, raw sensitive payloads, or secret environment values.
- Do not put browser-only APIs, random values, local time formatting, or auth state into Server Components where hydration mismatch can occur.
- Do not branch frontend logic on translated labels, display text, colors, Java exception names, array indexes, random values, or timestamps; use stable backend codes, statuses, identifiers, and allowed actions.
- Do not store tokens, secrets, session data, phone identifiers, or permission decisions in browser storage unless explicitly approved by architecture.
- Do not add frontend state as source of truth for workflow status, permissions, machine state, production state, or operational truth; backend remains authoritative.
- Do not hide critical state behind color alone; status, alarms, connection state, and quality gates need text labels, icons, or structured codes.
- Do not make dashboards look fresher than data is; realtime and near-realtime UI must show last-updated time, stale/disconnected state, partial-data state, and degraded feed behavior.
- Do not let live refresh steal focus, reset filters, jump scroll, reorder rows unexpectedly, replace user-entered data, or apply bulk actions to unseen/newly changed rows without explicit confirmation.
- Do not rely on toast-only feedback for important outcomes; critical success, failure, warning, and required-next-step messages must persist in page context, activity history, or audit trail.
- Operational actions such as start/stop, override, retry, acknowledge, reprocess, export, and destructive admin actions must show scope, impact, reversibility, pending state, and ambiguous retry/conflict behavior before or during execution.
- Disabled or hidden actions should explain required role, missing prerequisite, or current system state when safe to reveal.
- Operational alerts and errors must be actionable: show what happened, impact, next action, and responsible role when applicable; avoid vague `failed` messages.
- Empty, delayed, stale, partial, loading, error, forbidden, and degraded states are product behavior and must be designed and verified, not treated as edge UI polish.
- Metrics, charts, thresholds, reports, KPI, compliance metrics, SLA, defect rate, downtime, and productivity calculations must specify units, timezone basis, aggregation window, freshness expectation, source fields, formula version, and rounding rule.
- Every business-critical state change must record who/what changed it, when, previous value, new value, reason when required, and source channel where practical; application logs are not audit trails.
- Do not reuse or mutate externally visible IDs, order numbers, inspection IDs, device IDs, conversation IDs, or customer-facing references after creation unless explicit migration/audit path exists.
- Late, corrected, duplicate, and manually overridden events must define amend/supersede/duplicate/new-record behavior and preserve original machine-derived value, actor, reason, timestamp, and trace.
- Do not mark end-to-end business processes complete when only partial external obligations succeeded unless explicitly waived and visible to operators.
- Data deletion, anonymization, archival, and correction flows must follow documented retention and audit rules; do not hard-delete audit, compliance, production, or customer records by default.
- Every query, export, notification, dashboard, and background job touching scoped data must enforce tenant/site/customer boundaries at the backend layer.
- Acceptance criteria must name user-visible outcomes, success/failure states, permission/negative cases, degraded-mode behavior, and correction/audit path where relevant; implementation details alone are not acceptance criteria.
- Do not mark stories complete from plans, generated docs, mocks, screenshots, Storybook states, or artifact edits alone; map each acceptance criterion to runtime, test, request/response, database, audit, job, or UI evidence.
- Do not accept happy-path-only completion for user-facing workflows; include at least one failure, permission, empty, stale, or degraded-state evidence item.
- Do not weaken TypeScript strictness, Java compiler settings, Biome rules, test gates, security checks, or hooks to make generated code pass.
- Do not introduce new package managers, UI libraries, test frameworks, formatters, queues, databases, auth providers, infra services, or long-lived generated artifacts without explicit project decision.
- Do not treat generated code, migrations, seed data, dashboards, integration fixtures, or generated clients as harmless; review them as production source because they shape contracts and data.
---

## Usage Guidelines

**For AI Agents:**

- Read this file before implementing any code.
- Follow all rules exactly as documented.
- When in doubt, prefer the more restrictive option.
- **Anti-halu wajib:** jika butuh fakta API/schema/config, panggil MCP di `## Documentation MCP Reference` dulu (spring-docs / postgres / influxdb-docs / next-devtools / context7) — jangan jawab dari memori.
- Update this file if new non-obvious patterns emerge.

**For Humans:**

- Keep this file lean and focused on agent needs.
- Update when technology stack or project contracts change.
- Review periodically for outdated rules.
- Remove rules that become obvious or duplicated elsewhere.

Last Updated: 2026-08-24 (Phase 2 section added: maintenance workorder/preventive/sparepart-request, OPA authorization, hardened sync, org structure — see "Phase 2: Maintenance Execution & OPA Authorization")

## Phase 2: Maintenance Execution & OPA Authorization (2026-08-24)

> Source of truth: PRD `prd-Syncro-2026-08-24` (FR-100..FR-181), Architecture Spine `architecture-Syncro-2026-08-24/ARCHITECTURE-SPINE.md` (AD-1..AD-16), Epics 9-14 in `epics.md`. Read these before implementing Phase 2 stories.

### OPA Authorization (AD-1, AD-2, AD-13, AD-15, AD-16)

- OPA runs as a **sidecar HTTP service** (docker-compose stable name `opa`, v1.19.1) — never embedded WASM/IR. Backend calls `POST /v1/data/syncro/authz/...` via a single `authz.PolicyDecisionPoint` service (RestClient + Resilience4j timeout/retry/circuit-breaker, WAHA-client pattern).
- **OPA default-deny.** A failing/sidecar-down OPA call denies except a configurable degraded-mode allowlist for health/read endpoints.
- **Org data stays in PostgreSQL; never copy org data into the OPA data store.** OPA input is minimal and self-contained: `subject` (roles, plantIds, machineGroupIds, activeTeamIds) + `resource` + `action` + `context`. Derived scope is passed per request.
- **OPA input assembly is single-sourced** in `authz.PolicyDecisionPoint`; the interceptor, application services, and `/api/v1/authz/allowed-actions` all use it. Frontend never calls OPA directly (NFR-013a); it consumes `allowed-actions` for menu/button rendering (UX only, not enforcement).
- **Scope dimensions are exactly `{plantIds, machineGroupIds, activeTeamIds}`** — section is a container, NOT a scoping dimension. Scope is derived by a single service in the `org` module; the maintenance query layer consumes it and applies the same set to SQL row filtering and OPA input.
- **Role taxonomy (AD-15):** additive V41+ migration extends `auth_users.application_role` CHECK. MANAGE→MANAGER_MAINTENANCE, VIEWER→read-only. `subject.roles` = application role + derived scope. **MANAGE→MANAGER_MAINTENANCE is NOT a global promotion** — MANAGER_MAINTENANCE still requires plant assignments (AD-2). SUPER_ADMIN bypasses all checks.
- **Approval SoD (AD-16):** requester can never approve their own request, enforced server-side before OPA/state machine. Threshold by estimated cost (qty × est. price); tiers configurable via `escalation_configs` (defaults ≤5M / 5M–50M / >50M IDR); no-price requests require section-leader approval.
- **Decision logs** enabled with input masking (no WAHA secrets, no full phone numbers), 30-day configurable retention; each decision's `decision_id` stored alongside the app audit record.

### Org & Scope (AD-2, AD-13)

- Sections: `MACHINERY | UTILITY | WORKSHOP` per plant; `machine_groups.section_id` (one group = one section).
- **Section leadership is derived from `machine_responsibilities.level = LEADER` (or above)** on a machine group — no separate role assignment. Section leaders read/write only their own machine group(s); sibling-group data within the same section is excluded (FR-103).
- **Section leader cannot execute their own workorders** (FR-113); they delegate to technicians. Ratings immutable after submission.
- **Cross-plant teams are expiry-dated**; OPA input includes only active (non-expired) memberships. Team machine IDs merge into `machineGroupIds` for SQL filtering (identical to OPA input).
- Section leaders also monitor sparepart lifetime for machines in their own group.

### Workorder (AD-3, AD-4, AD-5, AD-6, AD-14)

- **Dual source:** `work_orders.id` VARCHAR(50) UTF-8 = external `sheet_no` (source SYNCED) or `WO-YYMM-XXXXX` (source INTERNAL, transaction + row lock, monthly sequence). `parent_id` self-FK, cross-source chains allowed. Create endpoint accepts optional `idempotencyKey` (5-min dedupe).
- **Lifecycle:** `DRAFT → OPEN → ASSIGNED → IN_PROGRESS → ON_PROCUREMENT → IN_PROGRESS → DONE → CLOSED` (+ CANCELLED from OPEN/ASSIGNED). Invalid transitions → `INVALID_STATE_TRANSITION`.
- **ON_PROCUREMENT is derived** from live non-READY sparepart requests (recomputed on transition events, guarded by a lock, writes `_status_history` with `source=DERIVED`, `actor=SYSTEM`). Manual placement allowed only when no live request exists. The 4-hour ack clock excludes ON_PROCUREMENT and is computed from history rows, not live status.
- **Parent cannot CLOSE while children are not CLOSED/CANCELLED**; closing a parent takes `SELECT ... FOR UPDATE` on children in the same transaction; override = SUPER_ADMIN/MANAGER with audit-logged reason.
- **MTBF ordered by `woStopAt` (not id)** between consecutive breakdown workorders; MTTR = cumulative repair-session durations; both backend-computed (frontend renders only). Analytics recomputed from current data; sync mutations invalidate cache.
- CP/CPK optional on any category; FMEA tag; stop-time reason required before DONE for breakdown.

### Sparepart Request & Stock (AD-4, AD-9, AD-11)

- Types: `SPAREPART` (electric/mechanic taxonomy), `CONSUMABLE` (no machine binding required), `SERVICE_EXTERNAL` (bound to parent workorder, no stock flow).
- **Request state machine:** `REQUESTED → ACKED → PROCESSING → [READY | PURCHASE_REQUESTED → PART_RECEIVED → READY] → PICKED_UP → CLOSED`; each transition writes timeline event + audit. ACK/PROCESSING/READY/PART_RECEIVED by INVENTORY_MAINTENANCE/STOREKEEPER; PICKED_UP/CLOSED by the workorder's section leader.
- MRE code is **manual** (no auto-generation); `purchase_reference_url` stored; new-item requests start `PENDING_COMPLETION` and are completed by inventory with material code/image/est price.
- **Stock OP/OQ is keyed by MATERIAL CODE** (global one-code-one-sparepart), per plant: `sparepart_stock(material_code, plant_id, stock_on_hand, order_point, order_qty)` unique per (material_code, plant_id). Reorder rule (`stock ≤ OP → PR qty=OQ`) is a business-rule signal in the application layer; the PR action is OPA-authorized.
- **Stock mutation is owned by the inventory module**; PICKED_UP decrements via inventory application service; optimistic lock + atomic conditional update; negative stock rejected. PENDING_COMPLETION creates/updates `spareparts` via the `masterdata` module's application service — never direct cross-module writes.

### Sync Module (AD-7, AD-8)

- Hardened pipeline: batch in transaction, ordered by `sheet_no ASC`, idempotent upsert by external id, watermark resume, distributed lock, quarantine failed rows (reason + payload + traceId), `sync_runs` audit, typed config (no hardcoded creds), retry/backoff, Asia/Jakarta → UTC.
- **Sync never writes `work_orders` via JPA** — passes every row to `maintenance.workorder.application.WorkorderImportService.upsert()` (owns history/audit/ON_PROCUREMENT/notifications). Sync does not notify independently.
- Machine/plant/category mapping via configurable mapping tables; unmapped → quarantine (no stubs, no silent skip). Field classification via `sync_field_mappings` config (MASTER vs OPERATIONAL); unmapped defaults MASTER.
- **Terminal-state protection:** sync must not regress DONE/CLOSED workorders (quarantine `TERMINAL_STATE_PROTECTED`); external status does not override derived ON_PROCUREMENT; sync child upsert rejects when parent CLOSED.

### Notifications (AD-9)

- `notification_jobs` extended polymorphically: nullable `target_type`/`target_id`; `alert_id` becomes nullable (additive migration; existing rows keep their non-null alert_id and unique `(alert_id, escalation_level)` semantics). **Polymorphic idempotency key = `target_type + target_id + template_name + recipient_id`.**
- Maintenance module enqueues via the `notification` module's application service (never writes `notification_jobs` directly). Same outbox/worker/rate-limit/circuit-breaker/escalation machinery as Epic 5.
- WA link auto-login token: short-lived (15-min default TTL or first-use), bound to registered WA number, revoked on expiry/mismatch/reuse.

### Frontend (Phase 2)

- Main operational views (workorder, preventive, sparepart request, dashboards) use **TanStack Table v9** (headless, shadcn/Radix pairing), server-side mode (sort/filter/page serialize to backend query names; no hidden client-side filtering with server pagination). Row actions driven by backend-provided `allowed-actions`.
- WYSIWYG print (workorder & preventive): browser print of HTML report — tabular, configurable logo, signature block (image + signer identity + timestamp). No server-side PDF generation in v1.
