---
title: 'OPA Infrastructure'
type: 'feature'
created: '2026-08-25'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: d61c463
final_revision: 9c87273
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-9-2-cross-plant-teams.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Authorization today relies on scattered Phase 1 role/plant checks; Epic 9's spine requires every authorization-sensitive request evaluated by an OPA sidecar (default-deny) before business logic, with decisions correlated into audit and surfaced to the frontend as allowed-actions. Nothing of that infrastructure exists yet.

**Approach:** Add an `opa` service (v1.19.1) to the local compose stack with an in-repo Rego bundle; create the `com.syncro.authz` bounded context containing the single-sourced `PolicyDecisionPoint` (input assembly + RestClient/Resilience4j client mirroring `WahaClient`), a coarse `allow` interceptor with configurable enforced-path list and fail-deny degraded-mode allowlist, `GET /api/v1/authz/allowed-actions`, and audit correlation via a nullable `audit_log.decision_id` (V44) auto-filled from the request's decision. Enforcement rollout on real endpoints is 9.5.

## Boundaries & Constraints

**Always:**
- Compose service `opa` (stable name) image `openpolicyagent/opa:1.19.1`, command `run --server --addr=0.0.0.0:8181 --set decision_logs.console=true`, port `"${OPA_PORT}:8181"`, healthcheck `curl -f http://localhost:8181/health`; `.env.example` gains `OPA_HOST=opa`, `OPA_PORT=<free port>`; `application.yml` gains `syncro.opa.url: http://${OPA_HOST}:${OPA_PORT}` + resilience block and `syncro.authz.degraded-allowlist` — all `${VAR:<local-default>}` style matching `application-local.yml` conventions; `AbstractPostgresIntegrationTest` gains fake `OPA_*`/`SYNCRO_AUTHZ_*` properties.
- Policy-as-code lives under `syncro/authz/` : package `syncro.authz`, `default allow := false`, `allow` if `input.subject.roles[_] == "SUPER_ADMIN"`, plus `actions` rule returning `string[]` (empty by default; SUPER_ADMIN gets a placeholder read set); sibling `_test.rego` cases cover allow/deny/default-deny. Rules are evaluated at `POST /v1/data/syncro/authz/{allow|actions}`.
- Input schema is code-authoritative (`Addendum A3`, AD-2 dims): `subject{userId, roles[], plantIds[], machineGroupIds[], activeTeamIds[]}` (section/team ids deliberately excluded — teams contribute group ids), `resource{type, id, plantId, machineGroupId, attributes{}}`, `action{name}`, `context{traceId}`. Assembly ONLY inside `PolicyDecisionPoint`; subject built from `JwtTokenService.AuthenticatedUser` + `OperationalScopeService.derive(user)`; `roles` = `[user.applicationRole().name()]` until 9.4.
- `PolicyDecisionPoint.evaluate(subject, resource, action)` returns `Decision(boolean allowed, boolean degraded, String decisionId)` — never throws. HTTP mechanics live in `OpaClient` (programmatic CircuitBreaker `"opa"` via `CircuitBreakerRegistry`, timeouts from properties, mirrors `WahaClient` construction exactly). Any client failure ⇒ deny, EXCEPT when the current request path matches `syncro.authz.degraded-allowlist` (Ant patterns; defaults `/api/v1/health`, `/actuator/**`) ⇒ allow with `degraded=true`. `decisionId` = OPA response `decision_id` when present else generated UUID.
- `AuthzInterceptor` (first-ever `HandlerInterceptor`, registered by a new `WebMvcConfigurer` adding `registry.addInterceptor(...).addPathPatterns(enforcedPaths)`) calls PDP with resource type `endpoint`, action `"<method> <pattern>"`; DENIED ⇒ 403 JSON `ErrorResponse(code="FORBIDDEN", ...)` written inline before any controller runs (house 401-lambda pattern) and request never proceeds; ALLOWED ⇒ stash `decisionId` into request attributes via `DecisionContext`. `enforced-paths` ships EMPTY in 9.3 (zero behavior change to existing routes; 9.5 populates) — interceptor still fully tested.
- V44 migration: `ALTER TABLE audit_log ADD COLUMN decision_id UUID NULL;` then drop/recreate `audit_log_immutable_before_update` INCLUDING `decision_id` in its column list (V16 style); `AuditLogEntity` + `AuditRecord` gain nullable `decision_id`; `AuditLogWriter.record*` auto-fills from `DecisionContext.currentDecisionId()` (request-bound) when record value is null.
- `GET /api/v1/authz/allowed-actions` (authenticated via existing SecurityConfig matchers; no method annotation) returns `{"actions":[...]}` by calling PDP for rule `actions` with resource type `self`; on OPA failure it returns `{"actions":[]}` with `degraded:true` flag in the view rather than failing the dashboard render.
- Error codes: `FORBIDDEN` 403 only; no new codes. All timestamps UTC via injected `Clock`; no secrets logged (OPA input contains no secrets by schema).

