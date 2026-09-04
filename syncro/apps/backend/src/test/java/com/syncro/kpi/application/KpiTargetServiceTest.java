package com.syncro.kpi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.kpi.application.KpiTargetService.TargetCommand;
import com.syncro.kpi.application.KpiTargetService.TargetMutationForbiddenException;
import com.syncro.kpi.infrastructure.db.KpiTargetEntity;
import com.syncro.kpi.infrastructure.db.KpiTargetRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Story 20-1 review tests for {@link KpiTargetService}: the SUPER_ADMIN/MANAGER
 * mutation gate, the KPI_TARGET audit trail with previous/new values, and the
 * partial-update semantics (null request fields keep stored values).
 */
@ExtendWith(MockitoExtension.class)
class KpiTargetServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final LocalDate AUG = LocalDate.of(2026, 8, 1);

  private final UUID plantId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();

  @Mock
  private KpiTargetRepository targets;
  @Mock
  private PlantRepository plants;
  @Mock
  private PlantScopeService plantScopes;
  @Mock
  private AuditLogWriter auditLog;

  private KpiTargetService service;

  @BeforeEach
  void setUp() {
    service = new KpiTargetService(targets, plants, plantScopes, auditLog, CLOCK);
  }

  private AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(userId.toString(), "u@syncro.test", role);
  }

  @Test
  @DisplayName("20.1-TGT-001 P0 technician cannot mutate targets (server-side role gate)")
  void technicianDenied() {
    assertThatThrownBy(() -> service.upsert(user(ApplicationRole.TECHNICIAN),
        new TargetCommand(plantId, AUG, 1, null, null, null, null)))
        .isInstanceOf(TargetMutationForbiddenException.class);
    verify(targets, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("20.1-TGT-002 P0 manager create writes a KPI_TARGET audit row with new values")
  void managerCreateAudits() {
    var admin = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(plants.existsById(plantId)).thenReturn(true);
    when(targets.findByPlantIdAndMonthForUpdate(plantId, AUG)).thenReturn(Optional.empty());
    when(targets.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.upsert(admin, new TargetCommand(plantId, AUG, 3,
        new BigDecimal("30.5"), null, null, null));

    assertThat(view.month()).isEqualTo(AUG);
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    var audit = captor.getValue();
    assertThat(audit.action()).isEqualTo(AuditAction.CREATE);
    assertThat(audit.entityType()).isEqualTo(AuditEntityType.KPI_TARGET);
    assertThat(audit.previousValue()).isNull();
    assertThat(audit.newValue()).containsEntry("monthlyBreakdownTarget", 3);
  }

  @Test
  @DisplayName("20.1-TGT-003 P1 partial update: null request fields keep the stored value")
  void partialUpdateKeepsStoredValues() {
    var admin = user(ApplicationRole.SUPER_ADMIN);
    var previous = new KpiTargetEntity(UUID.randomUUID(), plantId, AUG, 5,
        new BigDecimal("40.00"), new BigDecimal("120.00"), new BigDecimal("95.00"),
        new BigDecimal("90.00"), userId, NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(plants.existsById(plantId)).thenReturn(true);
    when(targets.findByPlantIdAndMonthForUpdate(plantId, AUG)).thenReturn(Optional.of(previous));
    when(targets.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    // Only the breakdown target changes; everything else stays.
    var view = service.upsert(admin, new TargetCommand(plantId, AUG, 7, null, null, null, null));

    assertThat(view.monthlyBreakdownTarget()).isEqualTo(7);
    assertThat(view.mtbfTargetDays()).isEqualByComparingTo(new BigDecimal("40.00"));
    assertThat(view.mttrTargetMinutes()).isEqualByComparingTo(new BigDecimal("120.00"));
    assertThat(view.oeeQualityPercent()).isEqualByComparingTo(new BigDecimal("95.00"));
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().action()).isEqualTo(AuditAction.UPDATE);
    assertThat(captor.getValue().previousValue()).containsEntry("monthlyBreakdownTarget", 5);
    assertThat(captor.getValue().newValue()).containsEntry("monthlyBreakdownTarget", 7);
  }

  @Test
  @DisplayName("20.1-TGT-004 P1 manager without plant access is denied before any write")
  void managerWithoutPlantAccessDenied() {
    var admin = user(ApplicationRole.MANAGER_MAINTENANCE);
    org.mockito.Mockito.doThrow(com.syncro.auth.application.PlantScopeService
        .PlantAccessDeniedException.class)
        .when(plantScopes).requirePlantAccess(any(), any());

    assertThatThrownBy(() -> service.upsert(admin,
        new TargetCommand(plantId, AUG, 1, null, null, null, null)))
        .isInstanceOf(com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException.class);
    verify(targets, never()).saveAndFlush(any());
  }
}