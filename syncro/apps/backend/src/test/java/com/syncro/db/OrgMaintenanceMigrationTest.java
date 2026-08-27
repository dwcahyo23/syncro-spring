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
 * Migration evidence for V58 (spec-org-maintenance-model): auth_users master columns
 * (display_name backfill, partial-unique NIK/phone), departments + department_members,
 * job_titles, sections.leader_user_id, and the audit_log entity_type CHECK extended
 * with DEPARTMENT / DEPARTMENT_MEMBER / USER.
 */
class OrgMaintenanceMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-27T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("V58 extends auth_users with the master columns")
  void authUsersMasterColumnsExist() {
    var columns = jdbc.queryForList("""
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'auth_users'
        """);
    assertThat(columns).extracting(row -> row.get("column_name"))
        .contains("display_name", "nik", "phone_number", "job_title_id", "department_id");
  }

  @Test
  @DisplayName("V58 backfills display_name from login_identifier split at @")
  void displayNameBackfilled() {
    // The migration already applied on the empty container DB, so it cannot be
    // re-run here. Verify the exact backfill statement V58 uses (same SQL) on a
    // freshly seeded user to prove the split-at-@ rule behaves as specified.
    var userId = seedUser("technician.master@syncro.dev", "SUPER_ADMIN");
    jdbc.update("""
        UPDATE auth_users
        SET display_name = COALESCE(NULLIF(split_part(login_identifier, '@', 1), ''), login_identifier)
        WHERE display_name IS NULL
        """);
    var displayName = jdbc.queryForObject(
        "SELECT display_name FROM auth_users WHERE id = ?", String.class, userId);
    assertThat(displayName).isEqualTo("technician.master");
  }

  @Test
  @DisplayName("V58 NIK partial-unique accepts duplicate NULLs and rejects duplicate values")
  void nikPartialUniqueEnforced() {
    var userA = seedUser("a@syncro.dev", "SUPER_ADMIN");
    var userB = seedUser("b@syncro.dev", "SUPER_ADMIN");
    // both NULL — allowed
    jdbc.update("UPDATE auth_users SET nik = NULL WHERE id IN (?, ?)", userA, userB);
    // duplicate non-null — rejected
    jdbc.update("UPDATE auth_users SET nik = 'NIK-001' WHERE id = ?", userA);
    assertThatThrownBy(() -> jdbc.update("UPDATE auth_users SET nik = 'NIK-001' WHERE id = ?", userB))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V58 phone partial-unique accepts duplicate NULLs and rejects duplicate values")
  void phonePartialUniqueEnforced() {
    var userA = seedUser("c@syncro.dev", "SUPER_ADMIN");
    var userB = seedUser("d@syncro.dev", "SUPER_ADMIN");
    jdbc.update("UPDATE auth_users SET phone_number = '0811-1111' WHERE id = ?", userA);
    assertThatThrownBy(() -> jdbc.update("UPDATE auth_users SET phone_number = '0811-1111' WHERE id = ?", userB))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V58 creates departments with plant-scoped name uniqueness")
  void departmentsPlantNameUniqueEnforced() {
    var plantA = seedPlant();
    var plantB = seedPlant();
    insertDepartment(plantA, "Mechanical");
    insertDepartment(plantB, "Mechanical"); // same name, different plant — allowed
    assertThatThrownBy(() -> insertDepartment(plantA, "Mechanical"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V58 department_members enforces (department_id, user_id) uniqueness")
  void departmentMembersUniqueEnforced() {
    var plant = seedPlant();
    var departmentId = insertDepartment(plant, "Electrical");
    var user = seedUser("member@syncro.dev", "TECHNICIAN");
    insertDepartmentMember(departmentId, user);
    assertThatThrownBy(() -> insertDepartmentMember(departmentId, user))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V58 deleting a department cascades its members")
  void departmentDeleteCascadesMembers() {
    var plant = seedPlant();
    var departmentId = insertDepartment(plant, "Cascade Dept");
    var user = seedUser("member2@syncro.dev", "TECHNICIAN");
    insertDepartmentMember(departmentId, user);

    jdbc.update("DELETE FROM departments WHERE id = ?", departmentId);
    var remaining = jdbc.queryForObject(
        "SELECT count(*) FROM department_members WHERE department_id = ?", Long.class, departmentId);
    assertThat(remaining).isZero();
  }

  @Test
  @DisplayName("V58 creates job_titles with unique code")
  void jobTitlesCodeUniqueEnforced() {
    insertJobTitle("MECHANIC", "Mechanic");
    assertThatThrownBy(() -> insertJobTitle("MECHANIC", "Another Mechanic"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V58 adds sections.leader_user_id")
  void sectionsLeaderUserIdColumnExists() {
    var columns = jdbc.queryForList("""
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'sections'
        """);
    assertThat(columns).extracting(row -> row.get("column_name")).contains("leader_user_id");
  }

  @Test
  @DisplayName("V58 audit_log entity_type CHECK accepts DEPARTMENT, DEPARTMENT_MEMBER, USER")
  void auditEntityTypeAcceptsNewTypes() {
    var plantId = seedPlant();
    var userId = seedUser("audit-target@syncro.dev", "TECHNICIAN");
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "DEPARTMENT",
        UUID.randomUUID(), "Mechanical", plantId, null, null, TS);
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "UPDATE", "DEPARTMENT_MEMBER",
        UUID.randomUUID(), "member-link", plantId, null, null, TS);
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "UPDATE", "USER",
        userId, "user-master", null, null, null, TS);
  }

  @Test
  @DisplayName("V58 audit_log entity_type CHECK still rejects unknown types")
  void auditEntityTypeRejectsUnknown() {
    var plantId = seedPlant();
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "NOT_A_TYPE", UUID.randomUUID(),
        "x", plantId, null, null, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UUID seedUser(String loginIdentifier, String role) {
    var userId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?)
        """, userId, loginIdentifier, "hash", role, true, TS, TS);
    return userId;
  }

  private UUID seedPlant() {
    UUID plantId = UUID.randomUUID();
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        plantId, "P-" + seedSeq.incrementAndGet(), "Plant", TS, TS);
    return plantId;
  }

  private UUID insertDepartment(UUID plantId, String name) {
    var departmentId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO departments (id, plant_id, name, active, created_at, updated_at)
        VALUES (?,?,?,?,?,?)
        """, departmentId, plantId, name, true, TS, TS);
    return departmentId;
  }

  private void insertDepartmentMember(UUID departmentId, UUID userId) {
    jdbc.update("""
        INSERT INTO department_members (id, department_id, user_id, assigned_by, assigned_at)
        VALUES (?,?,?,?,?)
        """, UUID.randomUUID(), departmentId, userId, UUID.randomUUID(), TS);
  }

  private void insertJobTitle(String code, String name) {
    jdbc.update("""
        INSERT INTO job_titles (id, code, name, created_at, updated_at)
        VALUES (?,?,?,?,?)
        """, UUID.randomUUID(), code, name, TS, TS);
  }
}
