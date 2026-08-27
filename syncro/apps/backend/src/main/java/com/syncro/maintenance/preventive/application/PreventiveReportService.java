package com.syncro.maintenance.preventive.application;

import com.syncro.maintenance.preventive.domain.PreventiveChecklistItem;
import com.syncro.maintenance.preventive.domain.PreventiveChecklistResult;
import com.syncro.maintenance.preventive.domain.PreventiveReport;
import com.syncro.maintenance.preventive.domain.PreventiveReport.EvidenceRef;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistItemRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistResultEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistResultRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleAttachmentEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleAttachmentRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preventive report assembly (FR-133, story 11-3). Reads a schedule's program/machine
 * context, checklist result + items, evidence (with fresh short-TTL presigned URLs), the
 * leader signature block, and the linked auto-workorder id. Serves the data for the
 * browser-print page; image bytes and presigned URLs are never persisted. Reads are
 * any-authenticated (the preventive read posture).
 */
@Service
public class PreventiveReportService {

  private final PreventiveScheduleRepository schedules;
  private final PreventiveProgramRepository programs;
  private final PreventiveChecklistResultRepository results;
  private final PreventiveChecklistItemRepository items;
  private final PreventiveScheduleAttachmentRepository attachments;
  private final WorkOrderRepository workOrders;
  private final ObjectStorageService objectStorage;

  public PreventiveReportService(PreventiveScheduleRepository schedules, PreventiveProgramRepository programs,
      PreventiveChecklistResultRepository results, PreventiveChecklistItemRepository items,
      PreventiveScheduleAttachmentRepository attachments, WorkOrderRepository workOrders,
      ObjectStorageService objectStorage) {
    this.schedules = schedules;
    this.programs = programs;
    this.results = results;
    this.items = items;
    this.attachments = attachments;
    this.workOrders = workOrders;
    this.objectStorage = objectStorage;
  }

  @Transactional(readOnly = true)
  public PreventiveReport get(String scheduleId) {
    var schedule = schedules.findById(UUID.fromString(scheduleId))
        .orElseThrow(ScheduleNotFoundException::new);
    var program = programs.findById(schedule.getProgramId())
        .orElseThrow(ProgramNotFoundException::new);

    var resultEntity = results.findByScheduleId(schedule.getId()).orElse(null);
    PreventiveChecklistResult checklist = null;
    List<PreventiveChecklistItem> itemValues = List.of();
    if (resultEntity != null) {
      checklist = toDomain(resultEntity);
      itemValues = items.findByResultIdOrderByPositionAsc(resultEntity.getId()).stream()
          .map(e -> new PreventiveChecklistItem(e.getId(), e.getResultId(), e.getPosition(), e.getLabel(),
              e.getValue(), e.getLsl(), e.getUsl(), e.getNote()))
          .toList();
    }

    var evidence = attachments.findByScheduleIdOrderByCreatedAtAsc(schedule.getId()).stream()
        .map(this::toEvidenceRef)
        .toList();

    var signatureUrl = resultEntity != null && resultEntity.getSignatureObjectKey() != null
        ? presign(resultEntity.getSignatureObjectKey()) : null;

    var workOrderId = workOrders.findByPreventiveScheduleId(schedule.getId())
        .map(e -> e.getId()).orElse(null);

    return new PreventiveReport(schedule.getId(), program.getTitle(), program.getCategory(),
        program.getScheduleType(), program.isAutoWorkorder(), schedule.getMachineId(), schedule.getDueDate(),
        schedule.getStatus(), schedule.getCompletedAt(), schedule.getPerformedBy(), checklist, itemValues,
        evidence, signatureUrl,
        resultEntity != null ? resultEntity.getSignerIdentity() : null,
        resultEntity != null ? resultEntity.getApprovedAt() : null,
        workOrderId);
  }

  private EvidenceRef toEvidenceRef(PreventiveScheduleAttachmentEntity entity) {
    return new EvidenceRef(entity.getId(), entity.getFilename(), entity.getContentType(),
        presign(entity.getObjectKey()));
  }

  private String presign(String objectKey) {
    try {
      return objectStorage.presignGetUrl(objectKey);
    } catch (ObjectStorageException exception) {
      throw new ReportStorageException(exception);
    }
  }

  private static PreventiveChecklistResult toDomain(PreventiveChecklistResultEntity entity) {
    return new PreventiveChecklistResult(entity.getId(), entity.getScheduleId(), entity.getPerformedBy(),
        entity.getCompletedAt(), entity.getNotes(), entity.getLeaderId(), entity.getAssessment(),
        entity.getApprovedAt(), entity.getSignatureObjectKey(), entity.getSignerIdentity(),
        entity.getCreatedAt(), entity.getUpdatedAt());
  }

  public static class ScheduleNotFoundException extends RuntimeException {
  }

  public static class ProgramNotFoundException extends RuntimeException {
  }

  public static class ReportStorageException extends RuntimeException {
    public ReportStorageException(Throwable cause) {
      super(cause);
    }
  }
}
