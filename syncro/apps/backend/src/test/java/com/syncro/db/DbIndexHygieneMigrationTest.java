package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
class DbIndexHygieneMigrationTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private MachineRepository machineRepository;

  @Autowired
  private SparepartTaxonomyRepository taxonomyRepository;

  @Test
  @DisplayName("V17 drops the six redundant indexes after the full V1-V17 migration chain")
  void redundantIndexesAreDropped() {
    assertThat(toRegclass("public.idx_machine_sparepart_installations_machine_id_sparepart_id")).isNull();
    assertThat(toRegclass("public.uq_auth_user_plant_assignments_auth_user_plant")).isNull();
    assertThat(toRegclass("public.idx_auth_user_plant_assignments_auth_user_id")).isNull();
    assertThat(toRegclass("public.idx_machine_groups_plant_id")).isNull();
    assertThat(toRegclass("public.idx_machines_plant_id")).isNull();
    assertThat(toRegclass("public.idx_sparepart_taxonomy_dimension")).isNull();
  }

  @Test
  @DisplayName("V17 creates the three order-by-supporting indexes with expected definitions")
  void queryIndexesArePresentWithExpectedDefinitions() {
    assertThat(indexDef("idx_spareparts_code"))
        .isNotNull()
        .endsWith("USING btree (code)");
    assertThat(indexDef("idx_machines_code"))
        .isNotNull()
        .endsWith("USING btree (code)");
    assertThat(indexDef("idx_sparepart_taxonomy_dimension_name"))
        .isNotNull()
        .endsWith("USING btree (dimension, name)");
  }

  @Test
  @DisplayName("V17 keeps primary keys and unique constraints backing the dropped indexes")
  void keptConstraintsAndUniqueIndexesStillExist() {
    assertThat(toRegclass("public.pk_auth_user_plant_assignments")).isNotNull();
    assertThat(toRegclass("public.uq_machine_sparepart_installations_machine_sparepart_function")).isNotNull();
    assertThat(toRegclass("public.uq_machines_plant_id_lower_code")).isNotNull();
    assertThat(toRegclass("public.uq_sparepart_taxonomy_dimension_lower_code")).isNotNull();
    assertThat(toRegclass("public.uq_sparepart_taxonomy_dimension_lower_name")).isNotNull();
    assertThat(toRegclass("public.uq_machine_groups_plant_id_name")).isNotNull();
    assertThat(toRegclass("public.uq_machine_groups_id_plant_id")).isNotNull();
  }

  @Test
  @DisplayName("V17 keeps the FK-supporting single-column indexes")
  void keptSingleColumnIndexesStillExist() {
    assertThat(toRegclass("public.idx_machine_sparepart_installations_machine_id")).isNotNull();
    assertThat(toRegclass("public.idx_machine_sparepart_installations_sparepart_id")).isNotNull();
    assertThat(toRegclass("public.idx_auth_user_plant_assignments_plant_id")).isNotNull();
    assertThat(toRegclass("public.idx_machines_plant_id_status")).isNotNull();
    assertThat(toRegclass("public.idx_machines_machine_group_id")).isNotNull();
  }

  @Test
  @Transactional
  @DisplayName("ORDER_PRESERVED: findByDimensionOrderByNameAsc returns rows ordered by name after V17")
  void taxonomyOrderingPreservedAfterV17() {
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name) VALUES (?, 'CATEGORY', 'C-02', 'Beta')",
        UUID.randomUUID());
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name) VALUES (?, 'CATEGORY', 'C-01', 'Alpha')",
        UUID.randomUUID());
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name) VALUES (?, 'CATEGORY', 'C-03', 'Charlie')",
        UUID.randomUUID());

    List<String> names = taxonomyRepository
        .findByDimensionOrderByNameAsc(SparepartTaxonomyDimension.CATEGORY)
        .stream()
        .map(SparepartTaxonomyEntity::getName)
        .toList();

    assertThat(names).isSorted();
  }

  @Test
  @Transactional
  @DisplayName("JOIN_STILL_OK: findAllScoped/findAllUnscoped return machines ordered by code after V17")
  void machineListOrderingPreservedAfterV17() {
    UUID plantId = UUID.randomUUID();
    jdbc.update("INSERT INTO plants (id, code, name) VALUES (?, 'PLANT-A', 'Plant A')", plantId);

    UUID groupId = UUID.randomUUID();
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name) VALUES (?, ?, 'Group A')", groupId, plantId);

    UUID secondMachine = UUID.randomUUID();
    UUID firstMachine = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO machines (id, plant_id, machine_group_id, code, name, status) VALUES (?, ?, ?, 'M-002', 'Machine Two', 'ACTIVE')",
        secondMachine, plantId, groupId);
    jdbc.update(
        "INSERT INTO machines (id, plant_id, machine_group_id, code, name, status) VALUES (?, ?, ?, 'M-001', 'Machine One', 'ACTIVE')",
        firstMachine, plantId, groupId);

    List<String> scopedCodes = machineRepository
        .findAllScoped(List.of(plantId), null, null, null, null,
            PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "code")))
        .stream()
        .map(MachineEntity::getCode)
        .toList();
    assertThat(scopedCodes).containsExactly("M-001", "M-002");

    List<String> unscopedCodes = machineRepository
        .findAllUnscoped(null, null, null, null,
            PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "code")))
        .stream()
        .map(MachineEntity::getCode)
        .toList();
    assertThat(unscopedCodes).containsExactly("M-001", "M-002");
  }

  @Test
  @Transactional
  @DisplayName("V17 preserves FK enforcement: deleting a plant with machines is RESTRICTed after the index drops")
  void referentialIntegrityStillEnforcedAfterV17() {
    UUID plantId = UUID.randomUUID();
    jdbc.update("INSERT INTO plants (id, code, name) VALUES (?, 'PLANT-FK', 'Plant FK')", plantId);
    UUID groupId = UUID.randomUUID();
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name) VALUES (?, ?, 'Group FK')", groupId, plantId);
    UUID machineId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO machines (id, plant_id, machine_group_id, code, name, status) VALUES (?, ?, ?, 'M-FK', 'Machine FK', 'ACTIVE')",
        machineId, plantId, groupId);

    assertThatThrownBy(() -> jdbc.update("DELETE FROM plants WHERE id = ?", plantId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private Object toRegclass(String relation) {
    return jdbc.queryForObject("SELECT to_regclass(?)", Object.class, relation);
  }

  private String indexDef(String indexName) {
    return jdbc.query(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            (rs, rowNum) -> rs.getString("indexdef"),
            indexName)
        .stream()
        .findFirst()
        .orElse(null);
  }
}
