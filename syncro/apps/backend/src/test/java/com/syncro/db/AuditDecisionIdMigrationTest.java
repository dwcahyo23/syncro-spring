package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.authz.application.DecisionContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * V44 contract: audit_log.decision_id is nullable and correlated with OPA decisions
 * (FR-164), while the immutable-update trigger still guards it against tampering.
 */
class AuditDecisionIdMigrationTest extends AbstractPostgresIntegrationTest {

  private static final String DECISION_ID = "22222222-2222-2222-2222-222222222222";

  @Autowired
  private AuditLogWriter auditLogWriter;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @PersistenceContext
  private EntityManager entityManager;

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  @DisplayName("9.3-DB-001 P1 decision_id column exists and is nullable")
  void decisionIdColumnExistsAndIsNullable() {
    var nullable = jdbcTemplate.queryForObject(
        "SELECT is_nullable FROM information_schema.columns "
            + "WHERE table_name = 'audit_log' AND column_name = 'decision_id'",
        String.class);

    assertThat(nullable).isEqualTo("YES");
  }

  @Test
  @DisplayName("9.3-DB-002 P1 rows can be inserted with a decision_id")
  void insertRowWithDecisionIdWorks() {
    var entityId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id,
                               entity_label, plant_id, previous_value, new_value, created_at, decision_id)
        VALUES (?::uuid, ?::uuid, 'migration-it', 'CREATE', 'PLANT', ?::uuid, 'GM1',
                NULL, NULL, '{}', now(), ?::uuid)
        """,
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), entityId.toString(), DECISION_ID);

    var persisted = jdbcTemplate.queryForObject(
        "SELECT decision_id::text FROM audit_log WHERE entity_id = ?::uuid", String.class, entityId.toString());
    assertThat(persisted).isEqualTo(DECISION_ID);
  }

  @Test
  @DisplayName("9.3-DB-003 P0 DB trigger blocks UPDATE of decision_id")
  void updateDecisionIdIsBlockedByTrigger() {
    var entityId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id,
                               entity_label, plant_id, previous_value, new_value, created_at, decision_id)
        VALUES (?::uuid, ?::uuid, 'migration-it', 'CREATE', 'PLANT', ?::uuid, 'GM1',
                NULL, NULL, '{}', now(), ?::uuid)
        """,
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), entityId.toString(), DECISION_ID);
    var auditId = jdbcTemplate.queryForObject(
        "SELECT id::text FROM audit_log WHERE entity_id = ?::uuid", String.class, entityId.toString());

    // Tampering runs inside a SAVEPOINT: the trigger's RAISE EXCEPTION aborts everything
    // after it in the transaction, so the attempt must roll back to its own savepoint.
    assertThat(tamperViaSavepoint(auditId)).contains("audit_log is immutable");
  }

  @Test
  @DisplayName("9.3-DB-004 P1 writer round-trips an explicit decisionId")
  void writerPersistsExplicitDecisionId() {
    var actor = new AuthenticatedUser(UUID.randomUUID().toString(), "writer@syncro.dev", ApplicationRole.MANAGE);
    auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT,
        UUID.randomUUID(), "GM1", null, null, Map.of("code", "GM1"), UUID.fromString(DECISION_ID)));
    entityManager.flush();

    var persisted = jdbcTemplate.queryForObject(
        "SELECT decision_id::text FROM audit_log WHERE actor_name = 'writer@syncro.dev'", String.class);
    assertThat(persisted).isEqualTo(DECISION_ID);
  }

  @Test
  @DisplayName("9.3-DB-005 P1 writer autofills decisionId from the stashed request decision")
  void writerAutofillsFromRequestDecisionContext() {
    var request = new MockHttpServletRequest();
    DecisionContext.stash(request, DECISION_ID);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    var actor = new AuthenticatedUser(UUID.randomUUID().toString(), "autofill@syncro.dev", ApplicationRole.MANAGE);
    auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT,
        UUID.randomUUID(), "GM2", null, null, Map.of("code", "GM2"), null));
    entityManager.flush();

    var persisted = jdbcTemplate.queryForObject(
        "SELECT decision_id::text FROM audit_log WHERE actor_name = 'autofill@syncro.dev'", String.class);
    assertThat(persisted).isEqualTo(DECISION_ID);
  }

  @Test
  @DisplayName("9.3-DB-006 P2 outside any request the writer leaves decision_id NULL")
  void writerOutsideRequestLeavesDecisionIdNull() {
    var actor = new AuthenticatedUser(UUID.randomUUID().toString(), "system-ish@syncro.dev", ApplicationRole.MANAGE);
    auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT,
        UUID.randomUUID(), "GM3", null, null, Map.of("code", "GM3"), null));
    entityManager.flush();

    var persisted = jdbcTemplate.queryForObject(
        "SELECT decision_id::text FROM audit_log WHERE actor_name = 'system-ish@syncro.dev'", String.class);
    assertThat(persisted).isNull();
  }

  /** Runs the UPDATE inside a savepoint; returns the server error message (null on success). */
  private String tamperViaSavepoint(String auditId) {
    final String[] serverMessage = {null};
    var savepoint = "sp_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
      try (var st = con.createStatement()) {
        st.execute("SAVEPOINT " + savepoint);
      }
      try (var ps = con.prepareStatement(
          "UPDATE audit_log SET decision_id = ?::uuid WHERE id = ?::uuid")) {
        ps.setString(1, "33333333-3333-3333-3333-333333333333");
        ps.setString(2, auditId);
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
