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
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.api.MachineResponsibilityDtos.CreateMachineResponsibilityRequest;
import com.syncro.machine.api.MachineResponsibilityDtos.UpdateMachineResponsibilityRequest;
import com.syncro.machine.application.MachineResponsibilityService;
import com.syncro.machine.application.MachineService;
import com.syncro.machine.application.MachineService.MachineCommand;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.masterdata.application.MachineGroupService;
import com.syncro.masterdata.application.MachineGroupService.CreateMachineGroupCommand;
import com.syncro.masterdata.application.PlantService;
import com.syncro.masterdata.application.PlantService.CreatePlantCommand;
import com.syncro.masterdata.application.PlantService.DuplicatePlantCodeException;
import com.syncro.sparepart.application.MachineSparepartInstallationService;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationCommand;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationUpdateCommand;
import com.syncro.sparepart.application.SparepartService;
import com.syncro.sparepart.application.SparepartService.SparepartCommand;
import com.syncro.sparepart.application.SparepartTaxonomyService;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyCommand;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class AuditLogWiringIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private PlantService plants;

  @Autowired
  private MachineGroupService machineGroups;

  @Autowired
  private MachineService machines;

  @Autowired
  private SparepartTaxonomyService taxonomy;

  @Autowired
  private SparepartService spareparts;

  @Autowired
  private MachineSparepartInstallationService installations;

  @Autowired
  private MachineResponsibilityService responsibilities;

  @Autowired
  private AuditLogService auditLog;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private AuditLogWriter auditWriter;

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  @DisplayName("DW-103 regression: recordSystem with AuditEntityType.ALERT persists without DataIntegrityViolationException (V31 fix)")
  void alertAuditWritePersists() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "alert-writer@syncro.dev");
    UUID alertId = UUID.randomUUID();

    auditWriter.recordSystem(new AuditRecord(
        AuditAction.CREATE,
        AuditEntityType.ALERT,
        alertId,
        "alert-writer-label",
        null,
        null,
        Map.of("thresholdPercentage", 90)));

    var response = auditLog.list(admin,
        new AuditLogQuery(AuditEntityType.ALERT, null, null, null, null, null, 0, 100, "createdAt,asc"));
    assertThat(response.items())
        .anySatisfy(entry -> {
          assertThat(entry.entityType()).isEqualTo(AuditEntityType.ALERT);
          assertThat(entry.entityId()).isEqualTo(alertId);
          assertThat(entry.actorName()).isEqualTo("SYSTEM");
          assertThat(entry.newValue()).containsEntry("thresholdPercentage", 90);
        });
  }

  @Test
  @DisplayName("DW-103 regression: ck_audit_log_entity_type constraint includes 'ALERT' after V31")
  void auditLogEntityTypeConstraintAllowsAlert() {
    String def = jdbc.queryForObject("""
        SELECT pg_get_constraintdef(oid) FROM pg_constraint
        WHERE conname = 'ck_audit_log_entity_type'
          AND conrelid = 'audit_log'::regclass
        """, String.class);

    assertThat(def).isNotNull().contains("'ALERT'");
  }

  @Test
  @DisplayName("2.9-SVC-009 P2 plant create/update/delete each record an entry")
  void plantMutationsAreAudited() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "wiring-admin@syncro.dev");
    var created = plants.create(admin, new CreatePlantCommand("GM1", "Plant GM1"));

    var createEntry = entry(admin, AuditEntityType.PLANT, AuditAction.CREATE);
    assertThat(createEntry.actorName()).isEqualTo("wiring-admin@syncro.dev");
    assertThat(createEntry.entityLabel()).isEqualTo("GM1");
    assertThat(createEntry.plantId()).isEqualTo(created.id());
    assertThat(createEntry.previousValue()).isNull();
    assertThat(createEntry.newValue()).containsEntry("code", "GM1").containsEntry("name", "Plant GM1");

    plants.update(admin, created.id(), new CreatePlantCommand("GM1", "Plant GM1 Updated"));

    var updateEntry = entry(admin, AuditEntityType.PLANT, AuditAction.UPDATE);
    assertThat(updateEntry.plantId()).isEqualTo(created.id());
    assertThat(updateEntry.previousValue()).containsEntry("name", "Plant GM1");
    assertThat(updateEntry.newValue()).containsEntry("name", "Plant GM1 Updated");

    plants.delete(admin, created.id());

    var deleteEntry = entry(admin, AuditEntityType.PLANT, AuditAction.DELETE);
    assertThat(deleteEntry.previousValue()).containsEntry("name", "Plant GM1 Updated");
    assertThat(deleteEntry.newValue()).isNull();
  }

  @Test
  @DisplayName("2.9-SVC-010 P2 machine group create/update/delete each record an entry")
  void machineGroupMutationsAreAudited() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "wiring-admin@syncro.dev");
    var plantView = plants.create(admin, new CreatePlantCommand("GM1", "Plant GM1"));
    var created = machineGroups.create(admin, new CreateMachineGroupCommand(plantView.id(), "Forming"));

    var createEntry = entry(admin, AuditEntityType.MACHINE_GROUP, AuditAction.CREATE);
    assertThat(createEntry.entityLabel()).isEqualTo("Forming");
    assertThat(createEntry.plantId()).isEqualTo(plantView.id());
    assertThat(createEntry.newValue()).containsEntry("name", "Forming");

    machineGroups.update(admin, created.id(), new CreateMachineGroupCommand(plantView.id(), "Forming Line"));

    var updateEntry = entry(admin, AuditEntityType.MACHINE_GROUP, AuditAction.UPDATE);
    assertThat(updateEntry.previousValue()).containsEntry("name", "Forming");
    assertThat(updateEntry.newValue()).containsEntry("name", "Forming Line");

    machineGroups.delete(admin, created.id());

    var deleteEntry = entry(admin, AuditEntityType.MACHINE_GROUP, AuditAction.DELETE);
    assertThat(deleteEntry.previousValue()).containsEntry("name", "Forming Line");
    assertThat(deleteEntry.newValue()).isNull();
  }

  @Test
  @DisplayName("2.9-SVC-011 P2 manage user machine status update records one entry with before and after")
  void machineMutationsAreAudited() {
    var manage = persistedUser(ApplicationRole.MANAGE, "wiring-manage@syncro.dev");
    var plantView = plants.create(manage, new CreatePlantCommand("GM1", "Plant GM1"));
    var groupView = machineGroups.create(manage, new CreateMachineGroupCommand(plantView.id(), "Forming"));
    var machineView = machines.create(manage, new MachineCommand(plantView.id(), groupView.id(), "BF-08410",
        "Blow Forming Line 1", MachineStatus.ACTIVE, "KHS", LocalDate.parse("2024-01-15"), null, List.of()));

    var createEntry = entry(manage, AuditEntityType.MACHINE, AuditAction.CREATE);
    assertThat(createEntry.actorName()).isEqualTo("wiring-manage@syncro.dev");
    assertThat(createEntry.entityLabel()).isEqualTo("BF-08410");
    assertThat(createEntry.plantId()).isEqualTo(plantView.id());
    assertThat(createEntry.newValue()).containsEntry("code", "BF-08410")
        .containsEntry("status", "ACTIVE")
        .containsEntry("brand", "KHS")
        .containsEntry("installedAt", "2024-01-15");

    machines.update(manage, machineView.id(), new MachineCommand(plantView.id(), groupView.id(), "BF-08410",
        "Blow Forming Line 1", MachineStatus.INACTIVE, "KHS", LocalDate.parse("2024-01-15"), null, List.of()));

    var updateEntry = entry(manage, AuditEntityType.MACHINE, AuditAction.UPDATE);
    assertThat(updateEntry.plantId()).isEqualTo(plantView.id());
    assertThat(updateEntry.previousValue()).containsEntry("status", "ACTIVE");
    assertThat(updateEntry.newValue()).containsEntry("status", "INACTIVE");

    machines.delete(manage, machineView.id());

    var deleteEntry = entry(manage, AuditEntityType.MACHINE, AuditAction.DELETE);
    assertThat(deleteEntry.previousValue()).containsEntry("status", "INACTIVE");
    assertThat(deleteEntry.newValue()).isNull();
  }

  @Test
  @DisplayName("2.9-SVC-012 P2 sparepart taxonomy mutations record entries without a plant")
  void sparepartTaxonomyMutationsAreAudited() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "wiring-admin@syncro.dev");
    var category = taxonomy.create(admin, new SparepartTaxonomyCommand(SparepartTaxonomyDimension.CATEGORY, "ELECTRONIC", "Electronic", null));

    var createEntry = entry(admin, AuditEntityType.SPAREPART_TAXONOMY, AuditAction.CREATE);
    assertThat(createEntry.entityLabel()).isEqualTo("ELECTRONIC");
    assertThat(createEntry.plantId()).isNull();
    assertThat(createEntry.newValue()).containsEntry("dimension", "CATEGORY").containsEntry("code", "ELECTRONIC").containsEntry("categoryCode", null);

    taxonomy.update(admin, category.id(), new SparepartTaxonomyCommand(SparepartTaxonomyDimension.CATEGORY, "ELECTRONIC", "Electrical", null));

    var updateEntry = entry(admin, AuditEntityType.SPAREPART_TAXONOMY, AuditAction.UPDATE);
    assertThat(updateEntry.previousValue()).containsEntry("name", "Electronic");
    assertThat(updateEntry.newValue()).containsEntry("name", "Electrical");

    taxonomy.delete(admin, category.id());

    var deleteEntry = entry(admin, AuditEntityType.SPAREPART_TAXONOMY, AuditAction.DELETE);
    assertThat(deleteEntry.previousValue()).containsEntry("code", "ELECTRONIC");
    assertThat(deleteEntry.newValue()).isNull();
  }

  @Test
  @DisplayName("2.9-SVC-013 P2 sparepart mutations record entries scoped to the machine plant")
  void sparepartMutationsAreAudited() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "wiring-admin@syncro.dev");
    var machineView = machineChain(admin);
    var category = taxonomy.create(admin, new SparepartTaxonomyCommand(SparepartTaxonomyDimension.CATEGORY, "ELECTRONIC", "Electronic", null));
    var brand = taxonomy.create(admin, new SparepartTaxonomyCommand(SparepartTaxonomyDimension.BRAND, "WECON", "Wecon", category.id()));
    var kind = taxonomy.create(admin, new SparepartTaxonomyCommand(SparepartTaxonomyDimension.KIND, "PLC", "PLC", category.id()));
    var type = taxonomy.create(admin, new SparepartTaxonomyCommand(SparepartTaxonomyDimension.TYPE, "LX5", "LX5", category.id()));
    var created = spareparts.create(admin, new SparepartCommand(machineView.id(), category.id(), brand.id(), kind.id(), type.id()));

    var createEntry = entry(admin, AuditEntityType.SPAREPART, AuditAction.CREATE);
    assertThat(createEntry.entityLabel()).isEqualTo(created.code());
    assertThat(createEntry.plantId()).isEqualTo(machineView.plantId());
    assertThat(createEntry.newValue()).containsEntry("machineCode", "BF-08410")
        .containsEntry("categoryCode", "ELECTRONIC")
        .containsEntry("brandCode", "WECON");

    spareparts.update(admin, created.id(), new SparepartCommand(machineView.id(), category.id(), brand.id(), kind.id(), type.id()));

    var updateEntry = entry(admin, AuditEntityType.SPAREPART, AuditAction.UPDATE);
    assertThat(updateEntry.plantId()).isEqualTo(machineView.plantId());
    assertThat(updateEntry.previousValue()).containsEntry("code", created.code());
    assertThat(updateEntry.newValue()).containsEntry("code", created.code());

    spareparts.delete(admin, created.id());

    var deleteEntry = entry(admin, AuditEntityType.SPAREPART, AuditAction.DELETE);
    assertThat(deleteEntry.previousValue()).containsEntry("code", created.code());
    assertThat(deleteEntry.newValue()).isNull();
  }

  @Test
  @DisplayName("2.9-SVC-014 P2 installation mutations record entries including threshold edits")
  void installationMutationsAreAudited() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "wiring-admin@syncro.dev");
    var machineView = machineChain(admin);
    var sparepartView = sparepartChain(admin, machineView.id());
    var created = installations.create(admin, new InstallationCommand(machineView.id(), sparepartView.id(),
        "Primary", 1_000_000L, 1_200L, 90));

    var createEntry = entry(admin, AuditEntityType.INSTALLATION, AuditAction.CREATE);
    assertThat(createEntry.entityLabel()).isEqualTo("BF-08410 / " + sparepartView.code());
    assertThat(createEntry.plantId()).isEqualTo(machineView.plantId());
    assertThat(createEntry.newValue()).containsEntry("functionName", "Primary").containsEntry("thresholdPercentage", 90);

    installations.update(admin, created.id(), new InstallationUpdateCommand("Primary", 1_000_000L, 1_200L, 80));

    var updateEntry = entry(admin, AuditEntityType.INSTALLATION, AuditAction.UPDATE);
    assertThat(updateEntry.previousValue()).containsEntry("thresholdPercentage", 90);
    assertThat(updateEntry.newValue()).containsEntry("thresholdPercentage", 80);

    installations.delete(admin, created.id());

    var deleteEntry = entry(admin, AuditEntityType.INSTALLATION, AuditAction.DELETE);
    assertThat(deleteEntry.previousValue()).containsEntry("functionName", "Primary");
    assertThat(deleteEntry.newValue()).isNull();
  }

  @Test
  @DisplayName("2.9-SVC-015 P2 responsibility assign/update/unassign each record an entry")
  void responsibilityMutationsAreAudited() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "wiring-admin@syncro.dev");
    var machineView = machineChain(admin);
    var technician = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), "technician@syncro.dev",
        passwordEncoder.encode("syncro-test-password"), ApplicationRole.VIEWER, true, Instant.parse("2026-05-28T00:00:00Z"),
        Instant.parse("2026-05-28T00:00:00Z")));
    var assigned = responsibilities.assign(admin, new CreateMachineResponsibilityRequest(machineView.id(), technician.getId(), ResponsibilityLevel.TECHNICIAN));

    var createEntry = entry(admin, AuditEntityType.RESPONSIBILITY, AuditAction.CREATE);
    assertThat(createEntry.entityLabel()).isEqualTo("technician@syncro.dev");
    assertThat(createEntry.plantId()).isEqualTo(machineView.plantId());
    assertThat(createEntry.newValue()).containsEntry("level", "TECHNICIAN");

    responsibilities.update(admin, assigned.id(), new UpdateMachineResponsibilityRequest(ResponsibilityLevel.LEADER));

    var updateEntry = entry(admin, AuditEntityType.RESPONSIBILITY, AuditAction.UPDATE);
    assertThat(updateEntry.previousValue()).containsEntry("level", "TECHNICIAN");
    assertThat(updateEntry.newValue()).containsEntry("level", "LEADER");

    responsibilities.unassign(admin, assigned.id());

    var deleteEntry = entry(admin, AuditEntityType.RESPONSIBILITY, AuditAction.DELETE);
    assertThat(deleteEntry.previousValue()).containsEntry("level", "LEADER");
    assertThat(deleteEntry.newValue()).isNull();
  }

  @Test
  @DisplayName("2.9-SVC-016 P2 a failed mutation writes no audit entry")
  void failedMutationWritesNoAuditEntry() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "wiring-admin@syncro.dev");
    plants.create(admin, new CreatePlantCommand("GM1", "Plant GM1"));

    assertThatThrownBy(() -> plants.create(admin, new CreatePlantCommand("gm1", "Duplicate")))
        .isInstanceOf(DuplicatePlantCodeException.class);

    var response = auditLog.list(admin, new AuditLogQuery(AuditEntityType.PLANT, null, null, null, null, null, 0, 100, "createdAt,asc"));
    assertThat(response.totalElements()).isEqualTo(1);
  }

  private com.syncro.machine.application.MachineService.MachineView machineChain(AuthenticatedUser admin) {
    var plantView = plants.create(admin, new CreatePlantCommand("GM1", "Plant GM1"));
    var groupView = machineGroups.create(admin, new CreateMachineGroupCommand(plantView.id(), "Forming"));
    return machines.create(admin, new MachineCommand(plantView.id(), groupView.id(), "BF-08410",
        "Blow Forming Line 1", MachineStatus.ACTIVE, "KHS", LocalDate.parse("2024-01-15"), null, List.of()));
  }

  private com.syncro.sparepart.application.SparepartService.SparepartView sparepartChain(AuthenticatedUser admin, UUID machineId) {
    var category = taxonomy.create(admin, new SparepartTaxonomyCommand(SparepartTaxonomyDimension.CATEGORY, "ELECTRONIC", "Electronic", null));
    var brand = taxonomy.create(admin, new SparepartTaxonomyCommand(SparepartTaxonomyDimension.BRAND, "WECON", "Wecon", category.id()));
    var kind = taxonomy.create(admin, new SparepartTaxonomyCommand(SparepartTaxonomyDimension.KIND, "PLC", "PLC", category.id()));
    var type = taxonomy.create(admin, new SparepartTaxonomyCommand(SparepartTaxonomyDimension.TYPE, "LX5", "LX5", category.id()));
    return spareparts.create(admin, new SparepartCommand(machineId, category.id(), brand.id(), kind.id(), type.id()));
  }

  private AuditLogEntryView entry(AuthenticatedUser user, AuditEntityType type, AuditAction action) {
    var response = auditLog.list(user, new AuditLogQuery(type, null, null, null, null, null, 0, 100, "createdAt,asc"));
    return response.items().stream().filter(entry -> entry.action() == action).findFirst().orElseThrow();
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), loginIdentifier,
        passwordEncoder.encode("syncro-test-password"), role, true, now, now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }
}