**Block If:** Nothing requires human input. Pinned: enforced-paths empty in 9.3 (9.5 owns rollout); roles single-valued string array pre-9.4; sectionIds/production-line scope omitted (AD-2 dims win over A3 prose); decision-log retention/masking APIs deferred to 9.5 auditor story; no OPA Testcontainers requirement — compose + `opa test` via docker covers policy proof.

**Never:**
- Never embed WASM/IR or add an OPA java SDK dependency.
- Never let any class other than `PolicyDecisionPoint` assemble OPA input; never copy org data into OPA's store/bundle beyond the stateless rules.
- Never change behavior of any existing endpoint in 9.3 (empty enforced-paths invariant).
- Never implement 9.4 role taxonomy, 9.5 endpoint enforcement/row-scoping, decision-log query APIs, or frontend allowed-actions consumption here.
- Never hand-edit generated OpenAPI client files; snapshot refresh follows the committed-spec workflow only if the new endpoint's contract is added intentionally.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| PDP_ALLOW | OPA returns 200 `{decision_id, result:true}` | `Decision(true,false,"<id>")` | No error |
| PDP_DENY | `result:false` | `Decision(false,false,"<id>")` | No error |
| PDP_SIDECAR_DOWN | Connection refused/timeout/5xx while CB healthy | `Decision(false,…)` (deny) for non-allowlisted path | No throw; failure recorded by CB |
| PDP_DEGRADED_ALLOWLISTED | Same failure but path matches allowlist | `Decision(true,true,null-or-id)` | No throw |
| CB_OPEN | Forced-open breaker | Immediate deny/degraded path taken; no network call | `CallNotPermittedException` ignored |
| INTERCEPTOR_DENY | Enforced path, PDP disallows | 403 `FORBIDDEN` JSON; controller never invoked | Standard error shape |
| ACTIONS_OK | Authenticated user, OPA reachable | 200 `{actions:[...]}` from rule | No error |
| ACTIONS_FAIL | OPA unreachable | 200 `{actions:[],degraded:true}` | Dashboard-safe |
| AUDIT_CORRELATE | Any audit row written during an intercepted request | Row's `decision_id` = request decision id; outside requests → NULL | No error |
| INPUT_SHAPE | Any evaluate() call | JSON contains exactly subject/resource/action/context keys; subject carries derived scope arrays (possibly empty, never null) | Malformed target rejected by tests |

</intent-contract>

## Code Map

**Backend (`syncro/apps/backend/src/main/java/com/syncro` unless noted):**
- `resources/db/migration/V44__add_audit_decision_id.sql` -- NEW -- nullable column + immutable-update-trigger recreation including it.
- `authz/application/OpaSubject.java` -- NEW -- records `OpaSubject`, `OpaResource`, `OpaAction`, `OpaContext`, `OpaInput` (authoritative schema).
- `authz/application/Decision.java` -- NEW -- `(boolean allowed, boolean degraded, String decisionId)`.
- `authz/application/PolicyDecisionPoint.java` -- NEW -- single-sourced assembly + evaluate()/resolveActions(); degraded-allowlist matching lives here.
- `authz/infrastructure/OpaClient.java` -- NEW -- RestClient + programmatic CB `"opa"`; `postRule(rule, input)` returns raw envelope `{decisionId, result}`; never-throw `Result` style like WahaClient.
- `authz/infrastructure/AuthzInterceptor.java` + `AuthzWebMvcConfigurer.java` -- NEW -- first HandlerInterceptor/WebMvcConfigurer; inline 403 writer.
- `authz/application/DecisionContext.java` -- NEW -- static helpers over request attributes (`stash`/`currentDecisionId`).
- `authz/api/AuthzDtos.java` + `AuthzController.java` -- NEW -- `/api/v1/authz/allowed-actions`.
- `config/OpaProperties.java` (`syncro.opa.url`) + `OpaResilienceProperties.java` (`syncro.opa.resilience.*`, Waha defaults) + `AuthzProperties.java` (`syncro.authz.enforced-paths`, `syncro.authz.degraded-allowlist`) -- NEW.
- `audit/application/AuditRecord.java` + `AuditLogWriter.java` + `audit/infrastructure/AuditLogEntity.java` -- MODIFY -- nullable decisionId plumbing with request-context autofill.
- `resources/application.yml` + `application-local.yml` -- MODIFY -- `syncro.opa.*`, `syncro.authz.*` keys; readiness excludes `opaCircuitBreaker` health indicator if added (optional, skip unless trivial).
- `syncro/infra/docker-compose.yml` + `syncro/.env.example` -- MODIFY -- `opa` service + `OPA_HOST/OPA_PORT`.
- `syncro/authz/policy/authz.rego` + `authz_test.rego` -- NEW -- package `syncro.authz`; allow + actions rules; tests incl. default-deny.
- Tests: NEW `com/syncro/authz/OpaClientCircuitBreakerTest.java` (JDK HttpServer pattern: parse ok/deny, timeout, 500, forced-open), `com/syncro/authz/PolicyDecisionPointTest.java` (mock client; input-shape capture via ArgumentCaptor; degraded matrix), `com/syncro/authz/AuthzInterceptorTest.java` (standalone MockMvc + probe controller: deny-writes-403-before-controller, allow-stashes-id, degraded path), `com/syncro/authz/AuthzControllerTest.java` (@WebMvcTest style), `com/syncro/db/AuditDecisionIdMigrationTest.java` (column nullable, trigger blocks UPDATE of decision_id, audit row round-trip); MODIFY `AbstractPostgresIntegrationTest.java` (fake OPA/authz props).

