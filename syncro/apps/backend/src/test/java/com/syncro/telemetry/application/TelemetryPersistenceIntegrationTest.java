package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.config.InfluxProperties;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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
    "INFLUXDB_PASSWORD=test-password",
    "INFLUXDB_TOKEN=test-token-value",
    "INFLUXDB_ORG=test",
    "INFLUXDB_BUCKET=test",
    "SYNCRO_MQTT_HOST=localhost",
    "SYNCRO_MQTT_PORT=1883",
    "SYNCRO_MQTT_USERNAME=test",
    "SYNCRO_MQTT_PASSWORD=test",
    "SYNCRO_MQTT_CLIENT_ID=test",
    "SYNCRO_MQTT_TOPIC_FILTER=factory/+/+/telemetry",
    "WAHA_HOST=localhost",
    "WAHA_PORT=3000",
    "WAHA_API_KEY=test",
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
  static final GenericContainer<?> influx = new GenericContainer<>("influxdb:2.7")
      .withEnv("DOCKER_INFLUXDB_INIT_MODE", "setup")
      .withEnv("DOCKER_INFLUXDB_INIT_USERNAME", "test")
      .withEnv("DOCKER_INFLUXDB_INIT_PASSWORD", "test-password")
      .withEnv("DOCKER_INFLUXDB_INIT_ORG", "test")
      .withEnv("DOCKER_INFLUXDB_INIT_BUCKET", "test")
      .withEnv("DOCKER_INFLUXDB_INIT_ADMIN_TOKEN", "test-token-value")
      .withExposedPorts(8086)
      .waitingFor(Wait.forHttp("/health").forPort(8086).withStartupTimeout(Duration.ofSeconds(120)));

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    registry.add("syncro.influxdb.url", () -> "http://" + influx.getHost() + ":" + influx.getMappedPort(8086));
    registry.add("syncro.influxdb.token", () -> "test-token-value");
    registry.add("syncro.influxdb.username", () -> "test");
    registry.add("syncro.influxdb.password", () -> "test-password");
    registry.add("syncro.influxdb.org", () -> "test");
    registry.add("syncro.influxdb.bucket", () -> "test");
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
  private InfluxProperties influxProperties;

  @Test
  @DisplayName("3.4-PERS-001 accepted telemetry is written to InfluxDB with machineCode/plantCode tags and fields")
  void acceptedTelemetryWrittenToInfluxDb() {
    var machine = seedPlantAndMachine();
    String traceId = "trace-pers-001";
    Instant receivedAt = uniqueReceivedAt();
    persist(machine, traceId, receivedAt);

    var records = telemetryRecords(machine, receivedAt);

    assertThat(records).isNotEmpty();
    assertThat(records).allMatch(record -> "GM1".equals(record.getValueByKey("plantCode"))
        && "BF-08410".equals(record.getValueByKey("machineCode")));
    assertThat(records.stream().filter(record -> "running".equals(record.getField())).findFirst().orElseThrow()
        .getValueByKey("_value")).isEqualTo(true);
    assertThat(records.stream().filter(record -> "runtimeHours".equals(record.getField())).findFirst().orElseThrow()
        .getValueByKey("_value")).isEqualTo(12.5);
    assertThat(records.stream().filter(record -> "counting".equals(record.getField())).findFirst().orElseThrow()
        .getValueByKey("_value")).isEqualTo(100L);
    assertThat(records.stream().filter(record -> "countingDelta".equals(record.getField())).findFirst().orElseThrow()
        .getValueByKey("_value")).isEqualTo(0L);
    assertThat(records.stream().filter(record -> "traceId".equals(record.getField())).findFirst().orElseThrow()
        .getValueByKey("_value")).isEqualTo(traceId);
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
    String payload = payload(receivedAt);
    var accepted = (TelemetryValidationService.Result.Accepted) validationService.validate(TOPIC, payload);
    persistenceService.persist(accepted, envelope("trace-pers-003", receivedAt, payload));
    persistenceService.persist(accepted, envelope("trace-pers-003-duplicate", receivedAt.plusSeconds(5), payload));

    String flux = """
        from(bucket: "test")
          |> range(start: %s, stop: %s)
          |> filter(fn: (r) => r["_measurement"] == "telemetry")
          |> filter(fn: (r) => r["machineCode"] == "BF-08410" and r["plantCode"] == "GM1")
          |> filter(fn: (r) => r["_field"] == "traceId")
          |> group()
          |> count()
        """.formatted(receivedAt.minusSeconds(60), receivedAt.plusSeconds(120));
    var records = query(flux);

    assertThat(records).hasSize(1);
    assertThat(records.get(0).getValue()).isEqualTo(1L);

    String key = "syncro:machine:" + machine.getId() + ":latest";
    assertThat(redisTemplate.opsForHash().entries(key)).containsEntry("traceId", "trace-pers-003");
  }

  @Test
  @DisplayName("3.4-PERS-004 persisted point is queryable by machineCode and plantCode tags")
  void pointQueryableByMachineAndPlantTags() {
    var machine = seedPlantAndMachine();
    Instant receivedAt = uniqueReceivedAt();
    persist(machine, "trace-pers-004", receivedAt);

    String flux = """
        from(bucket: "test")
          |> range(start: %s, stop: %s)
          |> filter(fn: (r) => r["_measurement"] == "telemetry")
          |> filter(fn: (r) => r["machineCode"] == "BF-08410" and r["plantCode"] == "GM1")
        """.formatted(receivedAt.minusSeconds(60), receivedAt.plusSeconds(60));
    var records = query(flux);

    assertThat(records).isNotEmpty();
    assertThat(records).anyMatch(record -> "counting".equals(record.getField())
        && record.getValueByKey("_value").equals(100L));
    assertThat(records.stream().filter(record -> "countingDelta".equals(record.getField())).findFirst().orElseThrow()
        .getValueByKey("_value")).isEqualTo(0L);
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

    var records = telemetryRecords(machine, receivedAt);
    assertThat(records.stream().filter(record -> "countingDelta".equals(record.getField())).findFirst().orElseThrow()
        .getValueByKey("_value")).isEqualTo(0L);
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

    var secondPointRecords = telemetryRecords(machine, secondReceivedAt);
    assertThat(secondPointRecords.stream().filter(record -> "countingDelta".equals(record.getField()))
        .findFirst().orElseThrow().getValueByKey("_value")).isEqualTo(100L);
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

    var secondPointRecords = telemetryRecords(machine, secondReceivedAt);
    assertThat(secondPointRecords.stream().filter(record -> "countingDelta".equals(record.getField()))
        .findFirst().orElseThrow().getValueByKey("_value")).isEqualTo(1L);
  }

  @Test
  @DisplayName("3.6-PERS-001 configured optional fields stored in InfluxDB history with correct types")
  void configuredOptionalFieldsStoredInInfluxDb() {
    var machine = seedPlantAndMachine(List.of("vibration", "rpm", "heaterOn", "qualityGrade"));
    String traceId = "trace-pers-601";
    Instant receivedAt = uniqueReceivedAt();
    String payload = "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-int-" + MESSAGE_ID_OFFSET.incrementAndGet()
        + "\",\"timestamp\":\"" + receivedAt + "\",\"running\":true,\"runtimeHours\":12.5,\"counting\":100,"
        + "\"vibration\":2.4,\"rpm\":1200,\"heaterOn\":true,\"qualityGrade\":\"A\",\"temperature\":30}";
    var accepted = (TelemetryValidationService.Result.Accepted) validationService.validate(TOPIC, payload);
    persistenceService.persist(accepted, envelope(traceId, receivedAt, payload));

    var records = telemetryRecords(machine, receivedAt);

    assertThat(records.stream().filter(record -> "vibration".equals(record.getField())).findFirst().orElseThrow()
        .getValueByKey("_value")).isEqualTo(2.4);
    assertThat(records.stream().filter(record -> "rpm".equals(record.getField())).findFirst().orElseThrow()
        .getValueByKey("_value")).isEqualTo(1200L);
    assertThat(records.stream().filter(record -> "heaterOn".equals(record.getField())).findFirst().orElseThrow()
        .getValueByKey("_value")).isEqualTo(true);
    assertThat(records.stream().filter(record -> "qualityGrade".equals(record.getField())).findFirst().orElseThrow()
        .getValueByKey("_value")).isEqualTo("A");
    assertThat(records).noneMatch(record -> "temperature".equals(record.getField()));
  }

  @Test
  @DisplayName("3.6-PERS-002 configured optional fields stored in Redis latest hash with optional prefix")
  void configuredOptionalFieldsStoredInRedisLatestHash() {
    var machine = seedPlantAndMachine(List.of("vibration", "heaterOn", "qualityGrade"));
    String traceId = "trace-pers-602";
    Instant receivedAt = uniqueReceivedAt();
    String payload = "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-int-" + MESSAGE_ID_OFFSET.incrementAndGet()
        + "\",\"timestamp\":\"" + receivedAt + "\",\"running\":true,\"runtimeHours\":12.5,\"counting\":100,"
        + "\"vibration\":2.4,\"heaterOn\":true,\"qualityGrade\":\"A\",\"temperature\":30}";
    var accepted = (TelemetryValidationService.Result.Accepted) validationService.validate(TOPIC, payload);
    persistenceService.persist(accepted, envelope(traceId, receivedAt, payload));

    String key = "syncro:machine:" + machine.getId() + ":latest";
    assertThat(redisTemplate.opsForHash().entries(key))
        .containsEntry("optional.vibration", "2.4")
        .containsEntry("optional.heaterOn", "true")
        .containsEntry("optional.qualityGrade", "A")
        .doesNotContainKey("optional.temperature")
        .doesNotContainKey("temperature");
  }

  private void persist(MachineEntity machine, String traceId, Instant receivedAt) {
    String payload = payload(receivedAt);
    var accepted = (TelemetryValidationService.Result.Accepted) validationService.validate(TOPIC, payload);
    persistenceService.persist(accepted, envelope(traceId, receivedAt, payload));
  }

  private void persistCounting(MachineEntity machine, String traceId, Instant receivedAt, long counting) {
    String payload = payload(receivedAt, counting);
    var accepted = (TelemetryValidationService.Result.Accepted) validationService.validate(TOPIC, payload);
    persistenceService.persist(accepted, envelope(traceId, receivedAt, payload));
  }

  private TelemetryEnvelope envelope(String traceId, Instant receivedAt, String payload) {
    return new TelemetryEnvelope(traceId, TOPIC, payload, receivedAt);
  }

  private static Instant uniqueReceivedAt() {
    return BASE_RECEIVED_AT.plusSeconds(RECEIVED_AT_OFFSET.addAndGet(300));
  }

  private List<FluxRecord> telemetryRecords(MachineEntity machine, Instant receivedAt) {
    String flux = """
        from(bucket: "test")
          |> range(start: %s, stop: %s)
          |> filter(fn: (r) => r["_measurement"] == "telemetry")
          |> filter(fn: (r) => r["machineCode"] == "BF-08410")
        """.formatted(receivedAt.minusSeconds(60), receivedAt.plusSeconds(60));
    return query(flux);
  }

  private List<FluxRecord> query(String flux) {
    return influxClient.getQueryApi().query(flux, influxProperties.org()).stream()
        .flatMap(table -> table.getRecords().stream())
        .toList();
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
