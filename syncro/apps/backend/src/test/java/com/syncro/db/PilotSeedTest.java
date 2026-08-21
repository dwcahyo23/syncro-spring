package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies the canonical pilot seed (db/seed/pilot-seed.sql) against a real
 * PostgreSQL container: full Flyway migrate, then the seed applied like the
 * documented psql flow would. Plain JDBC, no Spring context - mirrors the
 * DbIndexHygiene Testcontainers pattern and avoids the documented
 * context-loading Testcontainers stalls.
 */
@Testcontainers
class PilotSeedTest {

  private static final String PILOT_SEED_PATH = "db/seed/pilot-seed.sql";
  private static final String PILOT_PASSWORD = "syncro-pilot-dev";
  private static final String PILOT_SPAREPART_CODE = "BF-08410GM1ELEPLCWEC000";

  private static final List<String> PILOT_LOGINS = List.of(
      "technician.gm1@syncro.dev",
      "staff.gm1@syncro.dev",
      "leader.gm1@syncro.dev");

  @Container
  @SuppressWarnings("rawtypes")
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static JdbcTemplate jdbc;

  /** Table counts right after migrations, before the seed - the delta baseline. */
  static Map<String, Long> postMigrationCounts;

  @BeforeAll
  static void migrateThenApplySeedOnce() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();
    jdbc = new JdbcTemplate(new DriverManagerDataSource(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    postMigrationCounts = snapshotCounts();
    applySeed();
  }

  @Test
  @DisplayName("[7.1] Migrations then seed produce the canonical pilot rows")
  void migrationsThenSeedProduceCanonicalRows() {
    Map<String, Object> plant = jdbc.queryForMap(
        "SELECT code, name FROM plants WHERE code = 'GM1'");
    assertThat(plant.get("code")).isEqualTo("GM1");
    assertThat(plant.get("name")).isEqualTo("Plant GM1");

    Map<String, Object> group = jdbc.queryForMap(
        "SELECT g.name FROM machine_groups g JOIN plants p ON p.id = g.plant_id "
            + "WHERE p.code = 'GM1' AND g.name = 'Forming'");
    assertThat(group.get("name")).isEqualTo("Forming");

    Map<String, Object> machine = jdbc.queryForMap(
        "SELECT m.code, m.name, m.status, m.brand, g.name AS group_name "
            + "FROM machines m "
            + "JOIN plants p ON p.id = m.plant_id "
            + "JOIN machine_groups g ON g.id = m.machine_group_id "
            + "WHERE m.code = 'BF-08410' AND p.code = 'GM1'");
    assertThat(machine.get("name")).isEqualTo("JBF19");
    assertThat(machine.get("status")).isEqualTo("ACTIVE");
    assertThat(machine.get("brand")).isEqualTo("Juki");
    assertThat(machine.get("group_name")).isEqualTo("Forming");

    // The seed reuses the V13/V15 ELECTRIC category row instead of duplicating it
    Long electricCategories = jdbc.queryForObject(
        "SELECT count(*) FROM sparepart_taxonomy WHERE dimension = 'CATEGORY' AND name = 'Electric'",
        Long.class);
    assertThat(electricCategories).isEqualTo(1L);

    // WECON/PLC/LX5 exist and are all linked to the single Electric category
    List<Map<String, Object>> taxonomy = jdbc.queryForList(
        "SELECT t.dimension, t.code, t.name, c.name AS category_name "
            + "FROM sparepart_taxonomy t "
            + "JOIN sparepart_taxonomy c ON c.id = t.category_id "
            + "WHERE (t.dimension, t.code) IN (('BRAND','WECON'), ('KIND','PLC'), ('TYPE','LX5')) "
            + "ORDER BY t.dimension");
    assertThat(taxonomy).extracting(row -> row.get("dimension"))
        .containsExactly("BRAND", "KIND", "TYPE");
    assertThat(taxonomy).extracting(row -> row.get("code"))
        .containsExactly("WECON", "PLC", "LX5");
    assertThat(taxonomy).extracting(row -> row.get("name"))
        .containsExactly("Wecon", "PLC", "LX5");
    assertThat(taxonomy).extracting(row -> row.get("category_name"))
        .containsOnly("Electric");

    // Sparepart carries the backend-exact generated code and label
    Map<String, Object> sparepart = jdbc.queryForMap(
        "SELECT s.code, s.name, m.code AS machine_code, "
            + "cat.code AS category, br.code AS brand, k.code AS kind, t.code AS type "
            + "FROM spareparts s "
            + "JOIN machines m ON m.id = s.machine_id "
            + "JOIN sparepart_taxonomy cat ON cat.id = s.category_id "
            + "JOIN sparepart_taxonomy br ON br.id = s.brand_id "
            + "JOIN sparepart_taxonomy k ON k.id = s.kind_id "
            + "JOIN sparepart_taxonomy t ON t.id = s.type_id "
            + "WHERE s.code = '" + PILOT_SPAREPART_CODE + "'");
    assertThat(sparepart.get("name")).isEqualTo("Electric · PLC · Wecon · LX5");
    assertThat(sparepart.get("machine_code")).isEqualTo("BF-08410");
    assertThat(sparepart.get("category")).isEqualTo("ELECTRIC");
    assertThat(sparepart.get("brand")).isEqualTo("WECON");
    assertThat(sparepart.get("kind")).isEqualTo("PLC");
    assertThat(sparepart.get("type")).isEqualTo("LX5");

    // Installation pins the pilot threshold math
    Map<String, Object> installation = jdbc.queryForMap(
        "SELECT i.expected_production_count, i.baseline_counter, i.threshold_percentage, i.function_name "
            + "FROM machine_sparepart_installations i "
            + "JOIN machines m ON m.id = i.machine_id "
            + "JOIN spareparts s ON s.id = i.sparepart_id "
            + "WHERE m.code = 'BF-08410' AND s.code = '" + PILOT_SPAREPART_CODE + "'");
    assertThat(installation.get("expected_production_count")).isEqualTo(1000L);
    assertThat(installation.get("baseline_counter")).isEqualTo(0L);
    assertThat(installation.get("threshold_percentage")).isEqualTo(90);
    assertThat(installation.get("function_name")).isEqualTo("Primary");

    // Three enabled VIEWER recipients with non-blank WhatsApp placeholders
    List<Map<String, Object>> users = jdbc.queryForList(
        "SELECT login_identifier, application_role, enabled, whatsapp_number FROM auth_users "
            + "WHERE login_identifier IN ('" + String.join("','", PILOT_LOGINS) + "') "
            + "ORDER BY login_identifier");
    assertThat(users).hasSize(3);
    assertThat(users).allSatisfy(user -> {
      assertThat(user.get("application_role")).isEqualTo("VIEWER");
      assertThat(user.get("enabled")).isEqualTo(true);
      assertThat(user.get("whatsapp_number")).asString().isNotBlank();
    });
    assertThat(users).extracting(user -> user.get("whatsapp_number"))
        .containsExactlyInAnyOrder("6281234567801", "6281234567802", "6281234567803");

    // Each recipient has a GM1 plant assignment
    Long assignments = jdbc.queryForObject(
        "SELECT count(*) FROM auth_user_plant_assignments a "
            + "JOIN plants p ON p.id = a.plant_id "
            + "WHERE p.code = 'GM1' "
            + "AND a.auth_user_id IN (SELECT id FROM auth_users WHERE login_identifier IN ('"
            + String.join("','", PILOT_LOGINS) + "'))",
        Long.class);
    assertThat(assignments).isEqualTo(3L);

    // Exactly one responsibility per level, three distinct users
    List<Map<String, Object>> responsibilities = jdbc.queryForList(
        "SELECT r.level, u.login_identifier FROM machine_responsibilities r "
            + "JOIN machines m ON m.id = r.machine_id "
            + "JOIN auth_users u ON u.id = r.user_id "
            + "WHERE m.code = 'BF-08410' "
            + "ORDER BY r.level");
    assertThat(responsibilities).extracting(row -> row.get("level"))
        .containsExactly("LEADER", "STAFF", "TECHNICIAN");
    assertThat(responsibilities).extracting(row -> row.get("login_identifier"))
        .containsExactlyInAnyOrder(
            "leader.gm1@syncro.dev", "staff.gm1@syncro.dev", "technician.gm1@syncro.dev");
  }

  @Test
  @DisplayName("[7.1] Seed creates zero rows in runtime-owned tables and keeps the V22 template")
  void seedCreatesNoRuntimeOwnedRows() {
    for (String table : List.of(
        "machine_counter_states",
        "sparepart_alerts",
        "notification_jobs",
        "notification_attempts",
        "telemetry_quarantine",
        "audit_log")) {
      assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class))
          .as("table %s must stay empty after the pilot seed", table)
          .isZero();
    }

