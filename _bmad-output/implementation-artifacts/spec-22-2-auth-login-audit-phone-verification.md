---
title: 'Story 22-2: Auth Login Audit & Phone Verification (redesigned 2026-08-31)'
type: 'feature'
created: '2026-09-05'
status: 'done'
baseline_revision: '57632fd'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-22-context.md'
  - '_bmad-output/implementation-artifacts/spec-21-1-non-conformance-8d-reports.md'
warnings: []
deferred:
  - summary: >-
      auth_login_audits has no traceId column (epic-22 context asks for one per attempt); V1 table is frozen and adding a column needs its own migration decision.
    evidence: |-
      epic-22-context.md requires "actor, timestamp, source, result, and traceId"; V1 DDL has no trace column and the story's Block-If forbids touching V1 contracts.
    location: >-
      syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql:1764
    severity: medium
  - summary: >-
      lower(identifier) LIKE '%…%' cannot use idx_auth_login_audits_identifier; every filtered audit read is a seq scan once login volume grows.
    evidence: |-
      AuthLoginAuditRepository.search applies lower() to the column; the only index is a plain btree on raw identifier. AuditLogRepository has the same precedent flaw but this table grows per login attempt.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthLoginAuditRepository.java:22
    severity: low
  - summary: >-
      openapi.json not regenerated for the new auth endpoints; frontend/Orval cannot see the surface until a consumption story runs the contract pipeline.
    evidence: |-
      syncro/apps/web/openapi.json last touched at story 9-5; spec explicitly scopes out frontend UI, so regeneration belongs to the consumption story.
    severity: low
  - summary: >-
      opa test is not wired into mvn test or CI, so rego regressions on the new auth paths ship undetected by the normal build.
    evidence: |-
      Already logged in _bmad-output/implementation-artifacts/deferred-work.md:1188; this story adds 14 rego cases riding on the same gap.
    severity: medium
  - summary: >-
      X-Forwarded-For first hop is trusted unconditionally for the audit IP; proper fix is trusted-proxy CIDR validation (ForwardedHeaderFilter), a deploy-topology decision.
    evidence: |-
      AuthController.clientIp takes the first XFF hop with no proxy validation; local infra sits behind no proxy chain today, so the spoof path is theoretical until a proxy is deployed. Decision recorded in spec Design Notes.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthController.java:136
    severity: medium
---

<intent-contract>

## Intent

**Problem:** `auth_login_audits` and `phone_verification_challenges` tables exist (V1, story 15-2) but nothing writes or reads them — login attempts leave no trace and the 4-hour ack auto-login (story 14-4, FR-181) has no verifiable phone challenge behind it, so SUPER_ADMIN cannot audit auth activity.

**Approach:** Hook `AuthService.login` to append one `AuthLoginAuditEntity` row per attempt (success + failure, never credentials), add a `phone_verification_challenges` application service (issue/verify/resend with attempt limits + expiry), expose SUPER_ADMIN/AUDITOR-only read endpoints with filters, and gate the new paths in rego with parity coverage.

## Boundaries & Constraints

**Always:**
- Forward-only change; never edit V1 or existing auth contracts (`/login`, `/me`, lockout behavior stay untouched)
- Every login attempt writes exactly one `auth_login_audits` row: identifier, ip, user agent, success flag, failure reason, occurred_at (server Clock); never password or hash material
- Reads are SUPER_ADMIN/AUDITOR only, filterable by identifier/success/date range, paginated; response masks nothing but never includes secrets (there are none stored)
- Phone challenges store only the OTP hash; verify compares hash, increments attempt_count, consumes on success; expired/exhausted/consumed challenges reject with stable codes
- Every mutation: service role gate AND rego rule for the same role set (parity), AND immutable audit-log row with previous/new values
- DTO records with Bean Validation; `Instant` UTC; error envelope via `AuthExceptionHandler` (extend, same code/message/timestamp/traceId shape)

**Block If:**
- A change to V1, the login contract, or lockout semantics seems required → HALT (all 22-2 work is additive)

