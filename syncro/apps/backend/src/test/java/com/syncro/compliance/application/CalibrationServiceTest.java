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
import com.syncro.compliance.application.CalibrationService.CalibrationInstrumentNotFoundException;
import com.syncro.compliance.application.CalibrationService.CreateInstrumentCommand;
import com.syncro.compliance.application.CalibrationService.RecalibrateCommand;
import com.syncro.compliance.application.CalibrationService.UpdateInstrumentCommand;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceValidationException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.domain.CalibrationStatus;
import com.syncro.compliance.infrastructure.db.CalibrationInstrumentEntity;
import com.syncro.compliance.infrastructure.db.CalibrationInstrumentRepository;
import com.syncro.compliance.infrastructure.db.CalibrationRecordRepository;
import com.syncro.config.CalibrationProperties;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
 * Story 21-2 unit tests for {@link CalibrationService}: the derived-status matrix
 * against a fixed clock (incl. the window boundary), recalibrate (record append +
 * date advance + dual audit), duplicate/scope/reference gates, and the
 * six-role mutation gate.
 */
@ExtendWith(MockitoExtension.class)
class CalibrationServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final UUID instrumentId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();

  @Mock
  private CalibrationInstrumentRepository instruments;
  @Mock
  private CalibrationRecordRepository records;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private AuditLogWriter auditLog;

  private CalibrationService service;

  @BeforeEach
  void setUp() {
    service = new CalibrationService(instruments, records, scopes, auditLog,
        new CalibrationProperties(14), CLOCK);
  }

  private AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(userId.toString(), "u@syncro.test", role);
  }

  private CalibrationInstrumentEntity entity(LocalDate next, CalibrationStatus stored) {
    return new CalibrationInstrumentEntity(instrumentId, "CAL-1", "Digital caliper",
        "Mitutoyo 500-752", "SN-1", "QC bench", 180, TODAY.minusDays(180), next, "B4T Lab",
        stored, null, NOW.minusSeconds(3600), NOW.minusSeconds(3600));
  }

  private void stubUnrestricted() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
  }

  private void stubVisible(CalibrationInstrumentEntity entity) {
    when(instruments.findById(instrumentId)).thenReturn(Optional.of(entity));
    stubUnrestricted();
    when(instruments.countVisible(eq(instrumentId), eq(true), any())).thenReturn(1L);
  }

  @Test
  @DisplayName("21.2-SVC-001 P0 derived status: past → EXPIRED, today/window edge → EXPIRING_SOON, beyond → stored")
  void deriveStatusMatrix() {
    stubUnrestricted();
    when(instruments.findScoped(eq(true), any())).thenReturn(List.of(
        entity(TODAY.minusDays(1), CalibrationStatus.VALID),      // past → EXPIRED
        entity(TODAY, CalibrationStatus.VALID),                   // today → EXPIRING_SOON
        entity(TODAY.plusDays(14), CalibrationStatus.VALID),      // window edge (inclusive)
        entity(TODAY.plusDays(15), CalibrationStatus.VALID),      // beyond window → stored VALID
        entity(TODAY.plusDays(15), CalibrationStatus.EXPIRING_SOON))); // beyond → stored snapshot

    var views = service.list(user(ApplicationRole.STAFF_MAINTENANCE), null);

    assertThat(views).extracting(CalibrationService.InstrumentView::status).containsExactly(
        CalibrationStatus.EXPIRED, CalibrationStatus.EXPIRING_SOON,
        CalibrationStatus.EXPIRING_SOON, CalibrationStatus.VALID,
        CalibrationStatus.EXPIRING_SOON);
  }

  @Test
  @DisplayName("21.2-SVC-002 P0 status filter applies to the derived value, not the snapshot")
  void expiredFilterMatchesDerived() {
    stubUnrestricted();
    // Stored snapshot says VALID, but the date is past → EXPIRED filter must hit.
    when(instruments.findScoped(eq(true), any()))
        .thenReturn(List.of(entity(TODAY.minusDays(30), CalibrationStatus.VALID)));

    var expired = service.list(user(ApplicationRole.STAFF_MAINTENANCE), CalibrationStatus.EXPIRED);
    assertThat(expired).hasSize(1);
    assertThat(service.list(user(ApplicationRole.STAFF_MAINTENANCE), CalibrationStatus.VALID))
        .isEmpty();
  }

  @Test
  @DisplayName("21.2-SVC-003 P0 staff create persists + audits CREATE with new values")
  void staffCreateAudits() {
    var plantId = UUID.randomUUID();
    stubUnrestricted();
    when(instruments.countPlant(plantId)).thenReturn(1L);
    when(instruments.existsByInstrumentCode("CAL-1")).thenReturn(false);
    when(instruments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateInstrumentCommand("CAL-1", "Dimensional caliper", "Mitutoyo", "SN-1",
            "QC bench", 180, TODAY.minusDays(180), TODAY.plusDays(120), "B4T Lab", plantId));

    assertThat(view.instrumentCode()).isEqualTo("CAL-1");
    assertThat(view.status()).isEqualTo(CalibrationStatus.VALID);
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().action()).isEqualTo(AuditAction.CREATE);
    assertThat(captor.getValue().entityType()).isEqualTo(AuditEntityType.CALIBRATION_INSTRUMENT);
    assertThat(captor.getValue().previousValue()).isNull();
    assertThat(captor.getValue().newValue()).containsEntry("instrumentCode", "CAL-1")
        .containsEntry("calibrationFrequencyDays", 180);
  }

  @Test
  @DisplayName("21.2-SVC-003b P0 non-admin cannot mint a global (plantId=null) instrument")
  void globalCreateDeniedForNonAdmin() {
    stubUnrestricted();
    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateInstrumentCommand("CAL-1", "Caliper", null, null, null, 180, null,
            TODAY.plusDays(120), null, null)))
        .isInstanceOf(ComplianceForbiddenException.class);
    verify(instruments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-003c P0 SUPER_ADMIN may mint a global instrument")
  void superAdminCanCreateGlobal() {
    stubUnrestricted();
    when(instruments.existsByInstrumentCode("CAL-1")).thenReturn(false);
    when(instruments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create(user(ApplicationRole.SUPER_ADMIN), new CreateInstrumentCommand(
        "CAL-1", "Caliper", null, null, null, 180, null, TODAY.plusDays(120), null, null));

    assertThat(view.plantId()).isNull();
  }

  @Test
  @DisplayName("21.2-SVC-004 P0 technician/auditor cannot mutate instruments")
  void readOnlyRolesDenied() {
    assertThatThrownBy(() -> service.create(user(ApplicationRole.TECHNICIAN),
        new CreateInstrumentCommand("CAL-1", "Caliper", null, null, null, 180, null,
            TODAY.plusDays(120), null, null)))
        .isInstanceOf(ComplianceForbiddenException.class);
    verify(instruments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-005 P0 duplicate instrument_code → DUPLICATE_IDENTIFIER")
  void duplicateCode() {
    stubUnrestricted();
    when(instruments.existsByInstrumentCode("CAL-1")).thenReturn(true);

    assertThatThrownBy(() -> service.create(user(ApplicationRole.SUPER_ADMIN),
        new CreateInstrumentCommand("CAL-1", "Caliper", null, null, null, 180, null,
            TODAY.plusDays(120), null, null)))
        .isInstanceOf(DuplicateIdentifierException.class);
    verify(instruments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-006 P0 unknown plant reference → 404 PLANT_NOT_FOUND")
  void unknownPlantReference() {
    var plantId = UUID.randomUUID();
    stubUnrestricted();
    when(instruments.countPlant(plantId)).thenReturn(0L);

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateInstrumentCommand("CAL-1", "Caliper", null, null, null, 180, null,
            TODAY.plusDays(120), null, plantId)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("PLANT_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.2-SVC-007 P0 out-of-scope plant filing is forbidden (21-1 P3 parity)")
  void outOfScopePlantFilingDenied() {
    var plantId = UUID.randomUUID();
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(UUID.randomUUID()),
        Set.of(), Set.of()));
    when(instruments.countPlant(plantId)).thenReturn(1L);

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        new CreateInstrumentCommand("CAL-1", "Caliper", null, null, null, 180, null,
            TODAY.plusDays(120), null, plantId)))
        .isInstanceOf(ComplianceForbiddenException.class);
  }

  @Test
  @DisplayName("21.2-SVC-008 P0 recalibrate appends record + advances dates + dual audit")
  void recalibrateAppendsAndAdvances() {
    var instrument = entity(TODAY.plusDays(3), CalibrationStatus.EXPIRING_SOON);
    stubVisible(instrument);
    when(instruments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(records.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var record = service.recalibrate(user(ApplicationRole.STAFF_MAINTENANCE), instrumentId,
        new RecalibrateCommand(TODAY, TODAY.plusDays(180), "B4T Lab", "CERT-9",
            "garage://cal/cert.pdf", "PASS", "Within tolerance"));

    assertThat(record.calibrationDate()).isEqualTo(TODAY);
    assertThat(record.nextCalibrationDate()).isEqualTo(TODAY.plusDays(180));
    assertThat(record.recordedBy()).isEqualTo(userId);
    assertThat(instrument.getLastCalibrationDate()).isEqualTo(TODAY);
    assertThat(instrument.getNextCalibrationDate()).isEqualTo(TODAY.plusDays(180));
    // Snapshot recomputed at write time: 180 days out is beyond the 14-day window.
    assertThat(instrument.getStatus()).isEqualTo(CalibrationStatus.VALID);

    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog, org.mockito.Mockito.times(2)).record(any(), captor.capture());
    var recordAudit = captor.getAllValues().get(0);
    assertThat(recordAudit.entityType()).isEqualTo(AuditEntityType.CALIBRATION_RECORD);
    assertThat(recordAudit.action()).isEqualTo(AuditAction.CREATE);
    assertThat(recordAudit.newValue()).containsEntry("certificateNumber", "CERT-9");
    var instrumentAudit = captor.getAllValues().get(1);
    assertThat(instrumentAudit.entityType()).isEqualTo(AuditEntityType.CALIBRATION_INSTRUMENT);
    assertThat(instrumentAudit.previousValue()).containsEntry("status", "EXPIRING_SOON");
    assertThat(instrumentAudit.newValue()).containsEntry("status", "VALID")
        .containsEntry("nextCalibrationDate", TODAY.plusDays(180).toString());
  }

  @Test
  @DisplayName("21.2-SVC-009 P0 recalibrate with next<=date → VALIDATION_ERROR, nothing appended")
  void recalibrateRejectsBackwardWindow() {
    stubVisible(entity(TODAY.plusDays(3), CalibrationStatus.EXPIRING_SOON));

    assertThatThrownBy(() -> service.recalibrate(user(ApplicationRole.STAFF_MAINTENANCE),
        instrumentId, new RecalibrateCommand(TODAY, TODAY, null, null, null, null, null)))
        .isInstanceOfSatisfying(ComplianceValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("nextCalibrationDate"));
    verify(records, never()).saveAndFlush(any());
    verify(instruments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-010 P0 unknown/out-of-scope instrument → INSTRUMENT_NOT_FOUND (404)")
  void outOfScopeInstrumentNotFound() {
    when(instruments.findById(instrumentId)).thenReturn(Optional.of(entity(TODAY,
        CalibrationStatus.VALID)));
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(UUID.randomUUID()),
        Set.of(), Set.of()));
    when(instruments.countVisible(eq(instrumentId), eq(false), any())).thenReturn(0L);

    assertThatThrownBy(() -> service.get(user(ApplicationRole.STAFF_MAINTENANCE), instrumentId))
        .isInstanceOf(CalibrationInstrumentNotFoundException.class);
  }

  @Test
  @DisplayName("21.2-SVC-011 P1 negative frequency PATCH rejected before persistence")
  void negativeFrequencyRejected() {
    stubVisible(entity(TODAY.plusDays(120), CalibrationStatus.VALID));

    assertThatThrownBy(() -> service.update(user(ApplicationRole.STAFF_MAINTENANCE), instrumentId,
        new UpdateInstrumentCommand(null, null, null, null, 0, null, null)))
        .isInstanceOfSatisfying(ComplianceValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("calibrationFrequencyDays"));
    verify(instruments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-012 P1 record detail of another instrument's record → CALIBRATION_RECORD_NOT_FOUND")
  void recordNotFoundUnderInstrument() {
    stubVisible(entity(TODAY.plusDays(120), CalibrationStatus.VALID));
    var recordId = UUID.randomUUID();
    when(records.findByInstrumentIdAndId(instrumentId, recordId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getRecord(user(ApplicationRole.STAFF_MAINTENANCE),
        instrumentId, recordId))
        .isInstanceOf(CalibrationService.CalibrationRecordNotFoundException.class);
  }

  @Test
  @DisplayName("21.2-SVC-013 P0 create with lastCalibrationDate after next → VALIDATION_ERROR")
  void createRejectsInvertedDates() {
    stubUnrestricted();
    assertThatThrownBy(() -> service.create(user(ApplicationRole.SUPER_ADMIN),
        new CreateInstrumentCommand("CAL-1", "Caliper", null, null, null, 180,
            TODAY.plusDays(10), TODAY, null, null)))
        .isInstanceOfSatisfying(ComplianceValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("lastCalibrationDate"));
    verify(instruments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-014 P0 recalibrate with future or out-of-order calibrationDate → VALIDATION_ERROR")
  void recalibrateRejectsOutOfOrderHistory() {
    stubVisible(entity(TODAY.plusDays(3), CalibrationStatus.EXPIRING_SOON));
    assertThatThrownBy(() -> service.recalibrate(user(ApplicationRole.STAFF_MAINTENANCE),
        instrumentId, new RecalibrateCommand(TODAY.plusDays(5), TODAY.plusDays(185), null,
            null, null, null, null)))
        .isInstanceOfSatisfying(ComplianceValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("calibrationDate"));

    // lastCalibrationDate = TODAY-10 → a calibration dated TODAY-30 is out of order.
    var recent = new CalibrationInstrumentEntity(instrumentId, "CAL-1", "Digital caliper", null,
        null, null, 180, TODAY.minusDays(10), TODAY.plusDays(170), "B4T Lab",
        CalibrationStatus.VALID, null, NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    stubVisible(recent);
    assertThatThrownBy(() -> service.recalibrate(user(ApplicationRole.STAFF_MAINTENANCE),
        instrumentId, new RecalibrateCommand(TODAY.minusDays(30), TODAY.plusDays(150), null,
            null, null, null, null)))
        .isInstanceOfSatisfying(ComplianceValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("calibrationDate"));
    verify(records, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-015 P0 non-admin cannot re-plant an instrument (one-way claim, H3)")
  void plantIdChangeDeniedForNonAdmin() {
    stubVisible(entity(TODAY.plusDays(120), CalibrationStatus.VALID));
    assertThatThrownBy(() -> service.update(user(ApplicationRole.STAFF_MAINTENANCE), instrumentId,
        new UpdateInstrumentCommand(null, null, null, null, null, null, UUID.randomUUID())))
        .isInstanceOf(ComplianceForbiddenException.class);
    verify(instruments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-016 P1 SUPER_ADMIN re-plant validates the target plant exists")
  void superAdminReplantChecksPlant() {
    var plantId = UUID.randomUUID();
    stubVisible(entity(TODAY.plusDays(120), CalibrationStatus.VALID));
    when(instruments.countPlant(plantId)).thenReturn(0L);

    assertThatThrownBy(() -> service.update(user(ApplicationRole.SUPER_ADMIN), instrumentId,
        new UpdateInstrumentCommand(null, null, null, null, null, null, plantId)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("PLANT_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.2-SVC-017 P0 blank PATCH name is rejected with fieldErrors (nothing persisted)")
  void blankUpdateFieldsRejected() {
    stubVisible(entity(TODAY.plusDays(120), CalibrationStatus.VALID));

    assertThatThrownBy(() -> service.update(user(ApplicationRole.STAFF_MAINTENANCE), instrumentId,
        new UpdateInstrumentCommand("  ", null, null, null, null, null, null)))
        .isInstanceOfSatisfying(ComplianceValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("name"));
    verify(instruments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.2-SVC-018 P1 partial update: null fields keep stored values + UPDATE audit prev/new")
  void partialUpdateKeepsStoredValues() {
    stubVisible(entity(TODAY.plusDays(120), CalibrationStatus.VALID));
    when(instruments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.update(user(ApplicationRole.STAFF_MAINTENANCE), instrumentId,
        new UpdateInstrumentCommand("Renamed caliper", null, null, null, null, null, null));

    assertThat(view.name()).isEqualTo("Renamed caliper");
    assertThat(view.model()).isEqualTo("Mitutoyo 500-752");
    assertThat(view.calibrationBody()).isEqualTo("B4T Lab");
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().action()).isEqualTo(AuditAction.UPDATE);
    assertThat(captor.getValue().previousValue()).containsEntry("name", "Digital caliper");
    assertThat(captor.getValue().newValue()).containsEntry("name", "Renamed caliper");
  }

  @Test
  @DisplayName("21.2-SVC-019 P1 duplicate-race on save classified to DUPLICATE_IDENTIFIER")
  void duplicateRaceClassified() {
    stubUnrestricted();
    when(instruments.existsByInstrumentCode("CAL-1")).thenReturn(false);
    when(instruments.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "Batch entry violated constraint uq_calibration_instruments_code"));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.SUPER_ADMIN),
        new CreateInstrumentCommand("CAL-1", "Caliper", null, null, null, 180, null,
            TODAY.plusDays(120), null, null)))
        .isInstanceOf(DuplicateIdentifierException.class);
  }

  @Test
  @DisplayName("21.2-SVC-020 P1 unrelated constraint violation rethrown, never misclassified")
  void unrelatedRaceRethrown() {
    stubUnrestricted();
    when(instruments.existsByInstrumentCode("CAL-1")).thenReturn(false);
    when(instruments.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "violated constraint some_other_constraint"));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.SUPER_ADMIN),
        new CreateInstrumentCommand("CAL-1", "Caliper", null, null, null, 180, null,
            TODAY.plusDays(120), null, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("21.2-SVC-021 P1 deleted-actor FK on recalibrate classified to FORBIDDEN (M8)")
  void deletedActorFkOnRecalibrate() {
    stubVisible(entity(TODAY.plusDays(3), CalibrationStatus.EXPIRING_SOON));
    when(records.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "violates foreign key constraint fk_calibration_records_recorded_by"));

    assertThatThrownBy(() -> service.recalibrate(user(ApplicationRole.STAFF_MAINTENANCE),
        instrumentId, new RecalibrateCommand(TODAY, TODAY.plusDays(180), null, null, null,
            null, null)))
        .isInstanceOf(ComplianceForbiddenException.class);
  }

  @Test
  @DisplayName("21.2-SVC-022 P1 listRecords of an out-of-scope instrument → 404 (scope gate)")
  void listRecordsScopeGate() {
    when(instruments.findById(instrumentId)).thenReturn(Optional.of(entity(TODAY.plusDays(120),
        CalibrationStatus.VALID)));
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(UUID.randomUUID()),
        Set.of(), Set.of()));
    when(instruments.countVisible(eq(instrumentId), eq(false), any())).thenReturn(0L);

    assertThatThrownBy(() -> service.listRecords(user(ApplicationRole.STAFF_MAINTENANCE),
        instrumentId))
        .isInstanceOf(CalibrationInstrumentNotFoundException.class);
    verify(records, never()).findByInstrumentIdOrderByCalibrationDateDesc(any());
  }
}
