package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.maintenance.application.WorkorderPrintReportService.PrintReportStorageException;
import com.syncro.maintenance.application.WorkorderPrintReportService.PrintReportWorkOrderNotFoundException;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.RepairSessionEntity;
import com.syncro.maintenance.infrastructure.db.RepairSessionRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderAttachmentEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderAttachmentRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderSignatureEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderSignatureRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import com.syncro.sparepart.request.domain.SparepartRequestType;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestEntity;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestRepository;
import com.syncro.storage.application.ObjectStorageService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
class WorkorderPrintReportServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
  private static final String WORKORDER_ID = "WO-2609-00001";

  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private WorkOrderCategoryRepository categories;
  @Mock
  private MachineRepository machines;
  @Mock
  private RepairSessionRepository sessions;
  @Mock
  private WorkorderAttachmentRepository attachments;
  @Mock
  private SparepartRequestRepository sparepartRequests;
  @Mock
  private ObjectStorageService objectStorage;
  @Mock
  private AuthUserRepository users;
  @Mock
  private WorkorderSignatureRepository signatureRepository;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID categoryId = UUID.randomUUID();
  private final UUID technicianId = UUID.randomUUID();

  private WorkorderPrintReportService service;
  private WorkorderSignatureService signatureService;

  @BeforeEach
  void setUp() {
    signatureService = new WorkorderSignatureService(workOrders, machines, signatureRepository,
        objectStorage, null, null, clock);
    service = new WorkorderPrintReportService(workOrders, categories, machines, sessions, attachments,
        sparepartRequests, signatureService, objectStorage, users);
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(groupId, plant, "Group", NOW, NOW);
    var machine = new MachineEntity(machineId, plant, group, "M-001", "Pump 1",
        com.syncro.machine.domain.MachineStatus.ACTIVE, null, null, null, List.of(), NOW, NOW);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    lenient().when(objectStorage.presignGetUrl(any())).thenReturn("https://garage/presigned");
    lenient().when(users.findById(technicianId)).thenReturn(Optional.of(
        new AuthUserEntity(technicianId, "tech@syncro.dev", "hash", null, true, NOW, NOW)));
  }

  private WorkOrderEntity entity(WorkOrderStatus status) {
    return new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, status, categoryId, machineId,
        "Repair pump", 0L, null, technicianId, UUID.randomUUID(), NOW, NOW);
  }

  @Test
  @DisplayName("14.3-RPT-001 P0 report assembles header, sessions, narrative, cpk, evidence, parts and signature")
  void reportOk() {
    var wo = entity(WorkOrderStatus.DONE);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(wo));
    when(categories.findById(categoryId)).thenReturn(Optional.of(
        new WorkOrderCategoryEntity(categoryId, "01", "Breakdown", UUID.randomUUID(), NOW, NOW)));
    when(sessions.findByWorkOrderIdOrderByStartedAtAsc(WORKORDER_ID)).thenReturn(List.of(
        new RepairSessionEntity(UUID.randomUUID(), WORKORDER_ID, technicianId, "diagnosis",
            NOW.minusSeconds(3600), NOW, 60L, NOW, NOW)));
    when(attachments.findByWorkOrderIdOrderByCreatedAtAsc(WORKORDER_ID)).thenReturn(List.of(
        new WorkorderAttachmentEntity(UUID.randomUUID(), WORKORDER_ID, "photo.jpg", "image/jpeg",
            "workorders/WO-2609-00001/a/1.jpg", 3, technicianId, NOW, null)));
    when(sparepartRequests.findByWorkOrderIdOrderByRequestedAtAsc(WORKORDER_ID)).thenReturn(List.of(
        new SparepartRequestEntity(UUID.randomUUID(), SparepartRequestType.SPAREPART, WORKORDER_ID, machineId,
            null, "MRE-001", (short) 2, null, null, null, SparepartRequestStatus.READY,
            technicianId, NOW, null, NOW, NOW)));
    when(signatureRepository.findByWorkOrderId(WORKORDER_ID)).thenReturn(Optional.of(
        new WorkorderSignatureEntity(UUID.randomUUID(), WORKORDER_ID,
            "workorders/WO-2609-00001/signature/abc.png", "Leader", UUID.randomUUID(), NOW, NOW, NOW)));

    var report = service.get(WORKORDER_ID);

    assertThat(report.header().id()).isEqualTo(WORKORDER_ID);
    assertThat(report.header().categoryCode()).isEqualTo("01");
    assertThat(report.header().categoryLabel()).isEqualTo("Breakdown");
    assertThat(report.header().machineCode()).isEqualTo("M-001");
    assertThat(report.header().machineName()).isEqualTo("Pump 1");
    assertThat(report.sessions()).hasSize(1);
    assertThat(report.sessions().getFirst().description()).isEqualTo("diagnosis");
    assertThat(report.evidence()).hasSize(1);
    assertThat(report.evidence().getFirst().presignedUrl()).isEqualTo("https://garage/presigned");
    assertThat(report.parts()).hasSize(1);
    assertThat(report.parts().getFirst().materialCode()).isEqualTo("MRE-001");
    assertThat(report.signature()).isNotNull();
    assertThat(report.signature().signerIdentity()).isEqualTo("Leader");
    assertThat(report.signature().signaturePresignedUrl()).isEqualTo("https://garage/presigned");
  }

  @Test
  @DisplayName("14.3-RPT-002 P0 report on a sparse workorder returns empty sections, no error")
  void reportSparse() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.DONE)));
    when(sessions.findByWorkOrderIdOrderByStartedAtAsc(WORKORDER_ID)).thenReturn(List.of());
    when(attachments.findByWorkOrderIdOrderByCreatedAtAsc(WORKORDER_ID)).thenReturn(List.of());
    when(sparepartRequests.findByWorkOrderIdOrderByRequestedAtAsc(WORKORDER_ID)).thenReturn(List.of());
    when(signatureRepository.findByWorkOrderId(WORKORDER_ID)).thenReturn(Optional.empty());

    var report = service.get(WORKORDER_ID);

    assertThat(report.header().id()).isEqualTo(WORKORDER_ID);
    assertThat(report.sessions()).isEmpty();
    assertThat(report.narrative().reportChronological()).isNull();
    assertThat(report.cpk()).isNotNull();
    assertThat(report.cpk().cpkPdfPresignedUrl()).isNull();
    assertThat(report.evidence()).isEmpty();
    assertThat(report.parts()).isEmpty();
    assertThat(report.signature()).isNull();
  }

  @Test
  @DisplayName("14.3-RPT-003 P0 unknown workorder is PRINT_REPORT_NOT_FOUND")
  void reportNotFound() {
    when(workOrders.findById("WO-2609-NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get("WO-2609-NADA"))
        .isInstanceOf(PrintReportWorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("14.3-RPT-004 P0 signature block is null when presign returns null (deleted object)")
  void reportSignaturePresignNull() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.DONE)));
    when(sessions.findByWorkOrderIdOrderByStartedAtAsc(WORKORDER_ID)).thenReturn(List.of());
    when(attachments.findByWorkOrderIdOrderByCreatedAtAsc(WORKORDER_ID)).thenReturn(List.of());
    when(sparepartRequests.findByWorkOrderIdOrderByRequestedAtAsc(WORKORDER_ID)).thenReturn(List.of());
    when(signatureRepository.findByWorkOrderId(WORKORDER_ID)).thenReturn(Optional.of(
        new com.syncro.maintenance.infrastructure.db.WorkorderSignatureEntity(UUID.randomUUID(), WORKORDER_ID,
            "workorders/WO-2609-00001/signature/abc.png", "Leader", UUID.randomUUID(), NOW, NOW, NOW)));
    // presign returns null — Garage object was deleted
    when(objectStorage.presignGetUrl("workorders/WO-2609-00001/signature/abc.png")).thenReturn(null);

    var report = service.get(WORKORDER_ID);

    assertThat(report.signature()).isNull();
  }

  @Test
  @DisplayName("14.3-RPT-005 P0 storage failure for evidence presign surfaces PrintReportStorageException")
  void reportEvidenceStorageFailure() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.DONE)));
    when(sessions.findByWorkOrderIdOrderByStartedAtAsc(WORKORDER_ID)).thenReturn(List.of());
    when(attachments.findByWorkOrderIdOrderByCreatedAtAsc(WORKORDER_ID)).thenReturn(List.of(
        new com.syncro.maintenance.infrastructure.db.WorkorderAttachmentEntity(UUID.randomUUID(), WORKORDER_ID,
            "photo.jpg", "image/jpeg", "workorders/key.jpg", 3, UUID.randomUUID(), NOW, null)));
    when(sparepartRequests.findByWorkOrderIdOrderByRequestedAtAsc(WORKORDER_ID)).thenReturn(List.of());
    when(signatureRepository.findByWorkOrderId(WORKORDER_ID)).thenReturn(Optional.empty());
    when(objectStorage.presignGetUrl("workorders/key.jpg")).thenThrow(
        new com.syncro.storage.application.ObjectStorageException("garage down"));

    assertThatThrownBy(() -> service.get(WORKORDER_ID))
        .isInstanceOf(PrintReportStorageException.class);
  }
}
