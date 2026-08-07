package com.syncro.db;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class DbIndexHygieneTestData {
  private DbIndexHygieneTestData() {}

  static UUID plant(JdbcTemplate jdbc, String code, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO plants (id, code, name) VALUES (?, ?, ?)", id, code, name);
    return id;
  }

  static UUID machineGroup(JdbcTemplate jdbc, UUID plantId, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name) VALUES (?, ?, ?)", id, plantId, name);
    return id;
  }

  static UUID machine(JdbcTemplate jdbc, UUID plantId, UUID groupId, String code, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO machines (id, plant_id, machine_group_id, code, name, status) VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
        id, plantId, groupId, code, name);
    return id;
  }

  static UUID taxonomy(JdbcTemplate jdbc, String dimension, String code, String name) {
    UUID id = UUID.randomUUID();
    if ("CATEGORY".equals(dimension)) {
      jdbc.update(
          "INSERT INTO sparepart_taxonomy (id, dimension, code, name) VALUES (?, ?, ?, ?)",
          id, dimension, code, name);
    } else {
      UUID categoryId = jdbc.queryForObject(
          "SELECT id FROM sparepart_taxonomy WHERE dimension = 'CATEGORY' LIMIT 1", UUID.class);
      if (categoryId == null) {
        throw new IllegalStateException("Cannot link " + dimension + " row without an existing CATEGORY row");
      }
      jdbc.update(
          "INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id) VALUES (?, ?, ?, ?, ?)",
          id, dimension, code, name, categoryId);
    }
    return id;
  }

  static UUID sparepart(
      JdbcTemplate jdbc, String code, String name, UUID machineId,
      UUID categoryId, UUID brandId, UUID kindId, UUID typeId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO spareparts (id, code, name, machine_id, category_id, brand_id, kind_id, type_id)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        id, code, name, machineId, categoryId, brandId, kindId, typeId);
    return id;
  }

  static UUID installation(JdbcTemplate jdbc, UUID machineId, UUID sparepartId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO machine_sparepart_installations"
            + " (id, machine_id, sparepart_id, expected_production_count, baseline_counter, threshold_percentage, installed_at)"
            + " VALUES (?, ?, ?, 10, 0, 90, NOW())",
        id, machineId, sparepartId);
    return id;
  }

  static UUID authUser(JdbcTemplate jdbc, String loginIdentifier, String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO auth_users (id, login_identifier, password_hash, application_role) VALUES (?, ?, 'x', ?)",
        id, loginIdentifier, role);
    return id;
  }

  static void plantAssignment(JdbcTemplate jdbc, UUID userId, UUID plantId) {
    jdbc.update("INSERT INTO auth_user_plant_assignments (auth_user_id, plant_id) VALUES (?, ?)", userId, plantId);
  }

  static boolean exists(JdbcTemplate jdbc, String relation) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, relation));
  }

  static String indexDef(JdbcTemplate jdbc, String indexName) {
    return jdbc.queryForObject(
        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
        String.class,
        indexName);
  }
}
