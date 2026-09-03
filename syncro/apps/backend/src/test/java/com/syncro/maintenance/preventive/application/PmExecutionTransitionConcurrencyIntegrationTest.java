package com.syncro.maintenance.preventive.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.preventive.application.PmChecklistService.CreateItemCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ApproveChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.CreateChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmExecutionService.ExecutionAlreadyExistsException;
import com.syncro.maintenance.preventive.application.PmExecutionService.ExecutionView;
import com.syncro.maintenance.preventive.application.PmExecutionService.FillExecutionCommand;
import com.syncro.maintenance.preventive.application.PmExecutionService.InvalidExecutionTransitionException;
import com.syncro.maintenance.preventive.application.PmScheduleService.CreateScheduleCommand;
import com.syncro.maintenance.preventive.domain.PmItemInputType;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * Story 19-5: proves the execution transitions serialize on the
 * {@code findByIdForUpdate} PESSIMISTIC_WRITE row lock and the unique-index
 * backstops (19-4 {@code PmWorkOrderTransitionConcurrencyIntegrationTest} pattern).
 * (a) two threads start on the SAME workorder — exactly one wins, the loser sees
 * the committed execution and gets {@code ExecutionAlreadyExistsException} (the
 * same 409 the uq_pm_executions_pm_wo catch maps to — the pre-check and the catch
 * are one semantic path, and INT-016 in the service suite proves the constraint
 * the catch maps exists); (b) two threads complete on the same execution — exactly
 * one wins, the loser gets {@code InvalidExecutionTransitionException}, and the
 * work_orders table grows by exactly ONE finding workorder (never a duplicate
 * corrective action).
 *
 * <p>Deliberately NOT extending {@code AbstractPostgresIntegrationTest}: the race
 * needs committed data visible to independent transactions, and complete()'s
 * createSystem runs REQUIRES_NEW (only sees committed fixtures). This class is
 * non-transactional and owns a dedicated database ({@code withDatabaseName}) so its
 * committed rows never pollute the shared reused container.
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
class PmExecutionTransitionConcurrencyIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withDatabaseName("pm_execution_transition_concurrency");
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
  private WorkOrderRepository systemWorkOrders;
  @Autowired
  private WorkOrderCategoryRepository workOrderCategories;

  private record Fixture(AuthenticatedUser leader, AuthenticatedUser tech, UUID technicianId,
      UUID woId, UUID criticalNgItemId) {
  }

  private Fixture fixture() {
    var now = Instant.now();
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    if (!workOrderCategories.existsByCode("01")) {
      workOrderCategories.saveAndFlush(new WorkOrderCategoryEntity(
          UUID.randomUUID(), "01", "Breakdown", null, now, now));
    }
    var plant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "P-RACE-" + suffix, "Exec Race Plant", now, now));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Exec Race Group", now, now));
    var machine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-RACE-" + suffix, "Exec Race Machine",
        MachineStatus.ACTIVE, null, null, null, null, now, now));
    var leaderId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(leaderId, "race-leader-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SECTION_LEADER, true, now, now));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(leaderId, plant.getId(), now));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), machine.getId(), leaderId, ResponsibilityLevel.LEADER, now, now));
    var leader = new AuthenticatedUser(leaderId.toString(), "race-leader@test",
        ApplicationRole.SECTION_LEADER);
    var technicianId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(technicianId, "race-tech-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.TECHNICIAN, true, now, now));
    var tech = new AuthenticatedUser(technicianId.toString(), "race-tech@test",
        ApplicationRole.TECHNICIAN);

    var monthly = frequencies.list().stream()
        .filter(f -> f.code().equals("MONTHLY")).findFirst().orElseThrow().id();
    var checksheetId = checksheets.create(leader,
        new CreateChecksheetCommand(machine.getId(), monthly, null)).id();
    var criticalNgItemId = checklist.createItem(leader, new CreateItemCommand(checksheetId,
        null, 1, "Bearing temperature", "Thermometer", PmItemInputType.MEASUREMENT, "C",
        new BigDecimal("10"), new BigDecimal("40"), new BigDecimal("60"), true, null, null)).id();
    checksheets.approve(leader, checksheetId,
        new ApproveChecksheetCommand(LocalDate.of(2026, 1, 15)));
    var scheduleId = schedules.create(leader,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027)).id();
    schedules.submit(leader, scheduleId);
    schedules.approveSpv(leader, scheduleId);
    schedules.approveProd(leader, scheduleId);
    schedules.activate(leader, scheduleId);
    var wo = workOrders.generate(leader, scheduleId).getFirst();
    workOrders.assign(leader, wo.id(), technicianId);
    workOrders.start(tech, wo.id());
    return new Fixture(leader, tech, technicianId, wo.id(), criticalNgItemId);
  }

  private record RaceOutcome(List<ExecutionView> successes, List<Throwable> failures) {
  }

  private RaceOutcome race(java.util.concurrent.Callable<ExecutionView> firstCall,
      java.util.concurrent.Callable<ExecutionView> secondCall) throws Exception {
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    var successes = new ArrayList<ExecutionView>();
    var failures = new ArrayList<Throwable>();
    try {
      var first = executor.submit(() -> {
        start.await();
        return firstCall.call();
      });
      var second = executor.submit(() -> {
        start.await();
        return secondCall.call();
      });
      start.countDown();
      for (var future : List.of(first, second)) {
        try {
          successes.add(future.get(60, TimeUnit.SECONDS));
        } catch (ExecutionException exception) {
          failures.add(exception.getCause());
        }
      }
    } finally {
      executor.shutdownNow();
    }
    return new RaceOutcome(successes, failures);
  }

  @Test
  @DisplayName("19.5-RACE-001 P0 concurrent start on one IN_PROGRESS workorder: exactly one wins, loser EXECUTION_ALREADY_EXISTS")
  void concurrentStartWinsOnce() throws Exception {
    var fixture = fixture();
    var outcome = race(
        () -> executions.start(fixture.tech(), fixture.woId()),
        () -> executions.start(fixture.tech(), fixture.woId()));

    for (var failure : outcome.failures()) {
      failure.printStackTrace();
    }
    assertThat(outcome.successes()).hasSize(1);
    assertThat(outcome.failures()).hasSize(1);
    // The WO row lock serializes the two starts; the loser observes the committed
    // execution (pre-check) — the same 409 the uq_pm_executions_pm_wo catch maps to.
    assertThat(outcome.failures().get(0)).isInstanceOf(ExecutionAlreadyExistsException.class);
    assertThat(executions.list(fixture.tech(), fixture.woId(), null)).hasSize(1);
  }

  @Test
  @DisplayName("19.5-RACE-002 P0 concurrent complete on one execution: exactly one wins, loser 409, exactly ONE finding workorder created")
  void concurrentCompleteCreatesOneFindingWorkOrder() throws Exception {
    var fixture = fixture();
    var execution = executions.start(fixture.tech(), fixture.woId());
    executions.fill(fixture.tech(), execution.id(), fixture.criticalNgItemId(),
        new FillExecutionCommand(new BigDecimal("95"), null, true, "Overheating", null, false,
            null));
    var woCountBefore = systemWorkOrders.count();

    var outcome = race(
        () -> executions.complete(fixture.tech(), execution.id()),
        () -> executions.complete(fixture.tech(), execution.id()));

    for (var failure : outcome.failures()) {
      failure.printStackTrace();
    }
    assertThat(outcome.successes()).hasSize(1);
    assertThat(outcome.failures()).hasSize(1);
    assertThat(outcome.failures().get(0))
        .isInstanceOf(InvalidExecutionTransitionException.class);
    // The execution row lock serializes complete(); createSystem runs exactly once —
    // the work_orders table grows by exactly one finding workorder.
    assertThat(systemWorkOrders.count() - woCountBefore).isEqualTo(1);
    assertThat(outcome.successes().getFirst().findingWoId()).isNotNull();
  }
}
