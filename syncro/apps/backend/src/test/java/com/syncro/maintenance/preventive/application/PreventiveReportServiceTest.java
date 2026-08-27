package com.syncro.maintenance.preventive.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.preventive.domain.PreventiveCategory;
import com.syncro.maintenance.preventive.domain.ScheduleStatus;
import com.syncro.maintenance.preventive.domain.ScheduleType;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistItemEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistItemRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistResultEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistResultRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleAttachmentEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleAttachmentRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleRepository;
import com.syncro.storage.application.ObjectStorageService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
class PreventiveReportServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

  @Mock
  private PreventiveScheduleRepository schedules;
  @Mock
  private PreventiveProgramRepository programs;
  @Mock
  private PreventiveChecklistResultRepository results;
  @Mock
  private PreventiveChecklistItemRepository items;
  @Mock
  private PreventiveScheduleAttachmentRepository attachments;
  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private ObjectStorageService objectStorage;
  @Mock
  private com.syncro.machine.infrastructure.MachineRepository machines;

  private final UUID machineId = UUID.randomUUID();
  private final UUID scheduleId = UUID.randomUUID();
  private final UUID programId = UUID.randomUUID();
  private final UUID staffId = UUID.randomUUID();
  private final UUID leaderId = UUID.randomUUID();

  private PreventiveReportService service;
  private PreventiveScheduleEntity schedule;
  private PreventiveProgramEntity program;

  @BeforeEach
  void setUp() {
    service = new PreventiveReportService(schedules, programs, results, items, attachments, workOrders, objectStorage,
        machines);
    var plant = new com.syncro.auth.infrastructure.PlantEntity(UUID.randomUUID(), "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(UUID.randomUUID(), plant, "Group", NOW, NOW);
    var machine = new com.syncro.machine.infrastructure.MachineEntity(machineId, plant, group, "M-001", "Machine",
        com.syncro.machine.domain.MachineStatus.ACTIVE, null, null, null, List.of(), NOW, NOW);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    program = new PreventiveProgramEntity(programId, machineId, PreventiveCategory.MECHANICAL, ScheduleType.MONTHLY,
        (short) 15, null, "Monthly lube", null, true, true, staffId, NOW, NOW);
    lenient().when(programs.findById(programId)).thenReturn(Optional.of(program));
    schedule = new PreventiveScheduleEntity(scheduleId, programId, machineId, LocalDate.of(2026, 9, 15),
        ScheduleStatus.PERFORMED, NOW, staffId, NOW, NOW);
    lenient().when(schedules.findById(scheduleId)).thenReturn(Optional.of(schedule));
  }

  @Test
  @DisplayName("11.3-RPT-001 P0 report assembles checklist, evidence, signature and linked workorder")
  void reportOk() {
    var result = new PreventiveChecklistResultEntity(UUID.randomUUID(), scheduleId, staffId, NOW, "all good",
        leaderId, "ok", NOW, "preventive/sig.png", "Leader", NOW, NOW);
    when(results.findByScheduleId(scheduleId)).thenReturn(Optional.of(result));
    when(items.findByResultIdOrderByPositionAsc(result.getId())).thenReturn(List.of(
        new PreventiveChecklistItemEntity(UUID.randomUUID(), result.getId(), (short) 1, "lube", "ok",
            new BigDecimal("0.5"), new BigDecimal("1.5"), null, NOW)));
    when(attachments.findByScheduleIdOrderByCreatedAtAsc(scheduleId)).thenReturn(List.of(
        new PreventiveScheduleAttachmentEntity(UUID.randomUUID(), scheduleId, "photo.jpg", "image/jpeg",
            "preventive/" + scheduleId + "/a/1.jpg", 3, staffId, NOW, null)));
    when(objectStorage.presignGetUrl(any())).thenReturn("https://garage/presigned");
    var wo = new WorkOrderEntity("WO-2609-00001", "INTERNAL", null,
        com.syncro.maintenance.domain.workorder.WorkOrderStatus.OPEN, UUID.randomUUID(), machineId,
        "Preventive: Monthly lube due 2026-09-15", 0L, null, null, null, NOW, NOW);
    when(workOrders.findByPreventiveScheduleId(scheduleId)).thenReturn(Optional.of(wo));

    var report = service.get(scheduleId.toString());

    assertThat(report.programTitle()).isEqualTo("Monthly lube");
    assertThat(report.autoWorkorder()).isTrue();
    assertThat(report.checklist()).isNotNull();
    assertThat(report.checklist().notes()).isEqualTo("all good");
    assertThat(report.items()).hasSize(1);
    assertThat(report.items().getFirst().label()).isEqualTo("lube");
    assertThat(report.items().getFirst().lsl()).isEqualByComparingTo("0.5");
    assertThat(report.evidence()).hasSize(1);
    assertThat(report.evidence().getFirst().presignedUrl()).isEqualTo("https://garage/presigned");
    assertThat(report.signaturePresignedUrl()).isEqualTo("https://garage/presigned");
    assertThat(report.signerIdentity()).isEqualTo("Leader");
    assertThat(report.workOrderId()).isEqualTo("WO-2609-00001");
  }

  @Test
  @DisplayName("11.3-RPT-002 P0 report on a schedule with no checklist returns empty checklist")
  void reportNoChecklist() {
    when(results.findByScheduleId(scheduleId)).thenReturn(Optional.empty());
    when(attachments.findByScheduleIdOrderByCreatedAtAsc(scheduleId)).thenReturn(List.of());
    when(workOrders.findByPreventiveScheduleId(scheduleId)).thenReturn(Optional.empty());

    var report = service.get(scheduleId.toString());

    assertThat(report.checklist()).isNull();
    assertThat(report.items()).isEmpty();
    assertThat(report.evidence()).isEmpty();
    assertThat(report.signaturePresignedUrl()).isNull();
    assertThat(report.workOrderId()).isNull();
  }

  @Test
  @DisplayName("11.3-RPT-003 P0 unknown schedule is SCHEDULE_NOT_FOUND")
  void reportNotFound() {
    when(schedules.findById(scheduleId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(scheduleId.toString()))
        .isInstanceOf(com.syncro.maintenance.preventive.application.PreventiveReportService.ScheduleNotFoundException.class);
  }
}
