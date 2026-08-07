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
import org.junit.jupiter.api.Disabled;
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
  @Disabled("RED - R-2.9-1 gap: trigger OF list omits id and plant_id; expected to fail until V16 is fixed")
  @DisplayName("2.9-SVC-017 P0 DB trigger guards the primary key and plant_id columns")
  void dbTriggerGuardsPrimaryKeyAndPlantId() {
    var plant = plant("GM1", "Plant GM1");
    var actor = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT, plant.getId(), "GM1",
        plant.getId(), null, Map.of("code", "GM1")));
    entityManager.flush();
    var auditId = auditRowId(plant.getId());

    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE audit_log SET plant_id = NULL WHERE id = ?", auditId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE audit_log SET id = ? WHERE id = ?", UUID.randomUUID(), auditId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @Disabled("RED - R-2.9-11 FK ON DELETE SET NULL acceptance lock; activate once the gap run reaches it")
  @DisplayName("2.9-SVC-018 P2 deleting a plant nulls the audit plant_id and keeps the entries")
  void plantDeleteNullsAuditPlantId() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = plants.create(admin, new CreatePlantCommand("GM1", "Plant GM1"));
    entityManager.flush();
    var auditId = jdbcTemplate.queryForObject(
        "SELECT id::text FROM audit_log WHERE entity_id = ? AND action = 'CREATE'", String.class, created.id());
    var plantIdBefore = jdbcTemplate.queryForObject("SELECT plant_id::text FROM audit_log WHERE id = ?", String.class, auditId);
    assertThat(plantIdBefore).isEqualTo(created.id().toString());

    plants.delete(admin, created.id());

    var plantIdAfter = jdbcTemplate.queryForObject("SELECT plant_id::text FROM audit_log WHERE id = ?", String.class, auditId);
    assertThat(plantIdAfter).isNull();
    var response = auditLog.list(admin, new AuditLogQuery(null, null, null, null, null, 0, 100, "createdAt,asc"));
    assertThat(response.totalElements()).isEqualTo(2);
    assertThat(response.items()).extracting(AuditLogEntryView::entityLabel).contains("GM1");
  }

  @Test
  @Disabled("RED - 2.9-SVC-019 behavior lock; documents current 500 on corrupt JSON, revisit when graceful decode lands")
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
      jdbcTemplate.update("UPDATE audit_log SET previous_value = 'not-json' WHERE id = ?", auditId);
      assertThatThrownBy(() -> auditLog.list(actor,
          new AuditLogQuery(null, null, null, null, null, 0, 100, "createdAt,desc")))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      jdbcTemplate.execute("ALTER TABLE audit_log ENABLE TRIGGER audit_log_immutable_before_update");
    }
  }

  @Test
  @Disabled("RED - R-2.9-13 LIKE escaping acceptance lock; activate once the gap run reaches it")
  @DisplayName("2.9-SVC-020 P2 actor filter treats LIKE wildcards literally")
  void actorFilterEscapesLikeWildcards() {
    var actor = new AuthenticatedUser(UUID.randomUUID().toString(), "yusuf_dev", ApplicationRole.SUPER_ADMIN);
    auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART_TAXONOMY,
        UUID.randomUUID(), "ELEC", null, null, Map.of("code", "ELEC")));

    var exact = auditLog.list(actor, new AuditLogQuery(null, "yusuf_dev", null, null, null, 0, 100, "createdAt,desc"));
    assertThat(exact.totalElements()).isEqualTo(1);

    var wildcard = auditLog.list(actor, new AuditLogQuery(null, "yusuf%dev", null, null, null, 0, 100, "createdAt,desc"));
    assertThat(wildcard.totalElements()).isZero();
  }

  @Test
  @Disabled("RED - pagination acceptance lock; activate once the gap run reaches it")
  @DisplayName("2.9-SVC-021 P2 pagination beyond the first page reports accurate totals")
  void paginationBeyondFirstPage() {
    var actor = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    for (int i = 0; i < 5; i++) {
      auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART_TAXONOMY,
          UUID.randomUUID(), "ELEC-" + i, null, null, Map.of("code", "ELEC-" + i)));
    }

    var response = auditLog.list(actor, new AuditLogQuery(null, null, null, null, null, 1, 2, "createdAt,asc"));

    assertThat(response.items()).hasSize(2);
    assertThat(response.totalElements()).isEqualTo(5);
    assertThat(response.page()).isEqualTo(1);
    assertThat(response.size()).isEqualTo(2);
  }

  private String auditRowId(UUID entityId) {
    return jdbcTemplate.queryForObject("SELECT id::text FROM audit_log WHERE entity_id = ?", String.class, entityId);
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
