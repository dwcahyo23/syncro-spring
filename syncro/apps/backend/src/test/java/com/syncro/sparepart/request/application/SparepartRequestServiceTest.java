package com.syncro.sparepart.request.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import com.syncro.maintenance.application.WorkOrderService;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartPriceEntryRepository;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.request.application.SparepartRequestService.ApproveCommand;
import com.syncro.sparepart.request.application.SparepartRequestService.CreateRequestCommand;
import com.syncro.sparepart.request.application.SparepartRequestService.MreCommand;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestForbiddenException;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestValidationException;
import com.syncro.sparepart.request.application.SparepartRequestService.SelfApprovalForbiddenException;
import com.syncro.sparepart.request.application.SparepartRequestService.TransitionCommand;
import com.syncro.sparepart.request.application.SparepartRequestService.InvalidRequestStateTransitionException;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestNotFoundException;
import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import com.syncro.sparepart.request.domain.SparepartRequestType;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestEntity;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestRepository;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestTimelineEntity;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestTimelineRepository;
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
  private SparepartRequestTimelineRepository timelines;
  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private WorkOrderService workOrderService;
  @Mock
  private MachineRepository machines;
  @Mock
  private SparepartRepository spareparts;
  @Mock
  private SparepartPriceEntryRepository priceEntries;
  @Mock
  private EscalationConfigService escalationConfigs;
  @Mock
  private NotificationJobRepository notificationJobs;
  @Mock
  private AuditLogWriter auditLog;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private com.syncro.inventory.application.InventoryStockService stocks;
  @Mock
  private com.syncro.sparepart.application.SparepartService sparepartService;
  @Mock
  private com.syncro.sparepart.application.SparepartPriceEntryService priceEntryService;
  @Mock
  private com.syncro.sparepart.application.SparepartImageService sparepartImages;

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
    service = new SparepartRequestService(requests, timelines, workOrders, workOrderService, machines, spareparts,
        priceEntries, escalationConfigs, notificationJobs, auditLog, scopes, clock,
        stocks, sparepartService, priceEntryService, sparepartImages);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(requests.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(timelines.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
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
    var workOrder = new WorkOrderEntity("WO-260900001", "INTERNAL", null, WorkOrderStatus.OPEN, null, machineId,
        "fix", 0L, null, null, null, NOW, NOW);
    when(workOrders.findById("WO-260900001")).thenReturn(Optional.of(workOrder));

    var created = service.create(user, new CreateRequestCommand(SparepartRequestType.SERVICE_EXTERNAL,
        "WO-260900001", null, null, null, 1, null, null, null, null));

    assertThat(created.workOrderId()).isEqualTo("WO-260900001");
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
    var workOrder = new WorkOrderEntity("WO-260900002", "INTERNAL", null, WorkOrderStatus.OPEN, null, machineId,
        "consumable", 0L, null, null, null, NOW, NOW);
    when(workOrders.findById("WO-260900002")).thenReturn(Optional.of(workOrder));
    when(spareparts.findByMaterialCodeIgnoreCase("CONS-1"))
        .thenReturn(Optional.of(sparepartEntity("CONS-1")));

    var created = service.create(user, new CreateRequestCommand(SparepartRequestType.CONSUMABLE,
        "WO-260900002", null, null, "CONS-1", 1, null, null, null, null));

    assertThat(created.workOrderId()).isEqualTo("WO-260900002");
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

  private SparepartRequestEntity requestEntity(SparepartRequestStatus status) {
    return new SparepartRequestEntity(UUID.randomUUID(), SparepartRequestType.SPAREPART, null, machineId,
        sparepartId, "MC-0001", (short) 2, null, null, null, status, staffId, NOW, null, NOW, NOW);
  }

  private SparepartRequestEntity requestEntityBoundToWorkOrder(SparepartRequestStatus status, String workOrderId) {
    return new SparepartRequestEntity(UUID.randomUUID(), SparepartRequestType.SERVICE_EXTERNAL, workOrderId, null,
        null, null, (short) 1, null, null, null, status, staffId, NOW, null, NOW, NOW);
  }

  private void stubBoundWorkOrder(String workOrderId) {
    var workOrder = new WorkOrderEntity(workOrderId, "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, null, machineId,
        "fix", 0L, null, null, null, NOW, NOW);
    when(workOrders.findById(workOrderId)).thenReturn(Optional.of(workOrder));
  }

  private AuthenticatedUser inventoryUser() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "inv@test", ApplicationRole.INVENTORY_MAINTENANCE);
  }

  private AuthenticatedUser sectionLeaderUser() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "leader@test", ApplicationRole.SECTION_LEADER);
  }

  private AuthenticatedUser technicianUser() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "tech@test", ApplicationRole.TECHNICIAN);
  }

  private void stubScopedMachine(UUID machineId, AuthenticatedUser user) {
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
  }

  // -------------------------------------------------------------------------
  // Story 12-2: state machine tests
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("12.2-SVC-001 P0 REQUESTED→ACKED by inventory writes timeline + audit")
  void ackOk() {
    var user = inventoryUser();
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    var result = service.transition(user, id, new TransitionCommand(SparepartRequestStatus.ACKED, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.ACKED);
    verify(timelines).saveAndFlush(any(SparepartRequestTimelineEntity.class));
    verify(auditLog).record(eq(user), org.mockito.ArgumentMatchers.argThat(r ->
        r.action() == AuditAction.UPDATE && r.entityType() == AuditEntityType.SPAREPART_REQUEST));
    verify(workOrderService, never()).recomputeProcurementState(any());
  }

  @Test
  @DisplayName("12.2-SVC-002 P0 PENDING_COMPLETION→ACKED is allowed")
  void pendingAckOk() {
    var user = inventoryUser();
    var entity = requestEntity(SparepartRequestStatus.PENDING_COMPLETION);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    var result = service.transition(user, id, new TransitionCommand(SparepartRequestStatus.ACKED, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.ACKED);
  }

  @Test
  @DisplayName("12.2-SVC-003 P0 ACKED→PROCESSING by storekeeper")
  void processOk() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "store@test", ApplicationRole.STOREKEEPER);
    var entity = requestEntity(SparepartRequestStatus.ACKED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    var result = service.transition(user, id, new TransitionCommand(SparepartRequestStatus.PROCESSING, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.PROCESSING);
  }

  @Test
  @DisplayName("12.2-SVC-004 P0 PROCESSING→READY recomputes procurement on a bound workorder")
  void readyOk() {
    var user = inventoryUser();
    var entity = requestEntityBoundToWorkOrder(SparepartRequestStatus.PROCESSING, "WO-260900001");
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubBoundWorkOrder("WO-260900001");
    stubScopedMachine(machineId, user);

    var result = service.transition(user, id, new TransitionCommand(SparepartRequestStatus.READY, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.READY);
    verify(workOrderService).recomputeProcurementState("WO-260900001");
  }

  @Test
  @DisplayName("12.2-SVC-005 P0 PROCESSING→PURCHASE_REQUESTED recomputes ON_PROCUREMENT")
  void purchaseOk() {
    var user = inventoryUser();
    var entity = requestEntityBoundToWorkOrder(SparepartRequestStatus.PROCESSING, "WO-260900001");
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubBoundWorkOrder("WO-260900001");
    stubScopedMachine(machineId, user);

    var result = service.transition(user, id, new TransitionCommand(SparepartRequestStatus.PURCHASE_REQUESTED, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.PURCHASE_REQUESTED);
    verify(workOrderService).recomputeProcurementState("WO-260900001");
  }

  @Test
  @DisplayName("12.2-SVC-006 P0 PURCHASE_REQUESTED→PART_RECEIVED recomputes")
  void partReceivedOk() {
    var user = inventoryUser();
    var entity = requestEntityBoundToWorkOrder(SparepartRequestStatus.PURCHASE_REQUESTED, "WO-260900001");
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubBoundWorkOrder("WO-260900001");
    stubScopedMachine(machineId, user);

    var result = service.transition(user, id, new TransitionCommand(SparepartRequestStatus.PART_RECEIVED, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.PART_RECEIVED);
    verify(workOrderService).recomputeProcurementState("WO-260900001");
  }

  @Test
  @DisplayName("12.2-SVC-007 P0 READY→PICKED_UP by section leader")
  void pickUpOk() {
    var user = sectionLeaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.READY);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    var result = service.transition(user, id, new TransitionCommand(SparepartRequestStatus.PICKED_UP, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.PICKED_UP);
  }

  @Test
  @DisplayName("12.2-SVC-008 P0 PICKED_UP→CLOSED by section leader")
  void closeOk() {
    var user = sectionLeaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.PICKED_UP);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    var result = service.transition(user, id, new TransitionCommand(SparepartRequestStatus.CLOSED, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.CLOSED);
  }

  @Test
  @DisplayName("12.2-SVC-009 P0 invalid edge REQUESTED→READY is rejected (409 INVALID_STATE_TRANSITION)")
  void invalidEdge() {
    var user = inventoryUser();
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    assertThatThrownBy(() -> service.transition(user, id, new TransitionCommand(SparepartRequestStatus.READY, null)))
        .isInstanceOf(InvalidRequestStateTransitionException.class);
  }

  @Test
  @DisplayName("12.2-SVC-010 P0 terminal state CLOSED→anything is rejected")
  void terminalViolation() {
    var user = sectionLeaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.CLOSED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    assertThatThrownBy(() -> service.transition(user, id, new TransitionCommand(SparepartRequestStatus.READY, null)))
        .isInstanceOf(InvalidRequestStateTransitionException.class);
  }

  @Test
  @DisplayName("12.2-SVC-011 P0 technician (not assigned) cannot ACK → FORBIDDEN")
  void forbiddenRole() {
    var user = technicianUser();
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    assertThatThrownBy(() -> service.transition(user, id, new TransitionCommand(SparepartRequestStatus.ACKED, null)))
        .isInstanceOf(RequestForbiddenException.class);
  }

  @Test
  @DisplayName("12.2-SVC-012 P0 requester (non-leader) cannot PICK_UP their own request → FORBIDDEN")
  void wrongActorClose() {
    var user = new AuthenticatedUser(staffId.toString(), "staff@test", ApplicationRole.STAFF_MAINTENANCE);
    var entity = requestEntity(SparepartRequestStatus.READY);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    assertThatThrownBy(() -> service.transition(user, id, new TransitionCommand(SparepartRequestStatus.PICKED_UP, null)))
        .isInstanceOf(RequestForbiddenException.class);
  }

  @Test
  @DisplayName("12.2-SVC-013 P0 MRE in PURCHASE_REQUESTED records timeline + audit")
  void mreOk() {
    var user = inventoryUser();
    var entity = requestEntity(SparepartRequestStatus.PURCHASE_REQUESTED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    var result = service.recordMre(user, id, new MreCommand("MRE26023xxxx", null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.PURCHASE_REQUESTED);
    verify(timelines).saveAndFlush(any(SparepartRequestTimelineEntity.class));
    verify(auditLog).record(eq(user), org.mockito.ArgumentMatchers.argThat(r ->
        r.action() == AuditAction.UPDATE && r.entityType() == AuditEntityType.SPAREPART_REQUEST));
    verify(workOrderService, never()).recomputeProcurementState(any());
  }

  @Test
  @DisplayName("12.2-SVC-014 P0 MRE in wrong state (REQUESTED) → 409 INVALID_STATE_TRANSITION")
  void mreWrongState() {
    var user = inventoryUser();
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    assertThatThrownBy(() -> service.recordMre(user, id, new MreCommand("MRE26023xxxx", null)))
        .isInstanceOf(InvalidRequestStateTransitionException.class);
  }

  @Test
  @DisplayName("12.2-SVC-015 P0 blank MRE code → 400 VALIDATION_ERROR fieldErrors.mreCode")
  void mreBlank() {
    var user = inventoryUser();
    var id = UUID.randomUUID();

    assertThatThrownBy(() -> service.recordMre(user, id, new MreCommand("   ", null)))
        .isInstanceOf(RequestValidationException.class);
  }

  // -------------------------------------------------------------------------
  // Story 12-3: approval tests (FR-142/AD-16)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("12.3-SVC-001 P0 APPROVE_OK_LOW — SECTION_LEADER approves REQUESTED with cost ≤5M → ACKED")
  void approveOkLow() {
    var user = sectionLeaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);
    when(escalationConfigs.requiredApprovalRole(any(), anyBoolean())).thenReturn("SECTION_LEADER");

    var result = service.approve(user, id, new ApproveCommand(null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.ACKED);
    verify(timelines).saveAndFlush(any(SparepartRequestTimelineEntity.class));
    verify(auditLog).record(eq(user), org.mockito.ArgumentMatchers.argThat(r ->
        r.action() == AuditAction.UPDATE && r.entityType() == AuditEntityType.SPAREPART_REQUEST));
  }

  @Test
  @DisplayName("12.3-SVC-002 P0 APPROVE_OK_HIGH — MANAGER_MAINTENANCE approves REQUESTED with cost >50M → ACKED")
  void approveOkHigh() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "mgr@test", ApplicationRole.MANAGER_MAINTENANCE);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);
    when(escalationConfigs.requiredApprovalRole(any(), anyBoolean())).thenReturn("MANAGER_MAINTENANCE");

    var result = service.approve(user, id, new ApproveCommand(null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.ACKED);
  }

  @Test
  @DisplayName("12.3-SVC-003 P0 APPROVE_OK_NO_PRICE — SECTION_LEADER approves PENDING_COMPLETION with no price → ACKED")
  void approveOkNoPrice() {
    var user = sectionLeaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.PENDING_COMPLETION);
    // Unset price
    entity = new SparepartRequestEntity(entity.getId(), entity.getRequestType(), entity.getWorkOrderId(),
        entity.getMachineId(), entity.getSparepartId(), entity.getMaterialCode(), entity.getQuantity(),
        null, null, null, entity.getStatus(), entity.getRequestedBy(), NOW, null, NOW, NOW);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);
    when(escalationConfigs.requiredApprovalRole(null, false)).thenReturn("SECTION_LEADER");

    var result = service.approve(user, id, new ApproveCommand(null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.ACKED);
  }

  @Test
  @DisplayName("12.3-SVC-004 P0 SELF_APPROVAL — requester approves their own request → 403 SELF_APPROVAL_FORBIDDEN")
  void selfApproval() {
    var user = new AuthenticatedUser(staffId.toString(), "staff@test", ApplicationRole.STAFF_MAINTENANCE);
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.approve(user, id, new ApproveCommand(null)))
        .isInstanceOf(SelfApprovalForbiddenException.class);
  }

  @Test
  @DisplayName("12.3-SVC-005 P0 INSUFFICIENT_ROLE — SECTION_LEADER cannot approve a MANAGER_MAINTENANCE tier")
  void insufficientRole() {
    var user = sectionLeaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);
    when(escalationConfigs.requiredApprovalRole(any(), anyBoolean())).thenReturn("MANAGER_MAINTENANCE");

    assertThatThrownBy(() -> service.approve(user, id, new ApproveCommand(null)))
        .isInstanceOf(RequestForbiddenException.class);
  }

  @Test
  @DisplayName("12.3-SVC-006 P0 INSUFFICIENT_SCOPE — MANAGER_MAINTENANCE without plant scope → 403 FORBIDDEN")
  void insufficientScope() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "mgr@test", ApplicationRole.MANAGER_MAINTENANCE);
    // Empty scope — no plant/group access
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);
    when(escalationConfigs.requiredApprovalRole(any(), anyBoolean())).thenReturn("MANAGER_MAINTENANCE");

    assertThatThrownBy(() -> service.approve(user, id, new ApproveCommand(null)))
        .isInstanceOf(RequestForbiddenException.class);
  }

  @Test
  @DisplayName("12.3-SVC-007 P0 WRONG_STATE — ACKED request cannot be approved → 409 INVALID_STATE_TRANSITION")
  void approveWrongState() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "mgr@test", ApplicationRole.MANAGER_MAINTENANCE);
    var entity = requestEntity(SparepartRequestStatus.ACKED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.approve(user, id, new ApproveCommand(null)))
        .isInstanceOf(InvalidRequestStateTransitionException.class);
  }

  @Test
  @DisplayName("12.3-SVC-008 P0 ACK_STOP — REQUESTED→ACKED cancels active escalation jobs for the request")
  void ackStopOnAck() {
    var user = inventoryUser();
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    service.transition(user, id, new TransitionCommand(SparepartRequestStatus.ACKED, null));

    verify(notificationJobs).cancelActiveForRequest(eq("SPAREPART_REQUEST:" + id + ":%"),
        org.mockito.ArgumentMatchers.anyList(), eq(com.syncro.notification.domain.NotificationJobStatus.CANCELLED),
        any());
  }

  @Test
  @DisplayName("12.3-SVC-009 P0 CLOSE_STOP — PICKED_UP→CLOSED cancels active escalation jobs for the request")
  void closeStopOnClose() {
    var user = sectionLeaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.PICKED_UP);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    service.transition(user, id, new TransitionCommand(SparepartRequestStatus.CLOSED, null));

    verify(notificationJobs).cancelActiveForRequest(eq("SPAREPART_REQUEST:" + id + ":%"),
        org.mockito.ArgumentMatchers.anyList(), eq(com.syncro.notification.domain.NotificationJobStatus.CANCELLED),
        any());
  }

  @Test
  @DisplayName("12.3-SVC-010 P0 allowedActions — in-scope SECTION_LEADER on a low-tier request gets approve + requiredApprovalRole")
  void allowedActionsApproveForSectionLeader() {
    var user = sectionLeaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    stubScopedMachine(machineId, user);
    when(escalationConfigs.requiredApprovalRole(any(), anyBoolean())).thenReturn("SECTION_LEADER");
    var request = SparepartRequestMapper.toDomain(entity);

    var allowed = service.allowedActionsFor(user, request);

    assertThat(allowed.allowedActions()).contains("approve");
    assertThat(allowed.requiredApprovalRole()).isEqualTo("SECTION_LEADER");
  }

  @Test
  @DisplayName("12.3-SVC-011 P0 allowedActions — the requester never gets approve (SoD)")
  void allowedActionsNoApproveForRequester() {
    var user = new AuthenticatedUser(staffId.toString(), "staff@test", ApplicationRole.SECTION_LEADER);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.REQUESTED); // requestedBy = staffId
    stubScopedMachine(machineId, user);
    var request = SparepartRequestMapper.toDomain(entity);

    var allowed = service.allowedActionsFor(user, request);

    assertThat(allowed.allowedActions()).doesNotContain("approve");
    assertThat(allowed.requiredApprovalRole()).isNull();
  }

  @Test
  @DisplayName("12.3-SVC-012 P0 allowedActions — out-of-scope leader gets no approve action")
  void allowedActionsNoApproveOutOfScope() {
    var user = sectionLeaderUser();
    // Scope does not include the request's group — not an in-scope leader.
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    stubScopedMachine(machineId, user);
    when(escalationConfigs.requiredApprovalRole(any(), anyBoolean())).thenReturn("SECTION_LEADER");
    var request = SparepartRequestMapper.toDomain(entity);

    var allowed = service.allowedActionsFor(user, request);

    assertThat(allowed.allowedActions()).doesNotContain("approve");
  }

  @Test
  @DisplayName("12.3-SVC-013 P0 allowedActions — SUPER_ADMIN gets approve regardless of role/scope")
  void allowedActionsApproveForSuperAdmin() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "sa@test", ApplicationRole.SUPER_ADMIN);
    var entity = requestEntity(SparepartRequestStatus.REQUESTED);
    stubScopedMachine(machineId, user);
    when(escalationConfigs.requiredApprovalRole(any(), anyBoolean())).thenReturn("MANAGER_MAINTENANCE");
    var request = SparepartRequestMapper.toDomain(entity);

    var allowed = service.allowedActionsFor(user, request);

    assertThat(allowed.allowedActions()).contains("approve");
    assertThat(allowed.requiredApprovalRole()).isEqualTo("MANAGER_MAINTENANCE");
  }

  @Test
  @DisplayName("12.3-SVC-014 P0 allowedActions — a broken request (missing machine) does not 500 the list")
  void allowedActionsBrokenRequestNoThrow() {
    var user = sectionLeaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    // Unbound CONSUMABLE with a workorder whose machine is missing → machineForRequest throws.
    var entity = new SparepartRequestEntity(UUID.randomUUID(), SparepartRequestType.CONSUMABLE, "WO-MISSING", null,
        null, null, (short) 1, null, null, null, SparepartRequestStatus.REQUESTED, staffId, NOW, null, NOW, NOW);
    when(workOrders.findById("WO-MISSING")).thenReturn(Optional.empty());
    var request = SparepartRequestMapper.toDomain(entity);

    var allowed = service.allowedActionsFor(user, request);

    assertThat(allowed.allowedActions()).doesNotContain("approve");
    assertThat(allowed.requiredApprovalRole()).isNull();
  }

  @Test
  @DisplayName("12.2-SVC-016 P0 unknown request id → 404 REQUEST_NOT_FOUND")
  void notFound() {
    var user = inventoryUser();
    var id = UUID.randomUUID();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.transition(user, id, new TransitionCommand(SparepartRequestStatus.ACKED, null)))
        .isInstanceOf(RequestNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // Story 12-4: PICKED_UP decrement hook + completion (FR-144/FR-146/AD-11)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("12.4-SVC-001 P0 PICKUP_DECREMENT — READY→PICKED_UP decrements stock by quantity in the same transaction")
  void pickUpDecrementsStock() {
    var user = sectionLeaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.READY);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);
    when(spareparts.findByMaterialCodeIgnoreCase("MC-0001"))
        .thenReturn(Optional.of(sparepartEntity("MC-0001")));

    var result = service.transition(user, id, new TransitionCommand(SparepartRequestStatus.PICKED_UP, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.PICKED_UP);
    verify(stocks).consumeOnPickup(eq("MC-0001"), eq(plantId), any(java.math.BigDecimal.class));
  }

  @Test
  @DisplayName("12.4-SVC-002 P0 PICKUP_NO_STOCK — PICKED_UP with no stock row is a silent skip")
  void pickUpNoStockRow() {
    var user = sectionLeaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    var entity = requestEntity(SparepartRequestStatus.READY);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);
    when(spareparts.findByMaterialCodeIgnoreCase("MC-0001"))
        .thenReturn(Optional.of(sparepartEntity("MC-0001")));
    when(stocks.consumeOnPickup(any(), any(), any())).thenReturn(null);

    var result = service.transition(user, id, new TransitionCommand(SparepartRequestStatus.PICKED_UP, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.PICKED_UP);
  }

  @Test
  @DisplayName("12.4-SVC-003 P0 PICKUP_UNKNOWN_CODE — no material code on the request → no decrement")
  void pickUpNoMaterialCode() {
    var user = sectionLeaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    var entity = new SparepartRequestEntity(UUID.randomUUID(), SparepartRequestType.SPAREPART, null, machineId,
        null, null, (short) 1, null, null, null, SparepartRequestStatus.READY, staffId, NOW, null, NOW, NOW);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    var result = service.transition(user, id, new TransitionCommand(SparepartRequestStatus.PICKED_UP, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.PICKED_UP);
    verify(stocks, never()).consumeOnPickup(any(), any(), any());
  }

  @Test
  @DisplayName("12.4-SVC-004 P0 CONSUMABLE pickup does not touch stock (no stock flow)")
  void consumablePickUpNoStock() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "leader2@test", ApplicationRole.SECTION_LEADER);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(groupId), Set.of()));
    // CONSUMABLE bound to a workorder resolves the workorder's machine (so pickup is
    // allowed), but the hook skips because the request type is not SPAREPART.
    var entity = new SparepartRequestEntity(UUID.randomUUID(), SparepartRequestType.CONSUMABLE, "WO-260900001",
        null, null, "CONS-1", (short) 1, null, null, null, SparepartRequestStatus.READY, staffId, NOW, null, NOW, NOW);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubBoundWorkOrder("WO-260900001");
    stubScopedMachine(machineId, user);

    var result = service.transition(user, id, new TransitionCommand(SparepartRequestStatus.PICKED_UP, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.PICKED_UP);
    verify(stocks, never()).consumeOnPickup(any(), any(), any());
  }

  @Test
  @DisplayName("12.4-SVC-005 P0 COMPLETE_OK — PENDING_COMPLETION with new material code creates sparepart + ACKED")
  void completeOk() {
    var user = inventoryUser();
    var entity = requestEntity(SparepartRequestStatus.PENDING_COMPLETION);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);
    when(spareparts.findByMaterialCodeIgnoreCase("MC-NEW-001")).thenReturn(Optional.empty());
    when(sparepartService.createForCompletion(user, machineId, "MC-NEW-001"))
        .thenReturn(new com.syncro.sparepart.application.SparepartService.SparepartView(
            sparepartId, "BOM-0099", machineRef(), catRef(), catRef(), catRef(), catRef(),
            "MC-NEW-001", null, null, null, null, null,
            com.syncro.sparepart.domain.BomReviewStatus.PENDING_REVIEW, null, NOW, NOW));

    var result = service.complete(user, id,
        new com.syncro.sparepart.request.application.SparepartRequestService.CompleteCommand(
            "MC-NEW-001", null, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.ACKED);
    assertThat(result.sparepartId()).isEqualTo(sparepartId);
    verify(auditLog).record(eq(user), org.mockito.ArgumentMatchers.argThat(r ->
        r.action() == AuditAction.UPDATE && r.entityType() == AuditEntityType.SPAREPART_REQUEST));
    verify(stocks, never()).consumeOnPickup(any(), any(), any());
  }

  @Test
  @DisplayName("12.4-SVC-006 P0 COMPLETE_EXISTING — material code exists → patchProcurement + bind + ACKED")
  void completeExisting() {
    var user = inventoryUser();
    var entity = requestEntity(SparepartRequestStatus.PENDING_COMPLETION);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);
    when(spareparts.findByMaterialCodeIgnoreCase("MC-0001"))
        .thenReturn(Optional.of(sparepartEntity("MC-0001")));

    var result = service.complete(user, id,
        new com.syncro.sparepart.request.application.SparepartRequestService.CompleteCommand(
            "MC-0001", null, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.ACKED);
    assertThat(result.sparepartId()).isEqualTo(sparepartId);
    verify(sparepartService).patchProcurement(eq(user), eq(sparepartId),
        any(com.syncro.sparepart.application.SparepartService.SparepartProcurementCommand.class), eq(true));
  }

  @Test
  @DisplayName("12.4-SVC-007 P0 COMPLETE_DUPLICATE — material code belongs to a different sparepart → 409")
  void completeDuplicate() {
    var user = inventoryUser();
    // The request is already bound to a different sparepart than the code's owner.
    var otherSparepartId = UUID.randomUUID();
    var entity = new SparepartRequestEntity(UUID.randomUUID(), SparepartRequestType.SPAREPART, null, machineId,
        otherSparepartId, "OTHER-CODE", (short) 1, null, null, null,
        SparepartRequestStatus.PENDING_COMPLETION, staffId, NOW, null, NOW, NOW);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);
    when(spareparts.findByMaterialCodeIgnoreCase("MC-0001"))
        .thenReturn(Optional.of(sparepartEntity("MC-0001")));

    assertThatThrownBy(() -> service.complete(user, id,
        new com.syncro.sparepart.request.application.SparepartRequestService.CompleteCommand(
            "MC-0001", null, null)))
        .isInstanceOf(SparepartRequestService.DuplicateMaterialCodeException.class);
  }

  @Test
  @DisplayName("12.4-SVC-008 P0 COMPLETE_WRONG_STATE — ACKED (not PENDING_COMPLETION) → 409 INVALID_STATE_TRANSITION")
  void completeWrongState() {
    var user = inventoryUser();
    var entity = requestEntity(SparepartRequestStatus.ACKED);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    assertThatThrownBy(() -> service.complete(user, id,
        new com.syncro.sparepart.request.application.SparepartRequestService.CompleteCommand(
            "MC-0001", null, null)))
        .isInstanceOf(InvalidRequestStateTransitionException.class);
  }

  @Test
  @DisplayName("12.4-SVC-009 P0 COMPLETE_FORBIDDEN — TECHNICIAN cannot complete → 403")
  void completeForbidden() {
    var user = technicianUser();
    var entity = requestEntity(SparepartRequestStatus.PENDING_COMPLETION);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);

    assertThatThrownBy(() -> service.complete(user, id,
        new com.syncro.sparepart.request.application.SparepartRequestService.CompleteCommand(
            "MC-0001", null, null)))
        .isInstanceOf(RequestForbiddenException.class);
  }

  @Test
  @DisplayName("12.4-SVC-010 P0 COMPLETE_NO_PRICE — completes to ACKED with no price entry when none provided")
  void completeNoPrice() {
    var user = inventoryUser();
    var entity = requestEntity(SparepartRequestStatus.PENDING_COMPLETION);
    var id = entity.getId();
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);
    when(spareparts.findByMaterialCodeIgnoreCase("MC-NEW-001")).thenReturn(Optional.empty());
    when(sparepartService.createForCompletion(user, machineId, "MC-NEW-001"))
        .thenReturn(new com.syncro.sparepart.application.SparepartService.SparepartView(
            sparepartId, "BOM-0100", machineRef(), catRef(), catRef(), catRef(), catRef(),
            "MC-NEW-001", null, null, null, null, null,
            com.syncro.sparepart.domain.BomReviewStatus.PENDING_REVIEW, null, NOW, NOW));

    var result = service.complete(user, id,
        new com.syncro.sparepart.request.application.SparepartRequestService.CompleteCommand(
            "MC-NEW-001", null, null));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.ACKED);
    verify(priceEntryService, never()).create(any(), any(), any());
  }

  @Test
  @DisplayName("12.4-SVC-011 P0 COMPLETE_IMAGE_AND_PRICE — image key attached + estPriceId referenced")
  void completeWithImageAndPrice() {
    var user = inventoryUser();
    var entity = requestEntity(SparepartRequestStatus.PENDING_COMPLETION);
    var id = entity.getId();
    var priceEntryId = UUID.randomUUID();
    var priceEntry = new com.syncro.sparepart.infrastructure.SparepartPriceEntryEntity(
        priceEntryId, sparepartEntity("MC-NEW-002"), new java.math.BigDecimal("50000"), "IDR",
        java.math.BigDecimal.ONE, new java.math.BigDecimal("50000"), null, NOW);
    when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(entity));
    stubScopedMachine(machineId, user);
    when(spareparts.findByMaterialCodeIgnoreCase("MC-NEW-002")).thenReturn(Optional.empty());
    when(sparepartService.createForCompletion(user, machineId, "MC-NEW-002"))
        .thenReturn(new com.syncro.sparepart.application.SparepartService.SparepartView(
            sparepartId, "BOM-0101", machineRef(), catRef(), catRef(), catRef(), catRef(),
            "MC-NEW-002", null, null, null, null, null,
            com.syncro.sparepart.domain.BomReviewStatus.PENDING_REVIEW, null, NOW, NOW));
    when(priceEntries.findById(priceEntryId)).thenReturn(Optional.of(priceEntry));

    var result = service.complete(user, id,
        new com.syncro.sparepart.request.application.SparepartRequestService.CompleteCommand(
            "MC-NEW-002", "spareparts/abc/image.jpg", priceEntryId));

    assertThat(result.status()).isEqualTo(SparepartRequestStatus.ACKED);
    verify(sparepartImages).attachObjectKey(eq(user), eq(sparepartId),
        eq("spareparts/abc/image.jpg"), eq(true));
  }

  private com.syncro.sparepart.application.SparepartService.SparepartMachineRefView machineRef() {
    return new com.syncro.sparepart.application.SparepartService.SparepartMachineRefView(
        machineId, "M-001", "Machine", plantId, "P01", "Plant");
  }

  private com.syncro.sparepart.application.SparepartService.SparepartTaxonomyRefView catRef() {
    return new com.syncro.sparepart.application.SparepartService.SparepartTaxonomyRefView(
        UUID.randomUUID(), "ELECTRIC", "Electric");
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