    assertThat(jdbc.queryForObject("SELECT count(*) FROM waha_templates", Long.class))
        .isEqualTo(1L);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM waha_templates WHERE template_key = 'alert_notification'",
        Long.class))
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("[7.1] Seed is idempotent - a second apply changes no row counts")
  void seedIsIdempotent() throws Exception {
    Map<String, Long> before = snapshotCounts();

    applySeed();

    Map<String, Long> after = snapshotCounts();
    assertThat(after).as("counts must be identical after the second seed apply").isEqualTo(before);
    // Deltas against the post-migration baseline keep this test decoupled from
    // migration content (a future migration seeding more rows must not fail here).
    Map<String, Long> expected = new LinkedHashMap<>(postMigrationCounts);
    expected.merge("plants", 1L, Long::sum);
    expected.merge("machine_groups", 1L, Long::sum);
    expected.merge("machines", 1L, Long::sum);
    expected.merge("sparepart_taxonomy", 3L, Long::sum);
    expected.merge("spareparts", 1L, Long::sum);
    expected.merge("machine_sparepart_installations", 1L, Long::sum);
    expected.merge("auth_users", 3L, Long::sum);
    expected.merge("auth_user_plant_assignments", 3L, Long::sum);
    expected.merge("machine_responsibilities", 3L, Long::sum);
    assertThat(after).as("seeded counts must equal the post-migration baseline + seed deltas")
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("[7.1] Pre-existing case-variant rows are adopted, not orphaned; "
      + "pre-existing logins and conflicting responsibility levels are handled per contract")
  void seedResolvesPreExistingRowsWithoutPartialApplication() throws Exception {
    // Scenario: a machine stored with case-variant code, a pilot login that
    // pre-exists with a different UUID, and a pre-existing responsibility with
    // a different level for the same (machine, user).
    jdbc.update("DELETE FROM machine_responsibilities");
    jdbc.update("DELETE FROM machine_sparepart_installations");
    jdbc.update("DELETE FROM spareparts");
    jdbc.update("DELETE FROM machines");
    jdbc.update("DELETE FROM auth_user_plant_assignments a USING auth_users u "
        + "WHERE a.auth_user_id = u.id AND u.login_identifier = 'staff.gm1@syncro.dev'");
    jdbc.update("DELETE FROM auth_users WHERE login_identifier = 'staff.gm1@syncro.dev'");

    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, brand, "
        + "installed_at, notes, optional_telemetry_fields, created_at, updated_at) "
        + "SELECT '11111111-1111-4111-8111-111111111111'::uuid, p.id, g.id, 'Bf-08410', 'JBF19', "
        + "'ACTIVE', 'Juki', DATE '2026-05-27', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
        + "FROM plants p JOIN machine_groups g ON g.plant_id = p.id "
        + "WHERE p.code = 'GM1' AND lower(g.name) = 'forming'");

    // Pre-existing staff user with a different UUID (and no WhatsApp - adopted as-is)
    jdbc.update("INSERT INTO auth_users (id, login_identifier, password_hash, application_role, "
        + "enabled, whatsapp_number, created_at, updated_at) "
        + "VALUES ('22222222-2222-4222-8222-222222222222'::uuid, 'staff.gm1@syncro.dev', "
        + "'x', 'VIEWER', TRUE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

    // Pre-existing SPV responsibility for (machine, staff) - blocks the STAFF row
    jdbc.update("INSERT INTO machine_responsibilities (id, machine_id, user_id, level, "
        + "created_at, updated_at) "
        + "SELECT '33333333-3333-4333-8333-333333333333'::uuid, m.id, u.id, 'SPV', "
        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
        + "FROM machines m, auth_users u "
        + "WHERE lower(m.code) = 'bf-08410' AND u.login_identifier = 'staff.gm1@syncro.dev'");

    applySeed();

    // The case-variant machine was ADOPTED: no second machine, dependents attached to it
    assertThat(jdbc.queryForObject("SELECT count(*) FROM machines", Long.class)).isEqualTo(1L);
    String adoptedMachineId = jdbc.queryForObject(
        "SELECT id FROM machines WHERE lower(code) = 'bf-08410'", String.class);
    assertThat(adoptedMachineId).isEqualTo("11111111-1111-4111-8111-111111111111");
    String sparepartMachineId = jdbc.queryForObject(
        "SELECT machine_id FROM spareparts WHERE code = '" + PILOT_SPAREPART_CODE + "'", String.class);
    assertThat(sparepartMachineId).isEqualTo(adoptedMachineId);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM machine_sparepart_installations",
        Long.class)).isEqualTo(1L);

    // Assignments resolved to the PRE-EXISTING user id (not the seed's fixed UUID)
    String staffAssignmentUserId = jdbc.queryForObject(
        "SELECT a.auth_user_id FROM auth_user_plant_assignments a "
            + "JOIN auth_users u ON u.id = a.auth_user_id "
            + "WHERE u.login_identifier = 'staff.gm1@syncro.dev'", String.class);
    assertThat(staffAssignmentUserId).isEqualTo("22222222-2222-4222-8222-222222222222");

    // The SPV row is kept (not overwritten); TECHNICIAN/LEADER still attach
    List<String> levels = jdbc.queryForList(
        "SELECT r.level FROM machine_responsibilities r "
            + "JOIN machines m ON m.id = r.machine_id "
            + "WHERE lower(m.code) = 'bf-08410' ORDER BY r.level", String.class);
    assertThat(levels).containsExactly("LEADER", "SPV", "TECHNICIAN");

    // Self-heal: tear the scenario down and re-seed so later/earlier test
    // methods always observe the canonical state, regardless of run order.
    jdbc.update("DELETE FROM machine_responsibilities");
    jdbc.update("DELETE FROM machine_sparepart_installations");
    jdbc.update("DELETE FROM spareparts");
    jdbc.update("DELETE FROM machines");
    jdbc.update("DELETE FROM auth_user_plant_assignments a USING auth_users u "
        + "WHERE a.auth_user_id = u.id AND u.login_identifier = 'staff.gm1@syncro.dev'");
    jdbc.update("DELETE FROM auth_users WHERE login_identifier = 'staff.gm1@syncro.dev'");
    applySeed();
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM machines WHERE code = 'BF-08410'", Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("[7.1] Embedded bcrypt hash verifies against the documented pilot password")
  void embeddedHashMatchesDocumentedPassword() {
    List<String> hashes = jdbc.queryForList(
        "SELECT password_hash FROM auth_users WHERE login_identifier IN ('"
            + String.join("','", PILOT_LOGINS) + "')",
        String.class);
    assertThat(hashes).hasSize(3);
    var encoder = new BCryptPasswordEncoder();
    assertThat(hashes)
        .allSatisfy(hash -> assertThat(encoder.matches(PILOT_PASSWORD, hash))
            .as("seeded password hash must match the documented pilot password").isTrue());
  }

  private static void applySeed() throws SQLException {
    ClassPathResource seedResource = new ClassPathResource(PILOT_SEED_PATH);
    assertThat(seedResource.exists())
        .as("pilot seed must be on the test classpath at %s", PILOT_SEED_PATH).isTrue();
    try (Connection connection = DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      ScriptUtils.executeSqlScript(connection, new EncodedResource(seedResource, StandardCharsets.UTF_8));
    }
  }

  private static Map<String, Long> snapshotCounts() {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String table : List.of(
        "plants",
        "machine_groups",
        "machines",
        "sparepart_taxonomy",
        "spareparts",
        "machine_sparepart_installations",
        "auth_users",
        "auth_user_plant_assignments",
        "machine_responsibilities",
        "waha_templates",
        "audit_log")) {
      counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
    }
    return counts;
  }
}
