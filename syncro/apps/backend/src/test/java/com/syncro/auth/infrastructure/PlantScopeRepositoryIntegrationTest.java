package com.syncro.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.auth.domain.ApplicationRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.context.SpringBootTest;
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
class PlantScopeRepositoryIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private JdbcClient jdbcClient;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Test
  void migrationCreatesPlantsAndAuthUserPlantAssignments() {
    var now = Instant.parse("2026-05-26T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(),
        "manage@syncro.dev",
        passwordEncoder.encode("syncro-manage-dev"),
        ApplicationRole.MANAGE,
        true,
        now,
        now));
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "PLANT-1", "Plant One", now, now));

    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(user.getId(), plant.getId(), now));

    assertThat(plants.findAll()).contains(plant);
    assertThat(assignments.findByAuthUserId(user.getId()))
        .extracting(AuthUserPlantAssignmentEntity::getPlantId)
        .containsExactly(plant.getId());
  }

  @Test
  void plantCodeIsUnique() {
    var now = Instant.parse("2026-05-26T00:00:00Z");
    plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "PLANT-2", "Plant Two", now, now));

    assertThatThrownBy(() -> plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "PLANT-2", "Other", now, now)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void userPlantAssignmentIsUnique() {
    var now = Instant.parse("2026-05-26T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(),
        "viewer@syncro.dev",
        passwordEncoder.encode("syncro-viewer-dev"),
        ApplicationRole.VIEWER,
        true,
        now,
        now));
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "PLANT-3", "Plant Three", now, now));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(user.getId(), plant.getId(), now));

    assertThatThrownBy(() -> jdbcClient.sql("""
        INSERT INTO auth_user_plant_assignments (auth_user_id, plant_id, created_at)
        VALUES (:authUserId, :plantId, :createdAt)
        """)
        .param("authUserId", user.getId())
        .param("plantId", plant.getId())
        .param("createdAt", now)
        .update())
        .isInstanceOf(RuntimeException.class);
  }
}
