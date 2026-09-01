package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 15-1: single consolidated-migration evidence. Boots a fresh postgres:17
 * container, applies the ONE V1 migration, and asserts blueprint-critical schema
 * facts: exactly one applied migration, adapted legacy tables (department_users,
 * inventory_stock_balances, work_orders 6-value status/source, machine_areas on
 * machines, user_signatures/signature_uses), every blueprint Modul A-I table
 * exists, uq_/idx_/ck_ naming spot-checks, and the btree_gist extension for the
 * repair_sessions EXCLUDE constraint. Plain JDBC, no Spring context (PilotSeedTest
 * pattern).
 */
@Testcontainers
class V1BaseSchemaMigrationTest {

  @Container
  @SuppressWarnings("rawtypes")
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static JdbcTemplate jdbc;

  @BeforeAll
  static void migrateFreshDatabase() {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();
    jdbc = new JdbcTemplate(new DriverManagerDataSource(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
  }

  // -------------------------------------------------------------------------
  // AC: exactly one migration applied
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("15.1-DB-001 P0 flyway_schema_history has V1 baseline + V2/V3/V4/V5/V6 additive migrations")
  void migrationsApplied() {
    var rows = jdbc.queryForList(
        "SELECT version, script, success FROM flyway_schema_history ORDER BY installed_rank");
    assertThat(rows).hasSize(6);
    assertThat(rows.get(0).get("version")).isEqualTo("1");
    assertThat(rows.get(0).get("script")).isEqualTo("V1__orm_foundation_schema.sql");
    assertThat(rows.get(0).get("success")).isEqualTo(true);
    assertThat(rows.get(1).get("version")).isEqualTo("2");
    assertThat(rows.get(1).get("script")).isEqualTo("V2__auth_user_hardening.sql");
    assertThat(rows.get(1).get("success")).isEqualTo(true);
    assertThat(rows.get(2).get("version")).isEqualTo("3");
    assertThat(rows.get(2).get("script")).isEqualTo("V3__work_assignment_audit_type.sql");
    assertThat(rows.get(2).get("success")).isEqualTo(true);
    assertThat(rows.get(3).get("version")).isEqualTo("4");
    assertThat(rows.get(3).get("script")).isEqualTo("V4__work_log_audit_type.sql");
    assertThat(rows.get(3).get("success")).isEqualTo(true);
    assertThat(rows.get(4).get("version")).isEqualTo("5");
    assertThat(rows.get(4).get("script")).isEqualTo("V5__work_log_rating_audit_type.sql");
    assertThat(rows.get(4).get("success")).isEqualTo(true);
    assertThat(rows.get(5).get("version")).isEqualTo("6");
    assertThat(rows.get(5).get("script")).isEqualTo("V6__work_order_quality_rating_audit_type.sql");
    assertThat(rows.get(5).get("success")).isEqualTo(true);
  }

  // -------------------------------------------------------------------------
  // AC: adapted legacy tables
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("16.4-DB-001 P0 V2 adds auth hardening columns to auth_users")
  void authUserHardeningColumns() {
    var columns = columnNames("auth_users");
    assertThat(columns).contains("phone_verified_at", "force_password_change", "failed_login_attempts",
        "locked_at", "lock_reason");
  }

  @Test
  @DisplayName("15.1-DB-002 P0 work_orders status CHECK carries exactly the 6 blueprint values")
  void workOrderStatusCheck() {
    var check = jdbc.queryForMap(
        "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint "
            + "WHERE conname = 'ck_work_orders_status'");
    assertThat((String) check.get("def")).contains(
        "OPEN", "IN_PROGRESS", "PENDING_SPAREPART", "PENDING_REVIEW", "CLOSED", "CANCELLED");
    // removed legacy values must be gone
    assertThat((String) check.get("def"))
        .doesNotContain("DRAFT", "ASSIGNED", "ON_PROCUREMENT", "'DONE'");
  }

  @Test
  @DisplayName("15.1-DB-003 P0 work_orders source CHECK is EXTERNAL/INTERNAL only")
  void workOrderSourceCheck() {
    var check = jdbc.queryForMap(
        "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint "
            + "WHERE conname = 'ck_work_orders_source'");
    assertThat((String) check.get("def")).contains("EXTERNAL", "INTERNAL").doesNotContain("SYNCED");
  }

  @Test
  @DisplayName("15.1-DB-004 P0 department_users replaces department_members with (department_id, user_id) uniqueness")
  void departmentUsersRenamed() {
    assertThat(tableExists("department_users")).isTrue();
    assertThat(tableExists("department_members")).isFalse();
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_department_users_department_user'",
        Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("15.1-DB-005 P0 inventory_stock_balances: UUID PK, unique (sparepart_id, location_id), new stock columns")
  void inventoryStockBalances() {
    assertThat(tableExists("sparepart_stock")).isFalse();
    var columns = columnNames("inventory_stock_balances");
    assertThat(columns).contains("id", "sparepart_id", "location_id",
        "available", "reserved", "consumed", "minimum_stock", "version");
    var pk = jdbc.queryForMap(
        "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint "
            + "WHERE conrelid = 'inventory_stock_balances'::regclass AND contype = 'p'");
    assertThat((String) pk.get("def")).isEqualTo("PRIMARY KEY (id)");
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_inventory_stock_balances_sparepart_location'",
        Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("15.1-DB-006 P0 machines.area_id FK to machine_areas; machine_groups kept alongside")
  void machineAreasWired() {
    assertThat(tableExists("machine_areas")).isTrue();
    assertThat(tableExists("machine_groups")).isTrue();
    var columns = columnNames("machines");
    assertThat(columns).contains("area_id");
    var fk = jdbc.queryForList(
        "SELECT (SELECT relname FROM pg_class c WHERE c.oid = confrelid) AS ref FROM pg_constraint "
            + "WHERE conrelid = 'machines'::regclass AND contype = 'f' AND conname = 'fk_machines_area'");
    assertThat(fk).hasSize(1);
    assertThat(((String) fk.getFirst().get("ref"))).contains("machine_areas");
  }

  @Test
  @DisplayName("15.1-DB-007 P0 user_signatures/signature_uses replace workorder_signatures; WO subject unique")
  void signatureTables() {
    assertThat(tableExists("user_signatures")).isTrue();
    assertThat(tableExists("signature_uses")).isTrue();
    assertThat(tableExists("workorder_signatures")).isFalse();
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_signature_uses_work_order'",
        Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("15.1-DB-008 P0 workorder_todos uses work_order_id (naming convention), no workorder_id column")
  void workorderTodosColumnRenamed() {
    var columns = columnNames("workorder_todos");
    assertThat(columns).contains("work_order_id").doesNotContain("workorder_id");
  }

  @Test
  @DisplayName("15.1-DB-009 P0 spareparts carries BOM master columns with blueprint D3 uniqueness")
  void sparepartBomColumns() {
    var columns = columnNames("spareparts");
    assertThat(columns).contains("hierarchy_identity_key", "bom_serial", "bom_code",
        "bom_code_version", "review_status", "rejection_reason");
    for (String index : List.of("uq_spareparts_hierarchy_identity_key", "uq_spareparts_bom_code",
        "uq_spareparts_machine_cat_kind_serial")) {
      assertThat(jdbc.queryForObject(
          "SELECT count(*) FROM pg_indexes WHERE indexname = ?", Long.class, index))
          .as("index %s must exist", index).isEqualTo(1L);
    }
  }

  @Test
  @DisplayName("15.1-DB-010 P0 legacy preventive_programs/schedules/checklist tables are kept")
  void legacyPreventiveTablesKept() {
    for (String table : List.of("preventive_programs", "preventive_schedules",
        "preventive_checklist_results", "preventive_checklist_items",
        "preventive_schedule_attachments")) {
      assertThat(tableExists(table)).as("table %s kept", table).isTrue();
    }
  }

  // -------------------------------------------------------------------------
  // AC: every blueprint Modul A-I table exists
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("15.1-DB-011 P0 every blueprint Modul A-I table exists")
  void allBlueprintTablesExist() {
    List<String> tables = List.of(
        // Modul A
        "system_roles", "role_permission_mappings", "menu_features", "domain_contexts",
        "user_job_bindings", "user_role_bindings", "machine_areas",
        "plant_working_calendars", "plant_working_calendar_dates",
        // Legacy support tables kept by the consolidated V1
        "authz_decisions", "teams", "team_members", "team_machines",
        // Modul B
        "work_assignments", "work_logs",
        // Modul C
        "work_log_rating_criteria", "work_log_rating_criterion_categories", "work_log_ratings",
        "work_order_rating_criteria", "work_order_rating_criterion_categories",
        "work_order_quality_ratings", "work_order_quality_rating_technicians",
        "work_order_quality_rating_scores",
        // Modul E (D rides on spareparts)
        "inventory_locations", "inventory_transfers", "inventory_reservations",
        // Modul F
        "pm_frequencies", "pm_checksheets", "active_checksheets", "pm_checklist_categories",
        "pm_checklist_items", "pm_schedules", "pm_schedule_dates", "pm_work_orders",
        "pm_executions", "pm_execution_items",
        // Modul G
        "kpi_targets", "kpi_monthly_breakdowns", "kpi_mtbf_monthlies", "kpi_mttr_monthlies",
        "kpi_mar_monthlies", "kpi_technician_monthlies", "kpi_pm_completion_monthlies",
        "kpi_aggregate_refresh_logs",
        // Modul H
        "non_conformances", "eight_d_reports", "calibration_instruments", "calibration_records",
        "equipment_change_notices", "machine_setup_baselines", "lesson_learned",
        "historical_machine_records",
        // Modul I
        "user_signatures", "signature_uses", "auth_login_audits",
        "phone_verification_challenges", "webhook_configs", "webhook_delivery_logs",
        "whatsapp_message_logs");
    for (String table : tables) {
      assertThat(tableExists(table)).as("blueprint table %s must exist", table).isTrue();
    }
  }

  // -------------------------------------------------------------------------
  // AC: naming spot-checks + extension
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("15.1-DB-012 P0 uq_/idx_/ck_ naming spot-checks hold across new tables")
  void constraintNamingSpotChecks() {
    var uniqueConstraints = jdbc.queryForList(
        "SELECT conname FROM pg_constraint WHERE contype = 'u' AND connamespace = 'public'::regnamespace");
    var checkConstraints = jdbc.queryForList(
        "SELECT conname FROM pg_constraint WHERE contype = 'c' AND connamespace = 'public'::regnamespace");
    var indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'");
    var uniqueNames = uniqueConstraints.stream().map(m -> (String) m.get("conname")).toList();
    var checkNames = checkConstraints.stream().map(m -> (String) m.get("conname")).toList();
    var indexNames = indexes.stream().map(m -> (String) m.get("indexname")).toList();

    // Every explicitly-named unique constraint uses uq_ (legacy-named exceptions
    // (uk_machine_user_responsibility, uq_sections_plant_code etc.) are renamed/kept per
    // final state; spot-check the known new ones).
    assertThat(uniqueNames).contains(
        "uq_department_users_department_user",
        "uq_inventory_stock_balances_sparepart_location",
        "uq_user_job_bindings_user",
        "uq_user_role_bindings_user_role",
        "uq_work_assignments_wo_tech_at",
        "uq_system_roles_code",
        "uq_machine_areas_plant_name",
        "uq_inventory_locations_plant_code",
        "uq_pm_frequencies_code",
        "uq_kpi_targets_plant_month",
        "uq_non_conformances_nc_number");

    // Named CHECK spot-checks on new blueprint tables.
    assertThat(checkNames).contains(
        "ck_work_order_status_history_actor_type",
        "ck_spareparts_review_status",
        "ck_inventory_stock_balances_non_negative",
        "ck_machine_areas_name_not_blank",
        "ck_pm_work_orders_status",
        "ck_work_logs_stopped_reason",
        "ck_eight_d_reports_status",
        "ck_webhook_delivery_logs_status",
        "ck_plant_working_calendars_workweek_mode");

    // Named FK/index spot-checks.
    assertThat(indexNames).contains(
        "uq_spareparts_hierarchy_identity_key",
        "idx_machine_areas_plant_id",
        "idx_inventory_stock_balances_location_id",
        "idx_signature_uses_subject",
        "idx_kpi_mtbf_monthlies_plant_month");
  }

  @Test
  @DisplayName("15.1-DB-013 P0 btree_gist extension present and repair_sessions EXCLUDE enforced")
  void btreeGistAndExclude() {
    var extension = jdbc.queryForMap(
        "SELECT extname FROM pg_extension WHERE extname = 'btree_gist'");
    assertThat(extension.get("extname")).isEqualTo("btree_gist");
    var exclude = jdbc.queryForMap(
        "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint "
            + "WHERE conname = 'excl_repair_sessions_no_overlap'");
    assertThat((String) exclude.get("def")).contains("EXCLUDE USING gist", "&&");
  }

  @Test
  @DisplayName("15.1-DB-014 P0 migration-owned seed rows exist (taxonomy, templates, dimensions, category 02, configs, mappings, settings)")
  void migrationSeededRows() {
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM waha_templates", Long.class)).isEqualTo(3L);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM sparepart_taxonomy WHERE dimension = 'CATEGORY'", Long.class))
        .as("the five V13 category rows are part of the consolidated seed")
        .isEqualTo(5L);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM rating_dimensions", Long.class)).isEqualTo(3L);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM work_order_categories WHERE code = '02'", Long.class)).isEqualTo(1L);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM escalation_configs WHERE scope = 'SPAREPART_REQUEST'", Long.class))
        .isEqualTo(6L);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM escalation_configs WHERE scope = 'WORKORDER'", Long.class))
        .isEqualTo(1L);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM sync_field_mappings", Long.class)).isEqualTo(20L);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM settings", Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("15.1-DB-015 P0 audit_log entity_type CHECK accepts renamed + new module values")
  void auditEntityTypeExtended() {
    var check = jdbc.queryForMap(
        "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint "
            + "WHERE conname = 'ck_audit_log_entity_type'");
    var def = (String) check.get("def");
    assertThat(def).contains("DEPARTMENT_USER", "INVENTORY_STOCK_BALANCE", "MACHINE_AREA",
        "SYSTEM_ROLE", "SIGNATURE_USE");
    assertThat(def).doesNotContain("DEPARTMENT_MEMBER");
  }

  /**
   * Story 17-1: V3 extends the CHECK additively with WORK_ASSIGNMENT so
   * {@code AuditEntityType.WORK_ASSIGNMENT} can be persisted. The value appears in
   * the constraint definition after the V1/V2 set (V3 reapplies the full list).
   */
  @Test
  @DisplayName("17.1-DB-001 P0 audit_log entity_type CHECK accepts WORK_ASSIGNMENT after V3")
  void auditEntityTypeAcceptsWorkAssignment() {
    var check = jdbc.queryForMap(
        "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint "
            + "WHERE conname = 'ck_audit_log_entity_type'");
    var def = (String) check.get("def");
    assertThat(def).contains("WORK_ASSIGNMENT");
    // every V1/V2 value survives the additive extension — a regression that drops
    // prior values must fail.
    assertThat(def).contains("WORK_ORDER", "REPAIR_SESSION", "WORKORDER_SIGNATURE",
        "SPAREPART_STOCK", "SYNC_RUN", "SYNC_QUARANTINE",
        "MACHINE_AREA", "USER_ROLE_BINDING", "PLANT_WORKING_CALENDAR",
        "PLANT", "MACHINE_GROUP", "MACHINE", "SPAREPART_TAXONOMY",
        "SPAREPART", "INSTALLATION", "RESPONSIBILITY", "ALERT",
        "SPAREPART_PRICE_ENTRY", "SECTION", "TEAM", "WORK_ORDER_CATEGORY",
        "WORKORDER_ATTACHMENT", "WORK_ORDER_TODO", "WORKORDER_RATING",
        "RATING_DIMENSION", "PREVENTIVE_PROGRAM", "PREVENTIVE_SCHEDULE",
        "PREVENTIVE_CHECKLIST", "PREVENTIVE_ATTACHMENT",
        "SPAREPART_REQUEST", "DEPARTMENT", "DEPARTMENT_USER", "USER",
        "INVENTORY_LOCATION", "INVENTORY_STOCK_BALANCE",
        "INVENTORY_TRANSFER", "INVENTORY_RESERVATION",
        "JOB_TITLE", "SYSTEM_ROLE", "ROLE_PERMISSION_MAPPING",
        "MENU_FEATURE", "DOMAIN_CONTEXT", "USER_JOB_BINDING",
        "SIGNATURE_USE", "WORKORDER_SIGNATURE");
  }

  /**
   * Story 17-2: V4 extends the CHECK additively with WORK_LOG so
   * {@code AuditEntityType.WORK_LOG} can be persisted. Every prior value —
   * including the V3 WORK_ASSIGNMENT — survives the extension.
   */
  @Test
  @DisplayName("17.2-DB-001 P0 audit_log entity_type CHECK accepts WORK_LOG after V4")
  void auditEntityTypeAcceptsWorkLog() {
    var check = jdbc.queryForMap(
        "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint "
            + "WHERE conname = 'ck_audit_log_entity_type'");
    var def = (String) check.get("def");
    assertThat(def).contains("WORK_LOG");
    // every prior value (V1 + V2 + V3) survives the additive V4 extension.
    assertThat(def).contains("WORK_ASSIGNMENT", "WORK_ORDER", "REPAIR_SESSION",
        "WORKORDER_SIGNATURE", "SPAREPART_STOCK", "SYNC_RUN", "SYNC_QUARANTINE",
        "MACHINE_AREA", "USER_ROLE_BINDING", "PLANT_WORKING_CALENDAR",
        "PLANT", "MACHINE_GROUP", "MACHINE", "SPAREPART_TAXONOMY",
        "SPAREPART", "INSTALLATION", "RESPONSIBILITY", "ALERT",
        "SPAREPART_PRICE_ENTRY", "SECTION", "TEAM", "WORK_ORDER_CATEGORY",
        "WORKORDER_ATTACHMENT", "WORK_ORDER_TODO", "WORKORDER_RATING",
        "RATING_DIMENSION", "PREVENTIVE_PROGRAM", "PREVENTIVE_SCHEDULE",
        "PREVENTIVE_CHECKLIST", "PREVENTIVE_ATTACHMENT",
        "SPAREPART_REQUEST", "DEPARTMENT", "DEPARTMENT_USER", "USER",
        "INVENTORY_LOCATION", "INVENTORY_STOCK_BALANCE",
        "INVENTORY_TRANSFER", "INVENTORY_RESERVATION",
        "JOB_TITLE", "SYSTEM_ROLE", "ROLE_PERMISSION_MAPPING",
        "MENU_FEATURE", "DOMAIN_CONTEXT", "USER_JOB_BINDING",
        "SIGNATURE_USE");
  }

  /**
   * Story 17-4: V5 extends the CHECK additively with WORK_LOG_RATING so
   * {@code AuditEntityType.WORK_LOG_RATING} can be persisted. Every prior value —
   * including the V3 WORK_ASSIGNMENT and V4 WORK_LOG — survives the extension.
   */
  @Test
  @DisplayName("17.4-DB-001 P0 audit_log entity_type CHECK accepts WORK_LOG_RATING after V5")
  void auditEntityTypeAcceptsWorkLogRating() {
    var check = jdbc.queryForMap(
        "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint "
            + "WHERE conname = 'ck_audit_log_entity_type'");
    var def = (String) check.get("def");
    assertThat(def).contains("WORK_LOG_RATING");
    // every prior value (V1 + V2 + V3 + V4) survives the additive V5 extension.
    assertThat(def).contains("WORK_LOG", "WORK_ASSIGNMENT", "WORK_ORDER", "REPAIR_SESSION",
        "WORKORDER_SIGNATURE", "SPAREPART_STOCK", "SYNC_RUN", "SYNC_QUARANTINE",
        "MACHINE_AREA", "USER_ROLE_BINDING", "PLANT_WORKING_CALENDAR",
        "PLANT", "MACHINE_GROUP", "MACHINE", "SPAREPART_TAXONOMY",
        "SPAREPART", "INSTALLATION", "RESPONSIBILITY", "ALERT",
        "SPAREPART_PRICE_ENTRY", "SECTION", "TEAM", "WORK_ORDER_CATEGORY",
        "WORKORDER_ATTACHMENT", "WORK_ORDER_TODO", "WORKORDER_RATING",
        "RATING_DIMENSION", "PREVENTIVE_PROGRAM", "PREVENTIVE_SCHEDULE",
        "PREVENTIVE_CHECKLIST", "PREVENTIVE_ATTACHMENT",
        "SPAREPART_REQUEST", "DEPARTMENT", "DEPARTMENT_USER", "USER",
        "INVENTORY_LOCATION", "INVENTORY_STOCK_BALANCE",
        "INVENTORY_TRANSFER", "INVENTORY_RESERVATION",
        "JOB_TITLE", "SYSTEM_ROLE", "ROLE_PERMISSION_MAPPING",
        "MENU_FEATURE", "DOMAIN_CONTEXT", "USER_JOB_BINDING",
        "SIGNATURE_USE");
  }

  /**
   * Story 17-5: V6 extends the CHECK additively with WORK_ORDER_QUALITY_RATING so
   * {@code AuditEntityType.WORK_ORDER_QUALITY_RATING} can be persisted. Every prior
   * value — including the V3 WORK_ASSIGNMENT, V4 WORK_LOG and V5 WORK_LOG_RATING —
   * survives the extension.
   */
  @Test
  @DisplayName("17.5-DB-001 P0 audit_log entity_type CHECK accepts WORK_ORDER_QUALITY_RATING after V6")
  void auditEntityTypeAcceptsWorkOrderQualityRating() {
    var check = jdbc.queryForMap(
        "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint "
            + "WHERE conname = 'ck_audit_log_entity_type'");
    var def = (String) check.get("def");
    assertThat(def).contains("WORK_ORDER_QUALITY_RATING");
    // every prior value (V1 + V2 + V3 + V4 + V5) survives the additive V6 extension.
    assertThat(def).contains("WORK_LOG_RATING", "WORK_LOG", "WORK_ASSIGNMENT", "WORK_ORDER",
        "REPAIR_SESSION", "WORKORDER_SIGNATURE", "SPAREPART_STOCK", "SYNC_RUN",
        "SYNC_QUARANTINE", "MACHINE_AREA", "USER_ROLE_BINDING", "PLANT_WORKING_CALENDAR",
        "PLANT", "MACHINE_GROUP", "MACHINE", "SPAREPART_TAXONOMY",
        "SPAREPART", "INSTALLATION", "RESPONSIBILITY", "ALERT",
        "SPAREPART_PRICE_ENTRY", "SECTION", "TEAM", "WORK_ORDER_CATEGORY",
        "WORKORDER_ATTACHMENT", "WORK_ORDER_TODO", "WORKORDER_RATING",
        "RATING_DIMENSION", "PREVENTIVE_PROGRAM", "PREVENTIVE_SCHEDULE",
        "PREVENTIVE_CHECKLIST", "PREVENTIVE_ATTACHMENT",
        "SPAREPART_REQUEST", "DEPARTMENT", "DEPARTMENT_USER", "USER",
        "INVENTORY_LOCATION", "INVENTORY_STOCK_BALANCE",
        "INVENTORY_TRANSFER", "INVENTORY_RESERVATION",
        "JOB_TITLE", "SYSTEM_ROLE", "ROLE_PERMISSION_MAPPING",
        "MENU_FEATURE", "DOMAIN_CONTEXT", "USER_JOB_BINDING",
        "SIGNATURE_USE");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private boolean tableExists(String table) {
    Long count = jdbc.queryForObject(
        "SELECT count(*) FROM information_schema.tables "
            + "WHERE table_schema = 'public' AND table_name = ?",
        Long.class, table);
    return count != null && count > 0;
  }

  private List<String> columnNames(String table) {
    return jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? ORDER BY column_name",
            String.class, table);
  }
}
