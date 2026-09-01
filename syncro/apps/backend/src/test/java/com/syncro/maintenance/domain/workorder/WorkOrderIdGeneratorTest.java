package com.syncro.maintenance.domain.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.maintenance.domain.workorder.WorkOrderIdGenerator.WorkorderIdExhaustedException;
import com.syncro.maintenance.infrastructure.db.WorkOrderIdSequenceEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderIdSequenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WorkorderIdGenerator evidence (FR-110/AD-3): WO-YYMM-XXXXX format, gapless ids
 * under 20-thread concurrency against a real Postgres (FOR UPDATE lock), monthly
 * rollover driven by an injected clock, and the 99999-per-month exhaustion guard.
 *
 * <p>Self-contained context (own {@code @SpringBootTest} + container) WITHOUT the
 * shared {@code @Transactional} test base: each {@code nextId()} must run in its own
 * committed transaction so the row lock is released per call.
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
class WorkOrderIdGeneratorTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withReuse(true);
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private WorkOrderIdGenerator generator;

  @Autowired
  private WorkOrderIdSequenceRepository sequences;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private MutableClock testClock;

  @BeforeEach
  void resetSequences() {
    testClock.setInstant(Instant.parse("2024-09-15T00:00:00Z"));
    jdbc.execute("DELETE FROM workorder_id_sequences");
  }

  @Test
  @DisplayName("10.1-GEN-001 P0 generated id matches WO-YYMMXXXXX format (no dash)")
  void generatedIdMatchesFormat() {
    var id = generator.nextId();
    assertThat(id).matches("^WO-\\d{9}$");
    assertThat(id).isEqualTo("WO-240900001");
  }

  @Test
  @DisplayName("10.1-GEN-002 P0 twenty concurrent nextId() calls yield twenty unique gapless ids")
  void concurrentGenerationProducesUniqueGaplessIds() throws Exception {
    // No pre-seed: the 20 threads simultaneously bootstrap a brand-new month prefix
    // (insertIfAbsent ON CONFLICT DO NOTHING + FOR UPDATE), exercising the first-call race.
    var executor = Executors.newFixedThreadPool(20);
    try {
      var ids = ConcurrentHashMap.<String>newKeySet();
      var futures = new ArrayList<java.util.concurrent.Future<Void>>();
      for (int i = 0; i < 20; i++) {
        futures.add(executor.submit(() -> {
          ids.add(generator.nextId());
          return null;
        }));
      }
      for (var future : futures) {
        future.get();
      }

      assertThat(ids).hasSize(20);
      assertThat(ids.stream().map(WorkOrderIdGeneratorTest::sequenceOf).sorted().toList())
          .isEqualTo(IntStream.rangeClosed(1, 20).boxed().toList());
      var row = sequences.findById("2409").orElseThrow();
      assertThat(row.getLastSeq()).isEqualTo(20);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("10.1-GEN-003 P0 advancing the month resets the sequence to 00001")
  void monthRolloverResetsSequence() {
    assertThat(generator.nextId()).isEqualTo("WO-240900001");
    assertThat(generator.nextId()).isEqualTo("WO-240900002");

    testClock.setInstant(Instant.parse("2024-10-01T00:00:00Z"));

    assertThat(generator.nextId()).isEqualTo("WO-241000001");
  }

  @Test
  @DisplayName("10.1-GEN-004 P0 nextId() throws WorkorderIdExhaustedException at 99999")
  void exhaustionThrows() {
    sequences.saveAndFlush(new WorkOrderIdSequenceEntity("2409", 99999, Instant.now(testClock)));

    assertThatThrownBy(() -> generator.nextId())
        .isInstanceOf(WorkorderIdExhaustedException.class);
    var row = sequences.findById("2409").orElseThrow();
    assertThat(row.getLastSeq()).isEqualTo(99999);
  }

  @Test
  @DisplayName("10.1-GEN-006 P1 prefix rolls at plant-local midnight, not UTC midnight (DW-134)")
  void prefixRollsAtPlantLocalMidnight() {
    // 2024-09-30T17:00:00Z = 2024-10-01T00:00+07 (Asia/Jakarta) — the plant month is
    // October while UTC is still September. The prefix must be 2410, not 2409.
    testClock.setInstant(Instant.parse("2024-09-30T17:00:00Z"));
    jdbc.execute("DELETE FROM workorder_id_sequences");

    var id = generator.nextId();

    assertThat(id).isEqualTo("WO-241000001");
    var row = sequences.findById("2410").orElseThrow();
    assertThat(row.getLastSeq()).isEqualTo(1);
  }

  private static int sequenceOf(String id) {
    return Integer.parseInt(id.substring(id.length() - 5));
  }

  static class MutableClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    MutableClock(Instant instant, ZoneId zone) {
      this.instant = instant;
      this.zone = zone;
    }

    void setInstant(Instant instant) {
      this.instant = instant;
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new MutableClock(instant, zone);
    }
  }

  @TestConfiguration
  static class TestClockConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.parse("2024-09-15T00:00:00Z"), ZoneOffset.UTC);
    }
  }
}
