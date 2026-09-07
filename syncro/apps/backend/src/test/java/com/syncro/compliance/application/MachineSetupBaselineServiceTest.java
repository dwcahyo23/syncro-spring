package com.syncro.compliance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.compliance.application.MachineSetupBaselineService.CreateBaselineCommand;
import com.syncro.compliance.application.MachineSetupBaselineService.MachineSetupBaselineNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.infrastructure.db.MachineSetupBaselineEntity;
import com.syncro.compliance.infrastructure.db.MachineSetupBaselineRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Story 21-3 unit tests for {@link MachineSetupBaselineService}: server-assigned
 * per-machine versioning (max+1), activate-supersedes-siblings in one
 * transaction, the scoped read gate, the six-role mutation gate, and the audit
 * trail with previous/new values.
 */
@ExtendWith(MockitoExtension.class)
class MachineSetupBaselineServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final UUID baselineId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();

  @Mock
  private MachineSetupBaselineRepository baselines;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private AuditLogWriter auditLog;

  private MachineSetupBaselineService service;

  @BeforeEach
  void setUp() {
    service = new MachineSetupBaselineService(baselines, scopes, auditLog, CLOCK);
  }

  private AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(userId.toString(), "u@syncro.test", role);
  }

  private MachineSetupBaselineEntity entity(int version, boolean active) {
    return new MachineSetupBaselineEntity(baselineId, machineId, null, version,
        Map.of("clampPressureBar", 6.5), null, null, active, NOW.minusSeconds(3600),
        NOW.minusSeconds(3600));
  }

  private MachineSetupBaselineRepository.MachineScopeView scopeView(UUID plantId, UUID groupId) {
    return new MachineSetupBaselineRepository.MachineScopeView() {
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

  private void stubVisible(MachineSetupBaselineEntity entity) {
    when(baselines.findById(baselineId)).thenReturn(Optional.of(entity));
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(baselines.countVisible(eq(baselineId), eq(true), any(), any())).thenReturn(1L);
  }

  @Test
  @DisplayName("21.3-SVC-001 P0 technician/auditor cannot mutate baselines (six-role gate)")
  void readOnlyRolesDenied() {
    assertThatThrownBy(() -> service.create(user(ApplicationRole.TECHNICIAN),
        new CreateBaselineCommand(machineId, null, Map.of("a", 1))))
        .isInstanceOf(ComplianceForbiddenException.class);
    assertThatThrownBy(() -> service.activate(user(ApplicationRole.AUDITOR), baselineId))
        .isInstanceOf(ComplianceForbiddenException.class);
    verify(baselines, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.3-SVC-002 P0 create assigns version max+1, activates, audits CREATE")
  void createAssignsServerVersion() {
    var plant = UUID.randomUUID();
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(baselines.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(plant,
        UUID.randomUUID())));
    when(baselines.maxVersion(machineId)).thenReturn(4);
    when(baselines.findByMachineIdAndActiveTrue(machineId)).thenReturn(List.of());
    when(baselines.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateBaselineCommand(machineId, null, Map.of("clampPressureBar", 6.5)));

    assertThat(view.version()).isEqualTo(5);
    assertThat(view.active()).isTrue();
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().action()).isEqualTo(AuditAction.CREATE);
    assertThat(captor.getValue().entityType()).isEqualTo(AuditEntityType.MACHINE_SETUP_BASELINE);
    assertThat(captor.getValue().plantId()).isEqualTo(plant);
    assertThat(captor.getValue().newValue()).containsEntry("version", 5)
        .containsEntry("active", true);
  }

  @Test
  @DisplayName("21.3-SVC-003 P0 create supersedes the machine's active siblings in one tx")
  void createSupersedesSiblings() {
    var old = entity(1, true);
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(baselines.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(
        UUID.randomUUID(), UUID.randomUUID())));
    when(baselines.maxVersion(machineId)).thenReturn(1);
    when(baselines.findByMachineIdAndActiveTrue(machineId)).thenReturn(List.of(old));
    when(baselines.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateBaselineCommand(machineId, null, Map.of("clampPressureBar", 7.0)));

    assertThat(view.version()).isEqualTo(2);
    assertThat(old.isActive()).isFalse();
    // Two audits: sibling UPDATE (deactivation) then new CREATE — both traceable.
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog, times(2)).record(any(), captor.capture());
    var deactivation = captor.getAllValues().get(0);
    assertThat(deactivation.action()).isEqualTo(AuditAction.UPDATE);
    assertThat(deactivation.entityId()).isEqualTo(old.getId());
    assertThat(deactivation.previousValue()).containsEntry("active", true);
    assertThat(deactivation.newValue()).containsEntry("active", false);
    assertThat(captor.getAllValues().get(1).action()).isEqualTo(AuditAction.CREATE);
  }

  @Test
  @DisplayName("21.3-SVC-004 P0 activate flips the target active and its siblings inactive")
  void activateFlipsPointer() {
    var target = entity(2, false);
    var sibling = new MachineSetupBaselineEntity(UUID.randomUUID(), machineId, null, 1,
        Map.of("clampPressureBar", 6.0), null, null, true, NOW.minusSeconds(7200),
        NOW.minusSeconds(7200));
    stubVisible(target);
    when(baselines.findByMachineIdAndActiveTrue(machineId)).thenReturn(List.of(sibling));
    when(baselines.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(
        UUID.randomUUID(), UUID.randomUUID())));
    when(baselines.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.activate(user(ApplicationRole.MANAGER_MAINTENANCE), baselineId);

    assertThat(view.active()).isTrue();
    assertThat(sibling.isActive()).isFalse();
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog, times(2)).record(any(), captor.capture());
    assertThat(captor.getAllValues().get(0).newValue()).containsEntry("active", false);
    assertThat(captor.getAllValues().get(1).previousValue()).containsEntry("active", false);
    assertThat(captor.getAllValues().get(1).newValue()).containsEntry("active", true);
  }

  @Test
  @DisplayName("21.3-SVC-005 P0 unknown machine → 404 MACHINE_NOT_FOUND")
  void unknownMachine() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(baselines.findMachineScope(machineId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateBaselineCommand(machineId, null, Map.of("a", 1))))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.3-SVC-006 P0 unknown/out-of-scope ECN link → 404 ECN_NOT_FOUND (M1 scope-check)")
  void unknownEcnLink() {
    var ecnId = UUID.randomUUID();
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(baselines.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(
        UUID.randomUUID(), UUID.randomUUID())));
    when(baselines.countEcnVisible(eq(ecnId), eq(true), any(), any())).thenReturn(0L);

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateBaselineCommand(machineId, ecnId, Map.of("a", 1))))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("ECN_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.3-SVC-006b P0 activate on an already-active baseline is a no-op (L3)")
  void activateAlreadyActiveIsNoop() {
    stubVisible(entity(1, true));

    var view = service.activate(user(ApplicationRole.MANAGER_MAINTENANCE), baselineId);

    assertThat(view.active()).isTrue();
    verify(baselines, never()).saveAndFlush(any());
    verify(auditLog, never()).record(any(), any());
  }

  @Test
  @DisplayName("21.3-SVC-006c P0 activate unknown id → 404 BASELINE_NOT_FOUND (VG-5)")
  void activateUnknownId() {
    when(baselines.findById(baselineId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.activate(user(ApplicationRole.STAFF_MAINTENANCE), baselineId))
        .isInstanceOf(MachineSetupBaselineNotFoundException.class);
  }

  @Test
  @DisplayName("21.3-SVC-007 P0 create-time machine scope gate: sibling machine → FORBIDDEN")
  void createScopeGate() {
    var plant = UUID.randomUUID();
    var group = UUID.randomUUID();
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(plant), Set.of(group),
        Set.of()));
    when(baselines.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(
        UUID.randomUUID(), UUID.randomUUID())));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.SECTION_LEADER),
        new CreateBaselineCommand(machineId, null, Map.of("a", 1))))
        .isInstanceOf(ComplianceForbiddenException.class);
  }

  @Test
  @DisplayName("21.3-SVC-008 P0 out-of-scope detail is 404 BASELINE_NOT_FOUND (not leaked)")
  void outOfScopeNotFound() {
    when(baselines.findById(baselineId)).thenReturn(Optional.of(entity(1, true)));
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(UUID.randomUUID()),
        Set.of(UUID.randomUUID()), Set.of()));
    when(baselines.countVisible(eq(baselineId), eq(false), any(), any())).thenReturn(0L);

    assertThatThrownBy(() -> service.get(user(ApplicationRole.SECTION_LEADER), baselineId))
        .isInstanceOf(MachineSetupBaselineNotFoundException.class);
  }

  @Test
  @DisplayName("21.3-SVC-009 P0 concurrent same-machine create → VERSION_CONFLICT (H1 partial index)")
  void duplicateVersionRace() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(baselines.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(
        UUID.randomUUID(), UUID.randomUUID())));
    when(baselines.maxVersion(machineId)).thenReturn(1);
    when(baselines.findByMachineIdAndActiveTrue(machineId)).thenReturn(List.of());
    when(baselines.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "violated constraint uq_machine_setup_baselines_machine_version"));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateBaselineCommand(machineId, null, Map.of("a", 1))))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("21.3-SVC-009b P0 concurrent active-flag race → VERSION_CONFLICT (H2 index)")
  void singleActiveRace() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(baselines.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(
        UUID.randomUUID(), UUID.randomUUID())));
    when(baselines.maxVersion(machineId)).thenReturn(1);
    when(baselines.findByMachineIdAndActiveTrue(machineId)).thenReturn(List.of());
    when(baselines.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "violated constraint uq_one_active_baseline_per_machine"));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateBaselineCommand(machineId, null, Map.of("a", 1))))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("21.3-SVC-010 P1 ECN-link race classified to DUPLICATE_IDENTIFIER")
  void ecnLinkRace() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(baselines.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(
        UUID.randomUUID(), UUID.randomUUID())));
    when(baselines.maxVersion(machineId)).thenReturn(1);
    when(baselines.findByMachineIdAndActiveTrue(machineId)).thenReturn(List.of());
    when(baselines.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "violated constraint uq_machine_setup_baselines_ecn"));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateBaselineCommand(machineId, null, Map.of("a", 1))))
        .isInstanceOf(DuplicateIdentifierException.class);
  }

  @Test
  @DisplayName("21.3-SVC-010b P1 unrelated constraint violation rethrown, never misclassified")
  void unrelatedRaceRethrown() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(baselines.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(
        UUID.randomUUID(), UUID.randomUUID())));
    when(baselines.maxVersion(machineId)).thenReturn(1);
    when(baselines.findByMachineIdAndActiveTrue(machineId)).thenReturn(List.of());
    when(baselines.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "violated constraint some_other_constraint"));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateBaselineCommand(machineId, null, Map.of("a", 1))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("21.3-SVC-011 P1 list merges machineGroupIds + activeTeamIds into one scope set")
  void listMergesScopeGroups() {
    var group = UUID.randomUUID();
    var teamGroup = UUID.randomUUID();
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(UUID.randomUUID()),
        Set.of(group), Set.of(teamGroup)));
    when(baselines.findScoped(anyBoolean(), any(), any(), any(), anyBoolean()))
        .thenReturn(List.of());

    service.list(user(ApplicationRole.SECTION_LEADER), null, false);

    verify(baselines).findScoped(eq(false), any(), eq(List.of(group, teamGroup)), eq(null),
        eq(false));
  }
}
