package com.syncro.maintenance.preventive.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.UserSignatureEntity;
import com.syncro.auth.infrastructure.UserSignatureRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.maintenance.preventive.application.PmChecklistService.CreateCategoryCommand;
import com.syncro.maintenance.preventive.application.PmChecklistService.CreateItemCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ApproveChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.CreateChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmExecutionService.ExecutionView;
import com.syncro.maintenance.preventive.application.PmExecutionService.FillExecutionCommand;
import com.syncro.maintenance.preventive.application.PmExecutionService.PmExecutionForbiddenException;
import com.syncro.maintenance.preventive.application.PmExecutionService.PmExecutionNotFoundException;
import com.syncro.maintenance.preventive.application.PmScheduleService.CreateScheduleCommand;
import com.syncro.maintenance.preventive.domain.PmItemInputType;
import com.syncro.maintenance.preventive.infrastructure.db.PmExecutionEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmExecutionRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 19-6 integration test: the PM preventive print report (FR-133) against a
 * real Postgres container. Covers every row of the spec's I/O matrix: the full
 * completed+verified report (machine/plant header, sequence-ordered rows with
 * bounds+actual+ok/ng, presigned NG photo, both signature blocks with resolved
 * display names and the login_identifier fallback), the unsigned execution (null
 * blocks, RUNNING), the deleted signature object (200 with null URL), the external
 * ng_photo_url (echoed verbatim, never presigned), the out-of-scope reader (403),
 * and the unknown id (404).
 *
 * <p>Non-transactional with a dedicated database (19-5 pattern): the fixture chain
 * runs the real 19-1..19-5 services end-to-end. ObjectStorageService is mocked —
 * presigning is the only storage touch, and its failure-skip behavior is exactly
 * what the deleted-object case must prove.
 */
