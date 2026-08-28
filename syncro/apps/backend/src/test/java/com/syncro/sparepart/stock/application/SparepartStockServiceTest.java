package com.syncro.sparepart.stock.application;

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
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.stock.application.SparepartStockService.AdjustStockCommand;
import com.syncro.sparepart.stock.application.SparepartStockService.NegativeStockRejectedException;
import com.syncro.sparepart.stock.application.SparepartStockService.SparepartStockForbiddenException;
import com.syncro.sparepart.stock.application.SparepartStockService.SparepartStockNotFoundException;
import com.syncro.sparepart.stock.application.SparepartStockService.UpdateStockCommand;
import com.syncro.sparepart.stock.application.SparepartStockService.UpsertStockCommand;
import com.syncro.sparepart.stock.application.SparepartStockService.VersionConflictException;
import com.syncro.sparepart.stock.infrastructure.db.SparepartStockEntity;
import com.syncro.sparepart.stock.infrastructure.db.SparepartStockId;
import com.syncro.sparepart.stock.infrastructure.db.SparepartStockRepository;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SparepartStockServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

  @Mock
  private SparepartStockRepository stocks;
  @Mock
  private SparepartRepository spareparts;
  @Mock
  private AuthUserPlantAssignmentRepository assignments;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final String materialCode = "MC-0001";
  private final UUID userId = UUID.randomUUID();

  private SparepartStockService service;

  @BeforeEach
  void setUp() {
    service = new SparepartStockService(stocks, spareparts, assignments, auditLog, clock);
    lenient().when(spareparts.findByMaterialCodeIgnoreCase(materialCode))
        .thenReturn(Optional.of(sparepartEntity()));
    lenient().when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, plantId, NOW)));
    lenient().when(stocks.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
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
    var entity = new SparepartEntity(UUID.randomUUID(), "BOM-001", "Part", machine, category, null, null, null, NOW, NOW);
    entity.updateProcurement(materialCode, null, NOW);
    return entity;
  }

  private SparepartStockEntity stockEntity(BigDecimal onHand, BigDecimal op, BigDecimal oq, long version) {
    return new SparepartStockEntity(materialCode, plantId, onHand, op, oq, NOW, NOW);
  }

  private SparepartStockEntity stocked(BigDecimal onHand, BigDecimal op, BigDecimal oq, long version) {
    var entity = stockEntity(onHand, op, oq, version);
    org.springframework.test.util.ReflectionTestUtils.setField(entity, "version", version);
    return entity;
  }

  @Test
  @DisplayName("12.4-STK-001 P0 STOCK_CREATE — upsert creates a row with provided values and audits CREATE")
  void createOk() {
    var user = inventoryUser();
    when(stocks.findById(new SparepartStockId(materialCode, plantId))).thenReturn(Optional.empty());

    var created = service.upsert(user, new UpsertStockCommand(materialCode, plantId,
        new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("20")));

    assertThat(created.materialCode()).isEqualTo(materialCode);
    assertThat(created.stockOnHand()).isEqualByComparingTo("10");
    assertThat(created.orderPoint()).isEqualByComparingTo("5");
    assertThat(created.orderQty()).isEqualByComparingTo("20");
    assertThat(created.reorderWarning()).isFalse();
    verify(auditLog).record(eq(user), org.mockito.ArgumentMatchers.argThat(r ->
        r.action() == AuditAction.CREATE && r.entityType() == AuditEntityType.SPAREPART_STOCK));
  }

  @Test
  @DisplayName("12.4-STK-002 P0 STOCK_UPSERT — existing row values are overwritten and audited as UPDATE")
  void upsertExisting() {
    var user = inventoryUser();
    when(stocks.findById(new SparepartStockId(materialCode, plantId)))
        .thenReturn(Optional.of(stocked(new BigDecimal("3"), new BigDecimal("5"), new BigDecimal("10"), 0L)));

    var updated = service.upsert(user, new UpsertStockCommand(materialCode, plantId,
        new BigDecimal("7"), new BigDecimal("4"), new BigDecimal("15")));

    assertThat(updated.stockOnHand()).isEqualByComparingTo("7");
    assertThat(updated.orderPoint()).isEqualByComparingTo("4");
    verify(auditLog).record(eq(user), org.mockito.ArgumentMatchers.argThat(r ->
        r.action() == AuditAction.UPDATE && r.entityType() == AuditEntityType.SPAREPART_STOCK));
  }

  @Test
  @DisplayName("12.4-STK-003 P0 STOCK_ADJUST_OK — delta -3 on on-hand 10 → 7")
  void adjustOk() {
    var user = inventoryUser();
    var id = new SparepartStockId(materialCode, plantId);
    when(stocks.findById(id)).thenReturn(Optional.of(stocked(new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, 0L)));
    when(stocks.adjustIfSufficient(materialCode, plantId, new BigDecimal("-3"), NOW)).thenReturn(1);
    when(stocks.findById(id)).thenReturn(Optional.of(stocked(new BigDecimal("7"), BigDecimal.ZERO, BigDecimal.ZERO, 1L)));

    var result = service.adjust(user, materialCode, new AdjustStockCommand(plantId, new BigDecimal("-3")));

    assertThat(result.stockOnHand()).isEqualByComparingTo("7");
    verify(auditLog).record(eq(user), org.mockito.ArgumentMatchers.argThat(r ->
        r.action() == AuditAction.UPDATE && r.entityType() == AuditEntityType.SPAREPART_STOCK));
  }

  @Test
  @DisplayName("12.4-STK-004 P0 STOCK_ADJUST_NEGATIVE — delta -5 on on-hand 2 → 409 NEGATIVE_STOCK_REJECTED")
  void adjustNegativeRejected() {
    var user = inventoryUser();
    var id = new SparepartStockId(materialCode, plantId);
    when(stocks.findById(id)).thenReturn(Optional.of(stocked(new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO, 0L)));
    when(stocks.adjustIfSufficient(materialCode, plantId, new BigDecimal("-5"), NOW)).thenReturn(0);

    assertThatThrownBy(() -> service.adjust(user, materialCode, new AdjustStockCommand(plantId, new BigDecimal("-5"))))
        .isInstanceOf(NegativeStockRejectedException.class);
  }

  @Test
  @DisplayName("12.4-STK-005 P0 STOCK_VERSION_CONFLICT — stale version → 409 VERSION_CONFLICT")
  void versionConflict() {
    var user = inventoryUser();
    var id = new SparepartStockId(materialCode, plantId);
    when(stocks.findById(id)).thenReturn(Optional.of(stocked(new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, 5L)));

    assertThatThrownBy(() -> service.update(user, materialCode,
        new UpdateStockCommand(plantId, 3L, new BigDecimal("8"), null, null)))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  @DisplayName("12.4-STK-006 P0 STOCK_REORDER — on-hand 4 ≤ OP 5 → row in reorder warnings with OQ recommendation")
  void reorderWarningListed() {
    var user = inventoryUser();
    var warning = stocked(new BigDecimal("4"), new BigDecimal("5"), new BigDecimal("20"), 0L);
    when(stocks.findReorderWarnings(plantId)).thenReturn(List.of(warning));

    var warnings = service.reorderWarnings(user, plantId);

    assertThat(warnings).hasSize(1);
    assertThat(warnings.getFirst().reorderWarning()).isTrue();
    assertThat(warnings.getFirst().orderQty()).isEqualByComparingTo("20");
  }

  @Test
  @DisplayName("12.4-STK-007 P0 STOCK_REORDER_OK — on-hand 6 > OP 5 → not in the list")
  void reorderWarningNotListed() {
    var user = inventoryUser();
    var fine = stocked(new BigDecimal("6"), new BigDecimal("5"), new BigDecimal("20"), 0L);
    when(stocks.findReorderWarnings(plantId)).thenReturn(List.of(fine));

    var warnings = service.reorderWarnings(user, plantId);

    assertThat(warnings).hasSize(1);
    assertThat(warnings.getFirst().reorderWarning()).isFalse();
  }

  @Test
  @DisplayName("12.4-STK-008 P0 FORBIDDEN — technician without inventory role cannot mutate stock")
  void forbiddenRole() {
    var user = technicianUser();

    assertThatThrownBy(() -> service.upsert(user, new UpsertStockCommand(materialCode, plantId,
        BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO)))
        .isInstanceOf(SparepartStockForbiddenException.class);
  }

  @Test
  @DisplayName("12.4-STK-009 P0 NOT_FOUND — adjusting a missing stock row → 404")
  void adjustMissingRow() {
    var user = inventoryUser();
    var id = new SparepartStockId(materialCode, plantId);
    when(stocks.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.adjust(user, materialCode, new AdjustStockCommand(plantId, BigDecimal.ONE)))
        .isInstanceOf(SparepartStockNotFoundException.class);
  }

  @Test
  @DisplayName("12.4-STK-010 P0 PICKUP_DECREMENT — decrementOnPickup subtracts quantity from stock")
  void decrementOnPickupOk() {
    var id = new SparepartStockId(materialCode, plantId);
    when(stocks.findById(id)).thenReturn(Optional.of(stocked(new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, 0L)));
    when(stocks.decrementIfSufficient(materialCode, plantId, new BigDecimal("-3"), NOW)).thenReturn(1);
    when(stocks.findById(id)).thenReturn(Optional.of(stocked(new BigDecimal("7"), BigDecimal.ZERO, BigDecimal.ZERO, 1L)));

    var result = service.decrementOnPickup(materialCode, plantId, new BigDecimal("3"));

    assertThat(result).isNotNull();
    assertThat(result.stockOnHand()).isEqualByComparingTo("7");
  }

  @Test
  @DisplayName("12.4-STK-011 P0 PICKUP_NO_STOCK — missing row returns null (silent skip)")
  void decrementOnPickupMissingRow() {
    var id = new SparepartStockId(materialCode, plantId);
    when(stocks.findById(id)).thenReturn(Optional.empty());

    var result = service.decrementOnPickup(materialCode, plantId, new BigDecimal("3"));

    assertThat(result).isNull();
  }

  @Test
  @DisplayName("12.4-STK-012 P0 PICKUP_NEGATIVE — decrement below zero throws NEGATIVE_STOCK_REJECTED")
  void decrementOnPickupNegative() {
    var id = new SparepartStockId(materialCode, plantId);
    when(stocks.findById(id)).thenReturn(Optional.of(stocked(new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO, 0L)));
    when(stocks.decrementIfSufficient(materialCode, plantId, new BigDecimal("-5"), NOW)).thenReturn(0);

    assertThatThrownBy(() -> service.decrementOnPickup(materialCode, plantId, new BigDecimal("5")))
        .isInstanceOf(NegativeStockRejectedException.class);
  }
}
