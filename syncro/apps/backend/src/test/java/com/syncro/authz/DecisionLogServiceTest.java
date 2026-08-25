package com.syncro.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.authz.application.DecisionLogService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Decision-log contract (FR-164 / NFR-P2-7) against a real database: retention purge
 * removes only stale rows, persisted rows carry only structural fields (masking by
 * construction), and the paged read view is newest-first.
 */
class DecisionLogServiceTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private DecisionLogService decisionLogs;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @PersistenceContext
  private EntityManager entityManager;

  @Test
  @DisplayName("9.5-DL-001 P0 purge removes only rows decided before the cutoff")
  void purgeRemovesOnlyStaleRows() {
    var young = insertDecision(Instant.now().minusSeconds(60));
    var stale = insertDecision(Instant.now().minusSeconds(3_600));

    long removed = decisionLogs.purgeOlderThan(Instant.now().minusSeconds(300));

    assertThat(removed).isEqualTo(1);
    assertThat(decisionCount(young)).isEqualTo(1);
    assertThat(decisionCount(stale)).isZero();
  }

  @Test
  @DisplayName("9.5-DL-002 P0 a decision row persists only structural fields, never body/phone-shaped data")
  void persistedRowIsMaskedToStructuralFields() {
    var userId = UUID.randomUUID();
    decisionLogs.record(UUID.randomUUID().toString(), "sha256:abc", true, false, userId,
        "POST /api/v1/machines", "endpoint");
    entityManager.flush();

    var row = jdbcTemplate.queryForMap(
        "SELECT decision_id, policy_revision, allowed, degraded, subject_user_id, action, "
            + "resource_type, decided_at FROM authz_decisions WHERE subject_user_id = ?::uuid",
        userId.toString());

    assertThat(row.keySet())
        .containsExactlyInAnyOrder("decision_id", "policy_revision", "allowed", "degraded",
            "subject_user_id", "action", "resource_type", "decided_at");
    assertThat(row.get("action")).isEqualTo("POST /api/v1/machines");
    assertThat(row.get("resource_type")).isEqualTo("endpoint");
    assertThat(row.get("policy_revision")).isEqualTo("sha256:abc");
    assertThat(row.get("allowed")).isEqualTo(true);
    assertThat(row.get("degraded")).isEqualTo(false);
    assertThat(row.get("subject_user_id")).isEqualTo(userId);

    var values = String.join("|", row.values().stream().map(String::valueOf).toList());
    assertThat(values)
        .doesNotContain("WAHA", "whatsapp", "628123456", "PASSWORD", "secret")
        .doesNotContain("{\"name\"", "\"body\"", "\"phone\"");
  }

  @Test
  @DisplayName("9.5-DL-003 P1 paged list returns newest-first with total")
  void pagedListIsNewestFirst() {
    var userId = UUID.randomUUID();
    decisionLogs.record(UUID.randomUUID().toString(), "r1", true, false, userId,
        "GET /api/v1/machines", "endpoint");
    decisionLogs.record(UUID.randomUUID().toString(), "r2", false, false, userId,
        "POST /api/v1/machines", "endpoint");
    entityManager.flush();

    var page = decisionLogs.list(0, 50);

    assertThat(page.totalElements()).isEqualTo(2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).action()).isEqualTo("POST /api/v1/machines");
    assertThat(page.items().get(1).action()).isEqualTo("GET /api/v1/machines");
    assertThat(page.items().get(0).policyRevision()).isEqualTo("r2");
    assertThat(page.items().get(1).policyRevision()).isEqualTo("r1");
  }

  private UUID insertDecision(Instant decidedAt) {
    var id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO authz_decisions (id, decision_id, policy_revision, allowed, degraded, "
            + "subject_user_id, action, resource_type, decided_at) "
            + "VALUES (?::uuid, ?::uuid, 'r', true, false, ?::uuid, 'GET /api/v1/machines', "
            + "'endpoint', ?::timestamptz)",
        id.toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        java.sql.Timestamp.from(decidedAt));
    return id;
  }

  private long decisionCount(UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM authz_decisions WHERE id = ?::uuid", Long.class, id.toString());
  }
}
