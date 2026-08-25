package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * V45 contract (story 9-4 / AD-15): stored MANAGE/VIEWER values are migrated to
 * MANAGER_MAINTENANCE/AUDITOR and the widened {@code ck_auth_users_application_role}
 * CHECK accepts exactly the ten PRD taxonomy roles - legacy spellings are rejected.
 */
class RoleTaxonomyMigrationTest extends AbstractPostgresIntegrationTest {

  private static final String PILOT_SEED_PATH = "db/seed/pilot-seed.sql";
  private static final String V45_MIGRATION_PATH = "db/migration/V45__role_taxonomy_migration.sql";
  private static final List<String> TAXONOMY_ROLES = List.of(
      "SUPER_ADMIN",
      "MANAGER_MAINTENANCE",
      "MAINTENANCE_LEADER",
      "SECTION_LEADER",
      "STAFF_MAINTENANCE",
      "TECHNICIAN",
      "INVENTORY_MAINTENANCE",
      "STOREKEEPER",
      "PRODUCTION_LEADER",
      "AUDITOR");
  private static final List<String> PILOT_LOGINS = List.of(
      "technician.gm1@syncro.dev",
      "staff.gm1@syncro.dev",
      "leader.gm1@syncro.dev");

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("9.4-DB-001 P0 inserting legacy MANAGE/VIEWER violates ck_auth_users_application_role")
  void legacyRoleInsertViolatesCheck() {
    assertThat(legacyInsertError("MANAGE")).contains("ck_auth_users_application_role");
    assertThat(legacyInsertError("VIEWER")).contains("ck_auth_users_application_role");
  }

