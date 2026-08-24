package com.syncro.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.domain.ApplicationRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

class PlantScopeRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

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
