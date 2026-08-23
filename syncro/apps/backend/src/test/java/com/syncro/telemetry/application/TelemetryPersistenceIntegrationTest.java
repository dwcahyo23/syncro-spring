package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.PointValues;
import com.influxdb.v3.client.query.QueryOptions;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
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
class TelemetryPersistenceIntegrationTest {

  private static final String TOPIC = "factory/GM1/BF-08410/telemetry";
  private static final Instant BASE_RECEIVED_AT = Instant.now().plusSeconds(3600);
  private static final AtomicInteger RECEIVED_AT_OFFSET = new AtomicInteger();
  private static final AtomicInteger MESSAGE_ID_OFFSET = new AtomicInteger();

  private static final String TEST_TOKEN =
      "apiv3_testtoken00000000000000000000000000000000000000000000000000000000000000";
  private static final String TEST_DATABASE = "syncro_test";

  private static final java.nio.file.Path ADMIN_TOKEN_FILE = writeAdminTokenFile();

  private static java.nio.file.Path writeAdminTokenFile() {
    try {
      var file = java.nio.file.Files.createTempFile("influxdb3-admin-token", ".json");
      java.nio.file.Files.writeString(file,
          "{\"token\":\"" + TEST_TOKEN + "\",\"name\":\"_admin\"}");
      return file;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("could not write influxdb admin token file", e);
    }
  }

  private static String payload(Instant timestamp) {
    return payload(timestamp, 100);
  }

  private static String payload(Instant timestamp, long counting) {
    return "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-int-" + MESSAGE_ID_OFFSET.incrementAndGet()
        + "\",\"timestamp\":\"" + timestamp + "\",\"running\":true,\"runtimeHours\":12.5,\"counting\":" + counting
        + "}";
  }

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
      .withExposedPorts(6379)
      .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

