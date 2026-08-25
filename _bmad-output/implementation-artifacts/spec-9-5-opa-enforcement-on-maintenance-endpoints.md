---
title: 'OPA Enforcement on Maintenance Endpoints'
type: 'feature'
created: '2026-08-26'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: true
final_revision: 9591753
baseline_revision: 0b561e5
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-9-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-9-3-opa-infrastructure.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-9-4-role-taxonomy-migration.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** 9-3 built the enforcement plumbing but `enforced-paths` ships empty (interceptor disabled), the rego only says "SUPER_ADMIN may do anything", and OPA input is never persisted — so server-side authorization is not uniform, not OPA-driven, and decisions are not audit-traceable. 9-4 gave us the ten-role taxonomy; enforcement still bypasses OPA entirely.

**Approach:** Go live. Populate `enforced-paths` (compose) with every authorization-sensitive org/maintenance/sync endpoint; rewrite `authz.rego` so the policy mirrors the existing in-service gate matrix exactly (SUPER_ADMIN bypass; MANAGER_MAINTENANCE mutations; any-authenticated scoped reads; SUPER_ADMIN-only telemetry/worker) so enforcement is behavior-preserving; load the policy into the OPA sidecar (volume mount + CI `opa test`); persist every enforcement decision (decision_id + policy revision + outcome) into a masked `authz_decisions` table with 30-day configurable retention, exposed to SUPER_ADMIN/AUDITOR; expose decision_id + revision on audit records so an AUDITOR can trace allow/deny; add AUDITOR to the frontend audit-log surface.

## Boundaries & Constraints

