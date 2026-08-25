# Epic 9 Context: Org Structure & OPA Authorization Foundation

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Establish the authorization backbone for all Phase 2 maintenance features. Users (SUPER_ADMIN / MANAGER_MAINTENANCE) can model the org structure — sections per plant, machine-group-to-section assignment, and expiry-dated cross-plant teams — with operational scope derived from existing machine responsibilities. Every authorization decision is evaluated by an OPA sidecar (default-deny), enforced server-side, correlated into the audit trail via decision_id, and surfaced to the frontend only as allowed-actions for rendering. Later epics (workorders, preventive, sparepart, sync) build on this spine rather than inventing their own permission logic.

## Stories

- Story 9.1: Sections Foundation
- Story 9.2: Cross-Plant Teams
- Story 9.3: OPA Infrastructure
- Story 9.4: Role Taxonomy Migration
- Story 9.5: OPA Enforcement on Maintenance Endpoints

## Requirements & Constraints

- Sections are `MACHINERY | UTILITY | WORKSHOP` per plant; a machine group belongs to exactly one section (reassign rejected) and deactivating a section with active groups is rejected with a machine-readable code. Section/team mutations are audit-logged with actor and traceId.
- Section leadership is **derived**: a user leads a machine group iff they hold `machine_responsibilities.level = LEADER` or above on it. No separate section-leader role assignment exists. Demotion/removal immediately removes scope server-side.
- Scope dimensions are exactly `{plantIds, machineGroupIds, activeTeamIds}` — a section is a container, never a scoping dimension. A section leader sees only their own machine group(s); sibling-group data within the same section is excluded (permission-denied for out-of-scope resource access).
- Cross-plant teams are expiry-dated. Active team memberships merge into `machineGroupIds` for SQL filtering; expired memberships are excluded from OPA input so access is revoked without any policy redeploy.
- OPA runs as a sidecar HTTP service (docker-compose stable name `opa`), not embedded. Every authorization-sensitive request is evaluated by OPA (default-deny) before business logic runs; a failing/sidecar-down call denies except a configurable degraded-mode allowlist for health/read endpoints. Each decision's `decision_id` is stored beside the app audit record (AUDITOR can trace policy revision + decision_id).
- The backend exposes an allowed-actions endpoint for the frontend, which never calls OPA directly; hiding is UX only — enforcement stays server-side.
- Policy is code: Rego in-repo, `opa test` in CI, versioned bundles, status + decision-log APIs wired. Decision logs mask sensitive input (no WAHA secrets, no full phone numbers) with configurable retention (default 30 days).
- The Phase 1 application-role model is extended by an **additive** V41+ migration (no data dropped): `MANAGE` → `MANAGER_MAINTENANCE`, `VIEWER` → read-only role, existing `SUPER_ADMIN` rows stay valid. This is not a global promotion — `MANAGER_MAINTENANCE` still requires plant assignments, and no user is silently granted global scope. `subject.roles` = the extended application role plus derived scope; `SUPER_ADMIN` bypasses all checks; `PRODUCTION_LEADER` scope comes from plant/line assignments.
- OPA subject input is minimal and self-contained: roles + derived scope only — org data always lives in PostgreSQL and is passed as input, never copied into the OPA data store.
- Standard project constraints apply unchanged: `/api/v1`, stable error shape with a permission-denied code, UTC timestamps, uppercase enums, immutable audit, `ddl-auto=validate` + additive Flyway migrations (V41+), typed `*Properties` config, no hardcoded URLs/credentials.

## Technical Decisions

- New bounded contexts `com.syncro.org` (sections, machine-group→section, cross-plant teams, derived scope) and `com.syncro.authz` (PolicyDecisionPoint, allowed-actions endpoint, OPA client), keeping the modular-monolith layered shape (`api`/`application`/`domain`/`infrastructure`). Cross-module access only via application services / public contracts — never another module's repository.
- **Scope derivation is single-sourced in the `org` module** and consumed by the `maintenance` query layer — one service produces the identical scope set used for both OPA input and SQL row filtering; never recomputed elsewhere.
- **OPA input assembly is single-sourced in `authz.PolicyDecisionPoint`** (RestClient + Resilience4j timeout/retry/circuit-breaker — the established WAHA-client pattern), called via `POST /v1/data/syncro/authz/...`. The interceptor, application services, and the allowed-actions endpoint all use this one service, and the input schema is authoritative: `subject` (userId, roles, plantIds, sectionIds, machineGroupIds, active teamIds, production-line scope) + `resource` (type, id, plantId, sectionId, machineId, attributes) + `action` + `context`.
- DB conventions for new tables: snake_case plural tables, FKs `<singular>_id`, `uq_<table>_<cols>` / `idx_<table>_<cols>`, UUID PKs, `version` optimistic lock, TIMESTAMPTZ defaults `NOW()`, enums as uppercase strings.

## Cross-Story Dependencies

- 9.1 (sections + derived scope) is the foundation for 9.2 (team machine IDs merge into the same `machineGroupIds`) and 9.5 (row-level scoping applies the identical derived scope set).
- 9.3 (OPA sidecar + PolicyDecisionPoint + allowed-actions) must land before 9.4 (roles feed `subject.roles`) and 9.5 (OPA enforcement) can be meaningfully evaluated.
- 9.4's role migration must precede downstream Epics 10–14, which assume the new role names; Phase 1 in-service `ApplicationRole` switches are migrated to new names when touched. Epic 9 is the prerequisite for all later Phase 2 epics, and depends on Phase 1 assets (`machine_responsibilities`, `auth_user_plant_assignments`, `auth_users.application_role`, audit infrastructure, Resilience4j).
