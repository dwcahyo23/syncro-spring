package com.syncro.sparepart.request.application;

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
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartPriceEntryRepository;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.request.application.SparepartRequestService.CreateRequestCommand;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestForbiddenException;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestValidationException;
import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import com.syncro.sparepart.request.domain.SparepartRequestType;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestEntity;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
class SparepartRequestServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

  @Mock
  private SparepartRequestRepository requests;
  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private MachineRepository machines;
  @Mock
  private SparepartRepository spareparts;
  @Mock
  private SparepartPriceEntryRepository priceEntries;
  @Mock
  private AuditLogWriter auditLog;
  @Mock
  private OperationalScopeService scopes;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID sparepartId = UUID.randomUUID();
  private final UUID staffId = UUID.randomUUID();

  private SparepartRequestService service;
  private MachineEntity machine;

  @BeforeEach
  void setUp() {
    service = new SparepartRequestService(requests, workOrders, machines, spareparts, priceEntries, auditLog, scopes, clock);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(requests.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  @DisplayName("12.1-SVC-001 P0 SPAREPART with matching material code is REQUESTED and resolves sparepart_id")
  void createSparepartOk() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(spareparts.findByMaterialCodeIgnoreCase("MC-0001"))
        .thenReturn(Optional.of(sparepartEntity("MC-0001")));

    var created = service.create(user, new CreateRequestCommand(SparepartRequestType.SPAREPART, null, machineId,
        null, "MC-0001", 2, null, null, null, "need this"));

    assertThat(created.requestType()).isEqualTo(SparepartRequestType.SPAREPART);
    assertThat(created.status()).isEqualTo(SparepartRequestStatus.REQUESTED);
    assertThat(created.sparepartId()).isEqualTo(sparepartId);
    assertThat(created.machineId()).isEqualTo(machineId);
    assertThat(created.quantity()).isEqualTo((short) 2);
    verify(auditLog).record(eq(user), org.mockito.ArgumentMatchers.argThat(r ->
        r.action() == AuditAction.CREATE && r.entityType() == AuditEntityType.SPAREPART_REQUEST));
  }

  @Test
  @DisplayName("12.1-SVC-002 P0 new part without material code starts PENDING_COMPLETION")
  void createNewPartPending() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    var created = service.create(user, new CreateRequestCommand(SparepartRequestType.SPAREPART, null, machineId,
        null, null, 1, null, null, null, null));

    assertThat(created.status()).isEqualTo(SparepartRequestStatus.PENDING_COMPLETION);
  }

  @Test
  @DisplayName("12.1-SVC-003 P0 unmatched material code starts PENDING_COMPLETION")
  void createUnmatchedCodePending() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(spareparts.findByMaterialCodeIgnoreCase("UNKNOWN-1")).thenReturn(Optional.empty());

    var created = service.create(user, new CreateRequestCommand(SparepartRequestType.SPAREPART, null, machineId,
        null, "UNKNOWN-1", 1, null, null, null, null));

    assertThat(created.status()).isEqualTo(SparepartRequestStatus.PENDING_COMPLETION);
    assertThat(created.materialCode()).isEqualTo("UNKNOWN-1");
  }

  @Test
  @DisplayName("12.1-SVC-004 P0 CONSUMABLE requires no machine and is REQUESTED with material code")
  void createConsumableOk() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(spareparts.findByMaterialCodeIgnoreCase("CONS-1"))
        .thenReturn(Optional.of(sparepartEntity("CONS-1")));

    var created = service.create(user, new CreateRequestCommand(SparepartRequestType.CONSUMABLE, null, null,
        null, "CONS-1", 5, null, null, null, null));

    assertThat(created.requestType()).isEqualTo(SparepartRequestType.CONSUMABLE);
    assertThat(created.status()).isEqualTo(SparepartRequestStatus.REQUESTED);
    assertThat(created.machineId()).isNull();
  }

  @Test
  @DisplayName("12.1-SVC-005 P0 SERVICE_EXTERNAL without a workorder is a validation error")
  void createServiceExternalRequiresWorkOrder() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.create(user, new CreateRequestCommand(SparepartRequestType.SERVICE_EXTERNAL,
        null, null, null, null, 1, null, null, null, null)))
        .isInstanceOf(RequestValidationException.class);
  }

  @Test
  @DisplayName("12.1-SVC-006 P0 SERVICE_EXTERNAL with a valid workorder creates REQUESTED")
  void createServiceExternalOk() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    var workOrder = new WorkOrderEntity("WO-2609-00001", "INTERNAL", null, WorkOrderStatus.OPEN, null, machineId,
        "fix", 0L, null, null, null, NOW, NOW);
    when(workOrders.findById("WO-2609-00001")).thenReturn(Optional.of(workOrder));

    var created = service.create(user, new CreateRequestCommand(SparepartRequestType.SERVICE_EXTERNAL,
        "WO-2609-00001", null, null, null, 1, null, null, null, null));

    assertThat(created.workOrderId()).isEqualTo("WO-2609-00001");
    assertThat(created.status()).isEqualTo(SparepartRequestStatus.REQUESTED);
  }

  @Test
  @DisplayName("12.1-SVC-007 P0 SPAREPART without a machine is a validation error")
  void createSparepartNoMachine() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.create(user, new CreateRequestCommand(SparepartRequestType.SPAREPART, null,
        null, null, "MC-1", 1, null, null, null, null)))
        .isInstanceOf(RequestValidationException.class);
  }

  @Test
  @DisplayName("12.1-SVC-008 P0 out-of-scope user is forbidden")
  void createForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "auditor@test", ApplicationRole.AUDITOR);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.create(user, new CreateRequestCommand(SparepartRequestType.SPAREPART, null,
        machineId, null, "MC-1", 1, null, null, null, null)))
        .isInstanceOf(RequestForbiddenException.class);
  }

  @Test
  @DisplayName("12.1-SVC-009 P0 bad purchase URL is a validation error")
  void createBadUrl() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.create(user, new CreateRequestCommand(SparepartRequestType.SPAREPART, null,
        machineId, null, "MC-1", 1, null, null, "ftp://bad", null)))
        .isInstanceOf(RequestValidationException.class);
  }

  @Test
  @DisplayName("12.1-SVC-011 P0 technician without a bound workorder cannot create a standalone SPAREPART")
  void createTechnicianStandaloneForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "tech@test", ApplicationRole.TECHNICIAN);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.create(user, new CreateRequestCommand(SparepartRequestType.SPAREPART, null,
        machineId, null, "MC-1", 1, null, null, null, null)))
        .isInstanceOf(RequestForbiddenException.class);
  }

  @Test
  @DisplayName("12.1-SVC-012 P0 section leader cannot create a standalone SPAREPART outside their group")
  void createSectionLeaderOutsideGroupForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "leader@test", ApplicationRole.SECTION_LEADER);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.create(user, new CreateRequestCommand(SparepartRequestType.SPAREPART, null,
        machineId, null, "MC-1", 1, null, null, null, null)))
        .isInstanceOf(RequestForbiddenException.class);
  }

  @Test
  @DisplayName("12.1-SVC-013 P0 unknown price entry is rejected (PRICE_ENTRY_NOT_FOUND)")
  void createUnknownPriceEntry() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    var priceEntryId = UUID.randomUUID();
    when(priceEntries.existsById(priceEntryId)).thenReturn(false);

    assertThatThrownBy(() -> service.create(user, new CreateRequestCommand(SparepartRequestType.CONSUMABLE, null,
        null, null, "CONS-1", 1, priceEntryId, null, null, null)))
        .isInstanceOf(SparepartRequestService.PriceEntryNotFoundException.class);
  }

  @Test
  @DisplayName("12.1-SVC-014 P0 CONSUMABLE with a workorder binds and resolves the workorder machine's plant")
  void createConsumableWithWorkOrder() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    var workOrder = new WorkOrderEntity("WO-2609-00002", "INTERNAL", null, WorkOrderStatus.OPEN, null, machineId,
        "consumable", 0L, null, null, null, NOW, NOW);
    when(workOrders.findById("WO-2609-00002")).thenReturn(Optional.of(workOrder));
    when(spareparts.findByMaterialCodeIgnoreCase("CONS-1"))
        .thenReturn(Optional.of(sparepartEntity("CONS-1")));

    var created = service.create(user, new CreateRequestCommand(SparepartRequestType.CONSUMABLE,
        "WO-2609-00002", null, null, "CONS-1", 1, null, null, null, null));

    assertThat(created.workOrderId()).isEqualTo("WO-2609-00002");
    assertThat(created.status()).isEqualTo(SparepartRequestStatus.REQUESTED);
  }

  @Test
  @DisplayName("12.1-SVC-015 P0 SPAREPART with non-electric/mechanic category is rejected")
  void createSparepartWrongCategory() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    var hydraulic = new SparepartTaxonomyEntity(UUID.randomUUID(), null, "HYDRAULIC", "Hydraulic", NOW, NOW);
    var sparepart = new SparepartEntity(sparepartId, "BOM-002", "Part", machine,
        hydraulic, null, null, null, NOW, NOW);
    when(spareparts.findByMaterialCodeIgnoreCase("HYD-1")).thenReturn(Optional.of(sparepart));

    assertThatThrownBy(() -> service.create(user, new CreateRequestCommand(SparepartRequestType.SPAREPART, null,
        machineId, null, "HYD-1", 1, null, null, null, null)))
        .isInstanceOf(RequestValidationException.class);
  }

  @Test
  @DisplayName("12.1-SVC-016 P0 SPAREPART from a different machine is rejected")
  void createSparepartCrossMachine() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    var otherMachine = machineWithPlant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    var category = new SparepartTaxonomyEntity(UUID.randomUUID(), null, "ELECTRIC", "Electric", NOW, NOW);
    var sparepart = new SparepartEntity(UUID.randomUUID(), "BOM-003", "Part", otherMachine,
        category, null, null, null, NOW, NOW);
    when(spareparts.findByMaterialCodeIgnoreCase("MC-0099")).thenReturn(Optional.of(sparepart));

    assertThatThrownBy(() -> service.create(user, new CreateRequestCommand(SparepartRequestType.SPAREPART, null,
        machineId, null, "MC-0099", 1, null, null, null, null)))
        .isInstanceOf(RequestValidationException.class);
  }

  @Test
  @DisplayName("12.1-SVC-017 P0 quantity above short range is a validation error")
  void createQuantityOverflow() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.create(user, new CreateRequestCommand(SparepartRequestType.CONSUMABLE, null,
        null, null, null, 40000, null, null, null, null)))
        .isInstanceOf(RequestValidationException.class);
  }

  @Test
  @DisplayName("12.1-SVC-018 P0 estUnitPrice with scale above 2 is a validation error")
  void createEstUnitPriceScaleOverflow() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.create(user, new CreateRequestCommand(SparepartRequestType.CONSUMABLE, null,
        null, null, null, 1, null, new BigDecimal("0.005"), null, null)))
        .isInstanceOf(RequestValidationException.class);
  }

  @Test
  @DisplayName("12.1-SVC-010 P0 purchase reference URL is stored for the storekeeper (FR-143)")
  void createStoresUrl() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(spareparts.findByMaterialCodeIgnoreCase("MC-0001"))
        .thenReturn(Optional.of(sparepartEntity("MC-0001")));

    var created = service.create(user, new CreateRequestCommand(SparepartRequestType.SPAREPART, null, machineId,
        null, "MC-0001", 1, null, new BigDecimal("50000"), "https://supplier.example/part", null));

    assertThat(created.purchaseReferenceUrl()).isEqualTo("https://supplier.example/part");
    assertThat(created.estUnitPrice()).isEqualByComparingTo("50000");
  }

  private SparepartEntity sparepartEntity(String materialCode) {
    var category = new SparepartTaxonomyEntity(UUID.randomUUID(), null, "ELECTRIC", "Electric", NOW, NOW);
    var entity = new SparepartEntity(sparepartId, "BOM-001", "Part", machine,
        category, null, null, null, NOW, NOW);
    entity.updateProcurement(materialCode, null, NOW);
    return entity;
  }

  private AuthenticatedUser staffUser() {
    return new AuthenticatedUser(staffId.toString(), "staff@test", ApplicationRole.STAFF_MAINTENANCE);
  }

  private static MachineEntity machineWithPlant(UUID plantId, UUID groupId, UUID machineId) {
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(groupId, plant, "Group", NOW, NOW);
    return new MachineEntity(machineId, plant, group, "M-001", "Machine", MachineStatus.ACTIVE, null, null, null,
        List.of(), NOW, NOW);
  }
}
