# Epic 15 Context: ORM Foundation & Schema Reset

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

The syncro-spring ORM is redesigned from scratch, adopting the mature syncro (Node/Prisma) data model as the authoritative blueprint. A fresh Flyway V1.. migration set replaces the legacy V1..V68 accumulation and creates the base schema for every new module package (org, inventory, kpi, compliance, integration), while shared entity and repository conventions keep the redesigned ORM consistent and maintainable across all modules. Because the project is still in development — real data lives in a separate database and all current data is dummy seed — the schema can be reset and reseeded freely, with no backward-compatibility or real-data migration burden. This epic is the foundation for the entire ORM maturation redesign; all later maturation epics build on its schema and conventions.

## Stories

- Story 15.1: Fresh Flyway V1.. Migration Set & Schema Reset
- Story 15.2: Base Entity & Repository Conventions

## Requirements & Constraints

- Development-phase reset: all data is dummy seed; real data lives in another database. The development database is reset and reseeded; no environment holding real data may be reset.
- The Flyway history starts from a fresh V1__ migration (replacing the legacy set). After this baseline, migrations are additive only, and `ddl-auto=validate` binds entities to migrations exactly.
- Success: reset plus reseed completes without error and tests are green.
- Functional-requirement numbering for this epic is pending a requirements pass; the governing constraints for now are the schema/reset and mapping conventions below plus the performance/schema NFR (schema via Flyway with validate, not ddl-auto generation).

## Technical Decisions

- Module packages are scaffolded per the blueprint layout: `com.syncro.org` (Department, DepartmentUser, JobTitle, SystemRole, RolePermissionMapping, MenuFeature, DomainContext, UserJobBinding, UserRoleBinding, MachineArea, PlantWorkingCalendar), `com.syncro.inventory` (InventoryLocation, InventoryStockBalance, InventoryTransfer, InventoryReservation), `com.syncro.kpi` (KpiTarget, Kpi*Monthly, KpiAggregateRefreshLog), `com.syncro.compliance` (NonConformance, EightDReport, Calibration*, ECN, MachineSetupBaseline, LessonLearned, HistoricalMachineRecord), `com.syncro.integration` (WebhookConfig, WebhookDeliveryLog). Each module keeps the api/application/domain/infrastructure layering; entities live in `infrastructure.db`.
- Base schema naming: snake_case plural table names, snake_case columns, `{singular}_id` FK columns; constraints named `uq_<table>_<cols>` / `idx_<table>_<cols>` / `ck_*`.
- Entity mapping conventions (from the Prisma→JPA mapping): UUID primary keys except special entities (work_orders keeps its VARCHAR(50) PK per the dual-source ID rule); timestamps as `Instant` UTC in TIMESTAMPTZ columns; decimals as `BigDecimal`; enums as `@Enumerated(EnumType.STRING)` uppercase values backed by CHECK constraints; JSON columns via `@JdbcTypeCode(SqlTypes.JSON)`; `String[]` relations become JSONB or pivot tables, preferring pivot tables for relations.
- Cross-aggregate references (e.g. workorder → machine) use plain UUID columns, never eager relations. In-aggregate references may use `@ManyToOne(fetch = LAZY)`.
- Repositories: `JpaRepository` + `@Repository`, named queries following the existing convention.
- Resolved schema-level decisions the base schema must reflect: machine_groups retained as categories with machine_areas added as optional physical locations on machines; the existing sparepart taxonomy is kept (no new category table) with spareparts extended by BOM-master columns; inventory locations/stock balances replace the old sparepart_stock model; a general user_signatures/signature_uses model replaces workorder-only signatures; workorder `source` is only EXTERNAL or INTERNAL (dual-source semantics kept); workorder status is the 6-value lifecycle.
- OPA authorization and the existing auth model are retained unchanged by this epic; OPA role enrichment is later-phase work.

## Cross-Story Dependencies

- Story 15.2 depends on Story 15.1: the fresh schema must exist before entities/repositories are written against it.
- Epic 15 is the prerequisite for the ORM maturation epics that follow (org & identity, workorder execution redesign, PM execution, inventory, KPI, compliance, integration): their tables are scaffolded here and they must follow the entity/repository conventions set here.
- The sync module continues to work but its workorder writes must adapt to the new work_orders schema.
- Story 15.1 is gated on approval of the ORM maturation redesign (sprint change proposal).
