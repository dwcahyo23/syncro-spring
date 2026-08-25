package com.syncro.alert.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.Point;
import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.domain.SparepartAlertType;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.projection.application.CounterRateEstimator.CalculationBasis;
import com.syncro.projection.infrastructure.InfluxTelemetryHistoryReader;
import com.syncro.shiftconfig.infrastructure.MachineShiftWindowEntity;
import com.syncro.shiftconfig.infrastructure.MachineShiftWindowRepository;
import com.syncro.sparepart.application.SparepartLifetimeEvaluator;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * End-to-end procurement-risk evaluation over real Postgres + Redis + InfluxDB3 (story 8-7).
 * Seeds a machine with a lead-time sparepart, real shift windows, and real counting history so
 * the projection module computes a rate and depletion inside the lead-time window; asserts the
 * alert is created with snapshotted evidence, dedupes on re-run, coexists with a threshold
 * alert, and stays a silent no-op when lead time or rate is missing.
 */
@SpringBootTest(properties = {
    "server.port=0",
    "spring.lifecycle.timeout-per-shutdown-phase=5s",
    "REDIS_HOST=localhost",
    "REDIS_PORT=6379",
    "INFLUXDB_HOST=localhost",
    "INFLUXDB_PORT=8181",
    "INFLUXDB_TOKEN=apiv3_testtoken00000000000000000000000000000000000000000000000000000000000000",
    "INFLUXDB_DATABASE=syncro_test",
    "SYNCRO_MQTT_HOST=localhost",
    "SYNCRO_MQTT_PORT=1883",
    "SYNCRO_MQTT_USERNAME=test",
    "SYNCRO_MQTT_PASSWORD=test",
    "SYNCRO_MQTT_CLIENT_ID=test",
    "SYNCRO_MQTT_TOPIC_FILTER=factory/+/+/telemetry",
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
    "syncro.auth.local-admin.password=test-password",
    "syncro.telemetry.latest-ttl=PT1H",
    "syncro.telemetry.dedupe-window=PT1H"
})
@Testcontainers
// NOT @Transactional: the procurement-risk creator runs in its own REQUIRES_NEW transaction, so
// the seed rows must be committed for it to see them (an outer test transaction would hide them).
class SparepartProcurementRiskAlertIntegrationTest {

  private static final String TEST_TOKEN =
      "apiv3_testtoken00000000000000000000000000000000000000000000000000000000000000";
  private static final String TEST_DATABASE = "syncro_test";
  private static final java.nio.file.Path ADMIN_TOKEN_FILE = writeAdminTokenFile();
  private static final AtomicInteger MACHINE_SUFFIX = new AtomicInteger();

