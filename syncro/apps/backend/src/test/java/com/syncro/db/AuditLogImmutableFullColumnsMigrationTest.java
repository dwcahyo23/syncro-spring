package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V68 contract: the BEFORE UPDATE OF trigger column list now covers all columns,
 * including {@code id} and {@code plant_id} which were previously omitted (DW-128).
 */
class AuditLogImmutableFullColumnsMigrationTest extends AbstractPostgresIntegrationTest {

  private static final UUID AUDIT_ID = UUID.randomUUID();
  private static final UUID ACTOR_ID = UUID.randomUUID();
  private static final UUID ENTITY_ID = UUID.randomUUID();
  private static final UUID PLANT_ID = UUID.randomUUID();

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  @DisplayName("68-DB-001 P0 UPDATE of id column is blocked by the trigger")
  void updateIdIsBlocked() {
    seedAuditRow();
    var error = tamperViaSavepoint("UPDATE audit_log SET id = ?::uuid WHERE id = ?::uuid",
        UUID.randomUUID().toString(), AUDIT_ID.toString());
    assertThat(error).contains("audit_log is immutable");
  }

  @Test
  @DisplayName("68-DB-002 P0 UPDATE of plant_id column is blocked by the trigger")
  void updatePlantIdIsBlocked() {
    seedAuditRow();
    var error = tamperViaSavepoint("UPDATE audit_log SET plant_id = ?::uuid WHERE id = ?::uuid",
        UUID.randomUUID().toString(), AUDIT_ID.toString());
    assertThat(error).contains("audit_log is immutable");
  }

  @Test
  @DisplayName("68-DB-003 P0 UPDATE of a previously-covered column still blocked")
  void updateActorNameIsBlocked() {
    seedAuditRow();
    var error = tamperViaSavepoint(
        "UPDATE audit_log SET actor_name = 'hacker' WHERE id = ?::uuid",
        AUDIT_ID.toString());
    assertThat(error).contains("audit_log is immutable");
  }

  private void seedAuditRow() {
    // plant_id is FK-constrained; insert a real plant row so the audit row is valid.
    jdbc.update("""
        INSERT INTO plants (id, code, name, created_at, updated_at)
        VALUES (?::uuid, 'AUDIT', 'Audit Plant', now(), now())
        """, PLANT_ID.toString());
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id,
                               entity_label, plant_id, previous_value, new_value, created_at)
        VALUES (?::uuid, ?::uuid, 'migration-it', 'CREATE', 'PLANT', ?::uuid, 'Test',
                ?::uuid, NULL, '{}', now())
        """,
        AUDIT_ID.toString(), ACTOR_ID.toString(), ENTITY_ID.toString(), PLANT_ID.toString());
  }

  /** Runs the UPDATE inside a savepoint; returns the server error message (null on success). */
  private String tamperViaSavepoint(String sql, String... args) {
    final String[] serverMessage = {null};
    var savepoint = "sp_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
      try (var st = con.createStatement()) {
        st.execute("SAVEPOINT " + savepoint);
      }
      try (var ps = con.prepareStatement(sql)) {
        for (int i = 0; i < args.length; i++) {
          ps.setString(i + 1, args[i]);
        }
        try {
          ps.executeUpdate();
        } catch (java.sql.SQLException se) {
          serverMessage[0] = se.getMessage();
        } finally {
          try (var st = con.createStatement()) {
            st.execute("ROLLBACK TO SAVEPOINT " + savepoint);
          }
        }
      }
      return null;
    });
    return serverMessage[0];
  }
}
