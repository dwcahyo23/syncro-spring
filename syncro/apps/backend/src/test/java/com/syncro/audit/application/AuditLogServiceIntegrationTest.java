package com.syncro.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.audit.application.AuditLogService.AuditLogQuery;
import com.syncro.audit.api.AuditLogDtos.AuditLogEntryView;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuditLogServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private AuditLogService auditLog;

  @Autowired
  private AuditLogWriter auditLogWriter;

  @Autowired
  private PlantRepository plants;

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
  @DisplayName("2.9-SVC-001 P1 recorded entry is returned with full detail")
  void recordedEntryIsReturnedWithFullDetail() {
    var plant = plant("GM1", "Plant GM1");
    var actor = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT, plant.getId(),
        plant.getCode(), plant.getId(), null, Map.of("code", "GM1", "name", "Plant GM1"), null));

    var response = auditLog.list(actor, new AuditLogQuery(null, null, null, null, null, null, 0, 100, "createdAt,desc"));

    assertThat(response.totalElements()).isEqualTo(1);
    var entry = response.items().get(0);
    assertThat(entry.action()).isEqualTo(AuditAction.CREATE);
    assertThat(entry.entityType()).isEqualTo(AuditEntityType.PLANT);
    assertThat(entry.entityId()).isEqualTo(plant.getId());
    assertThat(entry.entityLabel()).isEqualTo("GM1");
    assertThat(entry.actorName()).isEqualTo("super_admin@syncro.dev");
    assertThat(entry.plantId()).isEqualTo(plant.getId());
    assertThat(entry.previousValue()).isNull();
    assertThat(entry.newValue()).containsEntry("code", "GM1").containsEntry("name", "Plant GM1");
    assertThat(entry.createdAt()).isNotNull();
  }

  @Test
  @DisplayName("2.9-SVC-002 P1 assigned user sees only own plant plus global entries")
  void scopedUserSeesOnlyOwnPlantPlusGlobalEntries() {
    var plant1 = plant("GM1", "Plant GM1");
    var plant2 = plant("GM2", "Plant GM2");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    auditLogWriter.record(admin, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT, plant1.getId(), "GM1",
        plant1.getId(), null, Map.of("code", "GM1"), null));
    auditLogWriter.record(admin, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT, plant2.getId(), "GM2",
        plant2.getId(), null, Map.of("code", "GM2"), null));
    auditLogWriter.record(admin, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART_TAXONOMY,
        UUID.randomUUID(), "ELEC", null, null, Map.of("code", "ELEC"), null));

    var manage = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "scoped-manage@syncro.dev");
    assign(manage, plant1);

    var response = auditLog.list(manage, new AuditLogQuery(null, null, null, null, null, null, 0, 100, "createdAt,desc"));

    assertThat(response.items()).extracting(AuditLogEntryView::entityLabel).containsExactlyInAnyOrder("GM1", "ELEC");
    assertThat(response.totalElements()).isEqualTo(2);
  }

  @Test
  @DisplayName("2.9-SVC-003 P1 EMPTY scope user sees only global entries without error")
  void emptyScopeUserSeesOnlyGlobalEntries() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    auditLogWriter.record(admin, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART_TAXONOMY,
        UUID.randomUUID(), "ELEC", null, null, Map.of("code", "ELEC"), null));
    auditLogWriter.record(admin, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT, plant.getId(), "GM1",
        plant.getId(), null, Map.of("code", "GM1"), null));

    var viewer = persistedUser(ApplicationRole.AUDITOR, "empty-scope-viewer@syncro.dev");

    var response = auditLog.list(viewer, new AuditLogQuery(null, null, null, null, null, null, 0, 100, "createdAt,desc"));

    assertThat(response.items()).extracting(AuditLogEntryView::entityLabel).containsExactly("ELEC");
  }

  @Test
  @DisplayName("2.9-SVC-004 P1 out-of-scope plant filter is rejected")
  void outOfScopePlantFilterIsRejected() {
    var plant1 = plant("GM1", "Plant GM1");
    var plant2 = plant("GM2", "Plant GM2");
    var manage = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "scoped-manage@syncro.dev");
    assign(manage, plant1);

    assertThatThrownBy(() -> auditLog.list(manage, new AuditLogQuery(null, null, null, plant2.getId(), null, null, 0, 100, "createdAt,desc")))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("2.9-SVC-005 P1 entity type, actor, plant, and date range filters apply")
  void filtersApplyIndividually() {
    var plant1 = plant("GM1", "Plant GM1");
    var plant2 = plant("GM2", "Plant GM2");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var engineer = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "yusuf@syncro.dev");
    auditLogWriter.record(admin, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT, plant1.getId(), "GM1",
        plant1.getId(), null, Map.of("code", "GM1"), null));
    auditLogWriter.record(engineer, new AuditRecord(AuditAction.CREATE, AuditEntityType.MACHINE, UUID.randomUUID(),
        "BF-08410", plant1.getId(), null, Map.of("code", "BF-08410"), null));
    auditLogWriter.record(admin, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT, plant2.getId(), "GM2",
        plant2.getId(), null, Map.of("code", "GM2"), null));

    var byType = auditLog.list(admin, new AuditLogQuery(AuditEntityType.MACHINE, null, null, null, null, null, 0, 100, "createdAt,desc"));
    assertThat(byType.totalElements()).isEqualTo(1);
    assertThat(byType.items()).extracting(AuditLogEntryView::entityLabel).containsExactly("BF-08410");

    var byActor = auditLog.list(admin, new AuditLogQuery(null, null, "YUSUF", null, null, null, 0, 100, "createdAt,desc"));
    assertThat(byActor.totalElements()).isEqualTo(1);
    assertThat(byActor.items()).extracting(AuditLogEntryView::actorName).containsExactly("yusuf@syncro.dev");

    var byPlant = auditLog.list(admin, new AuditLogQuery(null, null, null, plant2.getId(), null, null, 0, 100, "createdAt,desc"));
    assertThat(byPlant.totalElements()).isEqualTo(1);
    assertThat(byPlant.items()).extracting(AuditLogEntryView::entityLabel).containsExactly("GM2");

    var from = Instant.now().minusSeconds(1);
    var to = Instant.now().plusSeconds(1);
    var byRange = auditLog.list(admin, new AuditLogQuery(null, null, null, null, from, to, 0, 100, "createdAt,desc"));
    assertThat(byRange.totalElements()).isEqualTo(3);

    var narrow = auditLog.list(admin, new AuditLogQuery(null, null, null, null, Instant.parse("2030-01-01T00:00:00Z"), null, 0, 100, "createdAt,desc"));
    assertThat(narrow.totalElements()).isZero();
  }

  @Test
  @DisplayName("2.9-SVC-006 P1 entries are ordered newest-first by default")
  void entriesAreOrderedNewestFirst() throws Exception {
    var plant = plant("GM1", "Plant GM1");
    var actor = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT, plant.getId(), "GM1",
        plant.getId(), null, Map.of("code", "GM1"), null));
    Thread.sleep(5);
    auditLogWriter.record(actor, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PLANT, plant.getId(), "GM1",
        plant.getId(), Map.of("code", "GM1"), Map.of("code", "GM1", "name", "Plant GM1 Updated"), null));

    var response = auditLog.list(actor, new AuditLogQuery(null, null, null, null, null, null, 0, 100, "createdAt,desc"));

    assertThat(response.totalElements()).isEqualTo(2);
    assertThat(response.sort()).isEqualTo("createdAt,desc");
    assertThat(response.items()).extracting(AuditLogEntryView::action).containsExactly(AuditAction.UPDATE, AuditAction.CREATE);
  }

  @Test
  @DisplayName("2.9-SVC-007 P1 DB trigger rejects UPDATE on audit_log")
  void dbTriggerRejectsUpdate() {
    var plant = plant("GM1", "Plant GM1");
    var actor = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT, plant.getId(), "GM1",
        plant.getId(), null, Map.of("code", "GM1"), null));
    entityManager.flush();
    var auditId = auditRowId(plant.getId());

    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE audit_log SET actor_name = 'hacker' WHERE id = ?", auditId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @DisplayName("2.9-SVC-008 P1 DB trigger rejects DELETE on audit_log")
  void dbTriggerRejectsDelete() {
    var plant = plant("GM1", "Plant GM1");
    var actor = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    auditLogWriter.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT, plant.getId(), "GM1",
        plant.getId(), null, Map.of("code", "GM1"), null));
    entityManager.flush();
    var auditId = auditRowId(plant.getId());

    assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM audit_log WHERE id = ?", auditId))
        .isInstanceOf(DataAccessException.class);
  }

  private String auditRowId(UUID entityId) {
    return jdbcTemplate.queryForObject("SELECT id::text FROM audit_log WHERE entity_id = ?", String.class, entityId);
  }

  private PlantEntity plant(String code, String name) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    return plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), code, name, now, now));
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
