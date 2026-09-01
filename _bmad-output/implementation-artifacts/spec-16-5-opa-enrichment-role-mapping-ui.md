---
title: 'Story 16-5: OPA Enrichment & Role-Mapping UI'
type: 'feature'
created: '2026-09-01'
status: 'done'
baseline_revision: 'a4d8927'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-16-context.md'
  - '_bmad-output/planning-artifacts/sprint-change-proposal-2026-08-31.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** OPA input `subject.roles` carries only the coarse application role enum, so the data-driven role model (job titles, system roles, role-permission mappings from 16-2, bindings from 16-3) is not yet enforced. There is also no UI for SUPER_ADMIN to map job titles and system roles to users.

**Approach:** Enrich OPA `subject.roles` at input assembly so it carries application role + job-title default system role + per-user role bindings, sourced from a single reader in the org module. Add a Role Mapping tab to the Organization screen (non-native shadcn/Radix selects) that reads/writes `/api/v1/user-bindings/*` and `/api/v1/system-roles`, `/api/v1/job-titles` from 16-2/16-3 endpoints.

## Boundaries & Constraints

**Always:**
- OPA input assembly stays single-sourced in `PolicyDecisionPoint`; the enrichment reader is injected into it (same pattern as `OperationalScopeService`).
- Effective role set = application_role name + the user's job-title default system role code + each user_role_binding's system role code (epic context: "application role + job-title default system role + per-user role overrides").
- OPA remains the enforcement point; the frontend only renders (no permission decisions in UI).
- Role-mapping UI uses the existing shadcn/Radix `Select` (non-native), `Checkbox`, `Table`, `Card` components — no new dependencies, no native `<select>`.
- The UI writes through the 16-3 backend binding endpoints (no direct DB access, no new backend mutation endpoints unless strictly needed).
- TypeScript strict; follow existing organization feature conventions (types.ts + hooks + components).

**Block If:**
- The enrichment reader cannot be wired without breaking the single-sourced input assembly contract → HALT blocked.

**Never:**
- No bypassing OPA anywhere (frontend hide/disable is UX only).
- No changes to the authz.rego policy rules themselves unless a specific role code path is broken.
- No schema/migration changes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| User with no bindings | userId with no job/role bindings | roles = [applicationRole.name()] only | no error |
| User with job binding | binding → job_title with default_system_role | default system role code added to roles | no error |
| User with role bindings | 1+ user_role_bindings | each bound system role code added to roles | no error |
| SUPER_ADMIN | any user | enrichment skipped/empty (SUPER_ADMIN bypasses) | no error |
| Unknown job title | binding references missing job title | skipped silently (no NPE) | no error |

</intent-contract>

## Code Map

### Backend — OPA enrichment
- `syncro/apps/backend/src/main/java/com/syncro/org/application/EffectiveRoleReader.java` -- NEW: reads user_job_bindings + user_role_bindings, resolves job title default system role + bound system roles, returns role codes.
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/db/UserJobBindingRepository.java` -- exists (16-3): findByUserId.
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/db/UserRoleBindingRepository.java` -- exists (16-3): findByUserId.
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/JobTitleEntity.java` -- has getDefaultSystemRoleId.
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/db/SystemRoleEntity.java` -- has getCode.
- `syncro/apps/backend/src/main/java/com/syncro/authz/application/PolicyDecisionPoint.java` -- inject EffectiveRoleReader; buildInput builds roles = applicationRole + reader.read(userId).
- `syncro/apps/backend/src/test/java/com/syncro/authz/PolicyDecisionPointTest.java` -- update constructor; add enrichment assertion.
- `syncro/apps/backend/src/test/java/com/syncro/org/application/EffectiveRoleReaderTest.java` -- NEW unit test covering the I/O matrix.

### Frontend — Role-mapping UI
- `syncro/apps/web/src/features/organization/types.ts` -- add SystemRoleView, UserBindingsView, JobBindingView, RoleBindingView, SetJobRequest, AddRoleRequest types.
- `syncro/apps/web/src/features/organization/hooks/use-user-bindings.ts` -- NEW: useGetUserBindings, useSetUserJob, useAddUserRole, useRemoveUserRole, useListSystemRoles.
- `syncro/apps/web/src/features/organization/components/role-mapping.tsx` -- NEW: user table + select-user + job-title Select + system-role multi-select with override Checkbox + save/remove actions, loading/empty/error states.
- `syncro/apps/web/src/app/(main)/dashboard/master-data/organization/tabs-content.tsx` -- add "roles" tab.
- `syncro/apps/web/src/features/organization/components/role-mapping.test.tsx` -- NEW (mirror machine-group-management.test.tsx pattern).

## Tasks & Acceptance

**Execution:**
1. EffectiveRoleReader (org.application) with I/O matrix coverage.
2. PolicyDecisionPoint: inject reader, enrich roles; update PolicyDecisionPointTest.
3. Frontend types + hooks + role-mapping component + tab registration.
4. Frontend test for role-mapping component.

**Acceptance Criteria:**
- Given a user with a job binding whose job title has a default system role, when OPA input is built, then subject.roles contains the application role name plus the default system role code.
- Given a user with role bindings, when OPA input is built, then subject.roles contains each bound system role code.
- Given a user with no bindings, when OPA input is built, then subject.roles contains only the application role name.
- Given a SUPER_ADMIN opens the Role Mapping tab, when they select a user, then job-title and system-role controls render from `/api/v1/job-titles` and `/api/v1/system-roles`, and saving writes to `/api/v1/user-bindings/{userId}/job` and `/roles`.
- Given the mapping UI renders, then selects are non-native shadcn/Radix and the page shows loading/empty/error states.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=EffectiveRoleReaderTest,PolicyDecisionPointTest"` -- expected: all green.
- `npm --prefix syncro/apps/web run typecheck` (or the repo's frontend check script) -- expected: no errors.
- `npm --prefix syncro/apps/web test -- --run role-mapping` -- expected: green.
