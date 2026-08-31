package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
import com.syncro.auth.infrastructure.SignatureUseEntity;
import com.syncro.auth.infrastructure.SignatureUseRepository;
import com.syncro.maintenance.application.WorkorderSignatureService.ApproveSignatureCommand;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureAlreadyExistsException;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureForbiddenException;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureStorageException;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureValidationException;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkorderSignatureService.WorkorderNotTerminalException;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Story 14-3 unit tests re-anchored to {@code signature_uses}
 * (subject_type='WORK_ORDER', blueprint I1/DP4, story 15-1).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkorderSignatureServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
  private static final String WORKORDER_ID = "WO-2609-00001";

  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private MachineRepository machines;
  @Mock
  private SignatureUseRepository signatureUses;
  @Mock
  private com.syncro.auth.infrastructure.AuthUserRepository users;
  @Mock
  private ObjectStorageService objectStorage;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID leaderId = UUID.randomUUID();

  private WorkorderSignatureService service;
  private MachineEntity machine;

  @BeforeEach
  void setUp() {
    service = new WorkorderSignatureService(workOrders, machines, signatureUses, users, objectStorage, scopes, auditLog, clock);
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(groupId, plant, "Group", NOW, NOW);
    machine = new MachineEntity(machineId, plant, group, "M-001", "Machine",
        com.syncro.machine.domain.MachineStatus.ACTIVE, null, null, null, java.util.List.of(), NOW, NOW);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
  }

  private WorkOrderEntity entity(WorkOrderStatus status) {
    return new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, status, UUID.randomUUID(), machineId,
        "Repair pump", 0L, null, null, null, NOW, NOW);
  }

  private AuthenticatedUser leader() {
    return new AuthenticatedUser(leaderId.toString(), "leader@syncro.dev", ApplicationRole.MAINTENANCE_LEADER);
  }

  private AuthenticatedUser auditor() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "audit@syncro.dev", ApplicationRole.AUDITOR);
  }

  @Test
  @DisplayName("14.3-SIG-001 P0 in-scope leader approves a PENDING_REVIEW workorder")
  void approveDoneOk() {
    var user = leader();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.PENDING_REVIEW)));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(signatureUses.existsBySubjectTypeAndSubjectId("WORK_ORDER", WORKORDER_ID)).thenReturn(false);
    when(signatureUses.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.approve(user, WORKORDER_ID,
        new ApproveSignatureCommand("workorders/WO-2609-00001/signature/abc.png", "Leader Name"));

    assertThat(result.signerIdentity()).isEqualTo("Leader Name");
    assertThat(result.signedBy()).isEqualTo(leaderId);
    assertThat(result.signedAt()).isEqualTo(NOW);
    verify(signatureUses).saveAndFlush(argThat((SignatureUseEntity e) ->
        "WORK_ORDER".equals(e.getSubjectType()) && WORKORDER_ID.equals(e.getSubjectId())));
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.CREATE
        && r.entityType() == AuditEntityType.WORKORDER_SIGNATURE
        && r.entityLabel().equals(WORKORDER_ID)
        && "Leader Name".equals(r.newValue().get("signerIdentity"))));
  }

  @Test
  @DisplayName("14.3-SIG-002 P0 blank signer identity defaults to the approver login identifier")
  void approveDefaultsSignerIdentity() {
    var user = leader();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.CLOSED)));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(signatureUses.existsBySubjectTypeAndSubjectId("WORK_ORDER", WORKORDER_ID)).thenReturn(false);
    when(signatureUses.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.approve(user, WORKORDER_ID,
        new ApproveSignatureCommand("workorders/WO-2609-00001/signature/abc.png", "  "));

    assertThat(result.signerIdentity()).isEqualTo("leader@syncro.dev");
  }

  @Test
  @DisplayName("14.3-SIG-003 P0 a non-leader/SPV is forbidden")
  void approveForbidden() {
    var user = auditor();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.PENDING_REVIEW)));

    assertThatThrownBy(() -> service.approve(user, WORKORDER_ID,
        new ApproveSignatureCommand("workorders/WO-2609-00001/signature/abc.png", "x")))
        .isInstanceOf(SignatureForbiddenException.class);
    verify(signatureUses, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("14.3-SIG-004 P0 a non-terminal workorder cannot be signed")
  void approveNotTerminal() {
    var user = leader();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.IN_PROGRESS)));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.approve(user, WORKORDER_ID,
        new ApproveSignatureCommand("workorders/WO-2609-00001/signature/abc.png", "x")))
        .isInstanceOf(WorkorderNotTerminalException.class);
    verify(signatureUses, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("14.3-SIG-005 P0 duplicate approve is rejected with already-signed")
  void approveDuplicate() {
    var user = leader();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.PENDING_REVIEW)));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(signatureUses.existsBySubjectTypeAndSubjectId("WORK_ORDER", WORKORDER_ID)).thenReturn(true);

    assertThatThrownBy(() -> service.approve(user, WORKORDER_ID,
        new ApproveSignatureCommand("workorders/WO-2609-00001/signature/abc.png", "x")))
        .isInstanceOf(SignatureAlreadyExistsException.class);
    verify(signatureUses, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("14.3-SIG-006 P0 unknown workorder is 404")
  void approveNotFound() {
    var user = leader();
    when(workOrders.findById("WO-2609-NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.approve(user, "WO-2609-NADA",
        new ApproveSignatureCommand("key", "x")))
        .isInstanceOf(SignatureWorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("14.3-SIG-006b P0 concurrent duplicate approve hits the unique constraint → already-signed")
  void approveUniqueConstraintRace() {
    var user = leader();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.PENDING_REVIEW)));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(signatureUses.existsBySubjectTypeAndSubjectId("WORK_ORDER", WORKORDER_ID)).thenReturn(false);
    var constraint = new org.hibernate.exception.ConstraintViolationException(
        "duplicate key", null, "uq_signature_uses_work_order");
    when(signatureUses.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("constraint", constraint));

    assertThatThrownBy(() -> service.approve(user, WORKORDER_ID,
        new ApproveSignatureCommand("workorders/WO-2609-00001/signature/abc.png", "Leader")))
        .isInstanceOf(SignatureAlreadyExistsException.class);
  }

  @Test
  @DisplayName("14.3-SIG-006c P0 blank signature object key is a validation error")
  void approveBlankObjectKey() {
    var user = leader();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.PENDING_REVIEW)));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(signatureUses.existsBySubjectTypeAndSubjectId("WORK_ORDER", WORKORDER_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.approve(user, WORKORDER_ID,
        new ApproveSignatureCommand("   ", "Leader")))
        .isInstanceOf(SignatureValidationException.class);
    verify(signatureUses, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("14.3-SIG-006d P0 an oversize signature object key is a validation error")
  void approveOversizeObjectKey() {
    var user = leader();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.PENDING_REVIEW)));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(signatureUses.existsBySubjectTypeAndSubjectId("WORK_ORDER", WORKORDER_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.approve(user, WORKORDER_ID,
        new ApproveSignatureCommand("k".repeat(513), "Leader")))
        .isInstanceOf(SignatureValidationException.class);
    verify(signatureUses, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("14.3-SIG-006e P0 an oversize signer identity is a validation error")
  void approveOversizeSignerIdentity() {
    var user = leader();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.PENDING_REVIEW)));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(signatureUses.existsBySubjectTypeAndSubjectId("WORK_ORDER", WORKORDER_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.approve(user, WORKORDER_ID,
        new ApproveSignatureCommand("workorders/WO-2609-00001/signature/abc.png", "n".repeat(201))))
        .isInstanceOf(SignatureValidationException.class);
    verify(signatureUses, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("14.3-SIG-009 P0 MANAGER_MAINTENANCE with plant scope may approve")
  void approveByPlantScopedManager() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "manager@syncro.dev",
        ApplicationRole.MANAGER_MAINTENANCE);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.PENDING_REVIEW)));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(signatureUses.existsBySubjectTypeAndSubjectId("WORK_ORDER", WORKORDER_ID)).thenReturn(false);
    when(signatureUses.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.approve(user, WORKORDER_ID,
        new ApproveSignatureCommand("workorders/WO-2609-00001/signature/abc.png", "Manager"));

    assertThat(result.signerIdentity()).isEqualTo("Manager");
  }

  @Test
  @DisplayName("14.3-SIG-010 P0 presign of a missing object key surfaces the storage exception")
  void presignStorageFailure() {
    when(objectStorage.presignGetUrl(any())).thenThrow(new ObjectStorageException("garage down"));

    assertThatThrownBy(() -> service.presignSignature("workorders/WO-2609-00001/signature/abc.png"))
        .isInstanceOf(SignatureStorageException.class);
  }

  @Test
  @DisplayName("14.3-SIG-011 P0 presign returns the URL for an existing object")
  void presignOk() {
    when(objectStorage.presignGetUrl("workorders/WO-2609-00001/signature/abc.png"))
        .thenReturn("https://garage/presigned");

    assertThat(service.presignSignature("workorders/WO-2609-00001/signature/abc.png"))
        .isEqualTo("https://garage/presigned");
  }

  @Test
  @DisplayName("14.3-SIG-007 P0 read signature returns the saved values with a resolved signer identity")
  void getSignatureOk() {
    var sigId = UUID.randomUUID();
    var entity = new SignatureUseEntity(sigId, leaderId, null, "maintenance", "WORK_ORDER",
        WORKORDER_ID, "APPROVE_WORKORDER", null, null,
        "workorders/WO-2609-00001/signature/abc.png", null, null, null, null, null, NOW, NOW);
    when(signatureUses.findBySubjectTypeAndSubjectId("WORK_ORDER", WORKORDER_ID))
        .thenReturn(Optional.of(entity));
    var signer = new com.syncro.auth.infrastructure.AuthUserEntity(leaderId, "leader@syncro.dev",
        "hash", ApplicationRole.MAINTENANCE_LEADER, true, NOW, NOW);
    when(users.findById(leaderId)).thenReturn(Optional.of(signer));

    var result = service.getSignature(WORKORDER_ID);

    assertThat(result).isNotNull();
    assertThat(result.signatureObjectKey()).isEqualTo("workorders/WO-2609-00001/signature/abc.png");
    assertThat(result.signedBy()).isEqualTo(leaderId);
    assertThat(result.signerIdentity()).isEqualTo("leader@syncro.dev");
  }

  @Test
  @DisplayName("14.3-SIG-008 P0 read signature returns null when absent")
  void getSignatureAbsent() {
    when(signatureUses.findBySubjectTypeAndSubjectId("WORK_ORDER", WORKORDER_ID))
        .thenReturn(Optional.empty());

    assertThat(service.getSignature(WORKORDER_ID)).isNull();
  }
}
