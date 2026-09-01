# Epic 16 Context: Org & Identity Maturation

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Mature org structure and identity following the syncro Prisma blueprint's module A: departments with a department-user pivot, machine areas as optional physical locations on machines, a fully data-driven role model (job titles, system roles, role-permission mappings, menu features, domain contexts), per-user job/role bindings, an extended auth user with verification/lockout state, plant working calendars, and OPA input enrichment so authorization decisions are made against `application_role + job_title + system_roles` instead of a rigid role enum.

## Stories

- Story 16.1: Department & MachineArea
- Story 16.2: JobTitle, SystemRole & RolePermissionMapping
- Story 16.3: UserJobBinding & UserRoleBinding
- Story 16.4: AuthUser Extension & Plant Working Calendar
- Story 16.5: OPA Enrichment & SUPER_ADMIN Role-Mapping UI

## Requirements & Constraints

- All Epic 16 tables are created in the fresh schema redesign migration (DB reset + reseed), not additive patches. After this baseline, migrations are additive only and `ddl-auto=validate` must bind entity-to-migration exactly.
- Every department, machine-area, role-config, binding, and calendar mutation must be persisted, OPA-authorized (default-deny, enforcement stays server-side), and audit-logged with actor, traceId, and correlated OPA decision id.
- Permission decisions must be derived from role-permission mappings at decision time — no hardcoded role enum in policy or code. A user's effective role set = application role + job-title default system role + per-user role overrides.
- OPA remains the single enforcement point; no permission check may bypass it. The frontend never calls OPA directly — it renders from an allowed-actions endpoint; hiding UI is UX only.
- Operational scope (plants, machine groups, teams) continues to be derived from org data and passed into OPA as input; org changes take effect without policy redeploy.
- Errors return the standard machine-readable error shape (code, message, fieldErrors, timestamp, traceId); denials never leak internals.
- The coarse application role remains: SUPER_ADMIN stays (and bypasses all checks, Phase 1 pattern); MANAGE maps to MANAGER_MAINTENANCE; VIEWER maps to a read-only role.
- Auth lockout/verification fields on the user must actually be respected by the authentication flow; plant working calendars must drive shift-aware scheduling rather than being display-only data.

## Technical Decisions

- New entities live in the `org` module package (`com.syncro.org`): Department, DepartmentUser, JobTitle, SystemRole, MenuFeature, DomainContext, RolePermissionMapping, UserJobBinding, UserRoleBinding, MachineArea, PlantWorkingCalendar. The auth_users extension belongs to the auth context.
- JPA conventions: snake_case plural tables, UUID primary keys (documented exceptions only), `Instant` UTC timestamps in TIMESTAMPTZ, enums as `@Enumerated(EnumType.STRING)` uppercase values with CHECK constraints, JSONB via `@JdbcTypeCode(SqlTypes.JSON)`, pivot tables preferred over array columns, cross-aggregate references as plain UUID columns (no eager relations), named constraints `uq_<table>_<cols>` / `idx_<table>_<cols>`, `JpaRepository` + `@Repository` with named queries per existing convention.
- Key data model: `departments` unique on (plant_id, name) with optional spv/mg references and is_active; `department_users` pivot unique on (department_id, user_id) with assigned_by/assigned_at (adapts the existing department_members table); `machine_areas` added as physical location with nullable `machines.area_id`, while `machine_groups` is retained as the machine category (resolved decision point).
- Role taxonomy: `job_titles` (code unique, binding_scope NONE/PLANT/AREA, optional default system role); `system_roles` (code unique, integer level); `role_permission_mappings` unique on (system_role, menu_feature, domain_context) with is_granted; `menu_features` codes like `cmms:wo:read`; `domain_contexts` limited to maintenance/production/inventory.
- Bindings: `user_job_bindings` unique on (user_id) — exactly one job title per user; `user_role_bindings` unique on (user_id, system_role_id) with is_override.
- `auth_users` gains phone_verified_at, force_password_change, failed_login_attempts, locked_at, lock_reason, plus plant/department relations.
- `plant_working_calendars` unique on (plant_id, year) with workweek_mode FIVE_DAY/SIX_DAY; `plant_working_calendar_dates` unique on (calendar, date) with a reason per date.
- OPA input assembly stays in the single shared service; `subject.roles` is enriched from the bindings at request time.

## UX & Interaction Patterns

- SUPER_ADMIN gets a role-mapping screen to assign job titles and system roles per user; it must use non-native shadcn/Radix selects and pickers (no native dropdowns), write through the backend binding APIs, and reflect its own changes in subsequent authorization decisions.
- Role configuration UI is configuration-data editing (tables/forms over the taxonomy tables), consistent with the existing table-first master-data layout.

## Cross-Story Dependencies

- Stories 16.1–16.4 all land their tables in the same fresh migration set; the shared schema reset and reseed must come first.
- Story 16.2's tables (job_titles, system_roles, menu_features, domain_contexts, role_permission_mappings) are prerequisites for 16.3 (bindings reference job titles and system roles) and 16.5 (OPA enrichment and the mapping UI).
- Story 16.5 consumes 16.3's bindings: OPA input derives roles from them, and the UI writes through them.
- Auth hardening (16.4) is extended later by the integration epic's login-audit and phone-verification work; working calendars feed downstream scheduling (preventive/workorder execution epics).
- Epics building on org data (sections-derived scope, workorder assignment) assume this epic's identity model is in place — it is Phase 1 of the maturation phasing.
