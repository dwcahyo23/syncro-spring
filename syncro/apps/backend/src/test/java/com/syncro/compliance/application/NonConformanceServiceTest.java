package com.syncro.compliance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceValidationException;
import com.syncro.compliance.application.NonConformanceService.CreateNcCommand;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.application.NonConformanceService.InvalidStateTransitionException;
import com.syncro.compliance.application.NonConformanceService.NonConformanceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.UpdateNcCommand;
import com.syncro.compliance.domain.NcSeverity;
import com.syncro.compliance.domain.NcStatus;
import com.syncro.compliance.infrastructure.db.NonConformanceEntity;
import com.syncro.compliance.infrastructure.db.NonConformanceRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
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

/**
 * Story 21-1 unit tests for {@link NonConformanceService}: the workorder-create-parity
 * role gate, the OPEN→IN_PROGRESS→CLOSED→VERIFIED transition matrix (incl. the
 * closure-analysis requirement), duplicate handling, partial-update semantics, and the
 * NON_CONFORMANCE audit trail with previous/new values.
 */
@ExtendWith(MockitoExtension.class)
class NonConformanceServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final UUID ncId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();

  @Mock
  private NonConformanceRepository nonConformances;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private AuditLogWriter auditLog;

  private NonConformanceService service;

  @BeforeEach
  void setUp() {
    service = new NonConformanceService(nonConformances, scopes, auditLog, CLOCK);
  }

  private AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(userId.toString(), "u@syncro.test", role);
  }

  private NonConformanceEntity entity(NcStatus status, String rootCause, String corrective) {
    return new NonConformanceEntity(ncId, null, null, machineId, "NC-1", "Drift", rootCause,
        corrective, null, status, NcSeverity.MINOR, null, null, NOW.minusSeconds(3600),
        NOW.minusSeconds(3600));
  }

  private void stubVisible(NonConformanceEntity entity) {
    when(nonConformances.findById(ncId)).thenReturn(Optional.of(entity));
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(nonConformances.countVisible(eq(ncId), eq(true), any(), any())).thenReturn(1L);
  }

  @Test
  @DisplayName("21.1-SVC-001 P0 technician/auditor cannot mutate NCs (server-side role gate)")
  void readOnlyRolesDenied() {
    assertThatThrownBy(() -> service.create(user(ApplicationRole.TECHNICIAN),
        new CreateNcCommand("NC-1", "Drift", null, null, null, null, null, null)))
        .isInstanceOf(ComplianceForbiddenException.class);
    assertThatThrownBy(() -> service.create(user(ApplicationRole.AUDITOR),
        new CreateNcCommand("NC-1", "Drift", null, null, null, null, null, null)))
        .isInstanceOf(ComplianceForbiddenException.class);
    verify(nonConformances, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.1-SVC-002 P0 staff create persists OPEN + audits CREATE with new values")
  void staffCreateAudits() {
    when(nonConformances.existsByNcNumber("NC-1")).thenReturn(false);
    when(nonConformances.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateNcCommand("NC-1", "Dimensional drift", NcSeverity.MAJOR, null, null, null,
            null, null));

    assertThat(view.status()).isEqualTo(NcStatus.OPEN);
    assertThat(view.severity()).isEqualTo(NcSeverity.MAJOR);
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    var audit = captor.getValue();
    assertThat(audit.action()).isEqualTo(AuditAction.CREATE);
    assertThat(audit.entityType()).isEqualTo(AuditEntityType.NON_CONFORMANCE);
    assertThat(audit.previousValue()).isNull();
    assertThat(audit.newValue()).containsEntry("ncNumber", "NC-1")
        .containsEntry("status", "OPEN").containsEntry("severity", "MAJOR");
  }

  @Test
  @DisplayName("21.1-SVC-003 P0 duplicate nc_number maps to DUPLICATE_IDENTIFIER")
  void duplicateNcNumber() {
    when(nonConformances.existsByNcNumber("NC-1")).thenReturn(true);

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateNcCommand("NC-1", "Drift", null, null, null, null, null, null)))
        .isInstanceOf(DuplicateIdentifierException.class);
    verify(nonConformances, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.1-SVC-003b P0 unknown responsible user maps to 404 USER_NOT_FOUND")
  void unknownResponsibleReference() {
    var unknownUser = UUID.randomUUID();
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(nonConformances.countUser(unknownUser)).thenReturn(0L);

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateNcCommand("NC-1", "Drift", null, null, null, null, unknownUser, null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("USER_NOT_FOUND"));
    verify(nonConformances, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.1-SVC-004 P1 unresolvable machine reference maps to 404 MACHINE_NOT_FOUND")
  void unknownMachineReference() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(nonConformances.findMachineScope(machineId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateNcCommand("NC-1", "Drift", null, null, null, machineId, null, null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.1-SVC-005 P0 illegal transitions OPEN→VERIFIED / OPEN→CLOSED → 409")
  void illegalTransitions() {
    stubVisible(entity(NcStatus.OPEN, null, null));
    assertThatThrownBy(() -> service.update(user(ApplicationRole.MANAGER_MAINTENANCE), ncId,
        new UpdateNcCommand(NcStatus.VERIFIED, null, null, null, null, null, null)))
        .isInstanceOf(InvalidStateTransitionException.class);

    stubVisible(entity(NcStatus.OPEN, null, null));
    assertThatThrownBy(() -> service.update(user(ApplicationRole.MANAGER_MAINTENANCE), ncId,
        new UpdateNcCommand(NcStatus.CLOSED, null, null, "rc", "ca", null, null)))
        .isInstanceOf(InvalidStateTransitionException.class);
    verify(nonConformances, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.1-SVC-005b P0 blank PATCH text is rejected with fieldErrors (nothing persisted)")
  void blankUpdateFieldsRejected() {
    stubVisible(entity(NcStatus.IN_PROGRESS, "stored cause", "stored action"));

    assertThatThrownBy(() -> service.update(user(ApplicationRole.MANAGER_MAINTENANCE), ncId,
        new UpdateNcCommand(null, "  ", null, "", null, null, null)))
        .isInstanceOfSatisfying(ComplianceValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKeys("description", "rootCause"));
    verify(nonConformances, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.1-SVC-006 P0 close without analysis → VALIDATION_ERROR with fieldErrors")
  void closeWithoutAnalysisRejected() {
    stubVisible(entity(NcStatus.IN_PROGRESS, null, null));

    assertThatThrownBy(() -> service.update(user(ApplicationRole.MANAGER_MAINTENANCE), ncId,
        new UpdateNcCommand(NcStatus.CLOSED, null, null, null, null, null, null)))
        .isInstanceOfSatisfying(ComplianceValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKeys("rootCause", "correctiveAction"));
    verify(nonConformances, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.1-SVC-007 P0 close with analysis stamps closedAt (server clock) + audits UPDATE")
  void closeStampsClosedAtAndAudits() {
    stubVisible(entity(NcStatus.IN_PROGRESS, null, null));
    when(nonConformances.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.update(user(ApplicationRole.MANAGER_MAINTENANCE), ncId,
        new UpdateNcCommand(NcStatus.CLOSED, null, null, "worn guide", "replace rail", null, null));

    assertThat(view.status()).isEqualTo(NcStatus.CLOSED);
    assertThat(view.closedAt()).isEqualTo(NOW);
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().action()).isEqualTo(AuditAction.UPDATE);
    assertThat(captor.getValue().previousValue()).containsEntry("status", "IN_PROGRESS");
    assertThat(captor.getValue().newValue()).containsEntry("status", "CLOSED")
        .containsEntry("closedAt", NOW.toString());
  }

  @Test
  @DisplayName("21.1-SVC-008 P1 partial update: null fields keep stored values")
  void partialUpdateKeepsStoredValues() {
    stubVisible(entity(NcStatus.IN_PROGRESS, "stored cause", "stored action"));
    when(nonConformances.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.update(user(ApplicationRole.SECTION_LEADER), ncId,
        new UpdateNcCommand(null, "new description", null, null, null, null, null));

    assertThat(view.description()).isEqualTo("new description");
    assertThat(view.rootCause()).isEqualTo("stored cause");
    assertThat(view.correctiveAction()).isEqualTo("stored action");
    assertThat(view.status()).isEqualTo(NcStatus.IN_PROGRESS);
  }

  @Test
  @DisplayName("21.1-SVC-009 P1 out-of-scope detail is 404 (existence not leaked)")
  void outOfScopeDetailNotFound() {
    when(nonConformances.findById(ncId)).thenReturn(Optional.of(entity(NcStatus.OPEN, null, null)));
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(UUID.randomUUID()),
        Set.of(UUID.randomUUID()), Set.of()));
    when(nonConformances.countVisible(eq(ncId), eq(false), any(), any())).thenReturn(0L);

    assertThatThrownBy(() -> service.get(user(ApplicationRole.SECTION_LEADER), ncId))
        .isInstanceOf(NonConformanceNotFoundException.class);
  }

  @Test
  @DisplayName("21.1-SVC-010 P1 verified only from CLOSED; VERIFIED→anything is illegal")
  void verifiedEdge() {
    stubVisible(entity(NcStatus.CLOSED, "rc", "ca"));
    when(nonConformances.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    assertThat(service.update(user(ApplicationRole.MANAGER_MAINTENANCE), ncId,
        new UpdateNcCommand(NcStatus.VERIFIED, null, null, null, null, null, null)).status())
        .isEqualTo(NcStatus.VERIFIED);

    stubVisible(entity(NcStatus.VERIFIED, "rc", "ca"));
    assertThatThrownBy(() -> service.update(user(ApplicationRole.MANAGER_MAINTENANCE), ncId,
        new UpdateNcCommand(NcStatus.IN_PROGRESS, null, null, null, null, null, null)))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  @DisplayName("21.1-SVC-011 P2 list merges machineGroupIds + activeTeamIds into one scope set")
  void listMergesScopeGroups() {
    var group = UUID.randomUUID();
    var teamGroup = UUID.randomUUID();
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(UUID.randomUUID()),
        Set.of(group), Set.of(teamGroup)));
    when(nonConformances.findScoped(anyBoolean(), any(), any(), any()))
        .thenReturn(java.util.List.of());

    service.list(user(ApplicationRole.SECTION_LEADER), null);

    verify(nonConformances).findScoped(eq(false), any(),
        eq(java.util.List.of(group, teamGroup)), eq(""));
  }

  @Test
  @DisplayName("21.1-SVC-012 P2 status filter passes the uppercase enum name to the query")
  void listStatusFilter() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(nonConformances.findScoped(anyBoolean(), any(), any(), any()))
        .thenReturn(java.util.List.of());

    service.list(user(ApplicationRole.SUPER_ADMIN), NcStatus.IN_PROGRESS);

    verify(nonConformances).findScoped(eq(true), any(), any(), eq("IN_PROGRESS"));
  }
}
