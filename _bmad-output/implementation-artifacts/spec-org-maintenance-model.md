---
title: 'Organization Maintenance Model — Departments, User Master, Section Leaders'
type: 'feature'
created: '2026-08-27'
baseline_commit: 218b3a87e27518ed873a8ad54a9508cdeb3170ce
status: 'done'
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/ui-maintenance-rework-plan-2026-08-27.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The org model lacks a people-organization layer: users have no display name/NIK, there is no department concept, section leaders are only derived (not explicit), and technicians are not bound as subordinates — so the maintenance org cannot be managed the way operators think about it (sections/departments with a named leader and their technicians).

**Approach:** Add a `departments` layer (plant-scoped, name, SPV/MG leaders, member technicians) mirroring the reference `E:\01 DEV\SYNCRO` pattern; extend `auth_users` with `display_name`/`nik`/`phone_number`/`job_title_id`/`department_id`; add `job_titles` master; store an explicit `sections.leader_user_id` that auto-creates the LEADER machine responsibility on assign; expose user master + department CRUD + a hierarchy read. V58 migration.

## Boundaries & Constraints

**Always:**
- **V58** (additive, on V57):
  - `ALTER TABLE auth_users ADD COLUMN` — `display_name VARCHAR(200)`, `nik VARCHAR(50)`, `phone_number VARCHAR(32)`, `job_title_id UUID NULL REFERENCES job_titles(id) ON DELETE SET NULL`, `department_id UUID NULL REFERENCES departments(id) ON DELETE SET NULL`. Partial unique index `uq_auth_users_nik ON auth_users(nik) WHERE nik IS NOT NULL`; unique `uq_auth_users_phone ON auth_users(phone_number) WHERE phone_number IS NOT NULL`. Backfill `display_name` = `login_identifier` (split at `@`).
  - `departments` — `id UUID PK DEFAULT gen_random_uuid()`, `plant_id UUID NOT NULL REFERENCES plants(id)`, `name VARCHAR(255) NOT NULL`, `spv_id UUID NULL REFERENCES auth_users(id) ON DELETE SET NULL`, `mg_id UUID NULL REFERENCES auth_users(id) ON DELETE SET NULL`, `active BOOLEAN NOT NULL DEFAULT TRUE`, `created_at`, `updated_at`. Unique `uq_departments_plant_name (plant_id, name)`.
  - `department_members` — `id UUID PK DEFAULT gen_random_uuid()`, `department_id UUID NOT NULL REFERENCES departments(id) ON DELETE CASCADE`, `user_id UUID NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE`, `assigned_by UUID NOT NULL`, `assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. Unique `uq_department_members (department_id, user_id)`.
  - `job_titles` — `id UUID PK DEFAULT gen_random_uuid()`, `code VARCHAR(50) NOT NULL UNIQUE`, `name VARCHAR(200) NOT NULL`, `description TEXT`, `created_at`, `updated_at`.
  - `sections` — `ALTER TABLE ADD COLUMN leader_user_id UUID NULL REFERENCES auth_users(id) ON DELETE SET NULL`.
  - Audit: drop/re-add `ck_audit_log_entity_type` adding `'DEPARTMENT'`, `'DEPARTMENT_MEMBER'`, `'USER'` (preserve all existing types).
- **User master** (`GET/PUT /api/v1/auth/users`): extend `AuthUserView` to `(id, loginIdentifier, displayName, nik, phoneNumber, applicationRole, enabled, jobTitleId, departmentId)`. `PUT /api/v1/auth/users/{id}` accepts `{displayName, nik, phoneNumber, jobTitleId, departmentId}` (not role/email/password). Gates: SUPER_ADMIN or MANAGER_MAINTENANCE. NIK/phone unique (null-safe); empty string clears to null. Audit `USER` UPDATE.
- **Departments CRUD** (`/api/v1/departments`): POST create (`{plantId, name, spvId?, mgId?}`), GET list (`?plantId=&includeInactive=`), GET `/{id}`, PUT `/{id}` (`{name?, spvId?, mgId?, active?}`), DELETE `/{id}` (deactivate-style: reject if active members, else soft-inactive; no hard delete), PUT `/{id}/members` (`{userIds[]}` — full replace, mirrors reference `setDepartmentUsers`). Gates: SUPER_ADMIN/MANAGER_MAINTENANCE with plant access. SPV/MG must be active users with plant access (same plant). Audit `DEPARTMENT`/`DEPARTMENT_MEMBER`.
- **Section leader (D2a):** `PUT /api/v1/sections/{id}/leader` (`{userId}`) — sets `sections.leader_user_id` AND auto-creates/updates a `machine_responsibilities` row level LEADER for that user on every machine in the section's machine groups (the side-effect the user asked for: "otomatis nge link responsibility"). `DELETE .../leader` clears both. Audit `SECTION` UPDATE.
- **Hierarchy read:** `GET /api/v1/organization/hierarchy` — computes `Plant → Departments (with spv/mg names, member users) → Sections (with leader name, machine groups)` as a single nested view for the Organization Maintenance tab. Any authenticated leader reads; SUPER_ADMIN/MANAGER unrestricted.
- **Errors:** unknown plant/department/section/user → 404 (`PLANT_NOT_FOUND`, `DEPARTMENT_NOT_FOUND`, `SECTION_NOT_FOUND`, `USER_NOT_FOUND`); access → 403 `FORBIDDEN`; NIK/phone duplicate → 409 `DUPLICATE_IDENTIFIER`; SPV/MG not in plant → 400 `VALIDATION_ERROR`; deactivate dept with members → 409 `DEPARTMENT_HAS_MEMBERS`.
- **Rego:** `department_paths := {"/api/v1/departments", "/api/v1/departments/*", "/api/v1/departments/*/members"}` + `section_leader_paths := {"/api/v1/sections/*/leader"}` + `user_management_paths := {"/api/v1/auth/users/*"}` — mutation allow `{MANAGER_MAINTENANCE}` + SUPER_ADMIN (admin-only paths pattern). Reads generic. `.env.example` enforced-paths += these.

**Ask First:** none — backend contract settled (mirrors reference; D1a/D2a confirmed by user).

**Never:**
- Never merge `departments` with `sections` — department = people/org unit; section = machine-group container type (reference keeps them separate).
- Never implement the ABAC `OrgNode` recursive tree / `UserOrgAssignment` — out of scope (only Department + members + leaders).
- Never add a user-create/delete endpoint — users are bootstrapped (LocalAdminBootstrap); only update master fields here.
- Never auto-generate NIK — manual entry only.
- Never touch V47-V57 or add V59 — V58 is the only migration.
- Never make NIK/phone mandatory — nullable with partial unique.
- Never hard-delete a department — soft-inactive only.
- Never wire department leaders into WAHA/approval flows — that is Epic 14 (notifications).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| DEPT_CREATE_OK | MANAGER in plant, valid | 201 view; audit CREATE | — |
| DEPT_CREATE_DUP_NAME | same plant, same name | 409/400 VALIDATION_ERROR | — |
| DEPT_SPV_WRONG_PLANT | spvId user in other plant | 400 VALIDATION_ERROR fieldErrors.spvId | — |
| DEPT_SET_MEMBERS_OK | active dept, userIds[] | 200 replaced members; audit | — |
| DEPT_SET_MEMBERS_INACTIVE | dept active=false | 409 INVALID_STATE | — |
| DEPT_DEACTIVATE_WITH_MEMBERS | dept has members | 409 DEPARTMENT_HAS_MEMBERS | — |
| SECTION_LEADER_ASSIGN_OK | section + active user in plant | leader_user_id set; LEADER responsibility auto-created; audit | — |
| SECTION_LEADER_CLEAR | existing leader | leader_user_id null; LEADER responsibility removed | — |
| USER_UPDATE_NIK_OK | MANAGER, unique nik | view updated; audit USER UPDATE | — |
| USER_UPDATE_NIK_DUP | nik already used | 409 DUPLICATE_IDENTIFIER | — |
| USER_UPDATE_FORBIDDEN | AUDITOR | 403 FORBIDDEN | — |
| HIERARCHY_READ_OK | leader/manager | nested Plant→Dept→Sections view | — |

</frozen-after-approval>

## Code Map

**Migration:**
- `syncro/apps/backend/src/main/resources/db/migration/V58__org_maintenance_departments_users.sql` -- NEW -- auth_users cols, departments, department_members, job_titles, sections.leader_user_id, audit types.

**Domain:**
- `com/syncro/org/domain/Department.java` -- NEW -- record (id, plantId, name, spvId, mgId, active, ...).
- `com/syncro/org/domain/JobTitle.java` -- NEW -- record (id, code, name, description).
- `com/syncro/audit/domain/AuditEntityType.java` -- MODIFY -- + DEPARTMENT, DEPARTMENT_MEMBER, USER.

**Persistence (org):**
- `com/syncro/org/infrastructure/DepartmentEntity.java` + `DepartmentRepository.java` -- NEW.
- `com/syncro/org/infrastructure/DepartmentMemberEntity.java` + `DepartmentMemberRepository.java` -- NEW (findByDepartmentId, deleteByDepartmentId).
- `com/syncro/org/infrastructure/JobTitleEntity.java` + `JobTitleRepository.java` -- NEW.
- `com/syncro/org/infrastructure/SectionEntity.java` -- MODIFY -- + leaderUserId field/getter/setter.
- `com/syncro/org/infrastructure/SectionRepository.java` -- MODIFY -- findByIdForUpdate, findMachineGroupsBySectionId if needed.

**Auth/persistence:**
- `com/syncro/auth/infrastructure/AuthUserEntity.java` -- MODIFY -- + displayName, nik, phoneNumber, jobTitleId, departmentId + getters/setters.
- `com/syncro/auth/infrastructure/AuthUserRepository.java` -- MODIFY -- findByNikIgnoreCase, findByPhoneNumber.

**Application:**
- `com/syncro/auth/application/AuthService.java` + `AuthUserView` -- MODIFY -- listUsers includes new fields; new updateUser(AuthenticatedUser, UUID, UpdateUserCommand).
- `com/syncro/auth/api/AuthController.java` -- MODIFY -- + PUT /users/{id}.
- `com/syncro/org/application/DepartmentService.java` -- NEW -- CRUD + members replace + SPV/MG plant validation + audit.
- `com/syncro/org/application/JobTitleService.java` -- NEW -- list/create (minimal).
- `com/syncro/org/application/SectionService.java` -- MODIFY -- assignLeader/clearLeader (sets leader_user_id + auto machine_responsibility via `MachineResponsibilityRepository`).
- `com/syncro/org/application/OrganizationHierarchyService.java` -- NEW -- nested read Plant→Departments→Sections.
- `com/syncro/machine/infrastructure/MachineResponsibilityRepository.java` -- READ -- findMachineIdsByGroupIds / save for auto LEADER side-effect.

**API:**
- `com/syncro/org/api/DepartmentController.java` + `DepartmentDtos.java` + `DepartmentExceptionHandler.java` -- NEW.
- `com/syncro/org/api/OrganizationHierarchyController.java` + DTOs -- NEW.
- `com/syncro/org/api/SectionController.java` + `SectionDtos.java` -- MODIFY -- + leader endpoints.
- `com/syncro/org/api/SectionExceptionHandler.java` -- MODIFY -- + SECTION_NOT_FOUND handling if absent.

**Enforcement:**
- `syncro/authz/policy/authz.rego` + `authz_test.rego` -- MODIFY -- department/section-leader/user-management path sets + parity.
- `syncro/.env.example` -- MODIFY -- enforced-paths += new paths.

**Frontend:**
- `src/navigation/sidebar/sidebar-items.ts` -- MODIFY -- Master Data subItems: Departments + Users (new), keep Sections/Teams/Responsibility.
- `src/features/organization/types.ts` + `hooks/use-departments.ts` + `hooks/use-users.ts` -- NEW -- department/user contracts + hooks (syncroFetch).
- `src/features/organization/components/department-management.tsx` -- NEW -- departments CRUD + members dialog (mirror reference department-settings-page).
- `src/features/organization/components/user-management.tsx` -- NEW -- user master (displayName, NIK, phone, job title, department).
- `src/features/master-data/sections/section-management.tsx` -- MODIFY -- assign leader by NIK/name combobox.
- `src/features/master-data/responsibilities/responsibility-management.tsx` -- MODIFY -- machine/user labels via displayName/NIK.

**Tests:**
- `com/syncro/org/application/DepartmentServiceTest.java` -- NEW -- CRUD + members replace + SPV/MG plant + deactivate-with-members.
- `com/syncro/org/application/SectionLeaderServiceTest.java` -- NEW -- assign/clear leader auto-responsibility.
- `com/syncro/auth/application/AuthUserUpdateTest.java` -- NEW -- update displayName/nik, NIK dup.
- `com/syncro/db/OrgMaintenanceMigrationTest.java` -- NEW -- V58 tables/constraints/backfill (Testcontainers).

## Tasks & Acceptance

**Execution:**
- [x] `V58__org_maintenance_departments_users.sql` -- auth_users cols + departments + members + job_titles + sections leader + audit.
- [x] Domain records + `AuditEntityType` -- department/jobtitle/user types.
- [x] Entities + repositories (department, member, jobtitle; auth_user, section, responsibility).
- [x] `DepartmentService` -- CRUD + members replace + plant validation + audit.
- [x] `AuthService.updateUser` + `AuthController PUT /users/{id}` -- user master fields.
- [x] `SectionService.assignLeader/clearLeader` -- leader + auto-responsibility side-effect.
- [x] `OrganizationHierarchyService` + controller -- nested read.
- [x] Controllers + DTOs + exception handlers.
- [x] Rego + parity + `.env.example`.
- [x] Frontend: department + user management components + section leader picker + sidebar.
- [x] Tests (service + auth + migration).

**Acceptance Criteria:**
- Given a MANAGER_MAINTENANCE, when they create a department and assign SPV/MG leaders by user, then the department persists with leaders and the SPV/MG must be active users in the same plant. [dept master]
- Given an active department, when members are set, then the member set is replaced wholesale and each technician can belong to multiple departments. [members]
- Given a section, when a leader is assigned by NIK/name, then `leader_user_id` is stored AND a LEADER machine responsibility is auto-created for every machine in the section's groups; clearing removes both. [D2a]
- Given a user master update, when displayName/NIK/phone/jobTitle/department are set, then they persist with NIK/phone unique (null-safe); duplicates rejected. [user master]
- Given an organization hierarchy read, when a leader requests it, then Plant→Departments(with members/leaders)→Sections(with leader) is returned nested. [hierarchy]
- Given OPA enforcement, then department/user/section-leader mutations are admin-role default-deny with parity tests. [FR-160]

## Design Notes

- **Department ≠ section.** Department is the people unit (plant, name, SPV/MG, members) — reference keeps it fully separate from the machine-group container (section). Section retains its `code` enum + `name`; department is the new org-maintenance layer.
- **Members = full replace.** `PUT /departments/{id}/members` replaces the member set (mirrors reference `setDepartmentUsers`); uniqueness `(department_id, user_id)` allows multi-department membership.
- **Section leader side-effect.** Assigning a leader writes `sections.leader_user_id` AND inserts/updates `machine_responsibilities(level=LEADER)` for that user across the section's machines. This satisfies "link with NIK leader... otomatis nge link responsibility" — the derived reader (`MachineResponsibilitySectionLeaderAdapter`) then naturally includes them. Clearing leader deletes those responsibility rows.
- **NIK/phone are manual and unique-null-safe.** Partial unique indexes; empty string → null on update. Backfill display_name from login_identifier keeps existing users usable immediately.
- **Hierarchy is computed, not stored.** `OrganizationHierarchyService` reads plants→departments(+members)+sections(+leaders)+machine groups and returns one nested DTO — no new hierarchy table.

## Verification

**Commands:**
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=DepartmentServiceTest,SectionLeaderServiceTest,AuthUserUpdateTest,OrgMaintenanceMigrationTest"` -- expected BUILD SUCCESS.
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=Org*Test,Auth*Test,Section*Test"` -- expected no regressions.
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS incl. new parity.
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green.
- `cd syncro/apps/web && npx biome check src/features/organization src/features/master-data src/navigation` -- expected clean.

## Spec Change Log

<!-- Empty until review loop. -->
