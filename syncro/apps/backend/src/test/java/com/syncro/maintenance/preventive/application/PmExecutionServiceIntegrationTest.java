package com.syncro.maintenance.preventive.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.preventive.application.PmChecklistService.CreateCategoryCommand;
import com.syncro.maintenance.preventive.application.PmChecklistService.CreateItemCommand;
import com.syncro.maintenance.preventive.application.PmChecklistService.PmChecklistItemNotFoundException;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ApproveChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.CreateChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmExecutionService.ExecutionAlreadyExistsException;
import com.syncro.maintenance.preventive.application.PmExecutionService.ExecutionValidationException;
import com.syncro.maintenance.preventive.application.PmExecutionService.ExecutionView;
import com.syncro.maintenance.preventive.application.PmExecutionService.FillExecutionCommand;
import com.syncro.maintenance.preventive.application.PmExecutionService.InvalidExecutionStateException;
import com.syncro.maintenance.preventive.application.PmExecutionService.InvalidExecutionTransitionException;
import com.syncro.maintenance.preventive.application.PmExecutionService.PmExecutionForbiddenException;
import com.syncro.maintenance.preventive.application.PmExecutionService.PmExecutionNotFoundException;
import com.syncro.maintenance.preventive.application.PmScheduleService.CreateScheduleCommand;
import com.syncro.maintenance.preventive.domain.PmItemInputType;
import com.syncro.maintenance.preventive.domain.PmScheduleDateStatus;
import com.syncro.maintenance.preventive.domain.PmWorkOrderStatus;
import com.syncro.maintenance.preventive.infrastructure.db.PmExecutionItemRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmExecutionRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleDateRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 19-5 integration test: PM executions & execution items against a real
 * Postgres container (blueprint F7/F8). Covers start on an IN_PROGRESS workorder
 * (stamps + audit CREATE, duplicate 409, non-IN_PROGRESS 409, unknown 404, wrong
 * assignee 403), per-item fill (snapshot + result, MEASUREMENT/OK_NG validation
 * 400s, NG-notes rule, re-fill update, foreign-item 404, fill-after-complete 409),
 * complete (NG rollup, workorder COMPLETED, schedule date EXECUTED, critical-NG
 * finding workorder via the 11-3 system path linked through finding_wo_id +
 * blocking_wo_id, clean-complete no finding, zero-filled 409, double-complete 409,
 * manual-period null schedule_date), SPV verify (stamps + audit, not-completed 409,
 * double-verify 409, non-leader 403, out-of-scope 403, SUPER_ADMIN bypass), the
 * technician gate on fill/complete, and scope-filtered reads.
 *
 * <p>Deliberately NOT extending {@code AbstractPostgresIntegrationTest}: complete()
 * creates the finding workorder through {@code WorkOrderService.createSystem}, which
 * runs in its own REQUIRES_NEW transaction and therefore can only see COMMITTED
 * machines/categories. The transactional base would leave every fixture invisible to
 * that inner transaction (FK violation). This class is non-transactional and owns a
 * dedicated database ({@code withDatabaseName}, the 19-4
 * {@code PmWorkOrderTransitionConcurrencyIntegrationTest} pattern); every test builds
 * its own uniquely-suffixed fixture chain and asserts only against its own rows.
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
class PmExecutionServiceIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withDatabaseName("pm_execution_integration");
    postgres.withReuse(true);
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

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
  private AuditLogRepository auditLogs;
  @Autowired
  private PmWorkOrderRepository workOrderRows;
  @Autowired
  private PmScheduleDateRepository scheduleDateRows;
  @Autowired
  private PmExecutionRepository executionRows;
  @Autowired
  private PmExecutionItemRepository executionItemRows;
  @Autowired
  private com.syncro.maintenance.preventive.infrastructure.db.PmChecklistItemRepository checklistItemRows;
  @Autowired
  private WorkOrderRepository systemWorkOrders;
  @Autowired
  private WorkOrderCategoryRepository workOrderCategories;

  /** One fully-wired fixture: ACTIVE monthly schedule, checklist items, one IN_PROGRESS WO. */
  private record Fixture(PlantEntity plant, MachineEntity machine,
      AuthenticatedUser leader, AuthenticatedUser staff, AuthenticatedUser manager,
      AuthenticatedUser tech, UUID technicianId, UUID checksheetId, UUID measurementItemId,
      UUID criticalMeasurementItemId, UUID okNgItemId, UUID foreignItemId,
      UUID longParameterItemId, UUID emojiParameterItemId, UUID scheduleId,
      UUID scheduleDateId, PmWorkOrderService.WorkOrderView workOrder) {
  }

  private Fixture fixture() {
    var now = Instant.now();
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    // The finding WO path needs the "01" Breakdown category (V1 seeds only "02";
    // production creates "01" via the 10-1 API). Committed once per container.
    if (!workOrderCategories.existsByCode("01")) {
      workOrderCategories.saveAndFlush(new WorkOrderCategoryEntity(
          UUID.randomUUID(), "01", "Breakdown", null, now, now));
    }
    var plant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "P-EX-" + suffix, "Exec Plant", now, now));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Exec Group", now, now));
    var machine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-EX-" + suffix, "Exec Machine",
        MachineStatus.ACTIVE, null, null, null, null, now, now));

    var leaderId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(leaderId, "ex-leader-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SECTION_LEADER, true, now, now));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(leaderId, plant.getId(), now));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), machine.getId(), leaderId, ResponsibilityLevel.LEADER, now, now));
    var leader = new AuthenticatedUser(leaderId.toString(), "ex-leader@test",
        ApplicationRole.SECTION_LEADER);

    var staffId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(staffId, "ex-staff-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.STAFF_MAINTENANCE, true, now, now));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(staffId, plant.getId(), now));
    var staff = new AuthenticatedUser(staffId.toString(), "ex-staff@test",
        ApplicationRole.STAFF_MAINTENANCE);

    var managerId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(managerId, "ex-manager-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.MANAGER_MAINTENANCE, true, now, now));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(managerId, plant.getId(), now));
    var manager = new AuthenticatedUser(managerId.toString(), "ex-manager@test",
        ApplicationRole.MANAGER_MAINTENANCE);

    var technicianId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(technicianId, "ex-tech-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.TECHNICIAN, true, now, now));
    var tech = new AuthenticatedUser(technicianId.toString(), "ex-tech@test",
        ApplicationRole.TECHNICIAN);

    var monthly = frequencies.list().stream()
        .filter(f -> f.code().equals("MONTHLY")).findFirst().orElseThrow().id();
    var checksheetId = checksheets.create(staff,
        new CreateChecksheetCommand(machine.getId(), monthly, null)).id();
    // Checklist content must exist BEFORE approval (19-2 freezes approved revisions).
    var categoryId = checklist.createCategory(staff,
        new CreateCategoryCommand(checksheetId, "Hydraulics", 0)).id();
    var measurementItemId = checklist.createItem(staff, new CreateItemCommand(checksheetId,
        categoryId, 1, "Oil pressure", "Gauge", PmItemInputType.MEASUREMENT, "bar",
        new BigDecimal("3.0"), new BigDecimal("5.0"), new BigDecimal("7.0"), false,
        null, null)).id();
    var criticalMeasurementItemId = checklist.createItem(staff, new CreateItemCommand(checksheetId,
        categoryId, 2, "Bearing temperature", "Thermometer", PmItemInputType.MEASUREMENT, "C",
        new BigDecimal("10"), new BigDecimal("40"), new BigDecimal("60"), true,
        null, null)).id();
    var okNgItemId = checklist.createItem(staff, new CreateItemCommand(checksheetId,
        categoryId, 3, "Visual leak check", "Eyes", PmItemInputType.OK_NG, null,
        null, null, null, false, null, null)).id();
    // Label-truncation fixtures (19.5-INT-017): 300 chars > 255 label limit, and a
    // surrogate-heavy text whose UTF-16 length exceeds 255 while its code-point
    // count stays under it (the old length()-based guard crashed on this). The emoji
    // row is seeded directly via the repository: PmChecklistService.itemLabel (19-2)
    // carries the same latent bug and would crash on createItem — out of 19-5 scope.
    var longParameterItemId = checklist.createItem(staff, new CreateItemCommand(checksheetId,
        null, 4, "a".repeat(300), null, PmItemInputType.OK_NG, null, null, null, null, false,
        null, null)).id();
    var emojiParameterItemId = checklistItemRows.saveAndFlush(new com.syncro.maintenance.preventive.infrastructure.db.PmChecklistItemEntity(
        UUID.randomUUID(), checksheetId, null, 5, "🔧".repeat(130), null,
        PmItemInputType.OK_NG, null, null, null, null, false, null, null, now, now)).getId();
    // A second checksheet on the same machine is impossible (uq per machine+frequency),
    // so the foreign-item probe uses a second machine's checksheet.
    var otherMachine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-EX2-" + suffix, "Exec Machine 2",
        MachineStatus.ACTIVE, null, null, null, null, now, now));
    var otherChecksheetId = checksheets.create(staff,
        new CreateChecksheetCommand(otherMachine.getId(), monthly, null)).id();
    var foreignItemId = checklist.createItem(staff, new CreateItemCommand(otherChecksheetId,
        null, 1, "Foreign parameter", null, PmItemInputType.OK_NG, null, null, null, null,
        false, null, null)).id();
    checksheets.approve(leader, checksheetId,
        new ApproveChecksheetCommand(LocalDate.of(2026, 1, 15)));

    var scheduleId = schedules.create(staff,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027)).id();
    schedules.submit(staff, scheduleId);
    schedules.approveSpv(leader, scheduleId);
    schedules.approveProd(leader, scheduleId);
    schedules.activate(leader, scheduleId);
    var generated = workOrders.generate(leader, scheduleId);
    var scheduleDateId = scheduleDateRows
        .findByScheduleIdAndPlannedDate(scheduleId, LocalDate.of(2027, 1, 15)).orElseThrow().getId();
    var wo = generated.getFirst();
    workOrders.assign(leader, wo.id(), technicianId);
    var started = workOrders.start(tech, wo.id());
    return new Fixture(plant, machine, leader, staff, manager, tech, technicianId, checksheetId,
        measurementItemId, criticalMeasurementItemId, okNgItemId, foreignItemId,
        longParameterItemId, emojiParameterItemId, scheduleId, scheduleDateId, started);
  }

  private static FillExecutionCommand measurement(BigDecimal value) {
    return new FillExecutionCommand(value, null, null, null, null, false, null);
  }

  private static FillExecutionCommand ok() {
    return new FillExecutionCommand(null, true, false, null, null, false, null);
  }

  private static FillExecutionCommand ng(String notes) {
    return new FillExecutionCommand(null, null, true, notes, null, false, null);
  }

  // -------------------------------------------------------------------------
  // AC1: start
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.5-INT-001 P0 start on IN_PROGRESS WO stamps execution + schedule date + audit CREATE; second start 409")
  void startStampsExecutionAndRejectsDuplicate() {
    var fx = fixture();
    var view = executions.start(fx.tech(), fx.workOrder().id());

    assertThat(view.pmWoId()).isEqualTo(fx.workOrder().id());
    assertThat(view.technicianId()).isEqualTo(fx.technicianId());
    assertThat(view.startedAt()).isNotNull();
    assertThat(view.completedAt()).isNull();
    assertThat(view.spvVerifierId()).isNull();
    assertThat(view.hasNgItems()).isFalse();
    assertThat(view.ngCount()).isZero();
    assertThat(view.findingWoId()).isNull();
    assertThat(view.items()).isEmpty();
    // Period resolution: the workorder's (machine, template, scheduled_date) triple.
    assertThat(view.scheduleDateId()).isEqualTo(fx.scheduleDateId());

    var persisted = executionRows.findById(view.id()).orElseThrow();
    assertThat(persisted.getTechnicianId()).isEqualTo(fx.technicianId());

    var audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(view.id());
    assertThat(audit).hasSize(1);
    assertThat(audit.getFirst().getEntityType()).isEqualTo(AuditEntityType.PM_EXECUTION);
    assertThat(audit.getFirst().getAction()).isEqualTo(AuditAction.CREATE);
    assertThat(audit.getFirst().getPlantId()).isEqualTo(fx.plant().getId());
    assertThat(audit.getFirst().getPreviousValue()).isNull();
    assertThat(audit.getFirst().getNewValue()).contains("\"technicianId\":\"" + fx.technicianId() + "\"");

    assertThatThrownBy(() -> executions.start(fx.tech(), fx.workOrder().id()))
        .isInstanceOf(ExecutionAlreadyExistsException.class);
    assertThat(executionRows.findByPmWoId(fx.workOrder().id())).isPresent();
  }

  @Test
  @DisplayName("19.5-INT-002 P0 start guards: non-IN_PROGRESS 409, unknown WO 404, wrong assignee 403")
  void startGuards() {
    var fx = fixture();
    // A SCHEDULED workorder (second generated row) is not executable. Assign it to
    // the fixture technician first so the assignee gate passes and the state check
    // fires (gate order mirrors 19-4: requireAssignee before requireState).
    var second = workOrderRows.findByMachineIdAndTemplateIdAndScheduledDate(
        fx.machine().getId(), fx.checksheetId(), LocalDate.of(2027, 2, 15)).orElseThrow();
    workOrders.assign(fx.leader(), second.getId(), fx.technicianId());
    assertThatThrownBy(() -> executions.start(fx.tech(), second.getId()))
        .isInstanceOf(InvalidExecutionStateException.class);
    // IN_PROGRESS now accepts a start; the second start hits the one-per-WO rule.
    workOrders.start(fx.tech(), second.getId());
    assertThat(executions.start(fx.tech(), second.getId()).pmWoId()).isEqualTo(second.getId());
    assertThatThrownBy(() -> executions.start(fx.tech(), second.getId()))
        .isInstanceOf(ExecutionAlreadyExistsException.class);

    assertThatThrownBy(() -> executions.start(fx.tech(), UUID.randomUUID()))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderNotFoundException.class);

    // A different technician is not the assignee — 403 even though the WO is IN_PROGRESS.
    var otherId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(otherId, "ex-tech2-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.TECHNICIAN, true, Instant.now(), Instant.now()));
    var otherTech = new AuthenticatedUser(otherId.toString(), "ex-tech2@test",
        ApplicationRole.TECHNICIAN);
    assertThatThrownBy(() -> executions.start(otherTech, fx.workOrder().id()))
        .isInstanceOf(PmExecutionForbiddenException.class);
  }

  @Test
  @DisplayName("19.5-INT-002b P0 SUPER_ADMIN starts on behalf of the assignee — technician_id stays the WO assignee")
  void superAdminStartBindsAssignee() {
    var fx = fixture();
    var adminId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(adminId, "ex-admin-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SUPER_ADMIN, true, Instant.now(), Instant.now()));
    var admin = new AuthenticatedUser(adminId.toString(), "ex-admin@test",
        ApplicationRole.SUPER_ADMIN);

    var view = executions.start(admin, fx.workOrder().id());
    assertThat(view.technicianId()).isEqualTo(fx.technicianId());
  }

  // -------------------------------------------------------------------------
  // AC2: fill
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.5-INT-003 P0 fill MEASUREMENT snapshots the checklist item + stores actualValue with audit UPDATE; re-fill updates the same row")
  void fillMeasurementSnapshotsAndStores() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());

    var item = executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        measurement(new BigDecimal("5.2")));

    assertThat(item.checklistItemId()).isEqualTo(fx.measurementItemId());
    assertThat(item.sequence()).isEqualTo(1);
    assertThat(item.categoryName()).isEqualTo("Hydraulics");
    assertThat(item.parameterText()).isEqualTo("Oil pressure");
    assertThat(item.checkMethod()).isEqualTo("Gauge");
    assertThat(item.inputType()).isEqualTo(PmItemInputType.MEASUREMENT);
    assertThat(item.isCriticalFlag()).isFalse();
    assertThat(item.unit()).isEqualTo("bar");
    assertThat(item.lsl()).isEqualByComparingTo("3.0");
    assertThat(item.nominal()).isEqualByComparingTo("5.0");
    assertThat(item.usl()).isEqualByComparingTo("7.0");
    assertThat(item.actualValue()).isEqualByComparingTo("5.2");
    assertThat(item.isNg()).isFalse();
    assertThat(item.filledAt()).isNotNull();

    var audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(item.id());
    assertThat(audit).hasSize(1);
    assertThat(audit.getFirst().getEntityType()).isEqualTo(AuditEntityType.PM_EXECUTION_ITEM);
    assertThat(audit.getFirst().getAction()).isEqualTo(AuditAction.UPDATE);
    assertThat(audit.getFirst().getPreviousValue()).isNull();
    assertThat(audit.getFirst().getNewValue()).contains("5.2");

    // Re-fill: same snapshot row, updated result — one row, second audit entry.
    var refilled = executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        measurement(new BigDecimal("4.8")));
    assertThat(refilled.id()).isEqualTo(item.id());
    assertThat(refilled.actualValue()).isEqualByComparingTo("4.8");
    assertThat(executionItemRows.findByExecutionIdOrderBySequenceAsc(execution.id())).hasSize(1);
    assertThat(auditLogs.findByEntityIdOrderByCreatedAtAsc(item.id())).hasSize(2);
  }

  @Test
  @DisplayName("19.5-INT-004 P0 fill validation: MEASUREMENT without actualValue 400; OK_NG needs ok/ng; NG needs non-blank notes")
  void fillValidationRules() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());

    assertThatThrownBy(() -> executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        new FillExecutionCommand(null, null, null, null, null, false, null)))
        .isInstanceOfSatisfying(ExecutionValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("actualValue"));

    assertThatThrownBy(() -> executions.fill(fx.tech(), execution.id(), fx.okNgItemId(),
        new FillExecutionCommand(null, null, null, null, null, false, null)))
        .isInstanceOfSatisfying(ExecutionValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("ok"));

    // ng=true with blank notes → 400.
    assertThatThrownBy(() -> executions.fill(fx.tech(), execution.id(), fx.okNgItemId(),
        ng("   ")))
        .isInstanceOfSatisfying(ExecutionValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("ngNotes"));

    // ok=true is valid for OK_NG.
    var okItem = executions.fill(fx.tech(), execution.id(), fx.okNgItemId(), ok());
    assertThat(okItem.isOk()).isTrue();
    assertThat(okItem.isNg()).isFalse();

    // ng=true with notes is valid and stores notes + photo.
    var ngItem = executions.fill(fx.tech(), execution.id(), fx.criticalMeasurementItemId(),
        new FillExecutionCommand(new BigDecimal("95"), null, true, "Overheating",
            "https://evidence/x.jpg", false, null));
    assertThat(ngItem.isNg()).isTrue();
    assertThat(ngItem.ngNotes()).isEqualTo("Overheating");
    assertThat(ngItem.ngPhotoUrl()).isEqualTo("https://evidence/x.jpg");
    assertThat(ngItem.actualValue()).isEqualByComparingTo("95");
  }

  @Test
  @DisplayName("19.5-INT-005 P0 fill on unknown execution 404; foreign checklist item 404; wrong technician 403; after complete 409")
  void fillGuards() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());

    assertThatThrownBy(() -> executions.fill(fx.tech(), UUID.randomUUID(), fx.measurementItemId(),
        measurement(BigDecimal.ONE)))
        .isInstanceOf(PmExecutionNotFoundException.class);
    // An item from another checksheet is reported as not-found (no foreign probe).
    assertThatThrownBy(() -> executions.fill(fx.tech(), execution.id(), fx.foreignItemId(),
        ok()))
        .isInstanceOf(PmChecklistItemNotFoundException.class);

    var otherId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(otherId, "ex-tech3-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.TECHNICIAN, true, Instant.now(), Instant.now()));
    var otherTech = new AuthenticatedUser(otherId.toString(), "ex-tech3@test",
        ApplicationRole.TECHNICIAN);
    assertThatThrownBy(() -> executions.fill(otherTech, execution.id(), fx.measurementItemId(),
        measurement(BigDecimal.ONE)))
        .isInstanceOf(PmExecutionForbiddenException.class);

    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        measurement(new BigDecimal("5")));
    executions.complete(fx.tech(), execution.id());
    assertThatThrownBy(() -> executions.fill(fx.tech(), execution.id(), fx.okNgItemId(), ok()))
        .isInstanceOf(InvalidExecutionTransitionException.class);
  }

  // -------------------------------------------------------------------------
  // AC3: complete
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.5-INT-006 P0 complete with critical NG: WO COMPLETED, date EXECUTED, rollup, finding WO linked via finding_wo_id + blocking_wo_id")
  void completeWithCriticalNgCreatesFindingWorkOrder() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        measurement(new BigDecimal("5.2"))); // OK measurement
    executions.fill(fx.tech(), execution.id(), fx.criticalMeasurementItemId(),
        new FillExecutionCommand(new BigDecimal("95"), null, true, "Bearing overheating",
            null, false, null)); // CRITICAL NG
    executions.fill(fx.tech(), execution.id(), fx.okNgItemId(),
        ng("Weep at seal")); // non-critical NG

    var completed = executions.complete(fx.tech(), execution.id());

    assertThat(completed.completedAt()).isNotNull();
    assertThat(completed.hasNgItems()).isTrue();
    assertThat(completed.ngCount()).isEqualTo(2);
    assertThat(completed.findingWoId()).isNotNull();

    // PM workorder moved IN_PROGRESS → COMPLETED through the 19-4 service.
    assertThat(workOrders.get(fx.leader(), fx.workOrder().id()).status())
        .isEqualTo(PmWorkOrderStatus.COMPLETED);
    // Schedule date moved to EXECUTED.
    assertThat(scheduleDateRows.findById(fx.scheduleDateId()).orElseThrow().getStatus())
        .isEqualTo(PmScheduleDateStatus.EXECUTED);

    // The finding workorder exists via the 11-3 system path: INTERNAL, OPEN, category
    // "01", description referencing the critical parameter, no preventive schedule.
    var finding = systemWorkOrders.findById(completed.findingWoId()).orElseThrow();
    assertThat(finding.getStatus()).isEqualTo(WorkOrderStatus.OPEN);
    assertThat(finding.getMachineId()).isEqualTo(fx.machine().getId());
    assertThat(finding.getDescription()).contains("Bearing temperature");
    assertThat(finding.getPreventiveScheduleId()).isNull();
    assertThat(finding.getCategoryId())
        .isEqualTo(workOrderCategories.findByCode("01").orElseThrow().getId());
    // createSystem audits itself (SYSTEM actor) — not double-audited here.
    var findingAudit = auditLogs.findByEntityIdOrderByCreatedAtAsc(
        java.util.UUID.nameUUIDFromBytes(completed.findingWoId().getBytes(
            java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(findingAudit).anySatisfy(entry -> {
      assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.WORK_ORDER);
      assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
      assertThat(entry.getActorName()).isEqualTo("SYSTEM");
    });

    // Every CRITICAL NG item carries the blocking link; the non-critical NG does not.
    var items = executionItemRows.findByExecutionIdOrderBySequenceAsc(execution.id());
    var critical = items.stream()
        .filter(i -> i.getChecklistItemId().equals(fx.criticalMeasurementItemId())).findFirst()
        .orElseThrow();
    var nonCriticalNg = items.stream()
        .filter(i -> i.getChecklistItemId().equals(fx.okNgItemId())).findFirst().orElseThrow();
    var clean = items.stream()
        .filter(i -> i.getChecklistItemId().equals(fx.measurementItemId())).findFirst()
        .orElseThrow();
    assertThat(critical.getBlockingWoId()).isEqualTo(completed.findingWoId());
    assertThat(nonCriticalNg.getBlockingWoId()).isNull();
    assertThat(clean.getBlockingWoId()).isNull();

    // Execution audit UPDATE with previous + new values (order-independent: the
    // CREATE and UPDATE can share a millisecond timestamp).
    var audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(execution.id());
    assertThat(audit).hasSize(2);
    assertThat(audit).filteredOn(e -> e.getAction() == AuditAction.UPDATE).singleElement()
        .satisfies(entry -> {
          assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_EXECUTION);
          assertThat(entry.getPlantId()).isEqualTo(fx.plant().getId());
          assertThat(entry.getPreviousValue()).contains("\"ngCount\":0");
          assertThat(entry.getNewValue()).contains("\"ngCount\":2")
              .contains(completed.findingWoId());
        });
  }

  @Test
  @DisplayName("19.5-INT-007 P0 clean complete: WO COMPLETED, has_ng_items false, no finding WO, date EXECUTED")
  void completeClean() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        measurement(new BigDecimal("5")));
    executions.fill(fx.tech(), execution.id(), fx.criticalMeasurementItemId(),
        measurement(new BigDecimal("41")));
    executions.fill(fx.tech(), execution.id(), fx.okNgItemId(), ok());

    var completed = executions.complete(fx.tech(), execution.id());

    assertThat(completed.hasNgItems()).isFalse();
    assertThat(completed.ngCount()).isZero();
    assertThat(completed.findingWoId()).isNull();
    assertThat(workOrders.get(fx.leader(), fx.workOrder().id()).status())
        .isEqualTo(PmWorkOrderStatus.COMPLETED);
    assertThat(scheduleDateRows.findById(fx.scheduleDateId()).orElseThrow().getStatus())
        .isEqualTo(PmScheduleDateStatus.EXECUTED);
  }

  @Test
  @DisplayName("19.5-INT-008 P0 complete guards: zero filled items 409, double complete 409, wrong technician 403")
  void completeGuards() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());

    assertThatThrownBy(() -> executions.complete(fx.tech(), execution.id()))
        .isInstanceOf(InvalidExecutionTransitionException.class);

    var otherId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(otherId, "ex-tech4-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.TECHNICIAN, true, Instant.now(), Instant.now()));
    var otherTech = new AuthenticatedUser(otherId.toString(), "ex-tech4@test",
        ApplicationRole.TECHNICIAN);
    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        measurement(new BigDecimal("5")));
    assertThatThrownBy(() -> executions.complete(otherTech, execution.id()))
        .isInstanceOf(PmExecutionForbiddenException.class);

    executions.complete(fx.tech(), execution.id());
    assertThatThrownBy(() -> executions.complete(fx.tech(), execution.id()))
        .isInstanceOf(InvalidExecutionTransitionException.class);
  }

  @Test
  @DisplayName("19.5-INT-009 P0 workorder with no matching schedule period: schedule_date_id null, EXECUTED step skipped, complete still succeeds")
  void completeWithoutSchedulePeriod() {
    var fx = fixture();
    // A manually created IN_PROGRESS workorder whose scheduled_date is not a planned
    // date of the schedule (the 20th is not the 15th anchor).
    var now = Instant.now();
    var manual = workOrderRows.saveAndFlush(new PmWorkOrderEntity(UUID.randomUUID(),
        fx.machine().getId(), fx.checksheetId(), null, "MONTHLY", "Monthly", 1,
        PmWorkOrderStatus.IN_PROGRESS, fx.technicianId(), LocalDate.of(2027, 1, 20),
        now, null, null, now, now));

    var execution = executions.start(fx.tech(), manual.getId());
    assertThat(execution.scheduleDateId()).isNull();

    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        measurement(new BigDecimal("5")));
    var completed = executions.complete(fx.tech(), execution.id());
    assertThat(completed.completedAt()).isNotNull();
    assertThat(completed.scheduleDateId()).isNull();
    // The real 2027-01-15 date is untouched.
    assertThat(scheduleDateRows.findById(fx.scheduleDateId()).orElseThrow().getStatus())
        .isEqualTo(PmScheduleDateStatus.SCHEDULED);
  }

  // -------------------------------------------------------------------------
  // AC4: verify
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.5-INT-010 P0 leader verifies a completed execution: spv stamps + audit UPDATE; double verify 409; non-completed 409; non-leader 403")
  void verifyLifecycle() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    var signatureId = UUID.randomUUID();

    // Not completed yet → 409.
    assertThatThrownBy(() -> executions.verify(fx.leader(), execution.id(), signatureId))
        .isInstanceOf(InvalidExecutionTransitionException.class);

    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        measurement(new BigDecimal("5")));
    executions.complete(fx.tech(), execution.id());

    var verified = executions.verify(fx.leader(), execution.id(), signatureId);
    assertThat(verified.spvVerifierId()).isNotNull();
    assertThat(verified.spvSignedAt()).isNotNull();
    assertThat(verified.spvSignatureId()).isEqualTo(signatureId);

    var audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(execution.id());
    assertThat(audit).anySatisfy(entry -> {
      assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
      assertThat(entry.getNewValue()).contains("\"spvVerifierId\"");
    });

    // Double verify → 409.
    assertThatThrownBy(() -> executions.verify(fx.manager(), execution.id(), null))
        .isInstanceOf(InvalidExecutionTransitionException.class);

    // Non-leader roles cannot verify (fresh execution to keep the state clean).
    var fx2 = fixture();
    var execution2 = executions.start(fx2.tech(), fx2.workOrder().id());
    executions.fill(fx2.tech(), execution2.id(), fx2.measurementItemId(),
        measurement(new BigDecimal("5")));
    executions.complete(fx2.tech(), execution2.id());
    assertThatThrownBy(() -> executions.verify(fx2.tech(), execution2.id(), null))
        .isInstanceOf(PmExecutionForbiddenException.class);
    assertThatThrownBy(() -> executions.verify(fx2.staff(), execution2.id(), null))
        .isInstanceOf(PmExecutionForbiddenException.class);

    // Out-of-scope leader → 403; SUPER_ADMIN bypass works.
    var otherPlant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(),
        "Y-EX-" + UUID.randomUUID().toString().substring(0, 8), "Other Exec", Instant.now(),
        Instant.now()));
    var otherGroup = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), otherPlant, "Other Exec Group", Instant.now(), Instant.now()));
    var otherMachine = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), otherPlant,
        otherGroup, "MC-EXO-" + UUID.randomUUID().toString().substring(0, 8), "Other Exec M",
        MachineStatus.ACTIVE, null, null, null, null, Instant.now(), Instant.now()));
    var otherId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(otherId, "ex-other-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SECTION_LEADER, true, Instant.now(), Instant.now()));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        otherMachine.getId(), otherId, ResponsibilityLevel.LEADER, Instant.now(),
        Instant.now()));
    var otherLeader = new AuthenticatedUser(otherId.toString(), "ex-other@test",
        ApplicationRole.SECTION_LEADER);
    assertThatThrownBy(() -> executions.verify(otherLeader, execution2.id(), null))
        .isInstanceOf(PmExecutionForbiddenException.class);

    var adminId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(adminId, "ex-vadmin-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SUPER_ADMIN, true, Instant.now(), Instant.now()));
    var admin = new AuthenticatedUser(adminId.toString(), "ex-vadmin@test",
        ApplicationRole.SUPER_ADMIN);
    var adminVerified = executions.verify(admin, execution2.id(), null);
    assertThat(adminVerified.spvVerifierId()).isEqualTo(adminId);
    assertThat(adminVerified.spvSignatureId()).isNull();
  }

  // -------------------------------------------------------------------------
  // Reads
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.5-INT-011 P0 get returns execution + items; technician and in-scope leader read; out-of-scope leader 403; unknown 404; list filters")
  void readsAreScopeFiltered() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        measurement(new BigDecimal("5")));

    var byTech = executions.get(fx.tech(), execution.id());
    assertThat(byTech.items()).hasSize(1);
    assertThat(byTech.items().getFirst().parameterText()).isEqualTo("Oil pressure");
    assertThat(executions.get(fx.leader(), execution.id()).id()).isEqualTo(execution.id());
    assertThat(executions.get(fx.manager(), execution.id()).id()).isEqualTo(execution.id());

    assertThatThrownBy(() -> executions.get(fx.tech(), UUID.randomUUID()))
        .isInstanceOf(PmExecutionNotFoundException.class);

    // Out-of-scope leader: 403 on get, invisible on list.
    var otherPlant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(),
        "Z-EX-" + UUID.randomUUID().toString().substring(0, 8), "Other2", Instant.now(),
        Instant.now()));
    var otherGroup = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), otherPlant, "Other2 Group", Instant.now(), Instant.now()));
    var otherMachine = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), otherPlant,
        otherGroup, "MC-EXZ-" + UUID.randomUUID().toString().substring(0, 8), "Other2 M",
        MachineStatus.ACTIVE, null, null, null, null, Instant.now(), Instant.now()));
    var otherId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(otherId, "ex-z-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SECTION_LEADER, true, Instant.now(), Instant.now()));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        otherMachine.getId(), otherId, ResponsibilityLevel.LEADER, Instant.now(), Instant.now()));
    var otherLeader = new AuthenticatedUser(otherId.toString(), "ex-z@test",
        ApplicationRole.SECTION_LEADER);
    assertThatThrownBy(() -> executions.get(otherLeader, execution.id()))
        .isInstanceOf(PmExecutionForbiddenException.class);
    assertThat(executions.list(otherLeader, null, null))
        .extracting(ExecutionView::id).doesNotContain(execution.id());

    // List filters.
    assertThat(executions.list(fx.tech(), fx.workOrder().id(), null))
        .extracting(ExecutionView::id).containsExactly(execution.id());
    assertThat(executions.list(fx.tech(), null, fx.technicianId()))
        .extracting(ExecutionView::id).contains(execution.id());
    assertThat(executions.list(fx.tech(), null, UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("19.5-INT-012 P0 blocked flag + blockingWoCode are stored on fill (technician-declared block)")
  void fillStoresBlockedFields() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    var item = executions.fill(fx.tech(), execution.id(), fx.okNgItemId(),
        new FillExecutionCommand(null, false, true, "Cannot reach sensor", null, true,
            "WO-2609-00042"));
    assertThat(item.isBlocked()).isTrue();
    assertThat(item.blockedWoCode()).isEqualTo("WO-2609-00042");
  }

  @Test
  @DisplayName("19.5-INT-013 P0 OK_NG mutual exclusion: ok=false+ng=false and ok=true+ng=true are both 400")
  void fillOkNgMutualExclusion() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());

    // Neither true → 400.
    assertThatThrownBy(() -> executions.fill(fx.tech(), execution.id(), fx.okNgItemId(),
        new FillExecutionCommand(null, false, false, null, null, false, null)))
        .isInstanceOfSatisfying(ExecutionValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("ok"));

    // Both true → 400.
    assertThatThrownBy(() -> executions.fill(fx.tech(), execution.id(), fx.okNgItemId(),
        new FillExecutionCommand(null, true, true, "notes", null, false, null)))
        .isInstanceOfSatisfying(ExecutionValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("ok"));

    // Exactly one true still works.
    assertThat(executions.fill(fx.tech(), execution.id(), fx.okNgItemId(),
        new FillExecutionCommand(null, true, false, null, null, false, null)).isOk()).isTrue();
  }

  @Test
  @DisplayName("19.5-INT-014 P0 blocked without blockingWoCode → 400")
  void fillBlockedRequiresCode() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    assertThatThrownBy(() -> executions.fill(fx.tech(), execution.id(), fx.okNgItemId(),
        new FillExecutionCommand(null, null, true, "Sensor unreachable", null, true, "  ")))
        .isInstanceOfSatisfying(ExecutionValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("blockingWoCode"));
  }

  @Test
  @DisplayName("19.5-INT-015 P0 fill after the workorder left IN_PROGRESS (overdue sweep) → 409")
  void fillRejectedWhenWorkOrderNoLongerInProgress() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    // Simulate the sweep moving the WO out of IN_PROGRESS between start and fill.
    var wo = workOrderRows.findById(fx.workOrder().id()).orElseThrow();
    wo.markOverdue(Instant.now());
    workOrderRows.saveAndFlush(wo);

    assertThatThrownBy(() -> executions.fill(fx.tech(), execution.id(), fx.measurementItemId(),
        measurement(new BigDecimal("5"))))
        .isInstanceOf(InvalidExecutionTransitionException.class);
  }

  @Test
  @DisplayName("19.5-INT-016 P0 uq_pm_executions_schedule_date exists — the start() catch maps it to EXECUTION_ALREADY_EXISTS (409, never 500)")
  void scheduleDateUniqueConstraintBackstop() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    assertThat(execution.scheduleDateId()).isEqualTo(fx.scheduleDateId());
    // A second execution on a different workorder bound to the same schedule date
    // violates the unique index — the constraint the start() catch maps to 409.
    var otherWo = workOrderRows.findByMachineIdAndTemplateIdAndScheduledDate(
        fx.machine().getId(), fx.checksheetId(), LocalDate.of(2027, 3, 15)).orElseThrow();
    var now = Instant.now();
    assertThatThrownBy(() -> executionRows.saveAndFlush(new com.syncro.maintenance.preventive.infrastructure.db.PmExecutionEntity(
        UUID.randomUUID(), otherWo.getId(), fx.scheduleDateId(), fx.technicianId(),
        null, null, null, null, null, now, null, false, 0, null, now, now)))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("uq_pm_executions_schedule_date");
  }

  @Test
  @DisplayName("19.5-INT-017 P0 long parameterText: audit entity_label truncated to 255; surrogate-heavy text never crashes the label")
  void itemLabelTruncationIsCodePointSafe() {
    var fx = fixture();
    var execution = executions.start(fx.tech(), fx.workOrder().id());
    var longItem = executions.fill(fx.tech(), execution.id(), fx.longParameterItemId(), ok());
    var emojiItem = executions.fill(fx.tech(), execution.id(), fx.emojiParameterItemId(), ok());

    var longAudit = auditLogs.findByEntityIdOrderByCreatedAtAsc(longItem.id());
    assertThat(longAudit).singleElement().satisfies(entry -> {
      assertThat(entry.getEntityLabel()).hasSize(255);
      assertThat(entry.getEntityLabel()).isEqualTo("a".repeat(255));
    });
    var emojiAudit = auditLogs.findByEntityIdOrderByCreatedAtAsc(emojiItem.id());
    assertThat(emojiAudit).singleElement().satisfies(entry -> {
      // 130 code points fit VARCHAR(255) untouched — no truncation, no crash.
      assertThat(entry.getEntityLabel()).isEqualTo("🔧".repeat(130));
    });
  }
}