**Frontend:** none (allowed-actions consumption belongs to later epics).

## Tasks & Acceptance

**Execution:**
- [x] `syncro/infra/docker-compose.yml` + `.env.example` + `application*.yml` + `AbstractPostgresIntegrationTest` -- infra + config keys.
- [x] `syncro/authz/policy/*.rego` -- default-deny allow/actions rules + rego tests.
- [x] `config/Opa*Properties` + `AuthzProperties` -- typed config.
- [x] `OpaClient` + `OpaClientCircuitBreakerTest` -- resilient HTTP edge cases proven.
- [x] `PolicyDecisionPoint` + `Decision` + `OpaSubject` + `PolicyDecisionPointTest` -- single-sourced assembly, fail-deny/degraded matrix, exact input shape.
- [x] `DecisionContext` + `AuthzInterceptor` + `AuthzWebMvcConfigurer` + `AuthzInterceptorTest` -- coarse gate with empty enforced-paths default.
- [x] `AuthzController` (+Dtos) + `AuthzControllerTest` -- allowed-actions endpoint incl. degraded branch.
- [x] `V44` + audit entity/writer plumbing + `AuditDecisionIdMigrationTest` -- decision correlation without breaking immutability.
- [ ] Verify: targeted Maven suite green; `docker compose config` valid; rego tests pass via opa docker image; web tsc untouched-green (no FE changes).

**Acceptance Criteria:**
- Given the compose stack is started, when `docker compose ps` runs, then `opa` (1.19.1) is healthy on its configured port and the backend property `syncro.opa.url` points at it. [infra]
- Given any caller of `PolicyDecisionPoint`, when evaluation occurs, then the wire input contains exactly the subject/resource/action/context schema with derived scope, and a failed OPA call denies except allowlisted paths which return degraded-allow. [AD-1/A3]
- Given an enforced-path request whose decision denies, when the interceptor runs, then a 403 `FORBIDDEN` standard-shape response is returned and the controller is never reached. [FR-160]
- Given an intercepted request that writes an audit row, when the row is inspected, then its `decision_id` equals the request's decision id (null when no decision ran). [FR-164]
- Given an authenticated session, when `GET /api/v1/authz/allowed-actions` is called, then the OPA-computed action set is returned and degrades to an empty set with a degraded flag when OPA is down. [FR-161]

## Spec Change Log

<!-- Append-only. Populated by step-04 during review loops. -->

## Review Triage Log

### 2026-08-25 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 8: (high 1, medium 4, low 3)
- defer: 3: (high 1, medium 2)
- reject: 12
- addressed_findings:
  - `[high]` `[patch]` Bare `${SYNCRO_AUTHZ_*}` placeholders crashed boot outside the local profile and `.env.example` missed the vars — added `:`-defaults in `application.yml`, templated both vars in `.env.example`, and corrected `OPA_HOST` to `localhost` for host-run backends.
  - `[medium]` `[patch]` "Never throw" invariant was false: scope-derivation failures escaped as 500s — `evaluate`/`resolvedActions` now wrap assembly+call in fail-deny/degraded handling.
  - `[medium]` `[patch]` Raw `getRequestURI()` broke allowlist matching under normalization and action labels used config-order first match — switched to `ServletRequestPathUtils` cached path plus Spring's `BEST_MATCHING_PATTERN_ATTRIBUTE` (order-independent, maximally specific).
  - `[medium]` `[patch]` OPTIONS preflights and ERROR dispatches were re-evaluated by the interceptor — skipped via method + `DispatcherType.REQUEST` guard.
  - `[medium]` `[patch]` Broad enforced-paths could let the gate block `/api/v1/authz/allowed-actions` itself, destroying the degrade contract — registration always excludes it; new `AuthzWebMvcConfigTest` pins empty-guard, registration+exclusion, explicit-empty allowlist, and unset-defaults behaviors.
  - `[low]` `[patch]` Explicitly empty degraded-allowlist silently reverted to defaults — null-only fallback so operators can configure strict fail-deny.
  - `[low]` `[patch]` Interceptor 403 minted an uncorrelated traceId — now reuses the shared `PolicyDecisionPoint.currentTraceId()` source.
  - `[low]` `[patch]` Malformed stashed decision ids were dropped with zero signal — writer logs a warn before nulling.