  @Test
  @DisplayName("9.4-DB-005 P0 V45 migration converts legacy rows when run on existing data")
  void legacyRowsAreConvertedByMigrationSql() {
    // Simulate a pre-V45 state: insert legacy rows (temporarily bypass the
    // current ten-role CHECK by dropping it first), then re-execute the actual
    // V45 migration file and verify the conversions.
    var legacyRows = List.of(
        Map.entry("legacy-manage@migration-test.dev", "MANAGE"),
        Map.entry("legacy-viewer@migration-test.dev", "VIEWER"));

    jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
      try (var st = con.createStatement()) {
        st.execute("ALTER TABLE auth_users DROP CONSTRAINT ck_auth_users_application_role");
        for (var row : legacyRows) {
          st.execute("INSERT INTO auth_users (id, login_identifier, password_hash, application_role) "
              + "VALUES ('" + UUID.randomUUID() + "', '" + row.getKey() + "', 'x', '" + row.getValue() + "')");
        }
        ScriptUtils.executeSqlScript(
            con, new EncodedResource(new ClassPathResource(V45_MIGRATION_PATH), StandardCharsets.UTF_8));
      } catch (java.sql.SQLException e) {
        throw new RuntimeException(e);
      }
      return null;
    });

    var rolesByLogin = jdbcTemplate.query(
        "SELECT login_identifier, application_role FROM auth_users WHERE login_identifier LIKE '%@migration-test.dev'",
        (rs, rowNum) -> Map.entry(rs.getString("login_identifier"), rs.getString("application_role")));
    assertThat(rolesByLogin)
        .containsExactlyInAnyOrder(
            Map.entry("legacy-manage@migration-test.dev", "MANAGER_MAINTENANCE"),
            Map.entry("legacy-viewer@migration-test.dev", "AUDITOR"));
  }

  @Test
  @DisplayName("9.4-DB-002 P0 each of the ten taxonomy roles is accepted")
  void everyTaxonomyRoleIsAccepted() {
    for (String role : TAXONOMY_ROLES) {
      var login = role.toLowerCase(java.util.Locale.ROOT) + "@taxonomy-it.dev";
      jdbcTemplate.update(
          "INSERT INTO auth_users (id, login_identifier, password_hash, application_role) "
              + "VALUES (?::uuid, ?, 'x', ?)",
          UUID.randomUUID().toString(), login, role);

      var persisted = jdbcTemplate.queryForObject(
          "SELECT application_role FROM auth_users WHERE login_identifier = ?", String.class, login);
      assertThat(persisted).as("role %s must persist", role).isEqualTo(role);
    }

    var count = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM auth_users WHERE login_identifier LIKE '%@taxonomy-it.dev'", Long.class);
    assertThat(count).isEqualTo(10L);
  }

  @Test
  @DisplayName("9.4-DB-003 P0 constraint keeps its name and accepts exactly the ten roles")
  void checkConstraintKeepsNameAndTenValueDefinition() {
    var rows = jdbcTemplate.queryForList(
        "SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc "
            + "JOIN information_schema.check_constraints cc ON cc.constraint_name = tc.constraint_name "
            + "WHERE tc.table_name = 'auth_users' AND tc.constraint_type = 'CHECK' "
            + "AND tc.constraint_name = 'ck_auth_users_application_role'");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("constraint_name")).isEqualTo("ck_auth_users_application_role");

    var clause = String.valueOf(rows.get(0).get("check_clause"));
    for (String role : TAXONOMY_ROLES) {
      assertThat(clause).as("CHECK clause must accept %s", role).contains("'" + role + "'");
    }
    // Quoted forms prove the legacy spellings are gone ('MANAGE' cannot be a
    // substring of 'MANAGER_MAINTENANCE' once quotes are required).
    assertThat(clause).doesNotContain("'MANAGE'").doesNotContain("'VIEWER'");
  }

  @Test
  @DisplayName("9.4-DB-004 P0 pilot seed users carry identity-matched taxonomy roles")
  void pilotSeedUsersCarryMappedRoles() {
    // Defensive: drop any committed pilot rows from earlier container runs so the
    // idempotent seed re-inserts them with the current role literals. RESTRICT FKs
    // (price entries, team members) are cleared first; the rest cascade. All of it
    // rolls back with the test transaction.
    jdbcTemplate.update("DELETE FROM sparepart_price_entries WHERE entered_by IN "
        + "(SELECT id FROM auth_users WHERE login_identifier IN ('" + String.join("','", PILOT_LOGINS) + "'))");
    jdbcTemplate.update("DELETE FROM team_members WHERE user_id IN "
        + "(SELECT id FROM auth_users WHERE login_identifier IN ('" + String.join("','", PILOT_LOGINS) + "'))");
    jdbcTemplate.update("DELETE FROM auth_users WHERE login_identifier IN ('" + String.join("','", PILOT_LOGINS) + "')");

    applyPilotSeedOnTransactionConnection();

    var rolesByLogin = jdbcTemplate.query(
        "SELECT login_identifier, application_role FROM auth_users WHERE login_identifier IN ('"
            + String.join("','", PILOT_LOGINS) + "')",
        (rs, rowNum) -> Map.entry(rs.getString("login_identifier"), rs.getString("application_role")));
    assertThat(rolesByLogin)
        .containsExactlyInAnyOrder(
            Map.entry("technician.gm1@syncro.dev", "TECHNICIAN"),
            Map.entry("staff.gm1@syncro.dev", "STAFF_MAINTENANCE"),
            Map.entry("leader.gm1@syncro.dev", "SECTION_LEADER"));
  }

  /**
   * Runs the failing INSERT inside a SAVEPOINT so the check violation does not abort
   * the surrounding test transaction; returns the server error message.
   */
  private String legacyInsertError(String legacyRole) {
    final String[] serverMessage = {null};
    jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
      try (var st = con.createStatement()) {
        st.execute("SAVEPOINT sp_legacy_role_insert");
      }
      try (var ps = con.prepareStatement(
          "INSERT INTO auth_users (id, login_identifier, password_hash, application_role) "
              + "VALUES (?::uuid, ?, 'x', ?)")) {
        ps.setString(1, UUID.randomUUID().toString());
        ps.setString(2, "legacy-" + legacyRole.toLowerCase(java.util.Locale.ROOT) + "@taxonomy-it.dev");
        ps.setString(3, legacyRole);
        try {
          ps.executeUpdate();
        } catch (java.sql.SQLException se) {
          serverMessage[0] = se.getMessage();
        } finally {
          try (var st = con.createStatement()) {
            st.execute("ROLLBACK TO SAVEPOINT sp_legacy_role_insert");
          }
        }
      }
      return null;
    });
    return serverMessage[0];
  }

  /** Applies the canonical pilot seed on the transaction-bound connection (rolled back with the test). */
  private void applyPilotSeedOnTransactionConnection() {
    jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
      ScriptUtils.executeSqlScript(
          con, new EncodedResource(new ClassPathResource(PILOT_SEED_PATH), StandardCharsets.UTF_8));
      return null;
    });
  }
}
