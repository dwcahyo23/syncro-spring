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
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.config.WorkorderEvidenceProperties;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceAttachmentNotFoundException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceCommand;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceForbiddenException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.StorageException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.ValidationException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.WorkorderAttachmentView;
import com.syncro.maintenance.application.WorkOrderEvidenceService.WorkorderAttachmentsView;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkorderAttachmentEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderAttachmentRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkOrderEvidenceServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
  private static final String WORKORDER_ID = "WO-2409-00001";

  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private MachineRepository machines;
  @Mock
  private WorkorderAttachmentRepository attachments;
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

  private WorkOrderEvidenceService service;
  private MachineEntity machine;

  @BeforeEach
  void setUp() {
    var properties = new WorkorderEvidenceProperties(10L * 1024L * 1024L);
    service = new WorkOrderEvidenceService(workOrders, machines, attachments, auditLog, clock, objectStorage,
        scopes, properties);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    lenient().when(objectStorage.presignGetUrl(any())).thenReturn("https://presigned/" + machineId);
  }

  // -------------------------------------------------------------------------
  // Create
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.5-SVC-001 P0 assigned executor uploads a file: object stored, row saved, audit CREATE")
  void createOk() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var captor = ArgumentCaptor.forClass(WorkorderAttachmentEntity.class);
    when(attachments.saveAndFlush(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.create(user, WORKORDER_ID,
        new EvidenceCommand("photo.jpg", "image/jpeg", new byte[] {1, 2, 3}));

    assertThat(view.workOrderId()).isEqualTo(WORKORDER_ID);
    assertThat(view.filename()).isEqualTo("photo.jpg");
    assertThat(view.contentType()).isEqualTo("image/jpeg");
    assertThat(view.sizeBytes()).isEqualTo(3L);
    assertThat(view.presignedUrl()).isNotNull();
    assertThat(view.objectKey()).startsWith("workorders/" + WORKORDER_ID + "/").endsWith(".jpg");
    assertThat(view.updatedAt()).isNull();
    verify(objectStorage).store(eq(view.objectKey()), eq(new byte[] {1, 2, 3}), eq("image/jpeg"));
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.CREATE
        && r.entityType() == AuditEntityType.WORKORDER_ATTACHMENT
        && r.entityId().equals(captor.getValue().getId())
        && r.entityLabel().equals(WORKORDER_ID)
        && r.plantId().equals(plantId)
        && r.newValue() != null
        && WORKORDER_ID.equals(r.newValue().get("workOrderId"))));
  }

  @Test
  @DisplayName("10.5-SVC-002 P0 an in-scope MAINTENANCE_LEADER (plant scope) may upload")
  void createByPlantScopedLeader() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "lead@syncro.dev",
        ApplicationRole.MAINTENANCE_LEADER);
    var entity = entity(null);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(attachments.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(objectStorage.store(any(), any(), any())).thenReturn("workorders/" + WORKORDER_ID + "/x.jpg");

    var view = service.create(user, WORKORDER_ID,
        new EvidenceCommand("drawing.pdf", "application/pdf", new byte[] {1}));

    assertThat(view.objectKey()).endsWith(".pdf");
  }

  @Test
  @DisplayName("10.5-SVC-003 P0 upload by a non-executor non-leader is forbidden")
  void createForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "other@syncro.dev", ApplicationRole.TECHNICIAN);
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID,
        new EvidenceCommand("photo.jpg", "image/jpeg", new byte[] {1})))
        .isInstanceOf(EvidenceForbiddenException.class);
  }

  @Test
  @DisplayName("10.5-SVC-004 P0 unknown workorder is 404")
  void createUnknownWorkOrder() {
    var user = assignedTechnician();
    when(workOrders.findById("WO-2409-NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(user, "WO-2409-NADA",
        new EvidenceCommand("photo.jpg", "image/jpeg", new byte[] {1})))
        .isInstanceOf(EvidenceWorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("10.5-SVC-005 P0 oversize data yields a data field error")
  void createOversize() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID,
        new EvidenceCommand("photo.jpg", "image/jpeg", new byte[10 * 1024 * 1024 + 1])))
        .isInstanceOfSatisfying(ValidationException.class,
            exception -> assertThat(exception.getFieldErrors()).containsKey("data"));
  }

  @Test
  @DisplayName("10.5-SVC-006 P0 blank filename and empty data both fail validation")
  void createValidationErrors() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID,
        new EvidenceCommand("   ", "image/jpeg", new byte[0])))
        .isInstanceOfSatisfying(ValidationException.class, exception -> {
          assertThat(exception.getFieldErrors()).containsKey("filename");
          assertThat(exception.getFieldErrors()).containsKey("data");
        });
  }

  @Test
  @DisplayName("10.5-SVC-007 P0 blank contentType fails validation")
  void createBlankContentType() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID,
        new EvidenceCommand("photo.jpg", "  ", new byte[] {1})))
        .isInstanceOfSatisfying(ValidationException.class,
            exception -> assertThat(exception.getFieldErrors()).containsKey("contentType"));
  }

  @Test
  @DisplayName("10.5-SVC-008 P0 storage failure surfaces as StorageException")
  void createStorageFailure() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(objectStorage.store(any(), any(), any()))
        .thenThrow(new ObjectStorageException("s3 down"));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID,
        new EvidenceCommand("photo.jpg", "image/jpeg", new byte[] {1})))
        .isInstanceOf(StorageException.class);
  }

  @Test
  @DisplayName("10.5-SVC-020 P1 contentType longer than the VARCHAR(100) column fails validation")
  void createContentTypeTooLong() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID,
        new EvidenceCommand("photo.bin", "a".repeat(101), new byte[] {1})))
        .isInstanceOfSatisfying(ValidationException.class,
            exception -> assertThat(exception.getFieldErrors()).containsKey("contentType"));
    verify(objectStorage, never()).store(any(), any(), any());
    verify(attachments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("10.5-SVC-021 P1 replace store failure leaves the row unchanged and surfaces StorageException")
  void replaceStoreFailure() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    var attachment = attachmentEntity();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(attachments.findByIdAndWorkOrderId(attachment.getId(), WORKORDER_ID)).thenReturn(Optional.of(attachment));
    when(objectStorage.store(any(), any(), any()))
        .thenThrow(new ObjectStorageException("s3 down"));

    assertThatThrownBy(() -> service.replace(user, WORKORDER_ID, attachment.getId(),
        new EvidenceCommand("new.pdf", "application/pdf", new byte[] {9})))
        .isInstanceOf(StorageException.class);
    // The old object was deleted before the failing store; the row itself is untouched
    // (the transaction rolls back) — retry re-runs delete (idempotent) then store.
    verify(objectStorage).delete(attachment.getObjectKey());
    verify(attachments, never()).saveAndFlush(any());
    verify(auditLog, never()).record(any(), any());
  }

  // -------------------------------------------------------------------------
  // Replace
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.5-SVC-009 P0 replace deletes the old object before storing the new one and audits UPDATE")
  void replaceOkDeleteBeforeStore() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    var attachment = attachmentEntity();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(attachments.findByIdAndWorkOrderId(attachment.getId(), WORKORDER_ID)).thenReturn(Optional.of(attachment));
    var oldKey = attachment.getObjectKey();
    when(attachments.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.replace(user, WORKORDER_ID, attachment.getId(),
        new EvidenceCommand("new.pdf", "application/pdf", new byte[] {9}));

    var inOrder = inOrder(objectStorage);
    inOrder.verify(objectStorage).delete(oldKey);
    inOrder.verify(objectStorage).store(any(), any(), any());
    assertThat(view.objectKey()).isNotEqualTo(oldKey).endsWith(".pdf");
    assertThat(view.updatedAt()).isEqualTo(NOW);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.WORKORDER_ATTACHMENT
        && r.previousValue() != null
        && oldKey.equals(r.previousValue().get("objectKey"))
        && view.objectKey().equals(r.newValue().get("objectKey"))));
  }

  @Test
  @DisplayName("10.5-SVC-010 P0 replace on an attachment of another workorder is 404")
  void replaceWrongWorkOrder() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(attachments.findByIdAndWorkOrderId(any(), eq(WORKORDER_ID))).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.replace(user, WORKORDER_ID, UUID.randomUUID(),
        new EvidenceCommand("new.pdf", "application/pdf", new byte[] {1})))
        .isInstanceOf(EvidenceAttachmentNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // Delete
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.5-SVC-011 P0 delete removes the Garage object, the row and audits DELETE")
  void deleteOk() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    var attachment = attachmentEntity();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(attachments.findByIdAndWorkOrderId(attachment.getId(), WORKORDER_ID)).thenReturn(Optional.of(attachment));

    service.delete(user, WORKORDER_ID, attachment.getId());

    verify(objectStorage).delete(attachment.getObjectKey());
    verify(attachments).delete(attachment);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.DELETE
        && r.entityType() == AuditEntityType.WORKORDER_ATTACHMENT
        && r.previousValue() != null
        && attachment.getObjectKey().equals(r.previousValue().get("objectKey"))));
  }

  @Test
  @DisplayName("10.5-SVC-012 P0 delete on an unknown attachment is 404")
  void deleteUnknownAttachment() {
    var user = assignedTechnician();
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(attachments.findByIdAndWorkOrderId(any(), eq(WORKORDER_ID))).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(user, WORKORDER_ID, UUID.randomUUID()))
        .isInstanceOf(EvidenceAttachmentNotFoundException.class);
  }

  @Test
  @DisplayName("10.5-SVC-013 P0 delete by a non-executor non-leader is forbidden")
  void deleteForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "audit@syncro.dev", ApplicationRole.AUDITOR);
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.delete(user, WORKORDER_ID, UUID.randomUUID()))
        .isInstanceOf(EvidenceForbiddenException.class);
  }

  // -------------------------------------------------------------------------
  // List & get
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.5-SVC-014 P0 list returns attachments ordered by createdAt asc for any authenticated user")
  void listOrdered() {
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var first = new WorkorderAttachmentEntity(UUID.randomUUID(), WORKORDER_ID, "a.png", "image/png",
        "workorders/" + WORKORDER_ID + "/a.png", 1, technicianId, NOW.minusSeconds(60), null);
    var second = new WorkorderAttachmentEntity(UUID.randomUUID(), WORKORDER_ID, "b.pdf", "application/pdf",
        "workorders/" + WORKORDER_ID + "/b.pdf", 2, technicianId, NOW, null);
    when(attachments.findByWorkOrderIdOrderByCreatedAtAsc(WORKORDER_ID)).thenReturn(List.of(first, second));

    WorkorderAttachmentsView view = service.list(WORKORDER_ID);

    assertThat(view.workOrderId()).isEqualTo(WORKORDER_ID);
    assertThat(view.attachments()).hasSize(2);
    assertThat(view.attachments().get(0).filename()).isEqualTo("a.png");
    assertThat(view.attachments().get(1).filename()).isEqualTo("b.pdf");
    assertThat(view.attachments().get(0).presignedUrl()).isNotNull();
  }

  @Test
  @DisplayName("10.5-SVC-014b P1 list returns an empty array when the workorder has no attachments")
  void listEmpty() {
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(attachments.findByWorkOrderIdOrderByCreatedAtAsc(WORKORDER_ID)).thenReturn(List.of());

    WorkorderAttachmentsView view = service.list(WORKORDER_ID);

    assertThat(view.workOrderId()).isEqualTo(WORKORDER_ID);
    assertThat(view.attachments()).isEmpty();
  }

  @Test
  @DisplayName("10.5-SVC-015 P0 list on an unknown workorder is 404")
  void listUnknownWorkOrder() {
    when(workOrders.findById("WO-2409-NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.list("WO-2409-NADA"))
        .isInstanceOf(EvidenceWorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("10.5-SVC-016 P0 get returns a single view with presigned URL")
  void getSingle() {
    var entity = entity(technicianId);
    var attachment = attachmentEntity();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(attachments.findByIdAndWorkOrderId(attachment.getId(), WORKORDER_ID)).thenReturn(Optional.of(attachment));

    WorkorderAttachmentView view = service.get(WORKORDER_ID, attachment.getId());

    assertThat(view.id()).isEqualTo(attachment.getId());
    assertThat(view.filename()).isEqualTo(attachment.getFilename());
    assertThat(view.presignedUrl()).isNotNull();
  }

  @Test
  @DisplayName("10.5-SVC-017 P0 get on an unknown attachment is 404")
  void getUnknownAttachment() {
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(attachments.findByIdAndWorkOrderId(any(), eq(WORKORDER_ID))).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(WORKORDER_ID, UUID.randomUUID()))
        .isInstanceOf(EvidenceAttachmentNotFoundException.class);
  }

  @Test
  @DisplayName("10.5-SVC-018 P1 presign failure surfaces as StorageException on list")
  void listPresignFailure() {
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var attachment = attachmentEntity();
    when(attachments.findByWorkOrderIdOrderByCreatedAtAsc(WORKORDER_ID)).thenReturn(List.of(attachment));
    when(objectStorage.presignGetUrl(any())).thenThrow(new ObjectStorageException("presign down"));

    assertThatThrownBy(() -> service.list(WORKORDER_ID)).isInstanceOf(StorageException.class);
  }

  @Test
  @DisplayName("10.5-SVC-019 P1 a STOREKEEPER (no executor/leader) is forbidden on create")
  void createForbiddenStorekeeper() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "store@syncro.dev", ApplicationRole.STOREKEEPER);
    var entity = entity(technicianId);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID,
        new EvidenceCommand("photo.jpg", "image/jpeg", new byte[] {1})))
        .isInstanceOf(EvidenceForbiddenException.class);
    verify(attachments, never()).saveAndFlush(any());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private WorkOrderEntity entity(UUID assignedTechnician) {
    return new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, UUID.randomUUID(),
        machineId, "desc", 0, null, assignedTechnician, UUID.randomUUID(), NOW, NOW);
  }

  private WorkorderAttachmentEntity attachmentEntity() {
    return new WorkorderAttachmentEntity(UUID.randomUUID(), WORKORDER_ID, "photo.jpg", "image/jpeg",
        "workorders/" + WORKORDER_ID + "/old.jpg", 3, technicianId, NOW, null);
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