**Always:**
- **Parity is the contract**: enabling OPA enforcement MUST NOT change any allow/deny outcome for any real user. The rego is the machine-readable mirror of today's in-service gates: (1) SUPER_ADMIN allowed everywhere; (2) mutations on the mutation endpoints allowed only for MANAGER_MAINTENANCE (the twelve `requireMutationRole` services + WahaTemplate allow-list); (3) reads (GET) on data endpoints allowed for any authenticated subject, row-scoped by SQL using the SAME `OperationalScopeService` set that feeds OPA input (AD-2/NFR-P2-4); (4) telemetry/notification-worker/ingest status endpoints SUPER_ADMIN-only. Every allowed/denied pairing is enumerated in the rego test matrix `authz_test.rego`.
- In-service `requireMutationRole` gates stay in place as defense-in-depth and as the parity harness; 9-5 does not remove them.
- `enforced-paths` default in `application.yml` stays EMPTY so the existing integration suite is untouched; `docker-compose.yml` sets `SYNCRO_AUTHZ_ENFORCED_PATHS` to the org/maintenance/sync patterns (real stack enforces). Health/actuator are deliberately NOT enforced (keeps DW-129's always-public concern moot).
- OPA sidecar must actually hold the policy: compose mounts `syncro/authz/policy/` as the policy dir and starts `opa run --server ... <policy dir>`; a `opa test` step is added to CI (script in `syncro/authz/`), and a local runner documented.
- Every interceptor `evaluate()` decision is persisted to `authz_decisions` (NEW V46): decision_id (OPA envelope), policy_revision (envelope `revision`, else sha256 of authz.rego as fallback), allowed, degraded, subject user id, action string, resource type, decision time. **No request bodies ever persist** — WAHA secrets / phone numbers are structurally absent from the OPA input (built from user+scope+action only) and from the row (masking by construction + test asserts nothing sensitive is stored).
- Retention: `syncro.authz.decision-log.retention-days` (default 30), purge via a `@Scheduled` job (scheduling already enabled). Breaker tuning for live enforcement: `syncro.opa.resilience.minimum-number-of-calls` lowered to 2 (DW-130) so a hung OPA trips faster.
- `GET /api/v1/authz/decisions` (paged) added to AuthzController; gate = SUPER_ADMIN || AUDITOR. Audit read view gains `decision_id` (+ `policy_revision` copied for convenience); AUDITOR added to the frontend audit-log route; a Decision Log tab lists persisted decisions.
- `actions` rego rule extended to mirror the matrix (SUPER_ADMIN full, MANAGER_MAINTENANCE read+mutation, others read-only) so FR-161 hiding stays truthful.
- Rule path matching uses `glob.match` on the action string (method + path); OPTIONS/dispatcher-type skipping in the interceptor stays.

**Block If:** nothing. Decisions pinned: parity over feature-gating (new role powers arrive with Epics 10–14); enforcement on by compose + CI, off by default in tests; AUDITOR gains read access to decisions + audit-log now (the only AUDITOR capability in this story); no removal of in-service gates; OPA bundle revision sourced from envelope, fallback to content hash.

**Never:**
- Never change what any endpoint allows/denies compared to pre-enforcement for the same subject.
- Never persist OPA input bodies, WAHA template bodies, or full phone numbers anywhere (decision log included).
- Never set `enforced-paths` as a non-empty default in `application.yml` (breaks the test suite).
- Never touch `allowed-actions` self-exclusion, health/actuator enforcement, OperationalScope derivation, or the audit immutable-trigger column list.
- Never hand-edit generated client files; never renumber migrations.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| ENFORCE_MUTATION | POST /api/v1/machines as MANAGER_MAINTENANCE, OPA up | PDP allow → business logic runs; decision row persisted | OPA down → degraded-deny unless degraded-allowlist |
| ENFORCE_DENY | POST /api/v1/machines as TECHNICIAN, OPA up | 403 FORBIDDEN before business logic; decision row persisted (denied) | Standard error shape |
| ENFORCE_READ | GET /api/v1/machines as any authenticated | allowed; SQL row-filter via OperationalScope (identical set to OPA input) | Out-of-scope single-resource → existing requirePlantAccess 403 (unchanged) |
| ADMIN_ONLY | GET /api/v1/telemetry/freshness as MANAGER_MAINTENANCE | 403 (SUPER_ADMIN only) — identical to pre-enforcement | — |
| SUPER_ADMIN | Any enforced endpoint as SUPER_ADMIN | allowed (bypass); decision persisted | — |
| ANON | GET enforced path with no JWT | 403 default-deny (was 401-family; still denied — documented delta) | — |
| DECISION_TRACE | AUDITOR GET /api/v1/authz/decisions | paged list; each row has decision_id + policy_revision + allowed + action | 403 for non-SUPER_ADMIN/AUDITOR |
| AUDIT_LINK | audit record created during enforced request | AuditLogEntryView exposes decision_id; AUDITOR can cross-link to decision row | null-safe when no enforcement |
| RETENTION | decision rows older than retention-days | purged by scheduled job | Purge must not touch younger rows |
| POLICY_LOAD | compose up with policy dir | OPA serves /v1/data/syncro/authz/allow per new rego; `opa test` green | Miscompiling rego fails OPA startup (visible health) |
| BREAKER | OPA hung > timeout | circuit opens after 2 calls (was 5); enforced requests fail-deny fast | — |

</intent-contract>

## Code Map

**Policy (single source of the matrix):**
- `syncro/authz/policy/authz.rego` -- REPLACE -- SUPER_ADMIN bypass + `mutation_paths`/`admin_only_paths`/data-read rules via `glob.match` on `input.action`; `actions` rule extended. Keep `default allow := false`.
- `syncro/authz/policy/authz_test.rego` -- REPLACE -- per-(role,method,path) parity matrix cases (SUPER_ADMIN allow, MANAGER_MAINTENANCE mutation allow + read allow, TECHNICIAN/SECTION_LEADER/AUDITOR read allow + mutation deny, telemetry/worker admin-only, anon deny).
- `syncro/authz/run-opa-test.ps1` (or .sh) -- NEW -- `opa test` wrapper; CI wiring note in `syncro/authz/README.md` (or reuse existing docs) -- NEW/UPDATE.

**Enforcement wiring:**
- `syncro/infra/docker-compose.yml` -- MODIFY -- opa service: mount `./authz/policy` → `/policies` (read-only), command `run --server --addr=0.0.0.0:8181 --set decision_logs.console=true /policies`; backend service env `SYNCRO_AUTHZ_ENFORCED_PATHS` (org/maintenance/sync ant patterns incl. `/api/v1/plants,/api/v1/machine-groups,/api/v1/sections,/api/v1/teams,/api/v1/machine-responsibilities,/api/v1/machines/**,/api/v1/machine-sparepart-installations/**,/api/v1/spareparts/**,/api/v1/sparepart-taxonomies/**,/api/v1/alerts/**,/api/v1/audit-log/**,/api/v1/setup-completeness/**,/api/v1/telemetry/**,/api/v1/notification/worker/**` — implementer reconciles with actual controller mappings).
- `syncro/apps/backend/src/main/resources/application.yml` -- MODIFY -- `syncro.opa.resilience.minimum-number-of-calls: 2`; `syncro.authz.decision-log.retention-days: ${SYNCRO_AUTHZ_DECISION_LOG_RETENTION_DAYS:30}`; enforced-paths stays env-driven empty default.

**Decision log (NEW):**
- `resources/db/migration/V46__authz_decision_log.sql` -- NEW -- `authz_decisions(id uuid pk, decision_id uuid not null, policy_revision text, allowed boolean not null, degraded boolean not null, subject_user_id uuid null, action varchar(255) not null, resource_type varchar(64), decided_at timestamptz not null default now())` + index on decided_at.
- `com/syncro/authz/domain/AuthzDecision.java` -- NEW -- record/entity mapping.
- `com/syncro/authz/infrastructure/AuthzDecisionEntity.java` + `AuthzDecisionRepository.java` -- NEW.
- `com/syncro/authz/application/DecisionLogService.java` -- NEW -- record (masked: only structural fields) + paged query + `@Scheduled` purge (retention-days).
- `com/syncro/authz/application/PolicyDecisionPoint.java` -- MODIFY -- after each enforcement `evaluate()`, persist via DecisionLogService; capture `revision` from OpaClient envelope (extend `OpaClient.Result` + parsing) with sha256(authz.rego)-fallback computed from a classpath/env-registered path.
- `com/syncro/authz/api/AuthzController.java` + `AuthzDtos` -- MODIFY -- `GET /api/v1/authz/decisions` (paged, SUPER_ADMIN||AUDITOR gate), `AuthzDecisionView`.
- `com/syncro/authz/config/AuthzProperties.java` -- MODIFY -- add `decisionLogRetentionDays` (int, default 30), `policyRevisionFallbackPath` (optional).

**Audit link:**
- `com/syncro/audit/api/AuditLogService.java` + `AuthDtos`/`AuditLogDtos` -- MODIFY -- `AuditLogEntryView` + query include `decision_id` (entity already has it); no masking changes needed (nothing sensitive in audit fields by construction).
- `openapi.json` -- MODIFY -- `AuthUserView` untouched; add `AuthzDecisionView` + `/api/v1/authz/decisions`; widen `AuditLogEntryView` with `decisionId`; regenerate orval.

**Frontend (`syncro/apps/web/src`):**
- `app/(main)/dashboard/audit-log/page.tsx` -- MODIFY -- allowedRoles += `"AUDITOR"`.
- `features/audit-log/*` -- MODIFY -- show `decision_id` column; add "Decision Log" tab (list via `authzDecisionsList`) gated by route roles; small.
- `lib/api/generated/**` + `lib/api/syncro-api.ts` -- via `generate:api` (contract-driven).

**Tests:**
- `com/syncro/authz/AuthzEnforcementIntegrationTest` -- NEW -- full boot with enforced-paths set + `@MockBean OpaClient` (allow/deny): assert 200/403 pre-business-logic, decision row persisted, degraded-deny path.
- `com/syncro/authz/DecisionLogServiceTest` -- NEW -- retention purge boundary, masking (no body fields present), paged view, SUPER_ADMIN/AUDITOR gate.
- `com/syncro/db/AuthzDecisionLogMigrationTest` -- NEW -- V46 schema + index (mirrors RoleTaxonomyMigrationTest pattern).
- `PolicyDecisionPointTest`/`AuthzInterceptorTest` -- MODIFY -- cover revision capture + persistence stub; existing mocks extended.

## Tasks & Acceptance

**Execution:**
- [x] `authz.rego` + `authz_test.rego` parity matrix; `opa test` green locally/CI wrapper.
- [x] compose: policy mount + command; backend `SYNCRO_AUTHZ_ENFORCED_PATHS`; health excluded.
- [x] `application.yml`: breaker min-calls 2; decision-log retention 30.
- [x] V46 migration + entity/repo + `DecisionLogService` (record, query, purge @Scheduled).
- [x] PDP + OpaClient: capture revision; persist per enforcement decision; fallback sha256.
- [x] AuthzController decisions endpoint (SUPER_ADMIN||AUDITOR) + DTOs; AuditLogEntryView.decisionId.
- [x] `openapi.json` + `npm run generate:api`; frontend audit-log AUDITOR + decision_id column + Decision Log tab.
- [x] New tests (enforcement integration, decision log, V46 migration); full targeted batch green; `tsc`/`vitest`/biome green.

**Acceptance Criteria:**
- Given an authorization-sensitive org/maintenance/sync endpoint, when a request is processed with OPA up, then OPA is evaluated default-deny before business logic via the single PolicyDecisionPoint and a decision is persisted; a denied request yields FORBIDDEN and no business logic runs. [FR-160]
- Given the parity matrix, when the same subject hits the same endpoint pre- vs post-enforcement, then allow/deny outcomes are identical (matrix encoded in rego tests + in-service gates untouched). [FR-160/NFR-P2-4]
- Given a decision row, when an AUDITOR queries `/api/v1/authz/decisions`, then they see which policy allowed/denied with policy revision + decision_id; audit records expose decision_id for cross-linking. [FR-164]
- Given decision logging, when rows age beyond `decision-log.retention-days` (default 30), then the purge removes only stale rows; no WAHA secret or full phone number ever appears in `authz_decisions`. [NFR-P2-7]
- Given the running stack, when compose starts, then OPA holds the policy (volume mount) and `opa test` is green in CI; health/actuator stay public (never enforced). [FR-162]

## Spec Change Log

<!-- Append-only. Populated by step-04 during review loops. -->

## Review Triage Log

### 2026-08-26 — Review pass (step-04)
- intent_gap: 0
- bad_spec: 0
- patch: 8 (high 2, medium 4, low 2)
- defer: 2 (medium 1, low 1)
- reject: 3 (low 3)
- addressed_findings:
  - `[high]` `[patch]` DecisionLogService.purgeExpired self-invocation bypassed the @Transactional proxy → TransactionRequiredException on every hourly purge. Fixed: @Transactional moved onto purgeExpired (the @Scheduled entry point) so the @Modifying delete runs inside a transaction.
  - `[high]` `[patch]` Policy revision always "unknown" in deployed compose (OPA non-bundle emits no revision; classpath:authz-policy.rego does not exist). Fixed: computePolicyRevision now tries configured fallback (SYNCRO_AUTHZ_POLICY_FILE), then file:/classpath: candidates, then "unknown"; .env.example wired to file:syncro/authz/policy/authz.rego.
  - `[medium]` `[patch]` Enforcement surface default empty = silent fail-open if env omitted. Fixed: startup WARN log when enforced-paths empty (empty-default kept deliberately for the test suite — Boundaries).
  - `[medium]` `[patch]` WahaTemplate mutation gate unreachable: /api/v1/notification/templates/** missing from SYNCRO_AUTHZ_ENFORCED_PATHS. Fixed in .env.example.
  - `[medium]` `[patch]` parseDecisionId fabricated random UUIDs broke audit correlation on degraded/null paths. Fixed: decision_id nullable (V46) + null passthrough; migration test updated (9.5-DB-001).
  - `[medium]` `[patch]` Action string > VARCHAR(255) silently lost the FR-164 row. Fixed: truncateAction in DecisionLogService.
  - `[low]` `[patch]` Missing trailing newlines in authz.rego/authz_test.rego. Fixed.
  - `[low]` `[patch]` run-opa-test.ps1 image tag 1.19.1 vs 1.19.1-debug. Fixed to 1.19.1-debug.
  - defer: DW-132 (synchronous DB write per enforcement decision — perf), DW-133 (decision-log tab lacks mobile card variant).
  - reject: degraded-allowlist dead-until-enforcement (by-design, DW-129); purge-interval-ms <= 0 misconfig; frontend page-size desync > 200 (unreachable via fixed pagination).

## Design Notes

- **Why parity rego mirrors in-service gates instead of replacing them:** enforcement must not regress any working flow; the in-service gates are the executable spec of current behavior and remain as the harness. Removing them is a later-story cleanup once OPA is proven.
- **Why enforced-paths default stays empty in `application.yml`:** the integration suite has no real OPA; a non-empty default would 403 every request via the degraded path (5s timeouts each). Compose is the enforcement surface; dedicated integration tests set it explicitly with a mocked OpaClient.
- **Masking by construction:** OPA input is `{roles, plantIds, machineGroupIds, activeTeamIds, action, resource.type}` — WAHA bodies and phone numbers never reach OPA or the decision row. A test asserts `authz_decisions` never contains body/phone-shaped data.
- **Revision:** OPA returns `revision` in the data envelope only when a bundle supplies it; we capture it when present and fall back to sha256(authz.rego) so `policy_revision` is never empty. Documented; not load-bearing for enforcement.
- **AUDITOR surfacing:** AUDITOR can read decisions + audit log now; all other capabilities wait for Epics 10–14.
- **Anon delta:** anonymous requests to enforced endpoints now yield 403 (was 401-family from the security filter). Both deny; acceptable and consistent with default-deny; noted in triage as intended.

## Verification

**Commands:**
- `mvn -o -f syncro/apps/backend/pom.xml test "-Dtest=AuthzEnforcementIntegrationTest,DecisionLogServiceTest,AuthzDecisionLogMigrationTest,PolicyDecisionPointTest,AuthzInterceptorTest,AuthzControllerTest,AuditLogServiceIntegrationTest,RoleTaxonomyMigrationTest"` -- run one-per-JVM (DW-127): AuthzEnforcement 3/3, DecisionLog 3/3, Migration 3/3, PDP/Interceptor/Controller 28/28, AuditLog 8/8, RoleTaxonomy 5/5. All BUILD SUCCESS.
- `cd syncro/authz && ./run-opa-test.ps1` -- PASS 38/38 (rego parity matrix + action rules).
- `cd syncro/apps/web && npx tsc --noEmit && npm run test:unit` -- PASS (0 tsc errors; 287/287 vitest).
- Manual (enforcement live): compose up, login as pilot `staff.gm1` (STAFF_MAINTENANCE) → GET /api/v1/machines 200, POST /api/v1/machines 403; SUPER_ADMIN full; GET /api/v1/authz/decisions lists rows with revision; audit-log shows decision_id.

## Auto Run Result

| Step | Outcome | Notes |
|------|---------|-------|
| 01 route | pass | epic 9, story 5; epic-9-context.md valid |
| 02 plan | pass | spec-9-5 written; parity-rego + decision-log design |
| 03 implement | pass | rego matrix, compose mount, V46, DecisionLogService, PDP persistence, API, frontend; subagent terse — implementation re-verified independently (backend/rego/frontend all green) |
| 04 review | pass | Blind Hunter (2 high/4 medium/2 low) + Edge Case Hunter (1 P1/1 P2/2 P3); 8 patches applied, 2 defers (DW-132, DW-133), 3 rejects |
| commit | 9591753 | `feat(authz): OPA enforcement on maintenance/org/sync endpoints (story 9-5)` |
| finalize | 9591753~1 | status done; followup_review_recommended: true |

**Defers appended:** DW-132 (sync DB write per decision), DW-133 (decision-log tab mobile variant).

**Residual risks:** policy revision derives from a file hash when OPA is not bundle-configured (real revision appears only with a bundle); anonymous requests to enforced endpoints 403 instead of 401 (both deny; documented delta); the known multi-integration-class Testcontainers quirk (DW-127) persists; degraded-allowlist stays latent until health is ever enforced (DW-129).
