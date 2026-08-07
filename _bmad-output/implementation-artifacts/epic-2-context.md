# Epic 2 Context: Machine Master Data & Setup Foundation

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

SUPER_ADMIN and MANAGE users can configure the full machine foundation — plants, plant-scoped machine groups (process lines), machines with manual ACTIVE/INACTIVE status, sparepart taxonomy and spareparts, machine sparepart installations with lifetime baselines, and machine responsibility assignments — so Syncro knows what machines exist, what spareparts they carry, and who is responsible for response. This data is the prerequisite for telemetry acceptance (Epic 3), sparepart threshold alerting (Epic 4), and WAHA escalation routing (Epic 5).

## Stories

- Story 2.1: Manage Plants
- Story 2.2: Manage Plant-Scoped Machine Groups
- Story 2.3: Manage Machines with Manual Active State
- Story 2.4: Manage Sparepart Taxonomy
- Story 2.5: Manage Spareparts
- Story 2.6: Install Spareparts on Machines with Lifetime Baseline
- Story 2.7: Assign Machine Responsibility Levels
- Story 2.8: Show Setup Completeness
- Story 2.9: Implement Immutable Audit Log for Master Data

## Requirements & Constraints

- CRUD for plants, machine groups, machines, spareparts (via taxonomy), installations, and responsibility assignments; VIEWER is read-only everywhere.
- Same machine group name may exist in different plants; duplicates within the same plant are rejected.
- A machine requires `code`, `plantId`, `machineGroupId`, and manual `status` (`ACTIVE`/`INACTIVE` only). `brand`, `installedAt`, and `notes` are optional. Machine active state is manual master data, never inferred from MQTT or telemetry freshness.
- Sparepart taxonomy is normalized across category, brand, kind, and type; spareparts reference taxonomy dimensions; duplicate values within a dimension are rejected.
- An installation stores machine, sparepart, expected lifetime in production count, baseline counter value, and threshold percentage (default 90%, overridable per installation). Lifetime consumption is calculated from the current machine counter relative to the baseline counter, never from installation date.
- Machine responsibility is machine-specific, with levels `TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, `MANAGER`. Job scope is stored separately from application role so later ABAC can constrain MANAGE actions by job scope.
- Every master data mutation (plant, machine group, machine, sparepart, installation, responsibility, threshold) writes an immutable audit entry capturing actor, action, entity type/ID, previous value, new value, and timestamp; entries cannot be edited or deleted and are viewable via an audit log endpoint with filters (entity type, actor, plant, date range).
- All create/update request DTOs are validated with Jakarta Bean Validation (`@Valid`, explicit size/format/range constraints); duplicate conflicts, invalid data, and malformed JSON/type mismatch all return the standard safe error shape with field errors.
- Pilot baseline context: plant `GM1`, machine group `Forming`, machine `BF-08410` / `JBF19`, sparepart `Electric PLC Wecon LX5`, 90% threshold.

## Technical Decisions

- PostgreSQL (via Flyway migrations) is the system of record for all master data; migrations are created per story as needed.
- Backend is a modular monolith with domain modules (`masterdata`, `machine`, `sparepart`, `audit`, `common`); repositories are module-local, cross-module access goes through application services, controllers contain no business rules, and API DTOs never leak JPA entities.
- REST JSON API under `/api/v1` with plural resource paths (e.g., `/api/v1/plants`, `/api/v1/machines`); timestamps UTC ISO-8601; status/percent values numeric or uppercase enums; paginated list responses use `{ items, page, size, totalItems, totalPages }`; error responses use `{ code, message, fieldErrors, timestamp, traceId }`.
- Backend owns permissions, status transitions, enums, sparepart lifetime calculation, and audit evidence; the frontend renders backend decisions and must not reimplement domain rules.
- Springdoc OpenAPI must be in place before these data APIs; starting with this epic the frontend uses Orval-generated TypeScript clients and TanStack Query hooks from the OpenAPI contract. TanStack Query keys include active plant scope where data is plant-scoped; mutations invalidate affected keys explicitly. Zustand remains UI/session shell state only.
- Every request and worker execution carries a `traceId`; audit records capture actor, timestamp, source, action, target, result, traceId.
- Pilot seed/fixtures must include GM1 / Forming / BF-08410 / JBF19 / Electric PLC Wecon LX5.

## UX & Interaction Patterns

- Setup follows a guided dependency chain: Plant → Machine Group → Machine → Taxonomy → Sparepart → Installation (baseline + expected count + threshold) → Responsibility → setup complete. The machine is telemetry/alert-eligible only when the chain is complete.
- Dense desktop admin tables for all master data screens, stacked-card layouts on mobile/tablet.
- Shared domain components: `SetupCompletenessChecklist` (states: complete, incomplete, blocked, optional, loading, error; links to next step) and `MachineSummaryCard` (plant, group, code/name, status, freshness, risk, open alert count); `StatusBadge` uses non-color-only status communication.
- Operational components support loading, empty, error, stale, read-only, and forbidden states; empty states distinguish no-data-yet, no-plant-assignment, forbidden-by-role, load-failed, and prerequisite-missing, routing users to prerequisites rather than dead disabled forms.
- Forms mirror backend validation for guidance only (backend is authoritative): `fieldErrors` map to the exact field and focus the first invalid field; duplicate conflicts use specific copy (e.g., "Plant code already exists", "Machine group name already exists in this plant"); validation failure never clears entered values; no optimistic UI for these CRUD mutations (submit loading state then refetch/invalidation).
- Pickers for plant/machine/sparepart/user/taxonomy use Combobox/Radix patterns with keyboard navigation, clear, loading, empty, forbidden, and long-name truncation; labels show domain context (plant code + name, machine code + name).
- Destructive actions require confirmation; backend-blocked deletes show the exact blocking reason and next action.
- Every state-changing endpoint needs evidence for create/edit success, field errors, duplicate conflict, forbidden/read-only behavior, plant-scope restriction, missing-prerequisite, and dirty-form/plant-scope-change guard.

## Cross-Story Dependencies

- Story order follows entity dependencies: 2.1 → 2.2 → 2.3 → 2.4 → 2.5 → 2.6 → 2.7 → 2.8 → 2.9; a later story must not proceed on missing prerequisite entities.
- This epic requires Epic 1 (auth, roles, infra, OpenAPI baseline). Epic 3 telemetry acceptance validates machines against this epic's registered active machines; Epic 4 lifetime calculation uses installation baseline counter, expected count, and threshold; Epic 5 escalation routing resolves recipients from this epic's responsibility assignments.
- Audit log story (2.9) depends on the other master data mutations existing so it can record them; earlier stories should write audit events as they land.
- Setup completeness (2.8) must not infer machine active state from telemetry.
