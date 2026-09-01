---
title: 'Story 16-2: Data-Driven Roles'
type: 'feature'
created: '2026-09-01'
status: 'done'
baseline_revision: '57bc99d'
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

**Problem:** job_titles, system_roles, menu_features, domain_contexts, and role_permission_mappings tables exist in V1 and entities/repos were mapped in 15-2, but only JobTitle has a partial API (create + list only). There is no update/deactivate for JobTitle, no read-only endpoints for the other tables, and the JobTitle entity does not map `binding_scope`, `is_active`, or `default_system_role_id` from the schema.

**Approach:** Extend JobTitleEntity with the missing schema columns, add update/deactivate to JobTitleService/Controller, and add read-only list endpoints for SystemRole, MenuFeature, DomainContext, and RolePermissionMapping (by system_role_id) so the UI can render dropdowns and view mappings. Minimal — these are configuration tables, not high-churn CRUD.

## Boundaries & Constraints

**Always:**
- JobTitle update/deactivate follows the same SUPER_ADMIN/MANAGER_MAINTENANCE gate as JobTitle create.
- JobTitle deactivate is soft (is_active=false), no hard delete.
- Read-only endpoints for SystemRole, MenuFeature, DomainContext, RolePermissionMapping are accessible to any authenticated user (generic read_allowed in OPA).
- Seed the pilot seed with baseline system_roles, menu_features, domain_contexts, and role_permission_mappings rows.
- JobTitle mutations write audit via AuditLogWriter with `AuditEntityType.JOB_TITLE`.

**Block If:**
- Entity ↔ schema column mismatch that cannot be resolved by adding a field to the entity → HALT blocked.

**Never:**
- No frontend work in this story.
- No changes to V1 migration.
- No full CRUD for system_roles/menu_features/domain_contexts/role_permission_mappings — read-only only.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Update JobTitle | valid code/name/bindingScope/defaultSystemRoleId | 200 with updated view; audit UPDATE written | 400 VALIDATION_ERROR |
| Deactivate JobTitle | existing job title | is_active=false; audit DELETE written | 404 JOB_TITLE_NOT_FOUND |
| List system_roles | authenticated request | all active system roles returned | 401 AUTHENTICATION_REQUIRED |
| List role_permission_mappings by role | system_role_id param | current mappings for that role | 401 AUTHENTICATION_REQUIRED |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/JobTitleEntity.java` -- add bindingScope, active, defaultSystemRoleId fields + update()/deactivate()
- `syncro/apps/backend/src/main/java/com/syncro/org/application/JobTitleService.java` -- extend create with bindingScope/defaultSystemRoleId; add update + deactivate; inject AuditLogWriter
- `syncro/apps/backend/src/main/java/com/syncro/org/api/JobTitleController.java` -- add PUT /{id} + DELETE /{id}
- `syncro/apps/backend/src/main/java/com/syncro/org/api/JobTitleExceptionHandler.java` -- add not-found + duplicate-code handlers
- `syncro/apps/backend/src/main/java/com/syncro/org/domain/JobBindingScope.java` -- NEW enum for NONE/PLANT/AREA
- `syncro/org/application/SystemRoleService.java` -- NEW read-only list
- `syncro/org/application/MenuFeatureService.java` -- NEW read-only list
- `syncro/org/application/DomainContextService.java` -- NEW read-only list
- `syncro/org/application/RolePermissionMappingService.java` -- NEW read-only list-by-role
- `syncro/org/api/SystemRoleController.java` + Dtos -- NEW read-only list
- `syncro/org/api/MenuFeatureController.java` + Dtos -- NEW read-only list
- `syncro/org/api/DomainContextController.java` + Dtos -- NEW read-only list
- `syncro/org/api/RolePermissionMappingController.java` + Dtos -- NEW read-only list-by-role
- `syncro/authz/policy/authz.rego` -- add `/api/v1/job-titles` + `/api/v1/job-titles/*` to department_paths
- `syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql` -- seed system_roles, menu_features, domain_contexts, role_permission_mappings
- `syncro/apps/backend/src/test/java/com/syncro/db/PilotSeedTest.java` -- add seed counts

## Tasks & Acceptance

**Execution:**
1. JobBindingScope enum + JobTitleEntity extension + update/deactivate methods
2. JobTitleService: update + deactivate + audit + bindingScope/defaultSystemRoleId in create
3. JobTitleController: PUT /{id} + DELETE /{id}
4. SystemRole/MenuFeature/DomainContext/RolePermissionMapping read-only services + controllers
5. OPA rego: extend department_paths
6. Seed: baseline role data
7. Tests: JobTitleServiceTest, controller tests

**Acceptance Criteria:**
- Given a SUPER_ADMIN, when updating a JobTitle with binding_scope and default_system_role_id, then the row is persisted and audit-logged.
- Given a JobTitle with active=true, when deactivating it, then is_active=false.
- Given any authenticated user, when GET /api/v1/system-roles, then active roles are returned.
- Given the pilot seed, when applied twice, then seed rows are idempotent.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test-compile` -- expected: BUILD SUCCESS.
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=*JobTitle*,*SystemRole*,*MenuFeature*,*DomainContext*,*RolePermissionMapping*,PilotSeedTest"` -- expected: all green.