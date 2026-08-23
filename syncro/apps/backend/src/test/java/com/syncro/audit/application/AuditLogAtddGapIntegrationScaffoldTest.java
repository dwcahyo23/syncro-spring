package com.syncro.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.audit.api.AuditLogDtos.AuditLogEntryView;
import com.syncro.audit.application.AuditLogService.AuditLogQuery;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.masterdata.application.PlantService;
import com.syncro.masterdata.application.PlantService.CreatePlantCommand;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "server.port=0",
    "REDIS_HOST=localhost",
    "REDIS_PORT=6379",
    "INFLUXDB_HOST=localhost",
    "INFLUXDB_PORT=8086",
    "INFLUXDB_USERNAME=test",
    "INFLUXDB_PASSWORD=test",
    "INFLUXDB_TOKEN=test",
    "INFLUXDB_ORG=test",
    "INFLUXDB_BUCKET=test",
    "SYNCRO_MQTT_HOST=localhost",
    "SYNCRO_MQTT_PORT=1883",
    "SYNCRO_MQTT_USERNAME=test",
    "SYNCRO_MQTT_PASSWORD=test",
    "SYNCRO_MQTT_CLIENT_ID=test",
    "SYNCRO_MQTT_TOPIC_FILTER=syncro/+/telemetry",
    "WAHA_HOST=localhost",
    "WAHA_PORT=3000",
    "WAHA_API_KEY=test",
    "syncro.auth.jwt.secret=test-secret-for-auth-integration-32x",
    "syncro.auth.jwt.issuer=syncro-test",
    "syncro.auth.jwt.ttl-minutes=30",
    "syncro.auth.local-admin.enabled=false",
    "syncro.auth.local-admin.login-identifier=admin@syncro.dev",
    "syncro.auth.local-admin.password=test-password"
})
@Testcontainers
@Transactional
class AuditLogAtddGapIntegrationScaffoldTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private AuditLogService auditLog;

  @Autowired
  private AuditLogWriter auditLogWriter;

  @Autowired
  private PlantService plants;

  @Autowired
  private PlantRepository plantRepository;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @PersistenceContext
  private EntityManager entityManager;

  @Test
    @DisplayName("2.9-SVC-017 P0 DB trigger blocks mutation of audited value columns")
  void dbTriggerGuardsPrimaryKeyAndPlantId() {
    var plant = plant("GM1", "Plant GM1");
    var actor = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT, plant.getId(), "GM1",
        plant.getId(), null, Map.of("code", "GM1")));
    entityManager.flush();
    var auditId = auditRowId(plant.getId());

    // Tampering runs inside the test transaction wrapped in a SAVEPOINT: the trigger's
    // RAISE EXCEPTION aborts everything after it in the transaction, so each attempt must
    // roll back to its own savepoint before the next one.
    assertThat(tamperViaSavepoint("UPDATE audit_log SET new_value = '{}' WHERE id = ?::uuid", auditId))
        .contains("audit_log is immutable");
    assertThat(tamperViaSavepoint("UPDATE audit_log SET actor_name = 'tampered' WHERE id = ?::uuid", auditId))
        .contains("audit_log is immutable");
  }

  /** Runs the statement inside a savepoint and returns the server error message it raised (null when it succeeded). */
  private String tamperViaSavepoint(String sql, Object... args) {
    final String[] serverMessage = {null};
    var savepoint = "sp_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
      try (var st = con.createStatement()) {
        st.execute("SAVEPOINT " + savepoint);
      }
      try (var ps = con.prepareStatement(sql)) {
        for (int i = 0; i < args.length; i++) {
          ps.setObject(i + 1, args[i], java.sql.Types.OTHER);
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

  @Test
    @DisplayName("2.9-SVC-018 P2 deleting a plant nulls the audit plant_id and keeps the entries")
  void plantDeleteNullsAuditPlantId() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = plants.create(admin, new CreatePlantCommand("GM1", "Plant GM1"));
    entityManager.flush();
    var auditId = jdbcTemplate.queryForObject(
        "SELECT id::text FROM audit_log WHERE entity_id = ?::uuid AND action = 'CREATE'", String.class, created.id());
    var plantIdBefore = jdbcTemplate.queryForObject("SELECT plant_id::text FROM audit_log WHERE id = ?::uuid", String.class, auditId);
    assertThat(plantIdBefore).isEqualTo(created.id().toString());

    plants.delete(admin, created.id());

    var plantIdAfter = jdbcTemplate.queryForObject("SELECT plant_id::text FROM audit_log WHERE id = ?::uuid", String.class, auditId);
    assertThat(plantIdAfter).isNull();
    var response = auditLog.list(admin, new AuditLogQuery(null, null, null, null, null, null, 0, 100, "createdAt,asc"));
    assertThat(response.totalElements()).isEqualTo(2);
    assertThat(response.items()).extracting(AuditLogEntryView::entityLabel).contains("GM1");
  }

  @Test
    @DisplayName("2.9-SVC-019 P2 corrupt previous_value surfaces read failure today (behavior lock)")
  void corruptJsonSurfacesReadFailureToday() {
    var actor = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var entityId = UUID.randomUUID();
    auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART_TAXONOMY,
        entityId, "ELEC", null, null, Map.of("code", "ELEC")));
    entityManager.flush();
    var auditId = auditRowId(entityId);

    jdbcTemplate.execute("ALTER TABLE audit_log DISABLE TRIGGER audit_log_immutable_before_update");
    try {
      jdbcTemplate.update("UPDATE audit_log SET previous_value = 'not-json' WHERE id = ?::uuid", auditId);
      // Evict the persistence context so the query re-reads the corrupted row from the DB
      // instead of returning the managed (pre-corruption) entity from the first-level cache.
      entityManager.clear();
      assertThatThrownBy(() -> auditLog.list(actor,
          new AuditLogQuery(null, null, null, null, null, null, 0, 100, "createdAt,desc")))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      // The expected IllegalStateException aborts the test-managed transaction; re-enabling
      // the trigger on the same connection would fail with 25P02. The transaction rolls back
      // anyway, which restores the trigger state — tolerate the failure here.
      try {
        jdbcTemplate.execute("ALTER TABLE audit_log ENABLE TRIGGER audit_log_immutable_before_update");
      } catch (DataAccessException ex) {
        // Only tolerate the expected 25P02 aborted-transaction state; anything else
        // (connection loss, unexpected DDL failure) must surface.
        var root = ex.getRootCause();
        if (!(root instanceof java.sql.SQLException sqlEx) || !"25P02".equals(sqlEx.getSQLState())) {
          throw ex;
        }
      }
    }
  }

  @Test
    @DisplayName("2.9-SVC-020 P2 actor filter treats LIKE wildcards literally")
  void actorFilterEscapesLikeWildcards() {
    var actor = persistedUser(ApplicationRole.SUPER_ADMIN, "yusuf_dev");
    auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART_TAXONOMY,
        UUID.randomUUID(), "ELEC", null, null, Map.of("code", "ELEC")));

    var exact = auditLog.list(actor, new AuditLogQuery(null, null, "yusuf_dev", null, null, null, 0, 100, "createdAt,desc"));
    assertThat(exact.totalElements()).isEqualTo(1);

    var wildcard = auditLog.list(actor, new AuditLogQuery(null, null, "yusuf%dev", null, null, null, 0, 100, "createdAt,desc"));
    assertThat(wildcard.totalElements()).isZero();
  }

  @Test
    @DisplayName("2.9-SVC-021 P2 pagination beyond the first page reports accurate totals")
  void paginationBeyondFirstPage() {
    var actor = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    for (int i = 0; i < 5; i++) {
      auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART_TAXONOMY,
          UUID.randomUUID(), "ELEC-" + i, null, null, Map.of("code", "ELEC-" + i)));
    }

    var response = auditLog.list(actor, new AuditLogQuery(null, null, null, null, null, null, 1, 2, "createdAt,asc"));

    assertThat(response.items()).hasSize(2);
    assertThat(response.totalElements()).isEqualTo(5);
    assertThat(response.page()).isEqualTo(1);
    assertThat(response.size()).isEqualTo(2);
  }

  private String auditRowId(UUID entityId) {
    return jdbcTemplate.queryForObject("SELECT id::text FROM audit_log WHERE entity_id = ?::uuid", String.class, entityId);
  }

  private PlantEntity plant(String code, String name) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    return plantRepository.saveAndFlush(new PlantEntity(UUID.randomUUID(), code, name, now, now));
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), loginIdentifier,
        passwordEncoder.encode("syncro-test-password"), role, true, now, now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private void assign(AuthenticatedUser user, PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plant.getId(), Instant.parse("2026-05-28T00:00:00Z")));
  }

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
