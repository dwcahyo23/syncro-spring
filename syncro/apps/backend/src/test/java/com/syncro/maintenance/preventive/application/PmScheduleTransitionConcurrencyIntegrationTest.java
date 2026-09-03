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
import com.syncro.maintenance.preventive.application.PmChecksheetService.ApproveChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.CreateChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmScheduleService.CreateScheduleCommand;
import com.syncro.maintenance.preventive.domain.PmScheduleDateStatus;
import com.syncro.maintenance.preventive.domain.PmScheduleStatus;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
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
 * Story 19-3: proves the approval/date transitions serialize on the
 * {@code findByIdForUpdate} PESSIMISTIC_WRITE row lock (18-5 pattern). Two threads
 * race the SAME transition (submit, approve-spv, activate, date EXECUTED) on one
 * schedule; exactly one must succeed and the loser must see the committed status and
 * throw the 409 transition exception — never a double-apply (duplicate audit row) or
 * last-write-wins.
 *
 * <p>Deliberately NOT extending {@code AbstractPostgresIntegrationTest}: the race needs
 * committed data visible to independent transactions, so this class is non-transactional
 * and owns a dedicated database ({@code withDatabaseName}) — its committed rows never
 * pollute the shared reused container that the transactional suites count against.
 * Pattern anchor: {@code PmChecksheetReviseConcurrencyIntegrationTest} (19-1).
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
class PmScheduleTransitionConcurrencyIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withDatabaseName("pm_schedule_transition_concurrency");
    postgres.withReuse(true);
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private PmScheduleService schedules;
  @Autowired
  private PmChecksheetService checksheets;
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

  private record Fixture(PlantEntity plant, MachineEntity machine, AuthenticatedUser leader) {
  }

  private Fixture fixture() {
    var now = Instant.now();
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var plant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "P-RACE-" + suffix, "Race Plant", now, now));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Race Group", now, now));
    var machine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-RACE-" + suffix, "Race Machine",
        MachineStatus.ACTIVE, null, null, null, null, now, now));
    var leaderId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        leaderId, "race-leader-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.SECTION_LEADER, true, now, now));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(leaderId, plant.getId(), now));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), machine.getId(), leaderId, ResponsibilityLevel.LEADER, now, now));
    var leader = new AuthenticatedUser(leaderId.toString(), "race-leader@test",
        ApplicationRole.SECTION_LEADER);
    return new Fixture(plant, machine, leader);
  }

  private UUID approvedChecksheet(Fixture fixture) {
    var monthly = frequencies.list().stream()
        .filter(f -> f.code().equals("MONTHLY")).findFirst().orElseThrow();
    var id = checksheets.create(fixture.leader(),
        new CreateChecksheetCommand(fixture.machine().getId(), monthly.id(), null)).id();
    checksheets.approve(fixture.leader(), id, new ApproveChecksheetCommand(LocalDate.of(2026, 1, 15)));
    return id;
  }

  private UUID draftSchedule(Fixture fixture) {
    return schedules.create(fixture.leader(),
        new CreateScheduleCommand(fixture.plant().getId(), fixture.machine().getId(),
            approvedChecksheet(fixture), 2027)).id();
  }

  private UUID activeSchedule(Fixture fixture) {
    var id = draftSchedule(fixture);
    schedules.submit(fixture.leader(), id);
    schedules.approveSpv(fixture.leader(), id);
    schedules.approveProd(fixture.leader(), id);
    schedules.activate(fixture.leader(), id);
    return id;
  }

  /** Runs two threads against the same call; returns successes and thrown causes. */
  private record RaceOutcome(List<PmScheduleService.ScheduleView> successes,
      List<Throwable> failures) {
  }

  private RaceOutcome race(Runnable firstCall, Runnable secondCall) throws Exception {
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    List<PmScheduleService.ScheduleView> successes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();
    try {
      var first = executor.submit(() -> {
        start.await();
        firstCall.run();
        return null;
      });
      var second = executor.submit(() -> {
        start.await();
        secondCall.run();
        return null;
      });
      start.countDown();
      for (var future : List.of(first, second)) {
        try {
          future.get(60, TimeUnit.SECONDS);
          successes.add(null);
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
  @DisplayName("19.3-RACE-001 P0 concurrent submit on one DRAFT schedule: exactly one wins, loser 409")
  void concurrentSubmitWinsOnce() throws Exception {
    var fixture = fixture();
    var id = draftSchedule(fixture);
    var outcome = race(
        () -> schedules.submit(fixture.leader(), id),
        () -> schedules.submit(fixture.leader(), id));

    for (var failure : outcome.failures()) {
      failure.printStackTrace();
    }
    assertThat(outcome.successes()).hasSize(1);
    assertThat(outcome.failures()).hasSize(1);
    assertThat(outcome.failures().get(0))
        .isInstanceOf(PmScheduleService.InvalidScheduleTransitionException.class);
    assertThat(schedules.get(fixture.leader(), id).status())
        .isEqualTo(PmScheduleStatus.PENDING_SPV_APPROVAL);
  }

  @Test
  @DisplayName("19.3-RACE-002 P0 concurrent approve-spv on one schedule: exactly one wins, loser 409")
  void concurrentApproveSpvWinsOnce() throws Exception {
    var fixture = fixture();
    var id = draftSchedule(fixture);
    schedules.submit(fixture.leader(), id);
    var outcome = race(
        () -> schedules.approveSpv(fixture.leader(), id),
        () -> schedules.approveSpv(fixture.leader(), id));

    for (var failure : outcome.failures()) {
      failure.printStackTrace();
    }
    assertThat(outcome.successes()).hasSize(1);
    assertThat(outcome.failures()).hasSize(1);
    assertThat(outcome.failures().get(0))
        .isInstanceOf(PmScheduleService.InvalidScheduleTransitionException.class);
    assertThat(schedules.get(fixture.leader(), id).status())
        .isEqualTo(PmScheduleStatus.PENDING_PRODUCTION_APPROVAL);
  }

  @Test
  @DisplayName("19.3-RACE-003 P0 concurrent activate on one schedule: exactly one wins, loser 409")
  void concurrentActivateWinsOnce() throws Exception {
    var fixture = fixture();
    var id = draftSchedule(fixture);
    schedules.submit(fixture.leader(), id);
    schedules.approveSpv(fixture.leader(), id);
    schedules.approveProd(fixture.leader(), id);
    var outcome = race(
        () -> schedules.activate(fixture.leader(), id),
        () -> schedules.activate(fixture.leader(), id));

    for (var failure : outcome.failures()) {
      failure.printStackTrace();
    }
    assertThat(outcome.successes()).hasSize(1);
    assertThat(outcome.failures()).hasSize(1);
    assertThat(outcome.failures().get(0))
        .isInstanceOf(PmScheduleService.InvalidScheduleTransitionException.class);
    assertThat(schedules.get(fixture.leader(), id).status())
        .isEqualTo(PmScheduleStatus.ACTIVE);
  }

  @Test
  @DisplayName("19.3-RACE-004 P0 concurrent date EXECUTED transition: exactly one wins, loser 409")
  void concurrentDateTransitionWinsOnce() throws Exception {
    var fixture = fixture();
    var id = activeSchedule(fixture);
    var dateId = schedules.get(fixture.leader(), id).dates().getFirst().id();

    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    List<Throwable> failures = new ArrayList<>();
    List<PmScheduleDateStatus> successStatuses = new ArrayList<>();
    try {
      var first = executor.submit(() -> {
        start.await();
        return schedules.transitionDate(fixture.leader(), id, dateId,
            PmScheduleDateStatus.EXECUTED);
      });
      var second = executor.submit(() -> {
        start.await();
        return schedules.transitionDate(fixture.leader(), id, dateId,
            PmScheduleDateStatus.EXECUTED);
      });
      start.countDown();
      for (var future : List.of(first, second)) {
        try {
          successStatuses.add(future.get(60, TimeUnit.SECONDS).status());
        } catch (ExecutionException exception) {
          failures.add(exception.getCause());
        }
      }
    } finally {
      executor.shutdownNow();
    }

    for (var failure : failures) {
      failure.printStackTrace();
    }
    assertThat(successStatuses).hasSize(1);
    assertThat(successStatuses.get(0)).isEqualTo(PmScheduleDateStatus.EXECUTED);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0))
        .isInstanceOf(PmScheduleService.InvalidScheduleDateTransitionException.class);
    assertThat(schedules.get(fixture.leader(), id).dates().getFirst().status())
        .isEqualTo(PmScheduleDateStatus.EXECUTED);
  }
}
