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
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyValidationException;
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
    "GARAGE_HOST=localhost",
    "GARAGE_S3_PORT=3900",
    "GARAGE_ACCESS_KEY=test",
    "GARAGE_SECRET_KEY=test",
    "GARAGE_BUCKET=test",
    "GARAGE_REGION=garage",
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

    var created = taxonomyService.create(user, command(SparepartTaxonomyDimension.CATEGORY, " ELECTRONIC ", " Electronic "));

    assertThat(created.dimension()).isEqualTo(SparepartTaxonomyDimension.CATEGORY);
    assertThat(created.code()).isEqualTo("ELECTRONIC");
    assertThat(created.name()).isEqualTo("Electronic");
    assertThat(taxonomy.findById(created.id())).isPresent();
  }

  @Test
  @DisplayName("2.4-SVC-002 P1 duplicate same-dimension taxonomy name is rejected case-insensitively")
  void duplicateSameDimensionNameRejectedCaseInsensitively() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    assertThat(taxonomy.findByDimensionAndCodeIgnoreCase(SparepartTaxonomyDimension.CATEGORY, "ELECTRIC")).isPresent();

    assertThatThrownBy(() -> taxonomyService.create(admin, command(SparepartTaxonomyDimension.CATEGORY, " electric ", " electric ")))
        .isInstanceOf(DuplicateSparepartTaxonomyException.class);
  }

  @Test
  @DisplayName("2.R-SVC-001 P0 arbitrary category codes are rejected")
  void arbitraryCategoryCodesAreRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> taxonomyService.create(admin, command(SparepartTaxonomyDimension.CATEGORY, "CUSTOM", "Custom")))
        .isInstanceOf(SparepartTaxonomyValidationException.class);
  }

  @Test
  @DisplayName("2.4-SVC-003 P1 same taxonomy name is allowed across dimensions")
  void sameNameAllowedAcrossDimensions() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var baseline = taxonomy.findAll().size();

    var category = seededElectricCategory();
    var kind = taxonomyService.create(admin, command(SparepartTaxonomyDimension.KIND, "ELEC", "Electric", category.getId()));

    assertThat(category.getId()).isNotEqualTo(kind.id());
    assertThat(taxonomy.findAll()).hasSize(baseline + 1);
  }

  @Test
  @DisplayName("2.4-SVC-004 P0 database rejects case-insensitive duplicate taxonomy names within dimension")
  void databaseRejectsCaseInsensitiveDuplicateWithinDimension() {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    assertThat(seededElectricCategory()).isNotNull();

    assertThatThrownBy(() -> taxonomy.saveAndFlush(new SparepartTaxonomyEntity(
        UUID.randomUUID(), SparepartTaxonomyDimension.CATEGORY, "ELEC-2", "electric", now, now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("2.4-SVC-005 P1 list filters by dimension and orders by dimension then name")
  void listFiltersByDimensionAndOrdersPredictably() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var category = seededElectricCategory();
    var brand = taxonomyService.create(admin, command(SparepartTaxonomyDimension.BRAND, "WECON", "Wecon", category.getId()));
    var omron = taxonomyService.create(admin, command(SparepartTaxonomyDimension.BRAND, "OMRON", "Omron", category.getId()));

    var result = taxonomyService.list(admin, SparepartTaxonomyDimension.BRAND);

    assertThat(result).extracting(taxonomy -> taxonomy.id()).containsExactly(omron.id(), brand.id());
  }

  @Test
  @DisplayName("2.4-SVC-006 P1 update rejects taxonomy dimension changes")
  void updateRejectsTaxonomyDimensionChanges() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = seededElectricCategory();

    assertThatThrownBy(() -> taxonomyService.update(admin, created.getId(), command(SparepartTaxonomyDimension.BRAND, "ELEC-NEW", "Electrical")))
        .isInstanceOf(SparepartTaxonomyValidationException.class);
  }

  @Test
  @DisplayName("2.4-SVC-007 P0 VIEWER can list taxonomy but cannot mutate")
  void viewerCanListButCannotMutateTaxonomy() {
    var viewer = persistedUser(ApplicationRole.VIEWER, "viewer-taxonomy@syncro.dev");
    var entry = seededElectricCategory();

    assertThat(taxonomyService.list(viewer, null)).extracting(taxonomy -> taxonomy.id()).contains(entry.getId());
    assertThatThrownBy(() -> taxonomyService.create(viewer, command(SparepartTaxonomyDimension.BRAND, "WECON", "Wecon")))
        .isInstanceOf(SparepartTaxonomyMutationForbiddenException.class);
  }

  @Test
  @DisplayName("2.4-SVC-008 P1 delete removes taxonomy entry without dependents")
  void deleteRemovesTaxonomyEntryWithoutDependents() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var entry = taxonomyService.create(admin, command(SparepartTaxonomyDimension.CATEGORY, "ELECTRONIC", "Electronic"));

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
    var entry = taxonomyService.create(admin, command(SparepartTaxonomyDimension.CATEGORY, "ELECTRONIC", "Electronic"));
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

  private SparepartTaxonomyEntity seededElectricCategory() {
    return taxonomy.findByDimensionAndCodeIgnoreCase(SparepartTaxonomyDimension.CATEGORY, "ELECTRIC").orElseThrow();
  }

  private SparepartTaxonomyCommand command(SparepartTaxonomyDimension dimension, String code, String name) {
    return new SparepartTaxonomyCommand(dimension, code, name, null);
  }

  private SparepartTaxonomyCommand command(SparepartTaxonomyDimension dimension, String code, String name, UUID categoryId) {
    return new SparepartTaxonomyCommand(dimension, code, name, categoryId);
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