  @Container
  static final GenericContainer<?> influx = new GenericContainer<>("influxdb:3-core")
      .withCopyToContainer(MountableFile.forHostPath(ADMIN_TOKEN_FILE), "/etc/influxdb3/admin-token.json")
      .withCommand("serve",
          "--node-id=test-node-1",
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
  private TelemetryValidationService validationService;

  @Autowired
  private TelemetryPersistenceService persistenceService;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private StringRedisTemplate redisTemplate;

  @Autowired
  private InfluxDBClient influxClient;

  @Autowired
  private CacheManager cacheManager;

  @BeforeEach
  void clearCaches() {
    cacheManager.getCacheNames().forEach(name -> {
      var cache = cacheManager.getCache(name);
      if (cache != null) cache.clear();
    });
  }

  @Test
  @DisplayName("3.4-PERS-001 accepted telemetry is written to InfluxDB with machineCode/plantCode tags and fields")
  void acceptedTelemetryWrittenToInfluxDb() {
    var machine = seedPlantAndMachine();
    String traceId = "trace-pers-001";
    Instant receivedAt = uniqueReceivedAt();
    persist(machine, traceId, receivedAt);

    var points = telemetryPoints(receivedAt);

    assertThat(points).isNotEmpty();
    assertThat(points).allMatch(p -> "GM1".equals(p.getTag("plantCode"))
        && "BF-08410".equals(p.getTag("machineCode")));
    assertThat(points).anyMatch(p -> Boolean.TRUE.equals(p.getField("running")));
    assertThat(points).anyMatch(p -> {
      Object v = p.getField("runtimeHours");
      return v instanceof Number n && Double.compare(n.doubleValue(), 12.5) == 0;
    });
    assertThat(points).anyMatch(p -> {
      Object v = p.getField("counting");
      return v instanceof Number n && n.longValue() == 100L;
    });
    assertThat(points).anyMatch(p -> {
      Object v = p.getField("countingDelta");
      return v instanceof Number n && n.longValue() == 0L;
    });
    assertThat(points).anyMatch(p -> traceId.equals(p.getField("traceId")));
  }

  @Test
  @DisplayName("3.4-PERS-002 latest telemetry hash is written to Redis with TTL")
  void latestTelemetryWrittenToRedisWithTtl() {
    var machine = seedPlantAndMachine();
    String traceId = "trace-pers-002";
    Instant receivedAt = uniqueReceivedAt();
    persist(machine, traceId, receivedAt);

    String key = "syncro:machine:" + machine.getId() + ":latest";
    Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

    assertThat(entries)
        .containsEntry("machineId", machine.getId().toString())
        .containsEntry("machineCode", "BF-08410")
        .containsEntry("plantCode", "GM1")
        .containsEntry("running", "true")
        .containsEntry("runtimeHours", "12.5")
        .containsEntry("counting", "100")
        .containsEntry("receivedAt", receivedAt.toString())
        .containsEntry("traceId", traceId);
    Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
    assertThat(ttlSeconds).isNotNull().isBetween(3599L, 3600L);
  }

  @Test
  @DisplayName("3.4-PERS-003 duplicate payload for the same machine writes a single InfluxDB record")
  void duplicatePayloadWritesSingleInfluxDbRecord() {
    var machine = seedPlantAndMachine();
    Instant receivedAt = uniqueReceivedAt();
    String msgPayload = payload(receivedAt);
    var accepted = (TelemetryValidationService.Result.Accepted) validationService.validate(TOPIC, msgPayload);

    persistenceService.persist(accepted, envelope("trace-pers-003", receivedAt, msgPayload));
    persistenceService.persist(accepted, envelope("trace-pers-003-duplicate", receivedAt.plusSeconds(5), msgPayload));

    String sql = """
        SELECT "traceId" FROM telemetry
        WHERE "machineCode" = 'BF-08410' AND "plantCode" = 'GM1'
          AND time >= '%s' AND time <= '%s'
        """.formatted(receivedAt.minusSeconds(60), receivedAt.plusSeconds(120));
    var points = queryPoints(sql);

    assertThat(points).hasSize(1);
    assertThat(points.get(0).getField("traceId")).isEqualTo("trace-pers-003");

    String key = "syncro:machine:" + machine.getId() + ":latest";
    assertThat(redisTemplate.opsForHash().entries(key)).containsEntry("traceId", "trace-pers-003");
  }

  @Test
  @DisplayName("3.4-PERS-004 persisted point is queryable by machineCode and plantCode tags")
  void pointQueryableByMachineAndPlantTags() {
    var machine = seedPlantAndMachine();
    Instant receivedAt = uniqueReceivedAt();
    persist(machine, "trace-pers-004", receivedAt);

    var points = telemetryPoints(receivedAt);

    assertThat(points).isNotEmpty();
    assertThat(points).anyMatch(p -> {
      Object v = p.getField("counting");
      return v instanceof Number n && n.longValue() == 100L;
    });
    assertThat(points).anyMatch(p -> {
      Object v = p.getField("countingDelta");
      return v instanceof Number n && n.longValue() == 0L;
    });
  }

  @Test
  @DisplayName("3.5-DELTA-001 first sample countingDelta is 0 in latest hash and InfluxDB point")
  void firstSampleDeltaIsZero() {
    var machine = seedPlantAndMachine();
    String traceId = "trace-delta-001";
    Instant receivedAt = uniqueReceivedAt();
    persistCounting(machine, traceId, receivedAt, 100);

    String key = "syncro:machine:" + machine.getId() + ":latest";
    assertThat(redisTemplate.opsForHash().entries(key)).containsEntry("countingDelta", "0");

    var points = telemetryPoints(receivedAt);
    assertThat(points).anyMatch(p -> {
      Object v = p.getField("countingDelta");
      return v instanceof Number n && n.longValue() == 0L;
    });
  }

  @Test
  @DisplayName("3.5-DELTA-002 consecutive increasing counting produces direct delta")
  void consecutiveIncreaseProducesDirectDelta() {
    var machine = seedPlantAndMachine();
    Instant firstReceivedAt = uniqueReceivedAt();
    Instant secondReceivedAt = uniqueReceivedAt();
    persistCounting(machine, "trace-delta-002-a", firstReceivedAt, 100);
    persistCounting(machine, "trace-delta-002-b", secondReceivedAt, 200);

    String key = "syncro:machine:" + machine.getId() + ":latest";
    assertThat(redisTemplate.opsForHash().entries(key)).containsEntry("countingDelta", "100");

    var secondPoints = telemetryPoints(secondReceivedAt);
    assertThat(secondPoints).anyMatch(p -> {
      Object v = p.getField("countingDelta");
      return v instanceof Number n && n.longValue() == 100L;
    });
  }

  @Test
  @DisplayName("3.5-DELTA-003 counting wrap from 65535 to 0 produces delta 1")
  void wrapFromMaxToZeroProducesDeltaOne() {
    var machine = seedPlantAndMachine();
    Instant firstReceivedAt = uniqueReceivedAt();
    Instant secondReceivedAt = uniqueReceivedAt();
    persistCounting(machine, "trace-delta-003-a", firstReceivedAt, 65535);
    persistCounting(machine, "trace-delta-003-b", secondReceivedAt, 0);

    String key = "syncro:machine:" + machine.getId() + ":latest";
    assertThat(redisTemplate.opsForHash().entries(key)).containsEntry("countingDelta", "1");

    var secondPoints = telemetryPoints(secondReceivedAt);
    assertThat(secondPoints).anyMatch(p -> {
      Object v = p.getField("countingDelta");
      return v instanceof Number n && n.longValue() == 1L;
    });
  }

  @Test
  @DisplayName("3.5-DELTA-004 counting wrap from near-max to small value produces correct delta")
  void wrapFromNearMaxToSmallProducesCorrectDelta() {
    var machine = seedPlantAndMachine();
    Instant firstReceivedAt = uniqueReceivedAt();
    Instant secondReceivedAt = uniqueReceivedAt();
    persistCounting(machine, "trace-delta-004-a", firstReceivedAt, 65530);
    persistCounting(machine, "trace-delta-004-b", secondReceivedAt, 10);

    String key = "syncro:machine:" + machine.getId() + ":latest";
    assertThat(redisTemplate.opsForHash().entries(key)).containsEntry("countingDelta", "16");

    var secondPoints = telemetryPoints(secondReceivedAt);
    assertThat(secondPoints).anyMatch(p -> {
      Object v = p.getField("countingDelta");
      return v instanceof Number n && n.longValue() == 16L;
    });
  }

  @Test
  @DisplayName("3.6-OPT-001 int then float sample for the same configured field both persist as numeric")
  void intThenFloatSampleForSameOptionalFieldBothPersist() {
    var machine = seedPlantAndMachine(List.of("vibration"));
    Instant intReceivedAt = uniqueReceivedAt();
    Instant floatReceivedAt = uniqueReceivedAt();
    persistVibration(machine, "trace-opt-001-int", intReceivedAt, "2");
    persistVibration(machine, "trace-opt-001-float", floatReceivedAt, "2.4");

    var intPoints = telemetryPoints(intReceivedAt);
    var floatPoints = telemetryPoints(floatReceivedAt);

    assertThat(intPoints).anyMatch(p -> {
      Object v = p.getField("vibration");
      return v instanceof Number n && Double.compare(n.doubleValue(), 2.0) == 0;
    });
    assertThat(floatPoints).anyMatch(p -> {
      Object v = p.getField("vibration");
      return v instanceof Number n && Double.compare(n.doubleValue(), 2.4) == 0;
    });
  }

  // --- helpers ---

  private void persist(MachineEntity machine, String traceId, Instant receivedAt) {
    persistCounting(machine, traceId, receivedAt, 100);
  }

  private void persistCounting(MachineEntity machine, String traceId, Instant receivedAt, long counting) {
    String msgPayload = payload(receivedAt, counting);
    var accepted = (TelemetryValidationService.Result.Accepted) validationService.validate(TOPIC, msgPayload);
    persistenceService.persist(accepted, envelope(traceId, receivedAt, msgPayload));
  }

  private void persistVibration(MachineEntity machine, String traceId, Instant receivedAt, String vibration) {
    String msgPayload = "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-opt-"
        + MESSAGE_ID_OFFSET.incrementAndGet() + "\",\"timestamp\":\"" + receivedAt
        + "\",\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":" + vibration + "}";
    var accepted = (TelemetryValidationService.Result.Accepted) validationService.validate(TOPIC, msgPayload);
    persistenceService.persist(accepted, envelope(traceId, receivedAt, msgPayload));
  }

