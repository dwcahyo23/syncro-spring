package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.config.WorkorderEvidenceProperties;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.application.WorkOrderReportService.CpkPdfCommand;
import com.syncro.maintenance.application.WorkOrderReportService.ReportForbiddenException;
import com.syncro.maintenance.application.WorkOrderReportService.ReportWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderReportService.SaveReportCommand;
import com.syncro.maintenance.application.WorkOrderReportService.StorageException;
import com.syncro.maintenance.application.WorkOrderReportService.WorkOrderReportValidationException;
import com.syncro.maintenance.domain.workorder.FmeaFailureType;
import com.syncro.maintenance.domain.workorder.StopTimeReason;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
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

@ExtendWith(MockitoExtension.class)
class WorkOrderReportServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
  private static final String WORKORDER_ID = "WO-240900001";

  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private MachineRepository machines;
  @Mock
  private AuditLogWriter auditLog;
  @Mock
  private ObjectStorageService objectStorage;
  @Mock
  private OperationalScopeService scopes;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID technicianId = UUID.randomUUID();

  private WorkOrderReportService service;
  private MachineEntity machine;

  @BeforeEach
  void setUp() {
    var properties = new WorkorderEvidenceProperties(10L * 1024L * 1024L);
    service = new WorkOrderReportService(workOrders, machines, auditLog, clock, objectStorage, scopes, properties);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    lenient().when(objectStorage.presignGetUrl(any())).thenReturn("https://presigned/" + machineId);
  }

  // -------------------------------------------------------------------------
  // Save report
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.6-SVC-001 P0 assigned executor saves the four-section report and audits UPDATE")
  void saveReportOk() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.saveReport(user, WORKORDER_ID, reportCommand("Chronology", "Analyze", "Corrective",
        "Preventive", new BigDecimal("1.10"), new BigDecimal("1.20"), new BigDecimal("1.33"),
        FmeaFailureType.MECHANICAL, StopTimeReason.ELECTRIC, "Bearing worn"));

    assertThat(view.reportChronological()).isEqualTo("Chronology");
    assertThat(view.reportAnalyze()).isEqualTo("Analyze");
    assertThat(view.reportCorrective()).isEqualTo("Corrective");
    assertThat(view.reportPreventive()).isEqualTo("Preventive");
    assertThat(view.cpCkLower()).isEqualByComparingTo(new BigDecimal("1.10"));
    assertThat(view.cpk()).isEqualByComparingTo(new BigDecimal("1.33"));
    assertThat(view.fmeaFailureType()).isEqualTo("MECHANICAL");
    assertThat(view.stopTimeReason()).isEqualTo("ELECTRIC");
    assertThat(view.stopTimeDetail()).isEqualTo("Bearing worn");
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.WORK_ORDER
        && r.entityLabel().equals(WORKORDER_ID)
        && r.previousValue() != null && r.newValue() != null
        && "Chronology".equals(r.newValue().get("reportChronological"))
        && "ELECTRIC".equals(r.newValue().get("stopTimeReason"))));
  }

  @Test
  @DisplayName("10.6-SVC-002 P0 an in-scope MAINTENANCE_LEADER (plant scope) may save the report")
  void saveReportByPlantScopedLeader() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "lead@syncro.dev",
        ApplicationRole.MAINTENANCE_LEADER);
    var entity = entity(null);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.saveReport(user, WORKORDER_ID, reportCommand("Chronology", null, null, null,
        null, null, null, null, null, null));

    assertThat(view.reportChronological()).isEqualTo("Chronology");
  }

  @Test
  @DisplayName("10.6-SVC-003 P0 report save by a non-executor non-leader is forbidden")
  void saveReportForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "audit@syncro.dev", ApplicationRole.AUDITOR);
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.saveReport(user, WORKORDER_ID, reportCommand(null, null, null, null,
        null, null, null, null, null, null)))
        .isInstanceOf(ReportForbiddenException.class);
    verify(workOrders, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("10.6-SVC-004 P0 report save on an unknown workorder is 404")
  void saveReportNotFound() {
    var user = assignedTechnician();
    when(workOrders.findById("WO-2409NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.saveReport(user, "WO-2409NADA", reportCommand(null, null, null, null,
        null, null, null, null, null, null)))
        .isInstanceOf(ReportWorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("10.6-SVC-005 P0 a narrative over 4000 chars fails validation")
  void saveReportNarrativeTooLong() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.saveReport(user, WORKORDER_ID, reportCommand("x".repeat(4001), null, null,
        null, null, null, null, null, null, null)))
        .isInstanceOfSatisfying(WorkOrderReportValidationException.class,
            exception -> assertThat(exception.getFieldErrors()).containsKey("reportChronological"));
    verify(workOrders, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("10.6-SVC-006 P0 a negative CP/CPK value fails validation")
  void saveReportNegativeCpk() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.saveReport(user, WORKORDER_ID, reportCommand(null, null, null, null,
        null, null, new BigDecimal("-0.1"), null, null, null)))
        .isInstanceOfSatisfying(WorkOrderReportValidationException.class,
            exception -> assertThat(exception.getFieldErrors()).containsKey("cpk"));
  }

  @Test
  @DisplayName("10.6-SVC-007 P0 a CP/CPK value exceeding NUMERIC(8,4) fails validation, not a DB 500")
  void saveReportCpkOutOfRange() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.saveReport(user, WORKORDER_ID, reportCommand(null, null, null, null,
        null, null, new BigDecimal("100000"), null, null, null)))
        .isInstanceOfSatisfying(WorkOrderReportValidationException.class,
            exception -> assertThat(exception.getFieldErrors()).containsKey("cpk"));
    verify(workOrders, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("10.6-SVC-008 P0 a CP/CPK value with more than 4 fractional digits fails validation")
  void saveReportCpkTooManyDecimals() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.saveReport(user, WORKORDER_ID, reportCommand(null, null, null, null,
        null, null, new BigDecimal("1.12345"), null, null, null)))
        .isInstanceOfSatisfying(WorkOrderReportValidationException.class,
            exception -> assertThat(exception.getFieldErrors()).containsKey("cpk"));
    verify(workOrders, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("10.6-SVC-009 P0 a report PUT clears previously-set sections and optional fields")
  void saveReportClearsPreviousFields() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    entity.applyReport("Old chronology", "Old analyze", "Old corrective", "Old preventive",
        new BigDecimal("1.10"), new BigDecimal("1.20"), new BigDecimal("1.33"), "MECHANICAL", "ELECTRIC",
        "Bearing worn");
    entity.setCpkPdfObjectKey("workorders/" + WORKORDER_ID + "/cpk/uuid.pdf");
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.saveReport(user, WORKORDER_ID, reportCommand("New chronology", null, null, null,
        null, null, null, null, null, null));

    assertThat(entity.getReportChronological()).isEqualTo("New chronology");
    assertThat(entity.getReportAnalyze()).isNull();
    assertThat(entity.getReportCorrective()).isNull();
    assertThat(entity.getReportPreventive()).isNull();
    assertThat(entity.getCpCkLower()).isNull();
    assertThat(entity.getCpk()).isNull();
    assertThat(entity.getFmeaFailureType()).isNull();
    assertThat(entity.getStopTimeReason()).isNull();
    assertThat(entity.getStopTimeDetail()).isNull();
    assertThat(entity.getCpkPdfObjectKey()).isNotNull(); // CPK key is a separate endpoint, untouched
  }

  @Test
  @DisplayName("10.6-SVC-010 P0 stop-time detail over 500 chars fails validation")
  void saveReportStopDetailTooLong() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.saveReport(user, WORKORDER_ID, reportCommand(null, null, null, null,
        null, null, null, null, null, "d".repeat(501))))
        .isInstanceOfSatisfying(WorkOrderReportValidationException.class,
            exception -> assertThat(exception.getFieldErrors()).containsKey("stopTimeDetail"));
  }

  // -------------------------------------------------------------------------
  // Get report
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.6-SVC-011 P0 get returns the report for any authenticated user with a fresh presigned URL")
  void getReportOk() {
    var entity = entity(technicianId);
    entity.applyReport("Chronology", "Analyze", "Corrective", "Preventive", new BigDecimal("1.10"),
        new BigDecimal("1.20"), new BigDecimal("1.33"), "MECHANICAL", "ELECTRIC", "Bearing worn");
    entity.setCpkPdfObjectKey("workorders/" + WORKORDER_ID + "/cpk/uuid.pdf");
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    var view = service.getReport(WORKORDER_ID);

    assertThat(view.reportChronological()).isEqualTo("Chronology");
    assertThat(view.cpkPdfPresignedUrl()).isEqualTo("https://presigned/" + machineId);
    assertThat(view.fmeaFailureType()).isEqualTo("MECHANICAL");
    assertThat(view.stopTimeReason()).isEqualTo("ELECTRIC");
  }

  @Test
  @DisplayName("10.6-SVC-012 P0 get with no CPK PDF returns a null presigned URL")
  void getReportNoCpkPdf() {
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    var view = service.getReport(WORKORDER_ID);

    assertThat(view.cpkPdfPresignedUrl()).isNull();
  }

  @Test
  @DisplayName("10.6-SVC-013 P0 get on an unknown workorder is 404")
  void getReportNotFound() {
    when(workOrders.findById("WO-2409NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getReport("WO-2409NADA"))
        .isInstanceOf(ReportWorkOrderNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // CPK PDF upload
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.6-SVC-014 P0 CPK upload stores the PDF, persists the key and audits UPDATE")
  void uploadCpkPdfOk() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(objectStorage.store(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.uploadCpkPdf(user, WORKORDER_ID, new CpkPdfCommand("capability.pdf", "application/pdf",
        new byte[] {1}));

    assertThat(view.cpkPdfPresignedUrl()).isNotNull();
    assertThat(entity.getCpkPdfObjectKey())
        .startsWith("workorders/" + WORKORDER_ID + "/cpk/").endsWith(".pdf");
    verify(objectStorage).store(any(), eq(new byte[] {1}), eq("application/pdf"));
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.WORK_ORDER
        && r.previousValue() != null && r.previousValue().get("cpkPdfObjectKey") == null
        && r.newValue() != null && r.newValue().get("cpkPdfObjectKey") != null));
  }

  @Test
  @DisplayName("10.6-SVC-015 P0 CPK replace deletes the old object before storing the new one")
  void uploadCpkReplaceDeletesOld() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    entity.setCpkPdfObjectKey("workorders/" + WORKORDER_ID + "/cpk/old.pdf");
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.uploadCpkPdf(user, WORKORDER_ID, new CpkPdfCommand("capability.pdf", "application/pdf",
        new byte[] {2}));

    var inOrder = inOrder(objectStorage);
    inOrder.verify(objectStorage).delete("workorders/" + WORKORDER_ID + "/cpk/old.pdf");
    inOrder.verify(objectStorage).store(any(), eq(new byte[] {2}), eq("application/pdf"));
    assertThat(entity.getCpkPdfObjectKey()).isNotEqualTo("workorders/" + WORKORDER_ID + "/cpk/old.pdf");
    var expectedOldKey = "workorders/" + WORKORDER_ID + "/cpk/old.pdf";
    verify(auditLog).record(eq(user), argThat(r -> expectedOldKey.equals(r.previousValue().get("cpkPdfObjectKey"))));
  }

  @Test
  @DisplayName("10.6-SVC-016 P0 CPK upload of a non-PDF content type is rejected")
  void uploadCpkNonPdf() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.uploadCpkPdf(user, WORKORDER_ID,
        new CpkPdfCommand("capability.png", "image/png", new byte[] {1})))
        .isInstanceOfSatisfying(WorkOrderReportValidationException.class,
            exception -> assertThat(exception.getFieldErrors()).containsKey("contentType"));
    verify(objectStorage, never()).store(any(), any(), any());
  }

  @Test
  @DisplayName("10.6-SVC-017 P0 oversize CPK PDF fails validation")
  void uploadCpkOversize() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.uploadCpkPdf(user, WORKORDER_ID,
        new CpkPdfCommand("capability.pdf", "application/pdf", new byte[10 * 1024 * 1024 + 1])))
        .isInstanceOfSatisfying(WorkOrderReportValidationException.class,
            exception -> assertThat(exception.getFieldErrors()).containsKey("data"));
  }

  @Test
  @DisplayName("10.6-SVC-018 P0 CPK upload by a forbidden user is denied")
  void uploadCpkForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "store@syncro.dev", ApplicationRole.STOREKEEPER);
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.uploadCpkPdf(user, WORKORDER_ID,
        new CpkPdfCommand("capability.pdf", "application/pdf", new byte[] {1})))
        .isInstanceOf(ReportForbiddenException.class);
    verify(objectStorage, never()).store(any(), any(), any());
  }

  @Test
  @DisplayName("10.6-SVC-019 P0 storage failure on CPK upload surfaces as StorageException")
  void uploadCpkStorageFailure() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(objectStorage.store(any(), any(), any()))
        .thenThrow(new ObjectStorageException("s3 down"));

    assertThatThrownBy(() -> service.uploadCpkPdf(user, WORKORDER_ID,
        new CpkPdfCommand("capability.pdf", "application/pdf", new byte[] {1})))
        .isInstanceOf(StorageException.class);
    verify(workOrders, never()).saveAndFlush(any());
  }

  // -------------------------------------------------------------------------
  // CPK PDF delete
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.6-SVC-020 P0 CPK delete removes the object, clears the key and audits UPDATE")
  void deleteCpkPdfOk() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    entity.setCpkPdfObjectKey("workorders/" + WORKORDER_ID + "/cpk/uuid.pdf");
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.deleteCpkPdf(user, WORKORDER_ID);

    verify(objectStorage).delete("workorders/" + WORKORDER_ID + "/cpk/uuid.pdf");
    assertThat(entity.getCpkPdfObjectKey()).isNull();
    assertThat(view.cpkPdfPresignedUrl()).isNull();
    var expectedKey = "workorders/" + WORKORDER_ID + "/cpk/uuid.pdf";
    verify(auditLog).record(eq(user), argThat(r -> expectedKey.equals(r.previousValue().get("cpkPdfObjectKey"))
        && r.newValue().get("cpkPdfObjectKey") == null));
  }

  @Test
  @DisplayName("10.6-SVC-021 P0 CPK delete with no stored key is an idempotent no-op")
  void deleteCpkPdfIdempotent() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.deleteCpkPdf(user, WORKORDER_ID);

    verify(objectStorage, never()).delete(any());
    assertThat(entity.getCpkPdfObjectKey()).isNull();
    assertThat(view.cpkPdfPresignedUrl()).isNull();
    verify(auditLog).record(eq(user), argThat(r -> r.previousValue().get("cpkPdfObjectKey") == null
        && r.newValue().get("cpkPdfObjectKey") == null));
  }

  @Test
  @DisplayName("10.6-SVC-022 P0 CPK delete by a forbidden user is denied")
  void deleteCpkForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "audit@syncro.dev", ApplicationRole.AUDITOR);
    var entity = entity(technicianId);
    entity.setCpkPdfObjectKey("workorders/" + WORKORDER_ID + "/cpk/uuid.pdf");
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.deleteCpkPdf(user, WORKORDER_ID))
        .isInstanceOf(ReportForbiddenException.class);
    verify(objectStorage, never()).delete(any());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private SaveReportCommand reportCommand(String chronological, String analyze, String corrective,
      String preventive, BigDecimal cpCkLower, BigDecimal cpCkUpper, BigDecimal cpk,
      FmeaFailureType fmea, StopTimeReason stopTimeReason, String stopTimeDetail) {
    return new SaveReportCommand(chronological, analyze, corrective, preventive, cpCkLower, cpCkUpper, cpk,
        fmea, stopTimeReason, stopTimeDetail);
  }

  private WorkOrderEntity entity(UUID assignedTechnician) {
    return new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, UUID.randomUUID(),
        machineId, "desc", 0, null, assignedTechnician, UUID.randomUUID(), NOW, NOW);
  }

  private AuthenticatedUser assignedTechnician() {
    return new AuthenticatedUser(technicianId.toString(), "tech@syncro.dev", ApplicationRole.TECHNICIAN);
  }

  private static MachineEntity machineWithPlant(UUID plantId, UUID groupId, UUID machineId) {
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(groupId, plant, "Group", NOW, NOW);
    return new MachineEntity(machineId, plant, group, "M-001", "Machine", MachineStatus.ACTIVE, null, null, null,
        List.of(), NOW, NOW);
  }
}
