package com.syncro.maintenance.preventive.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.maintenance.preventive.application.PmChecksheetService.CreateChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.InvalidChecksheetTransitionException;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ReviseChecksheetCommand;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.time.Instant;
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
 * Story 19-1: proves the "concurrent revise → one r2 wins, other 409" matrix row.
 * Two threads revise the SAME source revision simultaneously; both compute
 * maxRevision+1 = 2, so exactly one insert commits and the loser hits
 * uq_pm_checksheets_machine_frequency_revision → InvalidChecksheetTransitionException.
 *
 * <p>Deliberately NOT extending {@code AbstractPostgresIntegrationTest}: the race needs
 * committed data visible to independent transactions, so this class is non-transactional
 * and owns a dedicated database ({@code withDatabaseName}) — its committed rows never
 * pollute the shared reused container that the transactional suites count against.
 * Pattern anchor: {@code InventoryReservationConsumeConcurrencyIntegrationTest} (18-5).
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
class PmChecksheetReviseConcurrencyIntegrationTest {

  @Container
  @SuppressWarnings("rawtypes")
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withDatabaseName("pm_checksheet_revise_concurrency");
    postgres.withReuse(true);
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

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
  private MachineResponsibilityRepository responsibilities;

  @Test
  @DisplayName("19.1-RACE-001 P0 concurrent revise of the same source: exactly one r2 wins, loser 409")
  void concurrentReviseWinsOnce() throws Exception {
    var now = Instant.now();
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var plant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "RACE-" + suffix, "Race Plant", now, now));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Race Group", now, now));
    var machine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-RACE-" + suffix, "Race Machine",
        MachineStatus.ACTIVE, null, null, null, null, now, now));
    var leaderId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        leaderId, "race-leader-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.SECTION_LEADER, true, now, now));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), machine.getId(), leaderId, ResponsibilityLevel.LEADER, now, now));
    var leader = new AuthenticatedUser(leaderId.toString(), "race-leader@test",
        ApplicationRole.SECTION_LEADER);

    var monthly = frequencies.list().stream()
        .filter(f -> f.code().equals("MONTHLY")).findFirst().orElseThrow();
    var r1 = checksheets.create(leader,
        new CreateChecksheetCommand(machine.getId(), monthly.id(), null));

    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    List<PmChecksheetService.ChecksheetView> successes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();
    try {
      var first = executor.submit(() -> {
        start.await();
        return checksheets.revise(leader, r1.id(), new ReviseChecksheetCommand("race A"));
      });
      var second = executor.submit(() -> {
        start.await();
        return checksheets.revise(leader, r1.id(), new ReviseChecksheetCommand("race B"));
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

    for (var failure : failures) {
      failure.printStackTrace();
    }
    assertThat(successes).hasSize(1);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0)).isInstanceOf(InvalidChecksheetTransitionException.class);
    // Exactly one revision 2 exists for the pair.
    assertThat(successes.get(0).revisionNo()).isEqualTo(2);
  }
}