**Never:**
- No frontend UI (consumption surface is a later story)
- No SMS/WhatsApp sending — issuance returns the challenge id/expiry only; the OTP transport is out of scope
- No new dependencies; no Lombok/MapStruct

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Login success audits | POST /login valid credentials | 200 + token + one audit row (was_success=true) | No error expected |
| Login failure audits | POST /login wrong password / unknown user | 401 INVALID_CREDENTIALS + one audit row (was_success=false, failure reason) | — |
| Locked account audits | POST /login on locked account | 423 ACCOUNT_LOCKED + one audit row | — |
| List audits | SUPER_ADMIN, filters + page | 200 page of audit views, newest first | 403 for other roles |
| Auditor reads | AUDITOR, list/detail | 200 (read-only; mutations still 403) | — |
| Issue challenge | SUPER_ADMIN, user + phone | 201 + challenge id + expiresAt (OTP hash stored) | 404 USER_NOT_FOUND |
| Verify challenge | correct OTP, active challenge | 200 + consumed_at stamped | 409 CHALLENGE_EXPIRED / CHALLENGE_EXHAUSTED / 404 CHALLENGE_NOT_FOUND |
| Wrong OTP | incorrect OTP, attempts left | 409 INVALID_OTP + attempt_count incremented | 409 CHALLENGE_EXHAUSTED on 5th |
| Resend | before resend_available_at | 409 RESEND_TOO_EARLY | 201 + new expiry after window |
| Unauthenticated | no JWT | 401 AUTHENTICATION_REQUIRED | SecurityConfig |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/auth/application/AuthService.java:48` -- login hook point -- append audit row per attempt (success/failure/locked paths)
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthLoginAuditEntity.java` -- append-only entity -- persist via new service
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthLoginAuditRepository.java` -- extend -- filtered paged query (identifier/success/date range)
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/PhoneVerificationChallengeEntity.java` -- registerFailedAttempt/consume exist -- verify/resend/issue service around them
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/PhoneVerificationChallengeRepository.java` -- extend -- active-challenge lookup per user
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthController.java` -- extend -- read endpoints + challenge endpoints under /api/v1/auth
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthExceptionHandler.java` -- extend -- stable codes for challenge failures (409s) + 403 audit gate
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthDtos.java` -- extend -- request/response records with Bean Validation
- `syncro/authz/policy/authz.rego` -- extend -- audit/challenge path sets + role rules (SUPER_ADMIN full, AUDITOR reads)
- `syncro/.env.example` -- extend -- SYNCRO_AUTHZ_ENFORCED_PATHS entries (parity test enforces)
- Tests: `AuthLoginLockoutTest.java` (Mockito login pattern), `AuthControllerTest.java` (@WebMvcTest), `AuthUserRepositoryIntegrationTest.java` (Testcontainers pattern)

## Tasks & Acceptance

**Execution:**
- `auth/application/AuthLoginAuditService.java` -- create -- audit write service (record per attempt) + filtered paged list (SUPER_ADMIN/AUDITOR reads)
- `auth/application/PhoneVerificationService.java` -- create -- issue/verify/resend with OTP hash + attempt limits + expiry (SUPER_ADMIN mutations)
- `auth/infrastructure/AuthLoginAuditRepository.java` + `PhoneVerificationChallengeRepository.java` -- extend -- filtered/pageable + active-challenge queries
- `auth/application/AuthService.java:48` -- extend -- append audit row on all three login outcomes (success, bad credentials, locked); unknown-user attempts audit with null userId
- `auth/api/AuthController.java` + `AuthDtos.java` + `AuthExceptionHandler.java` -- extend -- GET /login-audits (+/{id}), POST /phone-challenges, POST /phone-challenges/{id}/verify, POST /phone-challenges/{id}/resend; stable 409 codes
- `syncro/authz/policy/authz.rego` + `authz_test.rego` + `syncro/.env.example` -- extend -- audit/challenge paths + role rules + parity entries (SUPER_ADMIN mutate, AUDITOR read-only, TECHNICIAN denied)
- Tests: `AuthLoginAuditServiceTest` (Mockito, audit-per-attempt matrix incl. unknown user), `PhoneVerificationServiceTest` (Mockito, issue/verify/expiry/exhaust/resend), `AuthAuditControllerTest` (@WebMvcTest, 401/403/404/409 envelope), `AuthAuditIntegrationTest` (Testcontainers: login writes audit row, challenge lifecycle)

