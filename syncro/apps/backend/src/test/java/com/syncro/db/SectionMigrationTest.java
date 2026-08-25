package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration evidence for V42 (story 9-1): sections table with plant-scoped code and
 * lower(name) uniqueness, the code CHECK, name-not-blank, nullable machine_groups.section_id
 * plus index, and the audit_log entity_type CHECK re-added with SECTION.
 */
class SectionMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-25T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("V42 creates the sections table with the pinned columns")
  void sectionsTableExistsWithPinnedColumns() {
    var columns = jdbc.queryForList("""
        SELECT column_name, is_nullable FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'sections'
        """);
    assertThat(columns).extracting(row -> row.get("column_name"))
        .contains("id", "plant_id", "code", "name", "active", "version", "created_at", "updated_at");

    // machine_groups gains a nullable section_id referencing sections (V42).
    var sectionIdNullable = jdbc.queryForObject(
        "SELECT is_nullable FROM information_schema.columns "
            + "WHERE table_name = 'machine_groups' AND column_name = 'section_id'",
        String.class);
    assertThat(sectionIdNullable).as("machine_groups.section_id must be nullable").isEqualTo("YES");
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM information_schema.columns WHERE table_name = 'sections' AND column_name = 'section_id'",
        Long.class)).as("sections itself has no section_id column").isEqualTo(0L);
  }

  @Test
  @DisplayName("V42 enforces plant-scoped code uniqueness")
  void plantCodeUniqueEnforced() {
    UUID plantId = seedPlant();
    insertSection(plantId, "MACHINERY", "Machinery");

    assertThatThrownBy(() -> insertSection(plantId, "MACHINERY", "Another Machinery"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V42 enforces plant-scoped lower(name) uniqueness")
  void plantLowerNameUniqueEnforced() {
    UUID plantId = seedPlant();
    insertSection(plantId, "MACHINERY", "Machinery");

    assertThatThrownBy(() -> insertSection(plantId, "UTILITY", "machinery"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V42 code CHECK accepts the three values and rejects others")
  void codeCheckEnforced() {
    UUID plantId = seedPlant();
    insertSection(plantId, "MACHINERY", "Machinery");
    insertSection(plantId, "UTILITY", "Utility");
    insertSection(plantId, "WORKSHOP", "Workshop");

    assertThatThrownBy(() -> insertSection(plantId, "ELECTRICAL", "Electrical"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V42 name-not-blank CHECK rejects blank names")
  void nameNotBlankCheckEnforced() {
    UUID plantId = seedPlant();

    assertThatThrownBy(() -> insertSection(plantId, "MACHINERY", "  "))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V42 adds a nullable machine_groups.section_id column with an index")
  void machineGroupsSectionIdColumnExists() {
    assertThat(jdbc.queryForObject(
        "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'machine_groups' AND column_name = 'section_id'",
        String.class)).isEqualTo("YES");

    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'machine_groups'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_machine_groups_section_id");
  }

  @Test
  @DisplayName("V42 audit_log entity_type CHECK accepts SECTION")
  void auditEntityTypeAcceptsSection() {
    var plantId = seedPlant();
    var sectionId = insertSection(plantId, "MACHINERY", "Machinery");
    UUID actorId = UUID.randomUUID();

    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), actorId, "audit-actor", "CREATE", "SECTION", sectionId, "MACHINERY",
        plantId, null, null, TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'SECTION' AND entity_id = ?",
        Long.class, sectionId)).isEqualTo(1L);
  }

  @Test
  @DisplayName("V42 audit_log entity_type CHECK still rejects unknown types")
  void auditEntityTypeRejectsUnknown() {
    UUID plantId = seedPlant();

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "NOT_A_TYPE", UUID.randomUUID(),
        "x", plantId, null, null, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UUID seedPlant() {
    UUID plantId = UUID.randomUUID();
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        plantId, "P-" + seedSeq.incrementAndGet(), "Plant", TS, TS);
    return plantId;
  }

  private UUID insertSection(UUID plantId, String code, String name) {
    UUID sectionId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO sections (id, plant_id, code, name, active, version, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, sectionId, plantId, code, name, true, 0, TS, TS);
    return sectionId;
  }
}
