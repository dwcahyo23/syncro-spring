package com.syncro.alert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.domain.SparepartAlertType;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.notification.domain.AlertOpenedEvent;
import com.syncro.projection.application.CounterRateEstimator.CalculationBasis;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class SparepartProcurementRiskAlertCreatorTest {

  @Mock
  private SparepartAlertRepository alertRepository;

  @Mock
  private AuditLogWriter auditLogWriter;

  @Mock
  private ApplicationEventPublisher eventPublisher;

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);

  private SparepartProcurementRiskAlertCreator creator() {
    return new SparepartProcurementRiskAlertCreator(alertRepository, auditLogWriter, clock, eventPublisher);
  }

  private static final UUID MACHINE = UUID.randomUUID();
  private static final UUID PLANT = UUID.randomUUID();
  private static final UUID INSTALLATION = UUID.randomUUID();

  @Test
  void create_savesEntityWithEvidenceAndPublishesEventAndAudit() {
    when(alertRepository.existsByMachineSparepartInstallationIdAndAlertTypeAndStatusNot(
        INSTALLATION, SparepartAlertType.PROCUREMENT_RISK, SparepartAlertStatus.RESOLVED))
        .thenReturn(false);
    when(alertRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    creator().create(MACHINE, PLANT, INSTALLATION, "trace-cr-001",
        new BigDecimal("36.50"), new BigDecimal("15.00"), CalculationBasis.ROLLING_30_DAY,
        Instant.parse("2026-08-25T00:00:00Z"));

    var captor = org.mockito.ArgumentCaptor.forClass(SparepartAlertEntity.class);
    verify(alertRepository).saveAndFlush(captor.capture());
    SparepartAlertEntity saved = captor.getValue();
    assertThat(saved.getAlertType()).isEqualTo(SparepartAlertType.PROCUREMENT_RISK);
    assertThat(saved.getThresholdPercentage()).isNull();
    assertThat(saved.getCurrentCounterSnapshot()).isNull();
    assertThat(saved.getLeadTimeHours()).isEqualByComparingTo(new BigDecimal("36.50"));
    assertThat(saved.getRatePerOperatingHour()).isEqualByComparingTo(new BigDecimal("15.00"));
    assertThat(saved.getCalculationBasis()).isEqualTo(CalculationBasis.ROLLING_30_DAY);
    assertThat(saved.getProjectedDepletionAt()).isEqualTo(Instant.parse("2026-08-25T00:00:00Z"));

    verify(eventPublisher).publishEvent(any(AlertOpenedEvent.class));
    verify(auditLogWriter).recordSystem(any(AuditRecord.class));
  }

  @Test
  void create_whenDedupExists_throwsDedupConflictWithoutSaving() {
    when(alertRepository.existsByMachineSparepartInstallationIdAndAlertTypeAndStatusNot(
        INSTALLATION, SparepartAlertType.PROCUREMENT_RISK, SparepartAlertStatus.RESOLVED))
        .thenReturn(true);

    assertThatThrownBy(() -> creator().create(MACHINE, PLANT, INSTALLATION, "trace-cr-002",
        new BigDecimal("36.50"), new BigDecimal("15.00"), CalculationBasis.FULL_HISTORY,
        Instant.parse("2026-08-25T00:00:00Z")))
        .isInstanceOf(SparepartProcurementRiskAlertCreator.DedupConflict.class);

    verify(alertRepository, never()).saveAndFlush(any());
    verify(eventPublisher, never()).publishEvent(any());
    verify(auditLogWriter, never()).recordSystem(any());
  }

  @Test
  void create_onDedupIndexConstraintViolation_throwsDedupConflict() {
    when(alertRepository.existsByMachineSparepartInstallationIdAndAlertTypeAndStatusNot(
        INSTALLATION, SparepartAlertType.PROCUREMENT_RISK, SparepartAlertStatus.RESOLVED))
        .thenReturn(false);
    var violation = new ConstraintViolationException("duplicate key",
        null, SparepartProcurementRiskAlertCreator.DEDUPE_CONSTRAINT);
    when(alertRepository.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate key", violation));

    assertThatThrownBy(() -> creator().create(MACHINE, PLANT, INSTALLATION, "trace-cr-003",
        new BigDecimal("36.50"), new BigDecimal("15.00"), CalculationBasis.FULL_HISTORY,
        Instant.parse("2026-08-25T00:00:00Z")))
        .isInstanceOf(SparepartProcurementRiskAlertCreator.DedupConflict.class);

    verify(eventPublisher, never()).publishEvent(any());
    verify(auditLogWriter, never()).recordSystem(any());
  }

  @Test
  void create_onUnrelatedIntegrityViolation_rethrows() {
    when(alertRepository.existsByMachineSparepartInstallationIdAndAlertTypeAndStatusNot(
        INSTALLATION, SparepartAlertType.PROCUREMENT_RISK, SparepartAlertStatus.RESOLVED))
        .thenReturn(false);
    var violation = new ConstraintViolationException("violates check",
        null, "chk_sparepart_alerts_procurement_evidence");
    when(alertRepository.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("violates check", violation));

    assertThatThrownBy(() -> creator().create(MACHINE, PLANT, INSTALLATION, "trace-cr-004",
        new BigDecimal("36.50"), new BigDecimal("15.00"), CalculationBasis.FULL_HISTORY,
        Instant.parse("2026-08-25T00:00:00Z")))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("violates check");
  }
}