**Acceptance Criteria:**
- Given a login attempt (success, bad password, unknown user, locked), when it completes, then exactly one `auth_login_audits` row exists with identifier/result/timestamp and no credential material (AC1)
- Given a SUPER_ADMIN/AUDITOR queries audits, when filters apply, then the paged newest-first list returns without secrets; TECHNICIAN gets 403 (AC2)
- Given a phone challenge lifecycle, when issued/verified/resent, then only the OTP hash is stored, wrong OTPs increment toward exhaustion, expired/consumed challenges reject with stable codes (AC3)

## Spec Change Log

## Review Triage Log

### 2026-09-05 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 13: (high 3, medium 5, low 5)
- defer: 5
- reject: 5
- addressed_findings:
  - `[high]` `[patch]` Audit write failure must not break login (spec invariant unenforced) — try/catch around `recordAttempt` in `AuthService.login`, log + continue.
  - `[high]` `[patch]` `resend` un-exhausted a brute-forced challenge (attempt budget reset) — reject with `CHALLENGE_EXHAUSTED` when `attemptCount >= maxAttempts` + test.
  - `[high]` `[patch]` No optimistic locking on `PhoneVerificationChallengeEntity` — concurrent verifies could lose increments and enlarge the OTP guess budget — `@Version` + V15 migration + concurrency test (V13 precedent).
  - `[medium]` `[patch]` Success audit committed before outer tx could record success for a login that errored — move success `recordAttempt` to afterCommit synchronization.
  - `[medium]` `[patch]` Phone regex accepted separator-only strings (`"-------"`) — require a leading digit.
  - `[medium]` `[patch]` `auth_phone_challenge_paths` dead in rego (parity requires a rule, not a comment) — explicit deny rule + rego test cases.
  - `[medium]` `[patch]` Parity test missing parsed-set guards for the two new sets (21-1 pattern) — `contains(...)` assertions.
  - `[medium]` `[patch]` Test gaps: XFF branch, >255-char identifier truncation, `normalizeSize` clamps, case-insensitive filter, disabled-user audit branch, INT-005 exact audit count + previous/new delta, `maskPhone` branches.
  - `[low]` `[patch]` Unstable pagination — add `id` tiebreaker to `occurredAt DESC` sort.
  - `[low]` `[patch]` Spec Design Notes contradicted shipped behavior (same-tx claim vs deliberate `REQUIRES_NEW`) — amend Design Notes; record XFF decision.
  - `[low]` `[patch]` `LoginAuditListResponse` diverged from house pagination shape — add `totalPages`/`sort`.
  - `[low]` `[patch]` `truncate(userAgent, 2000)` magic cap against a TEXT column — remove; fix stale `AuditEntityType` javadoc V1 pointer.
  - `[low]` `[patch]` `findActiveForUser` spec-listed but untested — unit test pinning selection semantics.

## Design Notes

- Audit write must not break login: the shipped topology is (a) failure paths write the audit row via `REQUIRES_NEW` BEFORE the outer transaction touches `auth_users` (the outer tx rolls back on the thrown 401/423, so the audit must commit independently — the original "rides the same transaction" note was factually wrong: failure counters do NOT persist today); the pre-write ordering also keeps the audit's FK check from waiting on the outer's uncommitted UPDATE; (b) the success path defers its audit to `afterCommit` so a rolled-back commit never leaves a `was_success=true` row; (c) every audit write is wrapped in try/catch — an audit failure logs a warning and never changes the login outcome.
- Client IP: first `X-Forwarded-For` hop when present, else `getRemoteAddr()`. The local deployment sits behind no trusted proxy chain, so XFF is trusted as-is (spoofable by direct clients); revisit with a trusted-proxy hop count if the topology changes.
- OTP hashing reuses the existing `PasswordEncoder` bean (BCrypt) — no new crypto dependency; plain OTP is never persisted or returned.
- Challenge issuance is SUPER_ADMIN-only (operator-driven for the 4-hour ack flow); self-service issuance would need rate limits this story does not define. Resend renews the same row (new hash/expiry, attempt budget reset) but refuses an exhausted challenge (review 22-2 P2) — the reset must not revive a burned brute-force budget. `@Version` (V15) guards concurrent verifies against lost attempt increments (review 22-2 P3).

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=*AuthLogin*,*PhoneVerification*,*AuthAudit*"` -- expected: green incl. Testcontainers
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=AuthLoginLockoutTest,AuthControllerTest"` -- expected: existing auth behavior untouched, green
- `opa test syncro/authz/policy` (if opa CLI available; else covered by parity test) -- expected: policy tests green

