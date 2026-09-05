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
import com.syncro.compliance.application.EightDReportService.CreateEightDCommand;
import com.syncro.compliance.application.EightDReportService.EightDConflictException;
import com.syncro.compliance.application.EightDReportService.UpdateEightDCommand;
import com.syncro.compliance.application.EightDReportService.VerifyEffectivenessCommand;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.application.NonConformanceService.InvalidStateTransitionException;
import com.syncro.compliance.domain.EightDStatus;
import com.syncro.compliance.domain.NcStatus;
import com.syncro.compliance.infrastructure.db.EightDReportEntity;
import com.syncro.compliance.infrastructure.db.EightDReportRepository;
import com.syncro.compliance.infrastructure.db.NonConformanceEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 21-1 unit tests for {@link EightDReportService}: one-per-NC conflict,
 * duplicate report_number, the DRAFT→IN_PROGRESS→CLOSED transition matrix, the
 * SUPER_ADMIN/MANAGER-only effectiveness verification (CLOSED→EFFECTIVE|INEFFECTIVE
 * with verifiedAt stamp), and the EIGHT_D_REPORT audit trail.
 */
@ExtendWith(MockitoExtension.class)
class EightDReportServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final UUID ncId = UUID.randomUUID();
  private final UUID reportId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();

  @Mock
  private EightDReportRepository reports;
  @Mock
  private NonConformanceService nonConformances;
  @Mock
  private AuditLogWriter auditLog;

  private EightDReportService service;

  @BeforeEach
  void setUp() {
    service = new EightDReportService(reports, nonConformances, auditLog, CLOCK);
  }

  private AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(userId.toString(), "u@syncro.test", role);
  }

  private EightDReportEntity report(EightDStatus status) {
    return new EightDReportEntity(reportId, ncId, "8D-1", Map.of("members", List.of("Andi")),
        "Burr height", "Sorted 200 pcs", Map.of("cause", "worn guide"), "Replace rail",
        "Replaced", "Recurring wear", "Closed", status, null, null,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600));
  }

  private void stubVisibleNc() {
    when(nonConformances.loadVisible(any(), eq(ncId))).thenReturn(
        new NonConformanceEntity(ncId, null, null, null, "NC-1", "Drift", null, null, null,
            NcStatus.IN_PROGRESS, null, null, null, NOW.minusSeconds(3600),
            NOW.minusSeconds(3600)));
  }

  @Test
  @DisplayName("21.1-8D-001 P0 second report for one NC → EIGHT_D_CONFLICT")
  void secondReportConflicts() {
    stubVisibleNc();
    when(reports.findByNcId(ncId)).thenReturn(Optional.of(report(EightDStatus.DRAFT)));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE), ncId,
        new CreateEightDCommand("8D-2", null, null, null, null, null, null, null, null)))
        .isInstanceOf(EightDConflictException.class);
    verify(reports, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.1-8D-002 P0 duplicate report_number → DUPLICATE_IDENTIFIER")
  void duplicateReportNumber() {
    stubVisibleNc();
    when(reports.findByNcId(ncId)).thenReturn(Optional.empty());
    when(reports.existsByReportNumber("8D-1")).thenReturn(true);

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE), ncId,
        new CreateEightDCommand("8D-1", null, null, null, null, null, null, null, null)))
        .isInstanceOf(DuplicateIdentifierException.class);
  }

  @Test
  @DisplayName("21.1-8D-003 P0 create persists DRAFT + audits CREATE with JSONB sections")
  void createAudits() {
    stubVisibleNc();
    when(reports.findByNcId(ncId)).thenReturn(Optional.empty());
    when(reports.existsByReportNumber("8D-1")).thenReturn(false);
    when(reports.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create(user(ApplicationRole.STAFF_MAINTENANCE), ncId,
        new CreateEightDCommand("8D-1", Map.of("members", List.of("Andi")), "Burr", "Sorted",
            Map.of("cause", "worn"), null, null, null, null));

    assertThat(view.status()).isEqualTo(EightDStatus.DRAFT);
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().action()).isEqualTo(AuditAction.CREATE);
    assertThat(captor.getValue().entityType()).isEqualTo(AuditEntityType.EIGHT_D_REPORT);
    assertThat(captor.getValue().newValue()).containsEntry("reportNumber", "8D-1")
        .containsEntry("status", "DRAFT");
  }

  @Test
  @DisplayName("21.1-8D-004 P0 illegal section-update transition DRAFT→CLOSED → 409")
  void illegalTransition() {
    when(nonConformances.loadVisible(any(), eq(ncId))).thenReturn(
        new NonConformanceEntity(ncId, null, null, null, "NC-1", "Drift", null, null, null,
            NcStatus.IN_PROGRESS, null, null, null, NOW.minusSeconds(3600),
            NOW.minusSeconds(3600)));
    when(reports.findByNcId(ncId)).thenReturn(Optional.of(report(EightDStatus.DRAFT)));

    assertThatThrownBy(() -> service.update(user(ApplicationRole.MANAGER_MAINTENANCE), ncId,
        new UpdateEightDCommand(EightDStatus.CLOSED, null, null, null, null, null, null, null,
            null, null)))
        .isInstanceOf(InvalidStateTransitionException.class);
    verify(reports, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.1-8D-005 P0 verify EFFECTIVE from CLOSED stamps verifiedAt + audits UPDATE")
  void verifyEffectiveFromClosed() {
    when(nonConformances.loadVisible(any(), eq(ncId))).thenReturn(
        new NonConformanceEntity(ncId, null, null, null, "NC-1", "Drift", null, null, null,
            NcStatus.CLOSED, null, null, null, NOW.minusSeconds(3600), NOW.minusSeconds(3600)));
    when(reports.findByNcId(ncId)).thenReturn(Optional.of(report(EightDStatus.CLOSED)));
    when(reports.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.verifyEffectiveness(user(ApplicationRole.MANAGER_MAINTENANCE), ncId,
        new VerifyEffectivenessCommand(EightDStatus.EFFECTIVE));

    assertThat(view.status()).isEqualTo(EightDStatus.EFFECTIVE);
    assertThat(view.effectivenessVerifiedAt()).isEqualTo(NOW);
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().previousValue()).containsEntry("status", "CLOSED");
    assertThat(captor.getValue().newValue()).containsEntry("status", "EFFECTIVE")
        .containsEntry("effectivenessVerifiedAt", NOW.toString());
  }

  @Test
  @DisplayName("21.1-8D-006 P0 verify by non-manager role → 403 (even via section leader)")
  void verifyRoleGate() {
    assertThatThrownBy(() -> service.verifyEffectiveness(user(ApplicationRole.SECTION_LEADER),
        ncId, new VerifyEffectivenessCommand(EightDStatus.EFFECTIVE)))
        .isInstanceOf(ComplianceForbiddenException.class);
    verify(reports, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.1-8D-007 P1 verify from non-CLOSED status → 409")
  void verifyRequiresClosed() {
    when(nonConformances.loadVisible(any(), eq(ncId))).thenReturn(
        new NonConformanceEntity(ncId, null, null, null, "NC-1", "Drift", null, null, null,
            NcStatus.IN_PROGRESS, null, null, null, NOW.minusSeconds(3600),
            NOW.minusSeconds(3600)));
    when(reports.findByNcId(ncId)).thenReturn(Optional.of(report(EightDStatus.IN_PROGRESS)));

    assertThatThrownBy(() -> service.verifyEffectiveness(user(ApplicationRole.SUPER_ADMIN), ncId,
        new VerifyEffectivenessCommand(EightDStatus.INEFFECTIVE)))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  @DisplayName("21.1-8D-008 P1 verify verdict must be EFFECTIVE/INEFFECTIVE")
  void verifyRejectsNonVerdict() {
    assertThatThrownBy(() -> service.verifyEffectiveness(user(ApplicationRole.MANAGER_MAINTENANCE),
        ncId, new VerifyEffectivenessCommand(EightDStatus.DRAFT)))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  @DisplayName("21.1-8D-009 P1 partial section update keeps stored values")
  void partialSectionUpdate() {
    when(nonConformances.loadVisible(any(), eq(ncId))).thenReturn(
        new NonConformanceEntity(ncId, null, null, null, "NC-1", "Drift", null, null, null,
            NcStatus.IN_PROGRESS, null, null, null, NOW.minusSeconds(3600),
            NOW.minusSeconds(3600)));
    when(reports.findByNcId(ncId)).thenReturn(Optional.of(report(EightDStatus.DRAFT)));
    when(reports.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.update(user(ApplicationRole.STAFF_MAINTENANCE), ncId,
        new UpdateEightDCommand(null, null, "Updated problem statement", null, null, null, null,
            null, null, null));

    assertThat(view.d2Description()).isEqualTo("Updated problem statement");
    assertThat(view.d3Containment()).isEqualTo("Sorted 200 pcs");
    assertThat(view.d1Team()).containsEntry("members", List.of("Andi"));
  }
}
