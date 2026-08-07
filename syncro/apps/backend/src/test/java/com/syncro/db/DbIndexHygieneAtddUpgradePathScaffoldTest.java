package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class DbIndexHygieneAtddUpgradePathScaffoldTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Test
  @DisplayName("[P1] DH-P1-01 V17 applies over an existing V16 schema with seeded data intact")
  void v17UpgradePathFromV16WithSeedData() {
    migrateTo("16");

    JdbcTemplate jdbc = jdbc();
    java.util.UUID plantId = DbIndexHygieneTestData.plant(jdbc, "PLANT-A", "Plant A");
    java.util.UUID groupId = DbIndexHygieneTestData.machineGroup(jdbc, plantId, "Group A");
    java.util.UUID machineId = DbIndexHygieneTestData.machine(jdbc, plantId, groupId, "M-001", "Machine One");
    java.util.UUID categoryId = DbIndexHygieneTestData.taxonomy(jdbc, "CATEGORY", "C-01", "Alpha");
    java.util.UUID brandId = DbIndexHygieneTestData.taxonomy(jdbc, "BRAND", "B-01", "Brand");
    java.util.UUID kindId = DbIndexHygieneTestData.taxonomy(jdbc, "KIND", "K-01", "Kind");
    java.util.UUID typeId = DbIndexHygieneTestData.taxonomy(jdbc, "TYPE", "T-01", "Type");
    java.util.UUID sparepartId =
        DbIndexHygieneTestData.sparepart(jdbc, "SP-001", "Spare", machineId, categoryId, brandId, kindId, typeId);
    DbIndexHygieneTestData.installation(jdbc, machineId, sparepartId);
    java.util.UUID userId = DbIndexHygieneTestData.authUser(jdbc, "operator@syncro.dev", "MANAGE");
    DbIndexHygieneTestData.plantAssignment(jdbc, userId, plantId);

    migrateTo("17");

    assertThat(DbIndexHygieneTestData.exists(jdbc, "public.idx_machine_sparepart_installations_machine_id_sparepart_id"))
        .isFalse();
    assertThat(DbIndexHygieneTestData.exists(jdbc, "public.uq_auth_user_plant_assignments_auth_user_plant")).isFalse();
    assertThat(DbIndexHygieneTestData.exists(jdbc, "public.idx_auth_user_plant_assignments_auth_user_id")).isFalse();
    assertThat(DbIndexHygieneTestData.exists(jdbc, "public.idx_machine_groups_plant_id")).isFalse();
    assertThat(DbIndexHygieneTestData.exists(jdbc, "public.idx_machines_plant_id")).isFalse();
    assertThat(DbIndexHygieneTestData.exists(jdbc, "public.idx_sparepart_taxonomy_dimension")).isFalse();

    assertThat(DbIndexHygieneTestData.indexDef(jdbc, "idx_spareparts_code")).isNotNull().contains("USING btree (code)");
    assertThat(DbIndexHygieneTestData.indexDef(jdbc, "idx_machines_code")).isNotNull().contains("USING btree (code)");
    assertThat(DbIndexHygieneTestData.indexDef(jdbc, "idx_sparepart_taxonomy_dimension_name"))
        .isNotNull()
        .contains("USING btree (dimension, name)");

    assertThat(jdbc.queryForObject("SELECT count(*) FROM plants", Long.class)).isEqualTo(1L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM machine_groups", Long.class)).isEqualTo(1L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM machines", Long.class)).isEqualTo(1L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM spareparts", Long.class)).isEqualTo(1L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM machine_sparepart_installations", Long.class)).isEqualTo(1L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_user_plant_assignments", Long.class)).isEqualTo(1L);

    assertThat(DbIndexHygieneTestData.exists(jdbc, "public.pk_auth_user_plant_assignments")).isTrue();
    assertThat(DbIndexHygieneTestData.exists(jdbc, "public.uq_machines_plant_id_lower_code")).isTrue();
    assertThat(DbIndexHygieneTestData.exists(jdbc, "public.idx_machines_plant_id_status")).isTrue();
  }

  private void migrateTo(String version) {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target(MigrationVersion.fromVersion(version))
        .load()
        .migrate();
  }

  private JdbcTemplate jdbc() {
    var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
        postgres.getPassword());
    return new JdbcTemplate(dataSource);
  }
}
