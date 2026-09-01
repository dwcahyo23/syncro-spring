package com.syncro.sparepart.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.sparepart.application.SparepartService.SparepartCommand;
import com.syncro.sparepart.application.SparepartService.SparepartReviewTransitionException;
import com.syncro.sparepart.application.SparepartService.SparepartView;
import com.syncro.sparepart.domain.BomReviewStatus;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
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
 * Story 18-1: proves the review transitions serialize on the {@code findByIdForUpdate} row
 * lock. Two threads race approve vs reject on one PENDING_REVIEW sparepart; exactly one must
 * win and the loser must see the committed terminal status (409). Without the
 * {@code @Lock(PESSIMISTIC_WRITE)} both threads read PENDING_REVIEW, both pass the
 * precondition, and neither throws — the test is the lock's regression guard.
 *
 * <p>Deliberately NOT extending {@code AbstractPostgresIntegrationTest}: the race needs
 * committed data visible to independent transactions, so this class is non-transactional and
 * owns a dedicated database ({@code withDatabaseName}) — its committed sparepart/audit rows
 * never pollute the shared reused container that the transactional suites count against.
 * Pattern anchor: {@code WorkOrderIdGeneratorTest}.
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
class SparepartReviewConcurrencyIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withDatabaseName("sparepart_review_concurrency");
    postgres.withReuse(true);
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private SparepartService sparepartService;

  @Autowired
  private SparepartRepository spareparts;

  @Autowired
  private SparepartTaxonomyRepository taxonomy;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private PlantRepository plants;

  @Test
  @DisplayName("18.1-SVC-019 P0 concurrent approve vs reject: exactly one wins, the loser gets INVALID_REVIEW_TRANSITION")
  void concurrentApproveRejectSerializesOnRowLock() throws Exception {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "race-admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var created = sparepartService.create(admin, new SparepartCommand(
        machine().getId(), refs[0].getId(), refs[1].getId(), refs[2].getId(), refs[3].getId()));
    assertThat(created.reviewStatus()).isEqualTo(BomReviewStatus.PENDING_REVIEW);

    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    List<SparepartView> successes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();
    try {
      var approve = executor.submit(() -> {
        start.await();
        return sparepartService.approve(admin, created.id());
      });
      var reject = executor.submit(() -> {
        start.await();
        return sparepartService.reject(admin, created.id(), "concurrent reject");
      });
      start.countDown();
      for (var future : List.of(approve, reject)) {
        try {
          successes.add(future.get(60, TimeUnit.SECONDS));
        } catch (ExecutionException exception) {
          failures.add(exception.getCause());
        }
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(successes).hasSize(1);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0)).isInstanceOf(SparepartReviewTransitionException.class);
    var stored = spareparts.findById(created.id()).orElseThrow();
    assertThat(stored.getReviewStatus()).isEqualTo(successes.get(0).reviewStatus());
    assertThat(stored.getReviewStatus()).isIn(BomReviewStatus.ACTIVE, BomReviewStatus.REJECTED);
  }

  private MachineEntity machine() {
    var now = Instant.now();
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "RACE-PLANT-" + suffix(), "Race Plant", now, now));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, "Race Group", now, now));
    return machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group,
        "RACE-MCH-" + suffix(), "Race Machine", MachineStatus.ACTIVE, null, null, null, List.of(), now, now));
  }

  /** [category, brand, kind, type] — brand/kind/type all linked to the same category row. */
  private SparepartTaxonomyEntity[] taxonomyRefs() {
    var now = Instant.now();
    var tag = suffix();
    var category = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.CATEGORY, "RACEC" + tag, "Race Category " + tag, now, now));
    var brand = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.BRAND, "RACEB" + tag, "Race Brand " + tag, category, now, now));
    var kind = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.KIND, "RACEK" + tag, "Race Kind " + tag, category, now, now));
    var type = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.TYPE, "RACET" + tag, "Race Type " + tag, category, now, now));
    return new SparepartTaxonomyEntity[] {category, brand, kind, type};
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }
}
