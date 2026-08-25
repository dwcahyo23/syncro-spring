package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration evidence for V43 (story 9-2): teams table with lower(name) uniqueness,
 * the name-not-blank CHECK, team_members/team_machines link tables with their FK
 * semantics (team-delete CASCADE, user/machine-delete RESTRICT), and the audit_log
 * entity_type CHECK re-added with TEAM.
 */
class TeamMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-25T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("V43 creates the teams table with the pinned columns")
  void teamsTableExistsWithPinnedColumns() {
    var columns = jdbc.queryForList("""
        SELECT column_name, is_nullable FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'teams'
        """);
    assertThat(columns).extracting(row -> row.get("column_name"))
        .contains("id", "name", "expires_at", "version", "created_at", "updated_at");

    var columnsNullability = jdbc.queryForList("""
        SELECT column_name, is_nullable FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'teams'
          AND column_name IN ('name', 'expires_at', 'created_at', 'updated_at')
        """);
    assertThat(columnsNullability).allSatisfy(row ->
        assertThat(row.get("is_nullable")).as("column %s must be NOT NULL", row.get("column_name")).isEqualTo("NO"));
  }

  @Test
  @DisplayName("V43 enforces case-insensitive name uniqueness")
  void lowerNameUniqueEnforced() {
    insertTeam("Cross Repair", "2026-09-30T00:00:00Z");

    assertThatThrownBy(() -> insertTeam("cross repair", "2026-09-30T00:00:00Z"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V43 name-not-blank CHECK rejects blank names")
  void nameNotBlankCheckEnforced() {
    assertThatThrownBy(() -> insertTeam("   ", "2026-09-30T00:00:00Z"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V43 deleting a team cascades its members and machines")
  void teamDeleteCascadesLinks() {
    var teamId = insertTeam("Cascade Team", "2026-09-30T00:00:00Z");
    var userId = seedUser();
    var machineId = seedMachine();
    jdbc.update("INSERT INTO team_members (team_id, user_id) VALUES (?,?)", teamId, userId);
    jdbc.update("INSERT INTO team_machines (team_id, machine_id) VALUES (?,?)", teamId, machineId);

    jdbc.update("DELETE FROM teams WHERE id = ?", teamId);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM team_members WHERE team_id = ?", Long.class, teamId)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM team_machines WHERE team_id = ?", Long.class, teamId)).isZero();
  }

  @Test
  @DisplayName("V43 deleting a user referenced by a team member is RESTRICTed")
  void memberUserDeleteRestricted() {
    var teamId = insertTeam("User Restrict Team", "2026-09-30T00:00:00Z");
    var userId = seedUser();
    jdbc.update("INSERT INTO team_members (team_id, user_id) VALUES (?,?)", teamId, userId);

    assertThatThrownBy(() -> jdbc.update("DELETE FROM auth_users WHERE id = ?", userId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V43 deleting a machine referenced by a team machine is RESTRICTed")
  void machineDeleteRestricted() {
    var teamId = insertTeam("Machine Restrict Team", "2026-09-30T00:00:00Z");
    var machineId = seedMachine();
    jdbc.update("INSERT INTO team_machines (team_id, machine_id) VALUES (?,?)", teamId, machineId);

    assertThatThrownBy(() -> jdbc.update("DELETE FROM machines WHERE id = ?", machineId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V43 audit_log entity_type CHECK accepts TEAM")
  void auditEntityTypeAcceptsTeam() {
    var teamId = insertTeam("Audit Team", "2026-09-30T00:00:00Z");
    UUID actorId = UUID.randomUUID();

    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), actorId, "audit-actor", "CREATE", "TEAM", teamId, "Audit Team",
        null, null, null, TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'TEAM' AND entity_id = ?",
        Long.class, teamId)).isEqualTo(1L);
  }

  @Test
  @DisplayName("V43 audit_log entity_type CHECK still rejects unknown types")
  void auditEntityTypeRejectsUnknown() {
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "NOT_A_TYPE", UUID.randomUUID(),
        "x", null, null, null, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UUID seedPlant() {
    UUID plantId = UUID.randomUUID();
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        plantId, "P-" + seedSeq.incrementAndGet(), "Plant", TS, TS);
    return plantId;
  }

  private UUID seedUser() {
    UUID userId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?)
        """, userId, "team-migration-user-" + seedSeq.incrementAndGet() + "@syncro.dev",
        "hash", "VIEWER", true, TS, TS);
    return userId;
  }

  private UUID seedMachine() {
    var plantId = seedPlant();
    UUID groupId = UUID.randomUUID();
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        groupId, plantId, "Group " + seedSeq.incrementAndGet(), TS, TS);
    UUID machineId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, machineId, plantId, groupId, "M-" + seedSeq.incrementAndGet(), "Machine", "ACTIVE", TS, TS);
    return machineId;
  }

  private UUID insertTeam(String name, String expiresAt) {
    UUID teamId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO teams (id, name, expires_at, version, created_at, updated_at)
        VALUES (?,?,?,?,?,?)
        """, teamId, name, Timestamp.from(Instant.parse(expiresAt)), 0, TS, TS);
    return teamId;
  }
}