## Design Notes

- Why `enforced-paths` ships empty: 9.4 hasn't extended roles and 9.5 hasn't mapped endpoints; registering enforcement now would deny legitimate Phase 1 traffic (default-deny). The interceptor + tests prove the mechanism so 9.5 becomes configuration plus policy work only.
- Golden example: technician hits an enforced `POST /api/v1/work-orders` (future 9.5). Interceptor builds `subject{userId, roles:["MANAGER_MAINTENANCE"], plantIds:[GM1], machineGroupIds:[Forming], activeTeamIds:[PackagingGroupId]}`, `resource{type:"workorder", attributes:{estCost:3000000}}`, `action{"workorder.create"}`. OPA denies ⇒ 403 before service; OPA allows ⇒ decision id lands on the workorder's audit rows.
- Client posture copies `WahaClient` verbatim (SimpleClientHttpRequestFactory timeouts, registry-built breaker recording `ResourceAccessException`+status exceptions, ignoring `CallNotPermittedException`); difference: PDP interprets failures as policy inputs (deny/degrade), it never surfaces exceptions upward.

## Verification

**Commands:**
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=OpaClientCircuitBreakerTest,PolicyDecisionPointTest,AuthzInterceptorTest,AuthzControllerTest,AuditDecisionIdMigrationTest,SparepartAlertQueryServiceTeamScopeFilterIntegrationTest"` -- expected: BUILD SUCCESS (last entry guards 9-2 regression after audit-plumbing touch).
- `docker compose -f syncro/infra/docker-compose.yml config` -- expected: validates; `opa` present.
- `docker run --rm -v "${PWD}/syncro/authz/policy:/policy" openpolicyagent/opa:1.19.1 test /policy` -- expected: all rego tests pass.

**Manual checks:**
- `docker compose up -d opa` then `curl http://localhost:<OPA_PORT>/health` → 200; `curl -X POST .../v1/data/syncro/authz/allow -d '{"input":{...}}'` → `{"decision_id":..., "result":false}` for a non-SUPER_ADMIN subject.

## Auto Run Result

Status: implemented, reviewed (2 adversarial layers), patched, verified, committed.

**Summary:** OPA 1.19.1(-debug) sidecar in compose + Rego default-deny bundle with passing policy tests; new `com.syncro.authz` context — single-sourced `PolicyDecisionPoint` (subject/resource/action/context assembly from `OperationalScopeService.derive`, never-throw fail-deny with Ant-pattern degraded allowlist), resilient `OpaClient` (WahaClient-mirrored circuit breaker), coarse `AuthzInterceptor` (empty enforced-paths ⇒ zero behavior change; OPTIONS/ERROR-dispatch skipped; normalized path + Spring best-match pattern for action labels; allowed-actions endpoint always self-excluded), `GET /api/v1/authz/allowed-actions` with dashboard-safe degradation, and FR-164 correlation via V44 nullable `audit_log.decision_id` auto-filled from the request's decision (immutable-update trigger recreated including it).

**Review findings breakdown:** intent_gap 0 · bad_spec 0 · patch 8 applied (1 high: env-placeholder boot crash; 4 medium: never-throw restore, path normalization/best-match action labels, preflight+error-dispatch guard, allowed-actions self-exclusion; 3 low: explicit-empty allowlist respected, correlated 403 traceId, warn on dropped decision ids) · defer 3 (DW-128 trigger column coverage, DW-129 health-posture planning note for 9.5, DW-130 sparse-traffic CB stall) · reject 12.

**Verification:** targeted suite green across two passes (35 authz/migration tests incl. Testcontainers migration evidence; TeamControllerTest+SparepartAlertQueryServiceTest as regression guards); rego `opa test` 6/6 via docker image; `docker compose --env-file config` valid; full `mvn -o compile` clean after AuditRecord plumbing touched 15 call-site files. Residual risks: `-debug` image wget healthcheck to be eyeballed once at first `compose up`; DW-128/129/130 carried forward.

**Follow-up review recommendation rationale:** final pass produced only localized patches (no spec loopbacks); both adversarial layers already ran on this exact diff, so a third pass would re-tread the same ground → `followup_review_recommended: false`.