  private TelemetryEnvelope envelope(String traceId, Instant receivedAt, String msgPayload) {
    return new TelemetryEnvelope(traceId, TOPIC, msgPayload, receivedAt);
  }

  private static Instant uniqueReceivedAt() {
    return BASE_RECEIVED_AT.plusSeconds(RECEIVED_AT_OFFSET.addAndGet(300));
  }

  private List<PointValues> telemetryPoints(Instant receivedAt) {
    String sql = """
        SELECT * FROM telemetry
        WHERE "machineCode" = 'BF-08410' AND "plantCode" = 'GM1'
          AND time >= '%s' AND time <= '%s'
        """.formatted(receivedAt.minusSeconds(60), receivedAt.plusSeconds(60));
    return queryPoints(sql);
  }

  private List<PointValues> queryPoints(String sql) {
    try (Stream<PointValues> stream = influxClient.queryPoints(sql,
        new QueryOptions(TEST_DATABASE))) {
      return stream.toList();
    } catch (Exception e) {
      throw new RuntimeException("InfluxDB query failed", e);
    }
  }

  private MachineEntity seedPlantAndMachine() {
    return seedPlantAndMachine(List.of());
  }

  private MachineEntity seedPlantAndMachine(List<String> optionalTelemetryFields) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", now, now));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, "Forming", now, now));
    return machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group, "BF-08410", "JBF19",
        MachineStatus.ACTIVE, "Juki", LocalDate.parse("2026-05-27"), null, optionalTelemetryFields, now, now));
  }
}
