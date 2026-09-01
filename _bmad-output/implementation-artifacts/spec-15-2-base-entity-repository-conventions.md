---
title: 'Story 15-2: Base Entity & Repository Conventions'
type: 'feature'
created: '2026-09-01'
status: 'done'
baseline_revision: 'b15b9753'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The V1 schema created every blueprint table, but only tables consumed by existing code have JPA entities/repositories. The new-module tables (org A leftovers, inventory transfers/reservations, kpi, compliance, integration) are unmapped, so later epics (16-x onward) would have to hand-write against raw SQL and would lack a shared convention reference.

**Approach:** Write JPA entities + JpaRepository interfaces for the remaining new tables, following the codebase's existing convention exactly (as anchored by MachineEntity/WorkOrderEntity and the freshly written InventoryStockBalanceEntity/SignatureUseEntity from 15-1). Keep `ddl-auto=validate` green, prove the mapping with repository integration tests against Testcontainers, and keep scope to persistence only (no API/application/services).

## Boundaries & Constraints

**Always:**
- Entities in `com.syncro.<module>.infrastructure.db`; repositories co-located; protected no-arg ctor + full ctor + getters; explicit `@Column(name="snake_case")`; `Instant` timestamps; `@Enumerated(EnumType.STRING)`; `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition="jsonb"` for JSONB; UUID PKs (except VARCHAR work_orders which already exists); cross-aggregate refs = plain UUID columns (AD-3/AD-4), in-aggregate refs may be `@ManyToOne(LAZY)`.
- Enum Java classes live in the module's `domain` package, uppercase values matching the V1 CHECK constraints exactly.
- Every entity must pass `ddl-auto=validate` against the V1 schema (boot a Spring context in tests — validation is the AC gate).
- Prove round-trip persistence: at least one Testcontainers-backed repository test per new module package that saves + reads back an entity with enum/JSONB/BigDecimal/Instant fields.

**Block If:**
- V1 schema and blueprint disagree on a column (blueprint correction required) → HALT blocked with the contradiction.
- A table proves semantically unmappable without inventing business rules → HALT blocked naming the table.

**Never:**
- No controllers, services, DTOs, use cases, audit hooks, or OPA wiring — persistence layer only.
- No changes to V1 migration, existing entities, or any seed file.
- No `@OneToMany` collections, no eager relations, no Lombok, no `Optional` in entity fields.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Validate against V1 | boot Spring context, ddl-auto=validate | SessionFactory builds with zero schema-validation errors | validation error fails test |
| Round-trip with enum+JSONB | save entity with @Enumerated + @JdbcTypeCode(JSON) field, flush, reload | all fields equal (enum value, JSON contents, timestamps) | mismatch fails test |
| Null optionality | save entity leaving nullable columns null; reload | nulls preserved, non-null columns reject insert | DataIntegrityViolation on required column |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/inventory/infrastructure/db/InventoryStockBalanceEntity.java` -- convention anchor freshly written in 15-1 (UUID PK + unique pair + @Version + domain class in `../domain`).
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/SignatureUseEntity.java` -- anchor for a many-column table with nullable refs.
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineEntity.java` -- anchor for @ManyToOne(LAZY) + enum + JSONB style.
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql` -- authoritative column list per table (sections 12–23 hold the new modules).
- `syncro/apps/backend/src/test/java/com/syncro/AbstractPostgresIntegrationTest.java` -- base class for repository integration tests.
- Tables still lacking entities (grouped by target package):
  - `com.syncro.org.infrastructure.db`: machine_areas, plant_working_calendars, plant_working_calendar_dates, system_roles, menu_features, domain_contexts, role_permission_mappings, user_job_bindings, user_role_bindings (departments/department_users/job_titles already mapped).
  - `com.syncro.maintenance.infrastructure.db`: work_assignments, work_logs, work_log_rating_criteria(+_categories), work_log_ratings, work_order_rating_criteria(+_categories), work_order_quality_ratings(+_technicians+_scores).
  - `com.syncro.inventory.infrastructure.db`: inventory_transfers, inventory_reservations (locations/balances exist).
  - `com.syncro.preventive`→keep; PM blueprint tables (pm_frequencies…pm_execution_items) → `com.syncro.maintenance.preventive.infrastructure.db` (module boundary per project-context: preventive is maintenance.preventive).
  - `com.syncro.kpi.infrastructure.db`: kpi_targets, kpi_monthly_breakdowns, kpi_mtbf_monthlies, kpi_mttr_monthlies, kpi_mar_monthlies, kpi_technician_monthlies, kpi_pm_completion_monthlies, kpi_aggregate_refresh_logs.
  - `com.syncro.compliance.infrastructure.db`: non_conformances, eight_d_reports, calibration_instruments, calibration_records, equipment_change_notices, machine_setup_baselines, lesson_learned, historical_machine_records.
  - `com.syncro.integration.infrastructure.db`: webhook_configs, webhook_delivery_logs.
- Also: `signature_uses` already mapped; `auth_login_audits`, `phone_verification_challenges`, `whatsapp_message_logs` → `com.syncro.auth.infrastructure` / `com.syncro.notification.infrastructure` respectively (module homes per blueprint package tree), `webhook` pair under integration.

## Tasks & Acceptance

**Execution:**
1. Create enums + entities + repositories for each table group above (one task per package; ~35 entities / ~35 repositories total).
2. `com.syncro.<module>.infrastructure.db` repositories extend JpaRepository with no custom queries unless needed for round-trip tests.
3. Write `EntityConventionIntegrationTest` per module package (5 classes) extending AbstractPostgresIntegrationTest: save→reload→assert round-trip incl. enum/JSONB/nullable fields (covers the I/O matrix).
4. Run validation boot via the same tests (context load = validate gate).

**Acceptance Criteria:**
- Given the V1 schema, when a Spring context with all new entities boots, then `ddl-auto=validate` passes with no missing/extra mapped column.
- Given a persisted entity with enum + JSONB + Instant + BigDecimal fields, when it is reloaded, then all fields round-trip equal (per-module test green).
- Given `git grep` on `@Table(name =` in main sources, then every V1 table has exactly one mapped entity (or is intentionally legacy like audit-only singletons — verified list).

## Spec Change Log

## Review Triage Log

## Design Notes

- Workorder FK columns are VARCHAR(50) (String in Java) — work_logs.work_order_id, work_order_quality_ratings.work_order_id etc. follow that, not UUID.
- pm_executions.finding_wo_id / pm_execution_items.blocking_wo_id also VARCHAR(50) → work_orders.
- JSONB fields typed as `Map<String,Object>` for warnings/parameters/tags/arrays; `List<String>` where the column is an array (tags).
- Keep entity class count exact-minimum: no wrapper domain classes beyond the enums needed by `@Enumerated`.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test-compile` -- expected: BUILD SUCCESS.
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=*EntityConventionIntegrationTest"` -- expected: all 5 module classes green (validates + round-trips).
- `git grep -L "org.springframework.data.jpa.repository.JpaRepository" -- syncro/apps/backend/src/main/java/com/syncro/*/infrastructure/db/*Repository.java` -- expected: empty.
- Table-coverage check: list `@Table(name = "...")` values vs V1 `CREATE TABLE` names -- expected: every V1 table mapped (documented exceptions: audit-trigger-only and flyway internal).
