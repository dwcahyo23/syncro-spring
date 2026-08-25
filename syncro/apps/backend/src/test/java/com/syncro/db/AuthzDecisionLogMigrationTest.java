package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V46 contract (story 9-5): the {@code authz_decisions} table exists with the exact
 * structural columns and the {@code decided_at} index required by the purge job.
 */
class AuthzDecisionLogMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("9.5-DB-001 P0 authz_decisions table and its columns exist")
  void decisionTableExistsWithStructuralColumns() {
    var columns = jdbcTemplate.query(
        "SELECT column_name, is_nullable, data_type FROM information_schema.columns "
            + "WHERE table_name = 'authz_decisions' ORDER BY ordinal_position",
        (rs, rowNum) -> Map.entry(rs.getString("column_name"),
            rs.getString("is_nullable") + ":" + rs.getString("data_type")));

    assertThat(columns).containsExactlyInAnyOrder(
        Map.entry("id", "NO:uuid"),
        Map.entry("decision_id", "YES:uuid"),
        Map.entry("policy_revision", "YES:text"),
        Map.entry("allowed", "NO:boolean"),
        Map.entry("degraded", "NO:boolean"),
        Map.entry("subject_user_id", "YES:uuid"),
        Map.entry("action", "NO:character varying"),
        Map.entry("resource_type", "YES:character varying"),
        Map.entry("decided_at", "NO:timestamp with time zone"));
  }

  @Test
  @DisplayName("9.5-DB-002 P0 decided_at index exists for the purge query")
  void decidedAtIndexExists() {
    var indexes = jdbcTemplate.query(
        "SELECT indexname FROM pg_indexes WHERE tablename = 'authz_decisions'",
        (rs, rowNum) -> rs.getString("indexname"));
    assertThat(indexes).contains("idx_authz_decisions_decided_at");
  }

  @Test
  @DisplayName("9.5-DB-003 P0 a decision row round-trips through the table")
  void decisionRowRoundTrips() {
    var decisionId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    var subjectUserId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    jdbcTemplate.update("""
        INSERT INTO authz_decisions (id, decision_id, policy_revision, allowed, degraded,
                                     subject_user_id, action, resource_type)
        VALUES (?::uuid, ?::uuid, 'sha256:rego', true, false, ?::uuid, 'POST /api/v1/teams', 'endpoint')
        """, UUID.randomUUID().toString(), decisionId, subjectUserId);

    var row = jdbcTemplate.queryForMap(
        "SELECT decision_id, policy_revision, allowed, degraded, subject_user_id, action "
            + "FROM authz_decisions WHERE decision_id = ?::uuid", decisionId);

    assertThat(row.get("decision_id").toString()).isEqualTo(decisionId);
    assertThat(row.get("policy_revision")).isEqualTo("sha256:rego");
    assertThat(row.get("allowed")).isEqualTo(true);
    assertThat(row.get("degraded")).isEqualTo(false);
    assertThat(row.get("subject_user_id").toString()).isEqualTo(subjectUserId);
    assertThat(row.get("action")).isEqualTo("POST /api/v1/teams");
  }
}
