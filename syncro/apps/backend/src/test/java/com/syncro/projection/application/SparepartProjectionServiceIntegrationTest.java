package com.syncro.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.Point;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.config.ProjectionProperties;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.projection.api.ProjectionDtos.InstallationProjection;
import com.syncro.projection.api.ProjectionDtos.MachineSparepartProjectionsView;
import com.syncro.projection.application.CounterRateEstimator.CalculationBasis;
import com.syncro.projection.application.CounterRateEstimator.InsufficientReason;
import com.syncro.projection.infrastructure.InfluxTelemetryHistoryReader;
import com.syncro.shiftconfig.infrastructure.MachineShiftWindowEntity;
import com.syncro.shiftconfig.infrastructure.MachineShiftWindowRepository;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

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
@Transactional
class SparepartProjectionServiceIntegrationTest {

  private static final String TEST_TOKEN =
      "apiv3_testtoken00000000000000000000000000000000000000000000000000000000000000";
  private static final String TEST_DATABASE = "syncro_test";
  private static final java.nio.file.Path ADMIN_TOKEN_FILE = writeAdminTokenFile();
  private static final AtomicInteger MACHINE_SUFFIX = new AtomicInteger();

  private static java.nio.file.Path writeAdminTokenFile() {
    try {
      var file = java.nio.file.Files.createTempFile("influxdb3-projection-token", ".json");
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
          "--node-id=test-node-8-6",
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
  private SparepartProjectionService projectionService;

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
  private StringRedisTemplate redisTemplate;

  @Autowired
  private InfluxDBClient influxClient;

  @Autowired
  private ApplicationEventPublisher events;

  @Autowired
  private ProjectionProperties projectionProperties;

  @MockitoSpyBean
  private InfluxTelemetryHistoryReader historyReader;

  @Test
  @DisplayName("8.6-SVC-001 P0 rate estimate drives per-installation projections with lead-time consumption")
  void happyRateWithProjections() {
    var seeded = seedMachineWithHistory(true);

    var view = projectionService.getProjections(seeded.superAdmin(), seeded.machine().getId());

    assertThat(view.rateAvailable()).isTrue();
    assertThat(view.calculationBasis()).isEqualTo(CalculationBasis.FULL_HISTORY);
    assertThat(view.insufficientReason()).isNull();
    assertThat(view.ratePerOperatingHour()).isEqualByComparingTo(new BigDecimal("1200"));
    assertThat(view.shiftSource()).isEqualTo("MACHINE");
    assertThat(view.dailyOperatingHours()).isEqualByComparingTo(new BigDecimal("24.00"));
    var now = Instant.now();
    assertThat(view.windowEndAt()).isAfter(now.minusSeconds(60));

    assertThat(view.projections()).hasSize(1);
    InstallationProjection row = view.projections().getFirst();
    assertThat(row.available()).isTrue();
    assertThat(row.remainingCounters()).isEqualTo(1000L);
    assertThat(row.leadTimeHours()).isEqualByComparingTo(new BigDecimal("36.5"));
    assertThat(row.consumptionDuringLeadTime()).isEqualTo(43800L);
    assertThat(row.projectedDepletionAt()).isAfter(now.minusSeconds(60));
    assertThat(row.projectedDepletionAt()).isBefore(now.plusSeconds(7200));
  }

  @Test
  @DisplayName("8.6-SVC-002 P0 second GET within TTL is served from Redis without re-reading history")
  void cacheHitSkipsSecondRead() {
    var seeded = seedMachineWithHistory(true);

    var first = projectionService.getProjections(seeded.superAdmin(), seeded.machine().getId());
    var second = projectionService.getProjections(seeded.superAdmin(), seeded.machine().getId());

    verify(historyReader, times(1)).readSamples(eq(seeded.machine().getCode()), any(), any());
    assertThat(first.rateAvailable()).isTrue();
    assertThat(second.rateAvailable()).isTrue();

    String key = "syncro:machine:" + seeded.machine().getId() + ":projections";
    Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
    assertThat(ttlSeconds).isNotNull().isBetween(1L, projectionProperties.cacheTtl().toSeconds());
  }

  @Test
  @DisplayName("8.6-SVC-003 P0 eviction event deletes the cached key so the next GET recomputes")
  void evictionOnWriteForcesRecompute() {
    var seeded = seedMachineWithHistory(true);
    String key = "syncro:machine:" + seeded.machine().getId() + ":projections";
    projectionService.getProjections(seeded.superAdmin(), seeded.machine().getId());
    assertThat(redisTemplate.hasKey(key)).isTrue();

    events.publishEvent(new ProjectionCacheEvictionEvent(seeded.machine().getId()));

    assertThat(redisTemplate.hasKey(key)).isFalse();
    projectionService.getProjections(seeded.superAdmin(), seeded.machine().getId());
    verify(historyReader, times(2)).readSamples(eq(seeded.machine().getCode()), any(), any());
  }

  @Test
  @DisplayName("8.6-SVC-004 P1 ALL eviction event pattern-deletes every machine key")
  void evictAllPatternDelete() {
    var seeded = seedMachineWithHistory(false);
    String key = "syncro:machine:" + seeded.machine().getId() + ":projections";
    redisTemplate.opsForValue().set(key, "{}", java.time.Duration.ofMinutes(5));
    assertThat(redisTemplate.hasKey(key)).isTrue();

    events.publishEvent(ProjectionCacheEvictionEvent.all());

    assertThat(redisTemplate.hasKey(key)).isFalse();
  }

  @Test
  @DisplayName("8.6-SVC-005 P1 machine without telemetry reports explicit NO_TELEMETRY degradation")
  void noTelemetryMachineIsExplicit() {
    var seeded = seedMachineWithHistory(false);

    var view = projectionService.getProjections(seeded.superAdmin(), seeded.machine().getId());

    assertThat(view.rateAvailable()).isFalse();
    assertThat(view.insufficientReason()).isEqualTo(InsufficientReason.NO_TELEMETRY);
    assertThat(view.projections()).hasSize(1);
    InstallationProjection row = view.projections().getFirst();
    assertThat(row.available()).isFalse();
    assertThat(row.reason()).isEqualTo(InsufficientReason.NO_TELEMETRY);
    assertThat(row.remainingCounters()).isNull();
    assertThat(row.projectedDepletionAt()).isNull();
  }

  @Test
  @DisplayName("8.6-SVC-006 P0 user without plant access is rejected before computation")
  void wrongPlantUserDenied() {
    var seeded = seedMachineWithHistory(false);
    var outsider = new AuthenticatedUser(UUID.randomUUID().toString(), "wrong-plant@syncro.dev",
        ApplicationRole.MANAGE);

    assertThatThrownBy(() -> projectionService.getProjections(outsider, seeded.machine().getId()))
        .isInstanceOf(PlantAccessDeniedException.class);
    verify(historyReader, times(0)).readSamples(anyString(), any(), any());
  }

  @Test
  @DisplayName("8.6-SVC-007 P1 sparepart without lead time omits consumptionDuringLeadTime")
  void leadTimeOmittedWhenAbsent() {
    var seeded = seedMachineWithHistory(true, null);
    awaitHistoryVisible(seeded.machine().getCode());

    var view = projectionService.getProjections(seeded.superAdmin(), seeded.machine().getId());

    assertThat(view.rateAvailable()).isTrue();
    assertThat(view.projections()).hasSize(1);
    InstallationProjection row = view.projections().getFirst();
    assertThat(row.available()).isTrue();
    assertThat(row.remainingCounters()).isEqualTo(1000L);
    assertThat(row.leadTimeHours()).isNull();
    assertThat(row.consumptionDuringLeadTime()).isNull();
    assertThat(row.projectedDepletionAt()).isNotNull();
  }

  private record Seeded(MachineEntity machine, AuthenticatedUser superAdmin) {
  }

  /**
   * Seeds plant/group/machine plus a sparepart (lead time 36.5h), an installation
   * (baseline 1000, expected 4600) and 24h/day shift coverage. With {@code withHistory},
   * three counting samples spanning ~3h are written to InfluxDB ending near now.
   */
  private Seeded seedMachineWithHistory(boolean withHistory) {
    return seedMachineWithHistory(withHistory, new BigDecimal("36.5"));
  }

  private Seeded seedMachineWithHistory(boolean withHistory, java.math.BigDecimal leadTimeHours) {
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
      sparepart.updateProcurement("MAT-001", leadTimeHours, now);
    }
    var savedSparepart = spareparts.saveAndFlush(sparepart);

    installations.saveAndFlush(new MachineSparepartInstallationEntity(UUID.randomUUID(), machine,
        savedSparepart, "Primary feeder", 4600, 1000, 90, now, now, now));

    if (withHistory) {
      writeCounting(code, now.minusSeconds(10800), 1000);
      writeCounting(code, now.minusSeconds(5400), 2800);
      writeCounting(code, now.minusSeconds(600), 4600);
    }
    return new Seeded(machine,
        new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN));
  }

  /** InfluxDB v3 writes are synchronous but read visibility can lag briefly in CI. */
  private void awaitHistoryVisible(String machineCode) {
    var deadline = Instant.now().plusSeconds(15);
    while (Instant.now().isBefore(deadline)) {
      try {
        if (!historyReader.readSamples(machineCode, Instant.now().minus(java.time.Duration.ofDays(1)),
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
