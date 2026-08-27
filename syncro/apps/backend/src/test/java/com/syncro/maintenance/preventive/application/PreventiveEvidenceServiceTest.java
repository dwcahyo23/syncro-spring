package com.syncro.maintenance.preventive.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.config.WorkorderEvidenceProperties;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleAttachmentEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleAttachmentRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.storage.application.ObjectStorageService;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreventiveEvidenceServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

  @Mock
  private PreventiveScheduleRepository schedules;
  @Mock
  private PreventiveScheduleAttachmentRepository attachments;
  @Mock
  private AuditLogWriter auditLog;
  @Mock
  private ObjectStorageService objectStorage;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private MachineRepository machines;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID scheduleId = UUID.randomUUID();
  private final UUID staffId = UUID.randomUUID();

  private PreventiveEvidenceService service;

  @BeforeEach
  void setUp() {
    var properties = new WorkorderEvidenceProperties(10L * 1024L * 1024L);
    service = new PreventiveEvidenceService(schedules, attachments, auditLog, clock, objectStorage, properties,
        scopes, machines);
    var machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    var schedule = new PreventiveScheduleEntity(scheduleId, UUID.randomUUID(), machineId, LocalDate.of(2026, 9, 15),
        com.syncro.maintenance.preventive.domain.ScheduleStatus.SCHEDULED, null, null, NOW, NOW);
    lenient().when(schedules.findById(scheduleId)).thenReturn(Optional.of(schedule));
  }

  @Test
  @DisplayName("11.2-EVC-001 P0 scoped staff uploads evidence → stored in Garage + row persisted")
  void createOk() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(attachments.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(objectStorage.store(any(), any(), any())).thenReturn("k");
    when(objectStorage.presignGetUrl(any())).thenReturn("https://garage/presigned");

    var view = service.create(user, scheduleId, command("photo.jpg", "image/jpeg", new byte[] {1, 2, 3}));

    assertThat(view.filename()).isEqualTo("photo.jpg");
    assertThat(view.objectKey()).startsWith("preventive/" + scheduleId + "/" + view.id() + "/");
    assertThat(view.objectKey()).endsWith(".jpg");
    verify(objectStorage).store(eq(view.objectKey()), any(), eq("image/jpeg"));
  }

  @Test
  @DisplayName("11.2-EVC-002 P0 out-of-scope user is forbidden")
  void createForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "auditor@test", ApplicationRole.AUDITOR);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.create(user, scheduleId, command("photo.jpg", "image/jpeg", new byte[] {1})))
        .isInstanceOf(com.syncro.maintenance.preventive.application.PreventiveEvidenceService.EvidenceForbiddenException.class);
  }

  @Test
  @DisplayName("11.2-EVC-003 P0 empty file is rejected")
  void createEmptyFile() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.create(user, scheduleId, command("photo.jpg", "image/jpeg", new byte[] {})))
        .isInstanceOf(com.syncro.maintenance.preventive.application.PreventiveEvidenceService.EvidenceValidationException.class);
  }

  @Test
  @DisplayName("11.2-EVC-004 P0 delete removes the Garage object then the row")
  void deleteOk() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    var attachmentId = UUID.randomUUID();
    var entity = new PreventiveScheduleAttachmentEntity(attachmentId, scheduleId, "photo.jpg", "image/jpeg",
        "preventive/" + scheduleId + "/" + attachmentId + "/abc.jpg", 3, staffId, NOW, null);
    when(attachments.findByIdAndScheduleId(attachmentId, scheduleId)).thenReturn(Optional.of(entity));

    service.delete(user, scheduleId, attachmentId);

    verify(objectStorage).delete("preventive/" + scheduleId + "/" + attachmentId + "/abc.jpg");
    verify(attachments).delete(entity);
  }

  @Test
  @DisplayName("11.2-EVC-005 P0 list returns attachments ordered by createdAt asc")
  void listOk() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(objectStorage.presignGetUrl(any())).thenReturn("https://garage/presigned");
    var attachment = new PreventiveScheduleAttachmentEntity(UUID.randomUUID(), scheduleId, "photo.jpg", "image/jpeg",
        "k", 3, staffId, NOW, null);
    when(attachments.findByScheduleIdOrderByCreatedAtAsc(scheduleId)).thenReturn(List.of(attachment));

    var views = service.list(scheduleId);

    assertThat(views).hasSize(1);
    assertThat(views.getFirst().filename()).isEqualTo("photo.jpg");
  }

  private com.syncro.maintenance.preventive.application.PreventiveEvidenceService.EvidenceCommand command(
      String filename, String contentType, byte[] data) {
    return new com.syncro.maintenance.preventive.application.PreventiveEvidenceService.EvidenceCommand(
        filename, contentType, data);
  }

  private AuthenticatedUser staffUser() {
    return new AuthenticatedUser(staffId.toString(), "staff@test", ApplicationRole.STAFF_MAINTENANCE);
  }

  private static MachineEntity machineWithPlant(UUID plantId, UUID groupId, UUID machineId) {
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(groupId, plant, "Group", NOW, NOW);
    return new MachineEntity(machineId, plant, group, "M-001", "Machine", MachineStatus.ACTIVE, null, null, null,
        List.of(), NOW, NOW);
  }
}