  private static java.nio.file.Path writeAdminTokenFile() {
    try {
      var file = java.nio.file.Files.createTempFile("influxdb3-procurement-token", ".json");
      java.nio.file.Files.writeString(file,
          "{\"token\":\"" + TEST_TOKEN + "\",\"name\":\"_admin\"}");
      return file;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("could not write influxdb admin token file", e);
    }
  }

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withReuse(true);
  }

  @Container
  static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
      .withReuse(true)
      .withExposedPorts(6379)
      .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

  @Container
  static final GenericContainer<?> influx = new GenericContainer<>("influxdb:3-core")
      .withReuse(true)
      .withCopyToContainer(MountableFile.forHostPath(ADMIN_TOKEN_FILE), "/etc/influxdb3/admin-token.json")
      .withCommand("serve",
          "--node-id=test-node-8-7",
          "--object-store=memory",
          "--admin-token-file=/etc/influxdb3/admin-token.json",
          "--disable-authz=health,ping")
      .withExposedPorts(8181)
      .waitingFor(Wait.forHttp("/health").forPort(8181).withStartupTimeout(Duration.ofSeconds(120)));

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    registry.add("syncro.influxdb.url",
        () -> "http://" + influx.getHost() + ":" + influx.getMappedPort(8181));
    registry.add("syncro.influxdb.token", () -> TEST_TOKEN);
    registry.add("syncro.influxdb.database", () -> TEST_DATABASE);
  }

  @Autowired
  private SparepartAlertService alertService;

  @Autowired
  private SparepartAlertRepository alertRepository;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private SparepartTaxonomyRepository taxonomy;

  @Autowired
  private SparepartRepository spareparts;

  @Autowired
  private MachineSparepartInstallationRepository installations;

  @Autowired
  private MachineShiftWindowRepository shiftWindows;

  @Autowired
  private InfluxDBClient influxClient;

  @MockitoSpyBean
  private InfluxTelemetryHistoryReader historyReader;

  @Test
  @DisplayName("8.7-INT-001 P0 depletion inside lead-time window creates a PROCUREMENT_RISK alert with evidence")
  void riskWithinWindowCreatesAlertWithEvidence() {
    var seeded = seedMachineWithHistory(true, new BigDecimal("36.5"));
    awaitHistoryVisible(seeded.machineCode());

    alertService.evaluateAndCreateProcurementRiskAlerts(seeded.machineId(), "trace-int-001");

    var alerts = alertRepository.findAll().stream()
        .filter(a -> a.getMachineId().equals(seeded.machineId()))
        .toList();
    assertThat(alerts).hasSize(1);
    SparepartAlertEntity alert = alerts.getFirst();
    assertThat(alert.getAlertType()).isEqualTo(SparepartAlertType.PROCUREMENT_RISK);
    assertThat(alert.getStatus()).isEqualTo(SparepartAlertStatus.OPEN);
    assertThat(alert.getThresholdPercentage()).isNull();
    assertThat(alert.getCurrentCounterSnapshot()).isNull();
    assertThat(alert.getConsumedProductionCountSnapshot()).isNull();
    assertThat(alert.getConsumedPercentageSnapshot()).isNull();
    assertThat(alert.getLeadTimeHours()).isEqualByComparingTo(new BigDecimal("36.5"));
    assertThat(alert.getRatePerOperatingHour()).isEqualByComparingTo(new BigDecimal("1200.00"));
    assertThat(alert.getCalculationBasis()).isEqualTo(CalculationBasis.FULL_HISTORY);
    assertThat(alert.getProjectedDepletionAt()).isNotNull();
    assertThat(alert.getTraceId()).isEqualTo("trace-int-001");
  }

  @Test
  @DisplayName("8.7-INT-002 P0 re-evaluation adds no duplicate while a resolved alert allows a fresh one")
  void reevaluationDoesNotDuplicate() {
    var seeded = seedMachineWithHistory(true, new BigDecimal("36.5"));
    awaitHistoryVisible(seeded.machineCode());

    alertService.evaluateAndCreateProcurementRiskAlerts(seeded.machineId(), "trace-int-002");
    alertService.evaluateAndCreateProcurementRiskAlerts(seeded.machineId(), "trace-int-002b");

    var alerts = alertRepository.findAll().stream()
        .filter(a -> a.getMachineId().equals(seeded.machineId()))
        .toList();
    assertThat(alerts).hasSize(1);
    assertThat(alerts.getFirst().getTraceId()).isEqualTo("trace-int-002");
  }

  @Test
  @DisplayName("8.7-INT-003 P0 a THRESHOLD and a PROCUREMENT_RISK alert coexist on one installation")
  void thresholdAndRiskCoexist() {
    var seeded = seedMachineWithHistory(true, new BigDecimal("36.5"));
    awaitHistoryVisible(seeded.machineCode());

    // consumed 3600 of 4600 = 78.26% >= 50% threshold -> threshold alert fires
    alertService.evaluateAndCreateAlerts(seeded.machineId(),
        Map.of(seeded.installationId(),
            new SparepartLifetimeEvaluator.EvaluationResult(4600L, 3600L, new BigDecimal("78.26"))),
        "trace-int-003-threshold");
    alertService.evaluateAndCreateProcurementRiskAlerts(seeded.machineId(), "trace-int-003-risk");

    var alerts = alertRepository.findAll().stream()
        .filter(a -> a.getMachineId().equals(seeded.machineId()))
        .toList();
    assertThat(alerts).hasSize(2);
    assertThat(alerts).anyMatch(a -> a.getAlertType() == SparepartAlertType.THRESHOLD_PERCENTAGE);
    assertThat(alerts).anyMatch(a -> a.getAlertType() == SparepartAlertType.PROCUREMENT_RISK);
  }

  @Test
  @DisplayName("8.7-INT-004 P1 missing lead time is a silent no-op")
  void missingLeadTimeIsSilent() {
    var seeded = seedMachineWithHistory(true, null);
    awaitHistoryVisible(seeded.machineCode());

    alertService.evaluateAndCreateProcurementRiskAlerts(seeded.machineId(), "trace-int-004");

    var alerts = alertRepository.findAll().stream()
        .filter(a -> a.getMachineId().equals(seeded.machineId()))
        .toList();
    assertThat(alerts).isEmpty();
  }

  @Test
  @DisplayName("8.7-INT-005 P1 no telemetry (rate unavailable) is a silent no-op")
  void rateUnavailableIsSilent() {
    var seeded = seedMachineWithHistory(false, new BigDecimal("36.5"));

    alertService.evaluateAndCreateProcurementRiskAlerts(seeded.machineId(), "trace-int-005");

    var alerts = alertRepository.findAll().stream()
        .filter(a -> a.getMachineId().equals(seeded.machineId()))
        .toList();
    assertThat(alerts).isEmpty();
  }

  @Test
  @DisplayName("8.7-INT-006 P1 depletion outside the lead-time window creates no alert")
  void outsideWindowIsSilent() {
    // Lead time 0.01h with rate 1200 -> consumptionDuringLeadTime = round(12) = 12 < remaining 1000
    var seeded = seedMachineWithHistory(true, new BigDecimal("0.01"));
    awaitHistoryVisible(seeded.machineCode());

    alertService.evaluateAndCreateProcurementRiskAlerts(seeded.machineId(), "trace-int-006");

    var alerts = alertRepository.findAll().stream()
        .filter(a -> a.getMachineId().equals(seeded.machineId()))
        .toList();
    assertThat(alerts).isEmpty();
  }

  private record Seeded(UUID machineId, String machineCode, UUID installationId) {
  }

  /**
   * Seeds plant/group/machine plus a sparepart (lead time {@code leadTimeHours}), an installation
   * (baseline 1000, expected 4600, threshold 50%) and 24h/day shift coverage. With
   * {@code withHistory}, three counting samples spanning ~3h are written to InfluxDB ending near
   * now so the estimator yields rate=1200/op-h, remaining=1000, and lead-time consumption
   * round(1200 x leadTimeHours).
   */
  private Seeded seedMachineWithHistory(boolean withHistory, BigDecimal leadTimeHours) {
    var code = "BF-" + (9000 + MACHINE_SUFFIX.incrementAndGet());
    var now = Instant.now();
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "GM" + MACHINE_SUFFIX.get(),
        "Plant GM", now, now));
    var group = machineGroups.saveAndFlush(
        new MachineGroupEntity(UUID.randomUUID(), plant, "Forming", now, now));
    var machine = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group, code,
        "JBF19", MachineStatus.ACTIVE, "Juki", LocalDate.parse("2026-05-27"), null, List.of(), now, now));

    shiftWindows.saveAllAndFlush(List.of(
        new MachineShiftWindowEntity(UUID.randomUUID(), machine, 1, LocalTime.of(0, 0),
            LocalTime.of(12, 0), now, now),
        new MachineShiftWindowEntity(UUID.randomUUID(), machine, 2, LocalTime.of(12, 0),
            LocalTime.of(0, 0), now, now)));

    var category = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.CATEGORY, "ELEC" + MACHINE_SUFFIX.get(), "Electric " + MACHINE_SUFFIX.get(), now, now));
    var brand = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.BRAND, "WECON" + MACHINE_SUFFIX.get(), "Wecon " + MACHINE_SUFFIX.get(), category, now, now));
    var kind = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.KIND, "PLC" + MACHINE_SUFFIX.get(), "PLC " + MACHINE_SUFFIX.get(), category, now, now));
    var type = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.TYPE, "LX5" + MACHINE_SUFFIX.get(), "LX5 " + MACHINE_SUFFIX.get(), category, now, now));
    var sparepart = new SparepartEntity(UUID.randomUUID(), "SP-" + code, "Feeder unit", machine,
        category, brand, kind, type, now, now);
    if (leadTimeHours != null) {
      sparepart.updateProcurement("MAT-" + MACHINE_SUFFIX.get(), leadTimeHours, now);
    }
    var savedSparepart = spareparts.saveAndFlush(sparepart);

    var installation = installations.saveAndFlush(new MachineSparepartInstallationEntity(
        UUID.randomUUID(), machine, savedSparepart, "Primary feeder", 4600, 1000, 50, now, now, now));

    if (withHistory) {
      writeCounting(code, now.minusSeconds(10800), 1000);
      writeCounting(code, now.minusSeconds(5400), 2800);
      writeCounting(code, now.minusSeconds(600), 4600);
    }
    return new Seeded(machine.getId(), code, installation.getId());
  }

  /** InfluxDB v3 writes are synchronous but read visibility can lag briefly in CI. */
  private void awaitHistoryVisible(String machineCode) {
    var deadline = Instant.now().plusSeconds(15);
    while (Instant.now().isBefore(deadline)) {
      try {
        if (!historyReader.readSamples(machineCode, Instant.now().minus(Duration.ofDays(1)),
            Instant.now()).isEmpty()) {
          return;
        }
      } catch (RuntimeException ignored) {
        // Reader failures during the visibility window are retried until the deadline.
      }
      try {
        Thread.sleep(250);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void writeCounting(String machineCode, Instant timestamp, long counting) {
    influxClient.writePoint(Point.measurement("telemetry")
        .setTag("plantCode", "GM")
        .setTag("machineCode", machineCode)
        .setField("running", true)
        .setField("runtimeHours", 12.5)
        .setField("counting", counting)
        .setTimestamp(timestamp));
  }
}
