package com.syncro.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.inventory.application.InventoryStockService.AdjustBalanceCommand;
import com.syncro.inventory.application.InventoryStockService.InventoryStockForbiddenException;
import com.syncro.inventory.application.InventoryStockService.NegativeStockRejectedException;
import com.syncro.inventory.application.InventoryStockService.StockBalanceNotFoundException;
import com.syncro.inventory.application.InventoryStockService.UpdateBalanceCommand;
import com.syncro.inventory.application.InventoryStockService.UpsertBalanceCommand;
import com.syncro.inventory.application.InventoryStockService.VersionConflictException;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceEntity;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceRepository;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Story 15-1 re-key of the 12-4 stock service tests onto
 * {@code inventory_stock_balances}: UUID PK over (sparepart_id, location_id),
 * available/reserved/consumed/minimum_stock semantics, reorder rule
 * available &le; minimum_stock, PICKED_UP consumption moves the lifetime
 * consumed total.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryStockServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
  private static final String DEFAULT_LOCATION_CODE = "GUDANG-UTAMA";

  @Mock
  private InventoryStockBalanceRepository balances;
  @Mock
  private InventoryLocationRepository locations;
  @Mock
  private SparepartRepository spareparts;
  @Mock
  private AuthUserPlantAssignmentRepository assignments;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID locationId = UUID.randomUUID();
  private final UUID sparepartId = UUID.randomUUID();
  private final String materialCode = "MC-0001";
  private final UUID userId = UUID.randomUUID();

  private InventoryStockService service;

  @BeforeEach
  void setUp() {
    service = new InventoryStockService(balances, locations, spareparts, assignments, auditLog, clock);
    lenient().when(spareparts.findByMaterialCodeIgnoreCase(materialCode))
        .thenReturn(Optional.of(sparepartEntity()));
    lenient().when(locations.findByPlantIdAndCodeIgnoreCase(plantId, DEFAULT_LOCATION_CODE))
        .thenReturn(Optional.of(locationEntity()));
    lenient().when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, plantId, NOW)));
    lenient().when(balances.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  private AuthenticatedUser inventoryUser() {
    return new AuthenticatedUser(userId.toString(), "inv@test", ApplicationRole.INVENTORY_MAINTENANCE);
  }

  private AuthenticatedUser technicianUser() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "tech@test", ApplicationRole.TECHNICIAN);
  }

  private SparepartEntity sparepartEntity() {
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(UUID.randomUUID(), plant, "Group", NOW, NOW);
    var machine = new com.syncro.machine.infrastructure.MachineEntity(UUID.randomUUID(), plant, group,
        "M-001", "Machine", com.syncro.machine.domain.MachineStatus.ACTIVE, null, null, null, List.of(), NOW, NOW);
    var category = new com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity(
        UUID.randomUUID(), com.syncro.sparepart.domain.SparepartTaxonomyDimension.CATEGORY, "ELECTRIC", "Electric", NOW, NOW);
    var entity = new SparepartEntity(sparepartId, "BOM-001", "Part", machine, category, null, null, null, NOW, NOW);
    entity.updateProcurement(materialCode, null, NOW);
    return entity;
  }

  private InventoryLocationEntity locationEntity() {
    return new InventoryLocationEntity(locationId, plantId, DEFAULT_LOCATION_CODE, "GUDANG UTAMA",
        null, true, NOW, NOW);
  }

  private InventoryStockBalanceEntity balanceEntity(BigDecimal available, BigDecimal reserved,
      BigDecimal consumed, BigDecimal minimumStock, long version) {
    var entity = new InventoryStockBalanceEntity(UUID.randomUUID(), sparepartId, locationId,
        available, reserved, consumed, minimumStock, NOW, NOW);
    org.springframework.test.util.ReflectionTestUtils.setField(entity, "version", version);
    return entity;
  }

  @Test
  @DisplayName("12.4-STK-001 P0 STOCK_CREATE — upsert creates a row with provided values and audits CREATE")
  void createOk() {
    var user = inventoryUser();
    when(balances.findBySparepartIdAndLocationId(sparepartId, locationId)).thenReturn(Optional.empty());

    var created = service.upsert(user, new UpsertBalanceCommand(materialCode, plantId,
        new BigDecimal("10"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("5")));

    assertThat(created.sparepartId()).isEqualTo(sparepartId);
    assertThat(created.available()).isEqualByComparingTo("10");
    assertThat(created.minimumStock()).isEqualByComparingTo("5");
    assertThat(created.reorderWarning()).isFalse();
    verify(auditLog).record(eq(user), org.mockito.ArgumentMatchers.argThat(r ->
        r.action() == AuditAction.CREATE && r.entityType() == AuditEntityType.INVENTORY_STOCK_BALANCE));
  }

  @Test
  @DisplayName("12.4-STK-002 P0 STOCK_UPSERT — existing row values are overwritten and audited as UPDATE")
  void upsertExisting() {
    var user = inventoryUser();
    when(balances.findBySparepartIdAndLocationId(sparepartId, locationId))
        .thenReturn(Optional.of(balanceEntity(new BigDecimal("3"), BigDecimal.ZERO, new BigDecimal("10"), new BigDecimal("5"), 0L)));

    var updated = service.upsert(user, new UpsertBalanceCommand(materialCode, plantId,
        new BigDecimal("7"), new BigDecimal("1"), new BigDecimal("12"), new BigDecimal("4")));

    assertThat(updated.available()).isEqualByComparingTo("7");
    assertThat(updated.minimumStock()).isEqualByComparingTo("4");
    verify(auditLog).record(eq(user), org.mockito.ArgumentMatchers.argThat(r ->
        r.action() == AuditAction.UPDATE && r.entityType() == AuditEntityType.INVENTORY_STOCK_BALANCE));
  }

  @Test
  @DisplayName("12.4-STK-003 P0 STOCK_ADJUST_OK — delta -3 on available 10 → 7")
  void adjustOk() {
    var user = inventoryUser();
    when(balances.findBySparepartIdAndLocationId(sparepartId, locationId))
        .thenReturn(Optional.of(balanceEntity(new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L)))
        .thenReturn(Optional.of(balanceEntity(new BigDecimal("7"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1L)));
    when(balances.adjustAvailableIfSufficient(sparepartId, locationId, new BigDecimal("-3"), NOW)).thenReturn(1);

    var result = service.adjust(user, materialCode, new AdjustBalanceCommand(plantId, new BigDecimal("-3")));

    assertThat(result.available()).isEqualByComparingTo("7");
    verify(auditLog).record(eq(user), org.mockito.ArgumentMatchers.argThat(r ->
        r.action() == AuditAction.UPDATE && r.entityType() == AuditEntityType.INVENTORY_STOCK_BALANCE));
  }

  @Test
  @DisplayName("12.4-STK-004 P0 STOCK_ADJUST_NEGATIVE — delta -5 on available 2 → 409 NEGATIVE_STOCK_REJECTED")
  void adjustNegativeRejected() {
    var user = inventoryUser();
    when(balances.findBySparepartIdAndLocationId(sparepartId, locationId))
        .thenReturn(Optional.of(balanceEntity(new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L)));
    when(balances.adjustAvailableIfSufficient(sparepartId, locationId, new BigDecimal("-5"), NOW)).thenReturn(0);

    assertThatThrownBy(() -> service.adjust(user, materialCode, new AdjustBalanceCommand(plantId, new BigDecimal("-5"))))
        .isInstanceOf(NegativeStockRejectedException.class);
  }

  @Test
  @DisplayName("12.4-STK-005 P0 STOCK_VERSION_CONFLICT — stale version → 409 VERSION_CONFLICT")
  void versionConflict() {
    var user = inventoryUser();
    when(balances.findBySparepartIdAndLocationId(sparepartId, locationId))
        .thenReturn(Optional.of(balanceEntity(new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 5L)));

    assertThatThrownBy(() -> service.update(user, materialCode,
        new UpdateBalanceCommand(plantId, 3L, new BigDecimal("8"), null, null, null)))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  @DisplayName("12.4-STK-006 P0 STOCK_REORDER — available 4 ≤ minimum_stock 5 → row in reorder warnings")
  void reorderWarningListed() {
    var user = inventoryUser();
    var warning = balanceEntity(new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("20"), new BigDecimal("5"), 0L);
    when(balances.findReorderWarnings(plantId)).thenReturn(List.of(warning));

    var warnings = service.reorderWarnings(user, plantId);

    assertThat(warnings).hasSize(1);
    assertThat(warnings.getFirst().reorderWarning()).isTrue();
    assertThat(warnings.getFirst().consumed()).isEqualByComparingTo("20");
  }

  @Test
  @DisplayName("12.4-STK-007 P0 STOCK_REORDER_OK — available 6 > minimum_stock 5 → not a warning")
  void reorderWarningNotListed() {
    var user = inventoryUser();
    var fine = balanceEntity(new BigDecimal("6"), BigDecimal.ZERO, new BigDecimal("20"), new BigDecimal("5"), 0L);
    when(balances.findReorderWarnings(plantId)).thenReturn(List.of(fine));

    var warnings = service.reorderWarnings(user, plantId);

    assertThat(warnings).hasSize(1);
    assertThat(warnings.getFirst().reorderWarning()).isFalse();
  }

  @Test
  @DisplayName("12.4-STK-008 P0 FORBIDDEN — technician without inventory role cannot mutate stock")
  void forbiddenRole() {
    var user = technicianUser();

    assertThatThrownBy(() -> service.upsert(user, new UpsertBalanceCommand(materialCode, plantId,
        BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)))
        .isInstanceOf(InventoryStockForbiddenException.class);
  }

  @Test
  @DisplayName("12.4-STK-009 P0 NOT_FOUND — adjusting a missing balance row → 404")
  void adjustMissingRow() {
    var user = inventoryUser();
    when(balances.findBySparepartIdAndLocationId(sparepartId, locationId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.adjust(user, materialCode, new AdjustBalanceCommand(plantId, BigDecimal.ONE)))
        .isInstanceOf(StockBalanceNotFoundException.class);
  }

  @Test
  @DisplayName("12.4-STK-010 P0 PICKUP_CONSUME — consumeOnPickup decrements available and raises consumed")
  void consumeOnPickupOk() {
    when(balances.findBySparepartIdAndLocationId(sparepartId, locationId))
        .thenReturn(Optional.of(balanceEntity(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("40"), BigDecimal.ZERO, 0L)))
        .thenReturn(Optional.of(balanceEntity(new BigDecimal("7"), BigDecimal.ZERO, new BigDecimal("43"), BigDecimal.ZERO, 1L)));
    when(balances.consumeIfSufficient(sparepartId, locationId, new BigDecimal("3"), NOW)).thenReturn(1);

    var result = service.consumeOnPickup(materialCode, plantId, new BigDecimal("3"));

    assertThat(result).isNotNull();
    assertThat(result.available()).isEqualByComparingTo("7");
    assertThat(result.consumed()).isEqualByComparingTo("43");
  }

  @Test
  @DisplayName("12.4-STK-011 P0 PICKUP_NO_STOCK — missing row returns null (silent skip)")
  void consumeOnPickupMissingRow() {
    when(balances.findBySparepartIdAndLocationId(sparepartId, locationId)).thenReturn(Optional.empty());

    var result = service.consumeOnPickup(materialCode, plantId, new BigDecimal("3"));

    assertThat(result).isNull();
  }

  @Test
  @DisplayName("12.4-STK-012 P0 PICKUP_NEGATIVE — consumption below zero throws NEGATIVE_STOCK_REJECTED")
  void consumeOnPickupNegative() {
    when(balances.findBySparepartIdAndLocationId(sparepartId, locationId))
        .thenReturn(Optional.of(balanceEntity(new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L)));
    when(balances.consumeIfSufficient(sparepartId, locationId, new BigDecimal("5"), NOW)).thenReturn(0);

    assertThatThrownBy(() -> service.consumeOnPickup(materialCode, plantId, new BigDecimal("5")))
        .isInstanceOf(NegativeStockRejectedException.class);
  }
}
