package com.syncro.compliance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.compliance.application.EquipmentChangeNoticeService.ApproveEcnCommand;
import com.syncro.compliance.application.EquipmentChangeNoticeService.CreateEcnCommand;
import com.syncro.compliance.application.EquipmentChangeNoticeService.EquipmentChangeNoticeNotFoundException;
import com.syncro.compliance.application.EquipmentChangeNoticeService.ExecuteEcnCommand;
import com.syncro.compliance.application.EquipmentChangeNoticeService.UpdateEcnCommand;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.application.NonConformanceService.InvalidStateTransitionException;
import com.syncro.compliance.domain.EcnStatus;
import com.syncro.compliance.infrastructure.db.EquipmentChangeNoticeEntity;
import com.syncro.compliance.infrastructure.db.EquipmentChangeNoticeRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Story 21-2 unit tests for {@link EquipmentChangeNoticeService}: the full
 * DRAFT→UNDER_REVIEW→APPROVED→EXECUTED→CLOSED transition matrix (legal + illegal
 * edges), the narrower approve/execute/close role gate, the workorder evidence
 * link validation, duplicate/scope gates, and the audit trail with previous/new
 * values.
 */
@ExtendWith(MockitoExtension.class)
class EquipmentChangeNoticeServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final UUID ecnId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();

  @Mock
  private EquipmentChangeNoticeRepository notices;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private AuditLogWriter auditLog;

  private EquipmentChangeNoticeService service;

  @BeforeEach
  void setUp() {
    service = new EquipmentChangeNoticeService(notices, scopes, auditLog, CLOCK);
  }

  private AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(userId.toString(), "u@syncro.test", role);
  }

  private EquipmentChangeNoticeEntity entity(EcnStatus status) {
    return new EquipmentChangeNoticeEntity(ecnId, "ECN-1", machineId, "Retrofit guard",
        "Add light curtain", "IMPROVEMENT", "Near-miss", status, null, null, null, null, null,
        null, null, null, NOW.minusSeconds(3600), NOW.minusSeconds(3600));
  }

  private void stubVisible(EquipmentChangeNoticeEntity entity) {
    when(notices.findById(ecnId)).thenReturn(Optional.of(entity));
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(notices.countVisible(eq(ecnId), eq(true), any(), any())).thenReturn(1L);
  }

  private EquipmentChangeNoticeRepository.MachineScopeView scopeView(UUID plantId,
      UUID groupId) {
    return new EquipmentChangeNoticeRepository.MachineScopeView() {
      @Override
      public UUID getPlantId() {
        return plantId;
      }

      @Override
      public UUID getGroupId() {
        return groupId;
      }
    };
  }

  private void stubMachineScopeFound() {
    when(notices.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(
        UUID.randomUUID(), UUID.randomUUID())));
  }

  @Test
  @DisplayName("21.2-SVC-001 P0 create persists DRAFT + audits CREATE with machine plant")
  void createAudits() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    stubMachineScopeFound();
    when(notices.existsByEcnNumber("ECN-1")).thenReturn(false);
    when(notices.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create(user(ApplicationRole.STAFF_MAINTENANCE), new CreateEcnCommand(
        "ECN-1", machineId, "Retrofit guard", "Add light curtain", "IMPROVEMENT", "Near-miss",
        null));

    assertThat(view.status()).isEqualTo(EcnStatus.DRAFT);
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().action()).isEqualTo(AuditAction.CREATE);
    assertThat(captor.getValue().entityType()).isEqualTo(AuditEntityType.EQUIPMENT_CHANGE_NOTICE);
    assertThat(captor.getValue().plantId()).isNotNull();
    assertThat(captor.getValue().newValue()).containsEntry("ecnNumber", "ECN-1")
        .containsEntry("status", "DRAFT");
  }

  @Test
  @DisplayName("21.2-SVC-002 P0 unknown machine → 404 MACHINE_NOT_FOUND")
  void unknownMachine() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(notices.findMachineScope(machineId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateEcnCommand("ECN-1", machineId, "Guard", null, null, null, null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.2-SVC-003 P0 duplicate ecn_number → DUPLICATE_IDENTIFIER")
  void duplicateNumber() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    stubMachineScopeFound();
    when(notices.existsByEcnNumber("ECN-1")).thenReturn(true);

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateEcnCommand("ECN-1", machineId, "Guard", null, null, null, null)))
        .isInstanceOf(DuplicateIdentifierException.class);
    verify(notices, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-004 P0 submit stamps submitted_by + audits UPDATE DRAFT→UNDER_REVIEW")
  void submitStampsActor() {
    stubVisible(entity(EcnStatus.DRAFT));
    when(notices.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.submit(user(ApplicationRole.SECTION_LEADER), ecnId);

    assertThat(view.status()).isEqualTo(EcnStatus.UNDER_REVIEW);
    assertThat(view.submittedBy()).isEqualTo(userId);
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().previousValue()).containsEntry("status", "DRAFT");
    assertThat(captor.getValue().newValue()).containsEntry("status", "UNDER_REVIEW");
  }

  @Test
  @DisplayName("21.2-SVC-005 P0 approve stamps reviewer/approver/effective/sign-off (manager)")
  void approveStampsAll() {
    stubVisible(entity(EcnStatus.UNDER_REVIEW));
    when(notices.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.approve(user(ApplicationRole.MANAGER_MAINTENANCE), ecnId,
        new ApproveEcnCommand(LocalDate.of(2026, 10, 1)));

    assertThat(view.status()).isEqualTo(EcnStatus.APPROVED);
    assertThat(view.reviewedBy()).isEqualTo(userId);
    assertThat(view.approvedBy()).isEqualTo(userId);
    assertThat(view.effectiveDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(view.signOffAt()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("21.2-SVC-006 P0 approve/execute/close deny every non-manager role")
  void approvalGate() {
    // The role gate fires before any load — no stubbing needed (and none allowed:
    // strict stubs would flag an unused findById).
    for (ApplicationRole role : new ApplicationRole[] {ApplicationRole.SECTION_LEADER,
        ApplicationRole.MAINTENANCE_LEADER, ApplicationRole.STAFF_MAINTENANCE,
        ApplicationRole.PRODUCTION_LEADER, ApplicationRole.TECHNICIAN,
        ApplicationRole.AUDITOR}) {
      assertThatThrownBy(() -> service.approve(user(role), ecnId, new ApproveEcnCommand(null)))
          .as("approve denied for %s", role).isInstanceOf(ComplianceForbiddenException.class);
      assertThatThrownBy(() -> service.execute(user(role), ecnId,
          new ExecuteEcnCommand(null, null)))
          .as("execute denied for %s", role).isInstanceOf(ComplianceForbiddenException.class);
      assertThatThrownBy(() -> service.close(user(role), ecnId))
          .as("close denied for %s", role).isInstanceOf(ComplianceForbiddenException.class);
    }
    verify(notices, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-007 P0 execute links validated workorder; unknown → WORK_ORDER_NOT_FOUND")
  void executeLinksWorkOrder() {
    var plant = UUID.randomUUID();
    stubVisible(entity(EcnStatus.APPROVED));
    when(notices.countWorkOrder("WO-1")).thenReturn(1L);
    when(notices.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(plant,
        UUID.randomUUID())));
    when(notices.findWorkOrderMachinePlant("WO-1")).thenReturn(Optional.of(plant));
    when(notices.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.execute(user(ApplicationRole.SUPER_ADMIN), ecnId,
        new ExecuteEcnCommand("WO-1", "garage://after.jpg"));

    assertThat(view.status()).isEqualTo(EcnStatus.EXECUTED);
    assertThat(view.executedWoId()).isEqualTo("WO-1");
    assertThat(view.afterPhotoUrl()).isEqualTo("garage://after.jpg");

    stubVisible(entity(EcnStatus.APPROVED));
    when(notices.countWorkOrder("NOPE")).thenReturn(0L);
    assertThatThrownBy(() -> service.execute(user(ApplicationRole.SUPER_ADMIN), ecnId,
        new ExecuteEcnCommand("NOPE", null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("WORK_ORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.2-SVC-007b P0 execute rejects a cross-plant workorder evidence link (M6)")
  void executeRejectsCrossPlantWorkOrder() {
    stubVisible(entity(EcnStatus.APPROVED));
    when(notices.countWorkOrder("WO-X")).thenReturn(1L);
    when(notices.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(
        UUID.randomUUID(), UUID.randomUUID())));
    when(notices.findWorkOrderMachinePlant("WO-X")).thenReturn(Optional.of(UUID.randomUUID()));

    assertThatThrownBy(() -> service.execute(user(ApplicationRole.MANAGER_MAINTENANCE), ecnId,
        new ExecuteEcnCommand("WO-X", null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("WORK_ORDER_NOT_FOUND"));
    verify(notices, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-007c P1 blank afterPhotoUrl normalized to null on execute (L13)")
  void executeNormalizesBlankPhoto() {
    stubVisible(entity(EcnStatus.APPROVED));
    when(notices.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.execute(user(ApplicationRole.MANAGER_MAINTENANCE), ecnId,
        new ExecuteEcnCommand(null, "   "));

    assertThat(view.afterPhotoUrl()).isNull();
    assertThat(view.executedWoId()).isNull();
  }

  @Test
  @DisplayName("21.2-SVC-008 P0 full transition matrix: only the four forward edges are legal")
  void transitionMatrix() {
    // submit from non-DRAFT
    stubVisible(entity(EcnStatus.APPROVED));
    assertThatThrownBy(() -> service.submit(user(ApplicationRole.SECTION_LEADER), ecnId))
        .isInstanceOf(InvalidStateTransitionException.class);
    // approve from DRAFT (skipping submit)
    stubVisible(entity(EcnStatus.DRAFT));
    assertThatThrownBy(() -> service.approve(user(ApplicationRole.MANAGER_MAINTENANCE), ecnId,
        new ApproveEcnCommand(null)))
        .isInstanceOf(InvalidStateTransitionException.class);
    // execute from UNDER_REVIEW
    stubVisible(entity(EcnStatus.UNDER_REVIEW));
    assertThatThrownBy(() -> service.execute(user(ApplicationRole.MANAGER_MAINTENANCE), ecnId,
        new ExecuteEcnCommand(null, null)))
        .isInstanceOf(InvalidStateTransitionException.class);
    // close from APPROVED (must execute first)
    stubVisible(entity(EcnStatus.APPROVED));
    assertThatThrownBy(() -> service.close(user(ApplicationRole.MANAGER_MAINTENANCE), ecnId))
        .isInstanceOf(InvalidStateTransitionException.class);
    // close from EXECUTED is legal
    stubVisible(entity(EcnStatus.EXECUTED));
    when(notices.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    assertThat(service.close(user(ApplicationRole.MANAGER_MAINTENANCE), ecnId).status())
        .isEqualTo(EcnStatus.CLOSED);
    // CLOSED is terminal
    stubVisible(entity(EcnStatus.CLOSED));
    assertThatThrownBy(() -> service.submit(user(ApplicationRole.SECTION_LEADER), ecnId))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  @DisplayName("21.2-SVC-009 P0 out-of-scope detail is 404 ECN_NOT_FOUND (existence not leaked)")
  void outOfScopeNotFound() {
    when(notices.findById(ecnId)).thenReturn(Optional.of(entity(EcnStatus.DRAFT)));
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(UUID.randomUUID()),
        Set.of(UUID.randomUUID()), Set.of()));
    when(notices.countVisible(eq(ecnId), eq(false), any(), any())).thenReturn(0L);

    assertThatThrownBy(() -> service.get(user(ApplicationRole.SECTION_LEADER), ecnId))
        .isInstanceOf(EquipmentChangeNoticeNotFoundException.class);
  }

  @Test
  @DisplayName("21.2-SVC-010 P1 technician cannot mutate ECNs (six-role gate)")
  void technicianDenied() {
    assertThatThrownBy(() -> service.create(user(ApplicationRole.TECHNICIAN),
        new CreateEcnCommand("ECN-1", machineId, "Guard", null, null, null, null)))
        .isInstanceOf(ComplianceForbiddenException.class);
    verify(notices, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-011 P1 create-time machine scope gate: sibling machine → FORBIDDEN")
  void createScopeGate() {
    var plant = UUID.randomUUID();
    var group = UUID.randomUUID();
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(plant), Set.of(group),
        Set.of()));
    when(notices.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(
        UUID.randomUUID(), UUID.randomUUID())));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.SECTION_LEADER),
        new CreateEcnCommand("ECN-1", machineId, "Guard", null, null, null, null)))
        .isInstanceOf(ComplianceForbiddenException.class);
  }

  @Test
  @DisplayName("21.2-SVC-012 P0 content freeze: PATCH after APPROVED → 409 (H2)")
  void contentFrozenAfterApproval() {
    stubVisible(entity(EcnStatus.APPROVED));

    assertThatThrownBy(() -> service.update(user(ApplicationRole.STAFF_MAINTENANCE), ecnId,
        new UpdateEcnCommand("Rewritten title", null, null, null, null)))
        .isInstanceOf(InvalidStateTransitionException.class);
    verify(notices, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-013 P0 blank PATCH title is rejected with fieldErrors (M5)")
  void blankUpdateFieldsRejected() {
    stubVisible(entity(EcnStatus.DRAFT));

    assertThatThrownBy(() -> service.update(user(ApplicationRole.STAFF_MAINTENANCE), ecnId,
        new UpdateEcnCommand("  ", null, null, null, null)))
        .isInstanceOfSatisfying(NonConformanceService.ComplianceValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("title"));
    verify(notices, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-014 P1 partial update: null fields keep stored values + UPDATE audit prev/new (7b)")
  void partialUpdateKeepsStoredValues() {
    stubVisible(entity(EcnStatus.DRAFT));
    when(notices.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.update(user(ApplicationRole.STAFF_MAINTENANCE), ecnId,
        new UpdateEcnCommand("Renamed guard", null, null, null, null));

    assertThat(view.title()).isEqualTo("Renamed guard");
    assertThat(view.description()).isEqualTo("Add light curtain");
    assertThat(view.justification()).isEqualTo("Near-miss");
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().action()).isEqualTo(AuditAction.UPDATE);
    assertThat(captor.getValue().previousValue()).containsEntry("title", "Retrofit guard");
    assertThat(captor.getValue().newValue()).containsEntry("title", "Renamed guard");
  }

  @Test
  @DisplayName("21.2-SVC-015 P1 duplicate-race on save classified to DUPLICATE_IDENTIFIER (7d)")
  void duplicateRaceClassified() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    stubMachineScopeFound();
    when(notices.existsByEcnNumber("ECN-1")).thenReturn(false);
    when(notices.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "violated constraint uq_equipment_change_notices_ecn_number"));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateEcnCommand("ECN-1", machineId, "Guard", null, null, null, null)))
        .isInstanceOf(DuplicateIdentifierException.class);
  }

  @Test
  @DisplayName("21.2-SVC-016 P1 unrelated constraint violation rethrown, never misclassified (7d)")
  void unrelatedRaceRethrown() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    stubMachineScopeFound();
    when(notices.existsByEcnNumber("ECN-1")).thenReturn(false);
    when(notices.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "violated constraint some_other_constraint"));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateEcnCommand("ECN-1", machineId, "Guard", null, null, null, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("21.2-SVC-017 P1 deleted-actor FK on submit classified to FORBIDDEN (M8)")
  void deletedActorFkOnSubmit() {
    stubVisible(entity(EcnStatus.DRAFT));
    when(notices.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "violates foreign key constraint fk_equipment_change_notices_submitted_by"));

    assertThatThrownBy(() -> service.submit(user(ApplicationRole.STAFF_MAINTENANCE), ecnId))
        .isInstanceOf(ComplianceForbiddenException.class);
  }
}