@Testcontainers
@SpringBootTest(properties = {
    "server.port=0",
    "spring.lifecycle.timeout-per-shutdown-phase=5s",
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
    "OPA_HOST=localhost",
    "OPA_PORT=18181",
    "SYNCRO_OPA_URL=http://localhost:18181",
    "SYNCRO_AUTHZ_ENFORCED_PATHS=",
    "SYNCRO_AUTHZ_DEGRADED_ALLOWLIST=/api/v1/health,/actuator/**",
    "syncro.auth.jwt.secret=test-secret-for-auth-integration-32x",
    "syncro.auth.jwt.issuer=syncro-test",
    "syncro.auth.jwt.ttl-minutes=30",
    "syncro.auth.local-admin.enabled=false",
    "syncro.auth.local-admin.login-identifier=admin@syncro.dev",
    "syncro.auth.local-admin.password=test-password"
})
class PmExecutionReportServiceIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withDatabaseName("pm_execution_report_integration");
    postgres.withReuse(true);
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @MockitoBean
  private ObjectStorageService objectStorage;

  @Autowired
  private PmExecutionReportService reports;
  @Autowired
  private PmExecutionService executions;
  @Autowired
  private PmWorkOrderService workOrders;
  @Autowired
  private PmScheduleService schedules;
  @Autowired
  private PmChecksheetService checksheets;
  @Autowired
  private PmChecklistService checklist;
  @Autowired
  private PmFrequencyService frequencies;
  @Autowired
  private PlantRepository plants;
  @Autowired
  private MachineGroupRepository machineGroups;
  @Autowired
  private MachineRepository machines;
  @Autowired
  private AuthUserRepository users;
  @Autowired
  private AuthUserPlantAssignmentRepository assignments;
  @Autowired
  private MachineResponsibilityRepository responsibilities;
  @Autowired
  private UserSignatureRepository userSignatures;
  @Autowired
  private PmExecutionRepository executionRows;

  /** One fully-wired fixture: ACTIVE monthly schedule, checklist items, one IN_PROGRESS WO. */
  private record Fixture(PlantEntity plant, MachineEntity machine,
      AuthenticatedUser leader, AuthenticatedUser staff, AuthenticatedUser tech,
      UUID technicianId, UUID leaderId, UUID checksheetId, UUID measurementItemId,
      UUID okNgItemId, UUID beltItemId, UUID glassItemId, UUID scheduleId,
      PmWorkOrderService.WorkOrderView workOrder) {
  }

  private Fixture fixture() {
    var now = Instant.now();
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var plant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "P-RPT-" + suffix, "Report Plant", now, now));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Report Group", now, now));
    var machine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-RPT-" + suffix, "Report Machine",
        MachineStatus.ACTIVE, null, null, null, null, now, now));

    var leaderId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(leaderId, "rpt-leader-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SECTION_LEADER, true, now, now));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(leaderId, plant.getId(), now));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), machine.getId(), leaderId, ResponsibilityLevel.LEADER, now, now));
    var leader = new AuthenticatedUser(leaderId.toString(), "rpt-leader@test",
        ApplicationRole.SECTION_LEADER);

    var staffId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(staffId, "rpt-staff-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.STAFF_MAINTENANCE, true, now, now));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(staffId, plant.getId(), now));
    var staff = new AuthenticatedUser(staffId.toString(), "rpt-staff@test",
        ApplicationRole.STAFF_MAINTENANCE);

    var technicianId = UUID.randomUUID();
    var techUser = new AuthUserEntity(technicianId, "rpt-tech-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.TECHNICIAN, true, now, now);
    techUser.updateMasterFields("Tech One", null, null, null, null, now);
    users.saveAndFlush(techUser);
    var tech = new AuthenticatedUser(technicianId.toString(), "rpt-tech@test",
        ApplicationRole.TECHNICIAN);

    var monthly = frequencies.list().stream()
        .filter(f -> f.code().equals("MONTHLY")).findFirst().orElseThrow().id();
    var checksheetId = checksheets.create(staff,
        new CreateChecksheetCommand(machine.getId(), monthly, null)).id();
    var categoryId = checklist.createCategory(staff,
        new CreateCategoryCommand(checksheetId, "Hydraulics", 0)).id();
    var measurementItemId = checklist.createItem(staff, new CreateItemCommand(checksheetId,
        categoryId, 1, "Oil pressure", "Gauge", PmItemInputType.MEASUREMENT, "bar",
        new BigDecimal("3.0"), new BigDecimal("5.0"), new BigDecimal("7.0"), false,
        null, null)).id();
    var okNgItemId = checklist.createItem(staff, new CreateItemCommand(checksheetId,
        categoryId, 2, "Visual leak check", "Eyes", PmItemInputType.OK_NG, null,
        null, null, null, false, null, null)).id();
    // Extra OK_NG rows so one execution can carry every ng_photo_url classification
    // (external scheme, protocol-relative, domain-like, flat dotted key) at once.
    var beltItemId = checklist.createItem(staff, new CreateItemCommand(checksheetId,
        categoryId, 3, "Belt tension", "Tensiometer", PmItemInputType.OK_NG, null,
        null, null, null, false, null, null)).id();
    var glassItemId = checklist.createItem(staff, new CreateItemCommand(checksheetId,
        categoryId, 4, "Sight glass", "Eyes", PmItemInputType.OK_NG, null,
        null, null, null, false, null, null)).id();
    checksheets.approve(leader, checksheetId,
        new ApproveChecksheetCommand(LocalDate.of(2026, 1, 15)));

    var scheduleId = schedules.create(staff,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027)).id();
    schedules.submit(staff, scheduleId);
    schedules.approveSpv(leader, scheduleId);
    schedules.approveProd(leader, scheduleId);
    schedules.activate(leader, scheduleId);
    var generated = workOrders.generate(leader, scheduleId);
    var wo = generated.getFirst();
    workOrders.assign(leader, wo.id(), technicianId);
    var started = workOrders.start(tech, wo.id());
    return new Fixture(plant, machine, leader, staff, tech, technicianId, leaderId, checksheetId,
        measurementItemId, okNgItemId, beltItemId, glassItemId, scheduleId, started);
  }

  /** Runs the real 19-5 chain to a completed+verified execution with one NG photo item. */
  private ExecutionView completedVerified(Fixture fx) {
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        new FillExecutionCommand(new BigDecimal("5.2"), null, null, null, null, false, null));
    executions.fill(fx.tech(), execution.id(), fx.okNgItemId(),
        new FillExecutionCommand(null, null, true, "Weep at seal", "pm/evidence/ng-1.jpg",
            false, null));
    executions.complete(fx.tech(), execution.id());
    return executions.verify(fx.leader(), execution.id(), null);
  }

  private UUID seedSignature(UUID userId, String objectKey) {
    var now = Instant.now();
    return userSignatures.saveAndFlush(new UserSignatureEntity(UUID.randomUUID(), userId,
        "test", objectKey, "image/png", 0, null, now, now)).getId();
  }

  @Test
  @DisplayName("19.6-INT-001 P0 full report: header + sequence-ordered rows + presigned NG photo + both signature blocks")
  void fullReport() {
    var fx = fixture();
    var techSigId = seedSignature(fx.technicianId(), "signatures/tech.png");
    var spvSigId = seedSignature(fx.leaderId(), "signatures/leader.png");
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        new FillExecutionCommand(new BigDecimal("5.2"), null, null, null, null, false, null));
    executions.fill(fx.tech(), execution.id(), fx.okNgItemId(),
        new FillExecutionCommand(null, null, true, "Weep at seal", "pm/evidence/ng-1.jpg",
            false, null));
    // Blocked row (R3): technician-declared block must land on the report row.
    executions.fill(fx.tech(), execution.id(), fx.beltItemId(),
        new FillExecutionCommand(null, null, true, "Belt guard blocking access", null, true,
            "WO-2609-00042"));
    executions.complete(fx.tech(), execution.id());
    execution = executions.verify(fx.leader(), execution.id(), null);
    // verify() ran without a signature id; re-verify state is terminal, so stamp both
    // signature ids directly (the report reads whatever the row carries).
    var now = Instant.now();
    var e = executionRows.findById(execution.id()).orElseThrow();
    executionRows.saveAndFlush(new PmExecutionEntity(e.getId(), e.getPmWoId(),
        e.getScheduleDateId(), e.getTechnicianId(), e.getSpvVerifierId(), techSigId, now,
        spvSigId, e.getSpvSignedAt(), e.getStartedAt(), e.getCompletedAt(), e.hasNgItems(),
        e.getNgCount(), e.getFindingWoId(), e.getCreatedAt(), now));
    when(objectStorage.presignGetUrl("signatures/tech.png"))
        .thenReturn("https://garage/tech.png");
    when(objectStorage.presignGetUrl("signatures/leader.png"))
        .thenReturn("https://garage/leader.png");
    when(objectStorage.presignGetUrl("pm/evidence/ng-1.jpg"))
        .thenReturn("https://garage/ng.jpg");

    var report = reports.get(fx.tech(), execution.id());

    var header = report.header();
    assertThat(header.executionId()).isEqualTo(execution.id());
    assertThat(header.pmWoId()).isEqualTo(fx.workOrder().id());
    assertThat(header.machineId()).isEqualTo(fx.machine().getId());
    assertThat(header.machineCode()).isEqualTo(fx.machine().getCode());
    assertThat(header.machineName()).isEqualTo("Report Machine");
    assertThat(header.plantCode()).isEqualTo(fx.plant().getCode());
    assertThat(header.scheduledDate()).isEqualTo(LocalDate.of(2027, 1, 15));
    assertThat(header.frequencyCode()).isEqualTo("MONTHLY");
    assertThat(header.frequencyName()).isEqualTo("Monthly");
    assertThat(header.checksheetRevision()).isEqualTo(1);
    assertThat(header.startedAt()).isNotNull();
    assertThat(header.completedAt()).isNotNull();
    assertThat(header.status()).isEqualTo("VERIFIED");
    assertThat(header.hasNgItems()).isTrue();
    assertThat(header.ngCount()).isEqualTo(2);
    assertThat(header.findingWoId()).isNull();

    assertThat(report.items()).hasSize(3);
    assertThat(report.items()).extracting(
        com.syncro.maintenance.preventive.api.PreventiveDtos.PmExecutionReportItemView::sequence)
        .containsExactly(1, 2, 3);
    var first = report.items().getFirst();
    assertThat(first.categoryName()).isEqualTo("Hydraulics");
    assertThat(first.parameterText()).isEqualTo("Oil pressure");
    assertThat(first.checkMethod()).isEqualTo("Gauge");
    assertThat(first.inputType()).isEqualTo("MEASUREMENT");
    assertThat(first.criticalFlag()).isFalse();
    assertThat(first.unit()).isEqualTo("bar");
    assertThat(first.lsl()).isEqualByComparingTo("3.0");
    assertThat(first.nominal()).isEqualByComparingTo("5.0");
    assertThat(first.usl()).isEqualByComparingTo("7.0");
    assertThat(first.actualValue()).isEqualByComparingTo("5.2");
    assertThat(first.ok()).isNull();
    assertThat(first.ng()).isFalse();
    assertThat(first.blocked()).isFalse();
    assertThat(first.blockedWoCode()).isNull();
    assertThat(first.ngPhotoPresignedUrl()).isNull();
    var second = report.items().get(1);
    assertThat(second.parameterText()).isEqualTo("Visual leak check");
    assertThat(second.checkMethod()).isEqualTo("Eyes");
    assertThat(second.inputType()).isEqualTo("OK_NG");
    assertThat(second.ok()).isNull();
    assertThat(second.ng()).isTrue();
    assertThat(second.ngNotes()).isEqualTo("Weep at seal");
    assertThat(second.ngPhotoPresignedUrl()).isEqualTo("https://garage/ng.jpg");
    var third = report.items().get(2);
    assertThat(third.parameterText()).isEqualTo("Belt tension");
    assertThat(third.blocked()).isTrue();
    assertThat(third.blockedWoCode()).isEqualTo("WO-2609-00042");

    assertThat(report.signatures().technician()).isNotNull();
    assertThat(report.signatures().technician().userId()).isEqualTo(fx.technicianId());
    assertThat(report.signatures().technician().displayName()).isEqualTo("Tech One");
    assertThat(report.signatures().technician().signaturePresignedUrl())
        .isEqualTo("https://garage/tech.png");
    assertThat(report.signatures().technician().signedAt()).isNotNull();
    assertThat(report.signatures().spv()).isNotNull();
    assertThat(report.signatures().spv().userId()).isEqualTo(fx.leaderId());
    // Leader has no display_name — falls back to login_identifier (14-3 pattern).
    assertThat(report.signatures().spv().displayName())
        .startsWith("rpt-leader-").endsWith("@test");
    assertThat(report.signatures().spv().signaturePresignedUrl())
        .isEqualTo("https://garage/leader.png");
  }

  @Test
  @DisplayName("19.6-INT-002 P0 unsigned execution: signature blocks null, status RUNNING")
  void unsignedExecution() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        new FillExecutionCommand(new BigDecimal("5"), null, null, null, null, false, null));

    var report = reports.get(fx.tech(), execution.id());

    assertThat(report.header().status()).isEqualTo("RUNNING");
    assertThat(report.header().completedAt()).isNull();
    assertThat(report.signatures().technician()).isNull();
    assertThat(report.signatures().spv()).isNull();
  }

  @Test
  @DisplayName("19.6-INT-002b P0 completed but unverified: status COMPLETED")
  void completedUnverified() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        new FillExecutionCommand(new BigDecimal("5"), null, null, null, null, false, null));
    executions.complete(fx.tech(), execution.id());

    assertThat(reports.get(fx.tech(), execution.id()).header().status()).isEqualTo("COMPLETED");
  }

  @Test
  @DisplayName("19.6-INT-003 P0 deleted signature object: report still 200 with null signaturePresignedUrl")
  void deletedSignatureObject() {
    var fx = fixture();
    var spvSigId = seedSignature(fx.leaderId(), "signatures/gone.png");
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        new FillExecutionCommand(new BigDecimal("5"), null, null, null, null, false, null));
    executions.complete(fx.tech(), execution.id());
    executions.verify(fx.leader(), execution.id(), spvSigId);
    when(objectStorage.presignGetUrl("signatures/gone.png"))
        .thenThrow(new ObjectStorageException("object gone"));

    var report = reports.get(fx.tech(), execution.id());

    assertThat(report.header().status()).isEqualTo("VERIFIED");
    assertThat(report.signatures().spv()).isNotNull();
    assertThat(report.signatures().spv().signaturePresignedUrl()).isNull();
    assertThat(report.signatures().spv().signedAt()).isNotNull();
  }

  @Test
  @DisplayName("19.6-INT-004 P0 ng_photo_url classification: URL-like echoes verbatim, flat dotted key presigns")
  void externalNgPhotoUrl() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    // seq 1: flat dotted key — must presign (no slash, so not domain-like).
    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        new FillExecutionCommand(new BigDecimal("9.5"), null, true, "Out of bounds",
            "photo.jpg", false, null));
    // seq 2: scheme URL — echo verbatim.
    executions.fill(fx.tech(), execution.id(), fx.okNgItemId(),
        new FillExecutionCommand(null, null, true, "Weep", "https://external.example/x.jpg",
            false, null));
    // seq 3: protocol-relative — echo verbatim (the // branch).
    executions.fill(fx.tech(), execution.id(), fx.beltItemId(),
        new FillExecutionCommand(null, null, true, "Frayed", "//cdn.example/x.jpg",
            false, null));
    // seq 4: scheme-less domain-like first segment — echo verbatim.
    executions.fill(fx.tech(), execution.id(), fx.glassItemId(),
        new FillExecutionCommand(null, null, true, "Cracked", "photos.example.com/x.jpg",
            false, null));
    // Only the flat dotted key may be presigned; any other call is a URL-like value
    // wrongly classified as an object key → fail. (Single stub: a second when() on a
    // specific key would trip the generic throw during stubbing.)
    when(objectStorage.presignGetUrl(anyString())).thenAnswer(invocation -> {
      var key = invocation.getArgument(0, String.class);
      if (key.equals("photo.jpg")) {
        return "https://garage/photo.jpg";
      }
      throw new AssertionError("URL-like value must not be presigned: " + key);
    });

    var report = reports.get(fx.tech(), execution.id());

    assertThat(report.items().getFirst().ngPhotoPresignedUrl())
        .isEqualTo("https://garage/photo.jpg");
    assertThat(report.items().get(1).ngPhotoPresignedUrl())
        .isEqualTo("https://external.example/x.jpg");
    assertThat(report.items().get(2).ngPhotoPresignedUrl())
        .isEqualTo("//cdn.example/x.jpg");
    assertThat(report.items().get(3).ngPhotoPresignedUrl())
        .isEqualTo("photos.example.com/x.jpg");
    verify(objectStorage).presignGetUrl("photo.jpg");
  }

  @Test
  @DisplayName("19.6-INT-005 P0 out-of-scope reader 403; unknown id 404; in-scope leader and SUPER_ADMIN read")
  void gateParityWith195() {
    var fx = fixture();
    var execution = completedVerified(fx);

    // In-scope leader reads (19-5 posture).
    var report = reports.get(fx.leader(), execution.id());
    assertThat(report.header().executionId()).isEqualTo(execution.id());
    // verify(..., null) is production-reachable: signedAt set, signatureId null →
    // the spv block is present with a null presigned URL (no signature row to sign).
    assertThat(report.signatures().spv()).isNotNull();
    assertThat(report.signatures().spv().signaturePresignedUrl()).isNull();
    assertThat(report.signatures().spv().signedAt()).isNotNull();
    assertThat(report.signatures().technician()).isNull();

    var now = Instant.now();
    var otherPlant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(),
        "Z-RPT-" + UUID.randomUUID().toString().substring(0, 8), "Other", now, now));
    var otherGroup = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), otherPlant, "Other Group", now, now));
    var otherMachine = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), otherPlant,
        otherGroup, "MC-RPTO-" + UUID.randomUUID().toString().substring(0, 8), "Other M",
        MachineStatus.ACTIVE, null, null, null, null, now, now));
    var otherId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(otherId, "rpt-other-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SECTION_LEADER, true, now, now));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        otherMachine.getId(), otherId, ResponsibilityLevel.LEADER, now, now));
    var otherLeader = new AuthenticatedUser(otherId.toString(), "rpt-other@test",
        ApplicationRole.SECTION_LEADER);
    assertThatThrownBy(() -> reports.get(otherLeader, execution.id()))
        .isInstanceOf(PmExecutionForbiddenException.class);

    assertThatThrownBy(() -> reports.get(fx.tech(), UUID.randomUUID()))
        .isInstanceOf(PmExecutionNotFoundException.class);

    var adminId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(adminId, "rpt-admin-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SUPER_ADMIN, true, now, now));
    var admin = new AuthenticatedUser(adminId.toString(), "rpt-admin@test",
        ApplicationRole.SUPER_ADMIN);
    assertThat(reports.get(admin, execution.id()).header().status()).isEqualTo("VERIFIED");
  }
}
