package com.syncro.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.inventory.application.InventoryReservationService.CreateReservationCommand;
import com.syncro.inventory.application.InventoryReservationService.InsufficientStockException;
import com.syncro.inventory.application.InventoryReservationService.InvalidReservationTransitionException;
import com.syncro.inventory.domain.InventoryReservationStatus;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import com.syncro.inventory.infrastructure.db.InventoryReservationRepository;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceEntity;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.math.BigDecimal;
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
 * Story 18-5: proves the consume transition serializes on the {@code findByIdForUpdate}
 * row lock. Two threads race to consume the SAME reservation; exactly one must succeed
 * and the loser must see the committed terminal status (409
 * INVALID_RESERVATION_TRANSITION). The balance reserved must drop exactly ONCE and
 * available rise exactly ONCE — the regression guard that neither the lock nor the
 * ACTIVE precondition can be dropped without double-releasing stock.
 *
 * <p>Deliberately NOT extending {@code AbstractPostgresIntegrationTest}: the race needs
 * committed data visible to independent transactions, so this class is non-transactional
 * and owns a dedicated database ({@code withDatabaseName}) — its committed rows never
 * pollute the shared reused container that the transactional suites count against.
 * Pattern anchor: {@code InventoryTransferApproveConcurrencyIntegrationTest} (story 18-4).
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
class InventoryReservationConsumeConcurrencyIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withDatabaseName("inventory_reservation_concurrency");
    postgres.withReuse(true);
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private InventoryReservationService reservationService;
  @Autowired
  private InventoryReservationRepository reservations;
  @Autowired
  private InventoryStockBalanceRepository balances;
  @Autowired
  private InventoryLocationRepository locations;
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
  @Autowired
  private AuthUserRepository users;

  /** Persisted SUPER_ADMIN (requested_by/consumed_by have FKs to auth_users). */
  private AuthenticatedUser admin(Instant now) {
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(),
        "rsv-race-admin-" + UUID.randomUUID().toString().substring(0, 8) + "@syncro.dev",
        "password", ApplicationRole.SUPER_ADMIN, true, now, now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(),
        ApplicationRole.SUPER_ADMIN);
  }

  @Test
  @DisplayName("18.5-RACE-001 P0 concurrent consume: exactly one wins, stock releases ONCE")
  void concurrentConsumeReleasesStockOnce() throws Exception {
    var now = Instant.now();
    var suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "RSV-RACE-" + suffix,
        "Reservation Race Plant", now, now));
    var location = locations.saveAndFlush(new InventoryLocationEntity(UUID.randomUUID(),
        plant.getId(), "RSV-LOC" + suffix, "Reservation Race Location", null, true, now, now));
    var sparepart = sparepart(plant, "RSV-RACE-MC-" + suffix, now);
    var row = balances.saveAndFlush(new InventoryStockBalanceEntity(UUID.randomUUID(),
        sparepart.getId(), location.getId(), new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, now, now));
    var admin = admin(now);
    var created = reservationService.create(admin, new CreateReservationCommand(sparepart.getId(),
        location.getId(), new BigDecimal("4"), "WORK_ORDER", "WO-RACE-" + suffix, null));

    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    List<InventoryReservationService.ReservationView> successes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();
    try {
      var first = executor.submit(() -> {
        start.await();
        return reservationService.consume(admin, created.id(), new BigDecimal("4"));
      });
      var second = executor.submit(() -> {
        start.await();
        return reservationService.consume(admin, created.id(), new BigDecimal("4"));
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
    assertThat(failures.get(0)).isInstanceOf(InvalidReservationTransitionException.class);
    var stored = reservations.findById(created.id()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(InventoryReservationStatus.CONSUMED);
    assertThat(stored.getRemainingQuantity()).isEqualByComparingTo("0");
    // The decisive assertion: the balance moved exactly ONCE (available 6, reserved 4 -> 0,
    // consumed 0 -> 4) — never released twice.
    assertThat(balances.findById(row.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("6");
    assertThat(balances.findById(row.getId()).orElseThrow().getReserved())
        .isEqualByComparingTo("0");
    assertThat(balances.findById(row.getId()).orElseThrow().getConsumed())
        .isEqualByComparingTo("4");
  }

  @Test
  @DisplayName("18.5-RACE-002 P0 concurrent create: exactly one wins, one reservation created")
  void concurrentCreateWinsOnce() throws Exception {
    var now = Instant.now();
    var suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "CRT-RACE-" + suffix,
        "Create Race Plant", now, now));
    var location = locations.saveAndFlush(new InventoryLocationEntity(UUID.randomUUID(),
        plant.getId(), "CRT-LOC" + suffix, "Create Race Location", null, true, now, now));
    var sparepart = sparepart(plant, "CRT-RACE-MC-" + suffix, now);
    var row = balances.saveAndFlush(new InventoryStockBalanceEntity(UUID.randomUUID(),
        sparepart.getId(), location.getId(), new BigDecimal("1"), BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, now, now));
    var admin = admin(now);

    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    List<InventoryReservationService.ReservationView> successes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();
    try {
      var first = executor.submit(() -> {
        start.await();
        return reservationService.create(admin, new CreateReservationCommand(sparepart.getId(),
            location.getId(), new BigDecimal("1"), "WORK_ORDER", "WO-CRT-" + suffix, null));
      });
      var second = executor.submit(() -> {
        start.await();
        return reservationService.create(admin, new CreateReservationCommand(sparepart.getId(),
            location.getId(), new BigDecimal("1"), "WORK_ORDER", "WO-CRT-" + suffix, null));
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
    assertThat(failures.get(0)).isInstanceOf(InsufficientStockException.class);
    assertThat(balances.findById(row.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("0");
    assertThat(balances.findById(row.getId()).orElseThrow().getReserved())
        .isEqualByComparingTo("1");
  }

  private SparepartEntity sparepart(PlantEntity plant, String materialCode, Instant now) {
    var tag = materialCode;
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant,
        "Race Group " + tag, now, now));
    var machine = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group,
        "RACE-MCH-" + tag, "Race Machine " + tag, MachineStatus.ACTIVE, null, null, null,
        List.of(), now, now));
    var category = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.CATEGORY, "RACEC" + tag, "Race Category " + tag, now, now));
    var brand = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.BRAND, "RACEB" + tag, "Race Brand " + tag, category, now, now));
    var kind = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.KIND, "RACEK" + tag, "Race Kind " + tag, category, now, now));
    var type = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.TYPE, "RACET" + tag, "Race Type " + tag, category, now, now));
    var entity = new SparepartEntity(UUID.randomUUID(), "RACE-SP-" + tag, "Race Part " + tag,
        machine, category, brand, kind, type, now, now);
    entity.updateProcurement(materialCode, null, now);
    return spareparts.saveAndFlush(entity);
  }
}
