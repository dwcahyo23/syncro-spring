---
title: 'Story 16-3: User Job & Role Bindings'
type: 'feature'
created: '2026-09-01'
status: 'done'
baseline_revision: '572a68d'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-16-context.md'
  - '_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** user_job_bindings and user_role_bindings tables + entities/repos exist from 15-2, but there is no API to manage them. SUPER_ADMIN cannot bind a user to a job title or add/remove role overrides, so the data-driven role model is unusable.

**Approach:** Add a UserBindingController with:
- `GET /api/v1/user-bindings/{userId}` — current job binding + role bindings
- `PUT /api/v1/user-bindings/{userId}/job` — set/clear the single job-title binding (body: { jobTitleId } or null to clear)
- `POST /api/v1/user-bindings/{userId}/roles` — add a role binding (body: { systemRoleId, isOverride })
- `DELETE /api/v1/user-bindings/{userId}/roles/{bindingId}` — remove a role binding

All mutations SUPER_ADMIN|MANAGER_MAINTENANCE, audit-logged, OPA-gated.

## Boundaries & Constraints

**Always:**
- One user = one job title (unique user_id on user_job_bindings); setting a new one replaces the old.
- Role bindings unique per (user_id, system_role_id); POST re-inserts/updates rather than duplicating.
- All mutations require SUPER_ADMIN|MANAGER_MAINTENANCE (mirror user_management_paths gate).
- Audit with AuditEntityType.USER_JOB_BINDING / USER_ROLE_BINDING.
- Read is any authenticated user.

**Block If:**
- Cannot resolve user or job title / system role → 404 style error.

**Never:**
- No frontend work (16-5 covers the role-mapping UI).
- No schema/migration changes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Set job binding | valid userId + jobTitleId | binding persisted (replacing any existing); audit written | 404 USER_NOT_FOUND / JOB_TITLE_NOT_FOUND |
| Clear job binding | PUT with jobTitleId=null | existing binding deleted | 204 |
| Add role binding | userId + systemRoleId + isOverride | persisted; existing (user,role) updated in place | 404 if user/role missing |
| Remove role binding | bindingId | deleted | 404 BINDING_NOT_FOUND |
| Non-manager mutate | TECHNICIAN calls PUT | 403 | forbidden |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/db/UserJobBindingEntity.java` -- exists (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/db/UserRoleBindingEntity.java` -- exists (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/db/UserJobBindingRepository.java` -- exists, bare; add findByUserId / deleteByUserId
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/db/UserRoleBindingRepository.java` -- exists, bare; add findByUserId / deleteByUserIdAndSystemRoleId
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserRepository.java` -- exists (findById)
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/JobTitleRepository.java` -- exists
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/db/SystemRoleRepository.java` -- exists
- `syncro/apps/backend/src/main/java/com/syncro/org/application/UserBindingService.java` -- NEW: setJob, clearJob, addRole, removeRole, getBindings; audit; mutation-role gate
- `syncro/apps/backend/src/main/java/com/syncro/org/api/UserBindingController.java` -- NEW REST surface
- `syncro/apps/backend/src/main/java/com/syncro/org/api/UserBindingExceptionHandler.java` -- NEW error mapping
- `syncro/authz/policy/authz.rego` -- add `/api/v1/user-bindings/**` to a manager-gated set (user_management_paths already exists for `/api/v1/auth/users/*`; reuse it by adding paths)
- `syncro/apps/backend/src/test/java/com/syncro/org/application/UserBindingServiceTest.java` -- NEW unit tests
- `syncro/apps/backend/src/test/java/com/syncro/org/api/UserBindingControllerTest.java` -- NEW MockMvc tests

## Tasks & Acceptance

**Execution:**
1. Repos: add findByUserId / deleteByUserId on UserJobBindingRepository; findByUserId / deleteByUserIdAndSystemRoleId on UserRoleBindingRepository
2. UserBindingService: getBindings, setJob (replace-on-write), clearJob, addRole (upsert by (user,role)), removeRole; requireMutationRole + audit
3. UserBindingController + Dtos + ExceptionHandler
4. rego: extend user_management_paths with `/api/v1/user-bindings`, `/api/v1/user-bindings/*`
5. Tests: UserBindingServiceTest (I/O matrix rows 1-5), UserBindingControllerTest (auth + forbidden + 404)

**Acceptance Criteria:**
- Given a SUPER_ADMIN, when PUT /api/v1/user-bindings/{userId}/job with jobTitleId, then one user_job_bindings row exists for the user (replacing any prior) and an audit row is written.
- Given a user with an existing binding, when PUT with jobTitleId=null, then the binding row is deleted.
- Given a SUPER_ADMIN, when POST /api/v1/user-bindings/{userId}/roles with systemRoleId+isOverride, then the role binding is persisted (upsert); a repeat POST updates is_override in place (no duplicate).
- Given a role binding id, when DELETE /api/v1/user-bindings/{userId}/roles/{bindingId}, then the row is deleted and audit written.
- Given a TECHNICIAN, when any of the above mutations is attempted, then 403 FORBIDDEN.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test-compile` -- expected: BUILD SUCCESS.
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=UserBindingServiceTest,UserBindingControllerTest"` -- expected: all green.
