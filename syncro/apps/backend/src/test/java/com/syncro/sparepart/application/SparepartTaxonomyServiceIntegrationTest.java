package com.syncro.sparepart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.sparepart.application.SparepartTaxonomyService.DuplicateSparepartTaxonomyException;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyCommand;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyDataIntegrityException;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyMutationForbiddenException;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyNotFoundException;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
class SparepartTaxonomyServiceIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private SparepartTaxonomyService taxonomyService;

  @Autowired
  private SparepartTaxonomyRepository taxonomy;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  @DisplayName("2.4-SVC-001 P1 MANAGE creates normalized taxonomy entry")
  void manageCreatesNormalizedTaxonomyEntry() {
    var user = persistedUser(ApplicationRole.MANAGE, "manage-taxonomy@syncro.dev");

    var created = taxonomyService.create(user, command(SparepartTaxonomyDimension.CATEGORY, " Electric "));

    assertThat(created.dimension()).isEqualTo(SparepartTaxonomyDimension.CATEGORY);
    assertThat(created.name()).isEqualTo("Electric");
    assertThat(taxonomy.findById(created.id())).isPresent();
  }

  @Test
  @DisplayName("2.4-SVC-002 P1 duplicate same-dimension taxonomy name is rejected case-insensitively")
  void duplicateSameDimensionNameRejectedCaseInsensitively() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    taxonomyService.create(admin, command(SparepartTaxonomyDimension.CATEGORY, "Electric"));

    assertThatThrownBy(() -> taxonomyService.create(admin, command(SparepartTaxonomyDimension.CATEGORY, " electric ")))
        .isInstanceOf(DuplicateSparepartTaxonomyException.class);
  }

  @Test
  @DisplayName("2.4-SVC-003 P1 same taxonomy name is allowed across dimensions")
  void sameNameAllowedAcrossDimensions() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var category = taxonomyService.create(admin, command(SparepartTaxonomyDimension.CATEGORY, "Electric"));
    var kind = taxonomyService.create(admin, command(SparepartTaxonomyDimension.KIND, "Electric"));

    assertThat(category.id()).isNotEqualTo(kind.id());
    assertThat(taxonomy.findAll()).hasSize(2);
  }

  @Test
  @DisplayName("2.4-SVC-004 P0 database rejects case-insensitive duplicate taxonomy names within dimension")
  void databaseRejectsCaseInsensitiveDuplicateWithinDimension() {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.CATEGORY, "Electric", now, now));

    assertThatThrownBy(() -> taxonomy.saveAndFlush(new SparepartTaxonomyEntity(
        UUID.randomUUID(), SparepartTaxonomyDimension.CATEGORY, "electric", now, now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("2.4-SVC-005 P1 list filters by dimension and orders by dimension then name")
  void listFiltersByDimensionAndOrdersPredictably() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var brand = taxonomyService.create(admin, command(SparepartTaxonomyDimension.BRAND, "Wecon"));
    taxonomyService.create(admin, command(SparepartTaxonomyDimension.CATEGORY, "Electric"));
    var omron = taxonomyService.create(admin, command(SparepartTaxonomyDimension.BRAND, "Omron"));

    var result = taxonomyService.list(admin, SparepartTaxonomyDimension.BRAND);

    assertThat(result).extracting("id").containsExactly(omron.id(), brand.id());
  }

  @Test
  @DisplayName("2.4-SVC-006 P1 update preserves taxonomy dimension")
  void updatePreservesTaxonomyDimension() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = taxonomyService.create(admin, command(SparepartTaxonomyDimension.CATEGORY, "Electric"));

    var updated = taxonomyService.update(admin, created.id(), command(SparepartTaxonomyDimension.BRAND, "Electrical"));

    assertThat(updated.dimension()).isEqualTo(SparepartTaxonomyDimension.CATEGORY);
    assertThat(updated.name()).isEqualTo("Electrical");
  }

  @Test
  @DisplayName("2.4-SVC-007 P0 VIEWER can list taxonomy but cannot mutate")
  void viewerCanListButCannotMutateTaxonomy() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var viewer = persistedUser(ApplicationRole.VIEWER, "viewer-taxonomy@syncro.dev");
    var entry = taxonomyService.create(admin, command(SparepartTaxonomyDimension.CATEGORY, "Electric"));

    assertThat(taxonomyService.list(viewer, null)).extracting("id").containsExactly(entry.id());
    assertThatThrownBy(() -> taxonomyService.create(viewer, command(SparepartTaxonomyDimension.BRAND, "Wecon")))
        .isInstanceOf(SparepartTaxonomyMutationForbiddenException.class);
  }

  @Test
  @DisplayName("2.4-SVC-008 P1 delete removes taxonomy entry without dependents")
  void deleteRemovesTaxonomyEntryWithoutDependents() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var entry = taxonomyService.create(admin, command(SparepartTaxonomyDimension.CATEGORY, "Electric"));

    taxonomyService.delete(admin, entry.id());

    assertThat(taxonomy.findById(entry.id())).isEmpty();
  }

  @Test
  @DisplayName("2.4-SVC-009 P1 missing taxonomy entry is rejected safely")
  void missingTaxonomyEntryRejectedSafely() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> taxonomyService.get(admin, UUID.randomUUID()))
        .isInstanceOf(SparepartTaxonomyNotFoundException.class);
  }

  @Test
  @DisplayName("2.4-SVC-010 P1 delete dependency conflict returns data integrity exception")
  void deleteDependencyConflictReturnsDataIntegrityException() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var entry = taxonomyService.create(admin, command(SparepartTaxonomyDimension.CATEGORY, "Electric"));
    jdbc.execute("""
        CREATE TABLE sparepart_taxonomy_delete_dependencies (
          id UUID PRIMARY KEY,
          taxonomy_id UUID NOT NULL REFERENCES sparepart_taxonomy(id) ON DELETE RESTRICT
        )
        """);
    jdbc.update("INSERT INTO sparepart_taxonomy_delete_dependencies (id, taxonomy_id) VALUES (?, ?)", UUID.randomUUID(), entry.id());

    assertThatThrownBy(() -> taxonomyService.delete(admin, entry.id()))
        .isInstanceOf(SparepartTaxonomyDataIntegrityException.class);
  }

  private SparepartTaxonomyCommand command(SparepartTaxonomyDimension dimension, String name) {
    return new SparepartTaxonomyCommand(dimension, name);
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), loginIdentifier,
        passwordEncoder.encode("syncro-test-password"), role, true, now, now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
