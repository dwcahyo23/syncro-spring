package com.syncro.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.inventory.application.InventoryTransferService.CreateTransferCommand;
import com.syncro.inventory.application.InventoryTransferService.InvalidTransferTransitionException;
import com.syncro.inventory.domain.InventoryTransferStatus;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceEntity;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceRepository;
import com.syncro.inventory.infrastructure.db.InventoryTransferRepository;
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
 * Story 18-4: proves the approve transition serializes on the {@code findByIdForUpdate}
 * row lock AND the conditional debit guard. Two threads race approve on one
 * PENDING_APPROVAL transfer; exactly one must win and the loser must see the committed
 * APPROVED status (409 INVALID_TRANSFER_TRANSITION). The destination balance must
 * increase by the quantity exactly ONCE — the regression guard that neither the lock
 * nor the {@code available - qty >= 0} guard can be dropped without double-moving
 * stock.
 *
 * <p>Deliberately NOT extending {@code AbstractPostgresIntegrationTest}: the race needs
 * committed data visible to independent transactions, so this class is non-transactional
 * and owns a dedicated database ({@code withDatabaseName}) — its committed rows never
 * pollute the shared reused container that the transactional suites count against.
 * Pattern anchor: {@code SparepartReviewConcurrencyIntegrationTest} (story 18-1).
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
class InventoryTransferApproveConcurrencyIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withDatabaseName("inventory_transfer_concurrency");
    postgres.withReuse(true);
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private InventoryTransferService transferService;
  @Autowired
  private InventoryTransferRepository transfers;
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

  @Test
  @DisplayName("18.4-RACE-001 P0 concurrent approve: exactly one wins, stock moves ONCE")
  void concurrentApproveMovesStockOnce() throws Exception {
    var now = Instant.now();
    var suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "RACE-" + suffix,
        "Race Plant", now, now));
    var source = locations.saveAndFlush(new InventoryLocationEntity(UUID.randomUUID(),
        plant.getId(), "RACE-A" + suffix, "Race Source", null, true, now, now));
    var destination = locations.saveAndFlush(new InventoryLocationEntity(UUID.randomUUID(),
        plant.getId(), "RACE-B" + suffix, "Race Destination", null, true, now, now));
    var sparepart = sparepart(plant, "RACE-MC-" + suffix, now);
    balances.saveAndFlush(new InventoryStockBalanceEntity(UUID.randomUUID(), sparepart.getId(),
        source.getId(), new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        now, now));
    var destRow = balances.saveAndFlush(new InventoryStockBalanceEntity(UUID.randomUUID(),
        sparepart.getId(), destination.getId(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, now, now));

    // Requester and reviewer are distinct SUPER_ADMINs (SoD passes; scope bypassed).
    var requester = new AuthenticatedUser(UUID.randomUUID().toString(), "race-requester@syncro.dev",
        ApplicationRole.SUPER_ADMIN);
    var reviewerA = new AuthenticatedUser(UUID.randomUUID().toString(), "race-reviewer-a@syncro.dev",
        ApplicationRole.SUPER_ADMIN);
    var reviewerB = new AuthenticatedUser(UUID.randomUUID().toString(), "race-reviewer-b@syncro.dev",
        ApplicationRole.SUPER_ADMIN);
    var created = transferService.create(requester, new CreateTransferCommand(sparepart.getId(),
        source.getId(), destination.getId(), new BigDecimal("5")));

    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    List<InventoryTransferService.TransferView> successes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();
    try {
      var first = executor.submit(() -> {
        start.await();
        return transferService.approve(reviewerA, created.id());
      });
      var second = executor.submit(() -> {
        start.await();
        return transferService.approve(reviewerB, created.id());
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
    assertThat(failures.get(0)).isInstanceOf(InvalidTransferTransitionException.class);
    var stored = transfers.findById(created.id()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(InventoryTransferStatus.APPROVED);
    assertThat(stored.getReviewedBy()).isIn(UUID.fromString(reviewerA.id()),
        UUID.fromString(reviewerB.id()));
    // The decisive assertion: the destination gained 5 exactly ONCE (10 -> source 5,
    // destination 0 -> 5), never 10.
    assertThat(balances.findById(destRow.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("5");
    var sourceRow = balances.findBySparepartIdAndLocationId(sparepart.getId(), source.getId())
        .orElseThrow();
    assertThat(sourceRow.getAvailable()).isEqualByComparingTo("5");
  }

  @Test
  @DisplayName("18.4-RACE-002 P0 opposing transfers (X->Y vs Y->X) do not deadlock")
  void opposingTransfersDoNotDeadlock() throws Exception {
    var now = Instant.now();
    var suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "OPP-" + suffix,
        "Opposing Plant", now, now));
    var locX = locations.saveAndFlush(new InventoryLocationEntity(UUID.randomUUID(),
        plant.getId(), "OPP-X" + suffix, "Opposing X", null, true, now, now));
    var locY = locations.saveAndFlush(new InventoryLocationEntity(UUID.randomUUID(),
        plant.getId(), "OPP-Y" + suffix, "Opposing Y", null, true, now, now));
    var sparepart = sparepart(plant, "OPP-MC-" + suffix, now);
    // 10 units at X, 10 at Y — enough for two 5-unit opposing transfers.
    balances.saveAndFlush(new InventoryStockBalanceEntity(UUID.randomUUID(), sparepart.getId(),
        locX.getId(), new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        now, now));
    balances.saveAndFlush(new InventoryStockBalanceEntity(UUID.randomUUID(), sparepart.getId(),
        locY.getId(), new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        now, now));

    var requester = new AuthenticatedUser(UUID.randomUUID().toString(), "opp-requester@syncro.dev",
        ApplicationRole.SUPER_ADMIN);
    var reviewerA = new AuthenticatedUser(UUID.randomUUID().toString(), "opp-reviewer-a@syncro.dev",
        ApplicationRole.SUPER_ADMIN);
    var reviewerB = new AuthenticatedUser(UUID.randomUUID().toString(), "opp-reviewer-b@syncro.dev",
        ApplicationRole.SUPER_ADMIN);
    // X -> Y and Y -> X, both PENDING_APPROVAL.
    var xy = transferService.create(requester, new CreateTransferCommand(sparepart.getId(),
        locX.getId(), locY.getId(), new BigDecimal("5")));
    var yx = transferService.create(requester, new CreateTransferCommand(sparepart.getId(),
        locY.getId(), locX.getId(), new BigDecimal("5")));

    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    List<InventoryTransferService.TransferView> successes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();
    try {
      var first = executor.submit(() -> {
        start.await();
        return transferService.approve(reviewerA, xy.id());
      });
      var second = executor.submit(() -> {
        start.await();
        return transferService.approve(reviewerB, yx.id());
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

    // Without canonical lock ordering one transaction would deadlock and fail
    // with a lock-acquisition error; both must succeed here.
    for (var failure : failures) {
      failure.printStackTrace();
    }
    assertThat(successes).hasSize(2);
    assertThat(failures).isEmpty();
    // Net effect: X and Y each end at 10 (5 out, 5 in).
    assertThat(balances.findBySparepartIdAndLocationId(sparepart.getId(), locX.getId())
        .orElseThrow().getAvailable()).isEqualByComparingTo("10");
    assertThat(balances.findBySparepartIdAndLocationId(sparepart.getId(), locY.getId())
        .orElseThrow().getAvailable()).isEqualByComparingTo("10");
    assertThat(transfers.findById(xy.id()).orElseThrow().getStatus())
        .isEqualTo(InventoryTransferStatus.APPROVED);
    assertThat(transfers.findById(yx.id()).orElseThrow().getStatus())
        .isEqualTo(InventoryTransferStatus.APPROVED);
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