## Auto Run Result

**Summary:** Login attempts now write exactly one `auth_login_audits` row per outcome (success via afterCommit, failures via REQUIRES_NEW pre-write, all wrapped so audit failure never breaks login); phone verification challenges get a full issue/verify/resend lifecycle (BCrypt OTP hash only, 15-min expiry, 5-attempt cap, 60-s resend window, exhausted challenges cannot be revived, `@Version` anti-lost-update); SUPER_ADMIN/AUDITOR read endpoints with filtered paged queries; rego parity rules + env entries; V14 (audit entity type) and V15 (challenge version column) forward-only migrations.

**Files changed:**
- `auth/application/AuthLoginAuditService.java` (new) — audit write + gated paged reads
- `auth/application/PhoneVerificationService.java` (new) — challenge lifecycle
- `auth/application/AuthService.java` — login hook, audit-per-attempt on all paths
- `auth/api/AuthController.java` / `AuthDtos.java` / `AuthExceptionHandler.java` — endpoints, DTOs, stable 404/409/403 codes
- `auth/infrastructure/AuthLoginAuditRepository.java` / `PhoneVerificationChallengeRepository.java` / `PhoneVerificationChallengeEntity.java` — queries, `@Version`, `renew`
- `audit/domain/AuditEntityType.java` + `db/migration/V14__auth_evidence_audit_type.sql` + `V15__phone_challenge_version.sql` — schema/enum parity
- `syncro/authz/policy/authz.rego` + `authz_test.rego` + `syncro/.env.example` — path sets, AUDITOR read rule, SUPER_ADMIN mutation rule, parity entries
- Tests: `AuthLoginAuditServiceTest`, `PhoneVerificationServiceTest`, `AuthAuditControllerTest`, `AuthAuditIntegrationTest` (INT-001..008), updated `AuthControllerTest`/`AuthLoginLockoutTest`/`V1BaseSchemaMigrationTest`/`PmAuthzEnforcementParityTest`

**Review findings breakdown:** 13 patches applied (3 high, 5 medium, 5 low); 5 deferred (traceId column, index-for-LIKE, openapi regen, opa-test CI wiring, XFF trusted-proxy); 5 rejected (dead-set naming drift, README count, seq-scan perf, disabled-reason granularity, Location-URI nit).

**Follow-up review recommended:** true — patched counts: high 3, medium 5, low 5; score 3×5+5=20 ≥ 5.

**Verification performed:** `mvn test -Dtest=*AuthLogin*,*PhoneVerification*,*AuthAudit*,AuthLoginLockoutTest,AuthControllerTest,AuthUserUpdateTest,PlantScopeControllerTest,V1BaseSchemaMigrationTest,PmAuthzEnforcementParityTest` → 116/116 green incl. Testcontainers; `opa test` via docker opa:1.19.1 → 474/474 PASS; convention test touching `@Version` entity → 5/5 standalone.

**Residual risks:** shared-fork Testcontainers port flake (pre-existing, documented since 21-1) can fail unrelated suites in combined runs; `AuditLogWiringIntegrationTest.plantMutationsAreAudited` remains a pre-existing baseline failure at 57632fd; OTP transport out of scope means issued challenges are unverifiable end-to-end until a delivery story lands (spec-mandated).
