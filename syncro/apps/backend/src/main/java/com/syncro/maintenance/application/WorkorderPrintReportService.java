package com.syncro.maintenance.application;

import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportCpkView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportEvidenceView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportHeaderView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportNarrativeView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportPartView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportSessionView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportSignatureView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.WorkorderPrintReportView;
import com.syncro.maintenance.infrastructure.db.RepairSessionRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderAttachmentEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderAttachmentRepository;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestRepository;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workorder print report assembly (story 14-3, FR-175). Composes the WO header, repair
 * sessions, narrative, CP/CPK/stop-time, evidence attachments, sparepart requests and
 * the leader signature block into one aggregate DTO for the WYSIWYG browser-print page.
 * Mirrors the {@link com.syncro.maintenance.preventive.application.PreventiveReportService}
 * pattern. Reads are any-authenticated (workorder read posture).
 */
@Service
public class WorkorderPrintReportService {

  private final WorkOrderRepository workOrders;
  private final WorkOrderCategoryRepository categories;
  private final MachineRepository machines;
  private final RepairSessionRepository sessions;
  private final WorkorderAttachmentRepository attachments;
  private final SparepartRequestRepository sparepartRequests;
  private final WorkorderSignatureService signatureService;
  private final ObjectStorageService objectStorage;
  private final AuthUserRepository users;

  public WorkorderPrintReportService(WorkOrderRepository workOrders, WorkOrderCategoryRepository categories,
      MachineRepository machines, RepairSessionRepository sessions, WorkorderAttachmentRepository attachments,
      SparepartRequestRepository sparepartRequests, WorkorderSignatureService signatureService,
      ObjectStorageService objectStorage, AuthUserRepository users) {
    this.workOrders = workOrders;
    this.categories = categories;
    this.machines = machines;
    this.sessions = sessions;
    this.attachments = attachments;
    this.sparepartRequests = sparepartRequests;
    this.signatureService = signatureService;
    this.objectStorage = objectStorage;
    this.users = users;
  }

  @Transactional(readOnly = true)
  public WorkorderPrintReportView get(String workOrderId) {
    var entity = workOrders.findById(workOrderId)
        .orElseThrow(PrintReportWorkOrderNotFoundException::new);

    var header = buildHeader(entity);
    var sessionViews = buildSessions(workOrderId);
    var narrative = buildNarrative(entity);
    var cpkView = buildCpk(entity);
    var evidenceViews = buildEvidence(workOrderId);
    var partViews = buildParts(workOrderId);
    var signatureView = buildSignature(workOrderId);

    return new WorkorderPrintReportView(header, sessionViews, narrative, cpkView, evidenceViews, partViews,
        signatureView);
  }

  // -------------------------------------------------------------------------
  // Section builders
  // -------------------------------------------------------------------------

  private PrintReportHeaderView buildHeader(WorkOrderEntity entity) {
    var category = entity.getCategoryId() != null
        ? categories.findById(entity.getCategoryId()).orElse(null)
        : null;

    var machine = machines.findByIdWithPlantAndGroup(entity.getMachineId()).orElse(null);

    // Resolve technician display name
    String technicianName = null;
    if (entity.getAssignedTechnicianId() != null) {
      var user = users.findById(entity.getAssignedTechnicianId()).orElse(null);
      if (user != null) {
        technicianName = user.getDisplayName() != null && !user.getDisplayName().isBlank()
            ? user.getDisplayName().trim()
            : user.getLoginIdentifier();
      }
    }

    return new PrintReportHeaderView(
        entity.getId(),
        entity.getSource(),
        entity.getStatus().name(),
        category != null ? category.getCode() : null,
        category != null ? category.getLabel() : null,
        entity.getMachineId(),
        machine != null ? machine.getCode() : null,
        machine != null ? machine.getName() : null,
        machine != null ? machine.getPlant().getCode() : null,
        entity.getDescription(),
        entity.getAssignedTechnicianId(),
        technicianName,
        entity.getCreatedAt() != null ? LocalDate.ofInstant(entity.getCreatedAt(), ZoneOffset.UTC) : null,
        entity.getUpdatedAt() != null ? LocalDate.ofInstant(entity.getUpdatedAt(), ZoneOffset.UTC) : null,
        entity.getDoneReason());
  }

  private List<PrintReportSessionView> buildSessions(String workOrderId) {
    return sessions.findByWorkOrderIdOrderByStartedAtAsc(workOrderId).stream()
        .map(s -> new PrintReportSessionView(s.getId(), s.getTechnicianId(), s.getDescription(),
            s.getStartedAt(), s.getEndedAt(), s.getDurationMinutes()))
        .toList();
  }

  private static PrintReportNarrativeView buildNarrative(WorkOrderEntity entity) {
    return new PrintReportNarrativeView(
        entity.getReportChronological(),
        entity.getReportAnalyze(),
        entity.getReportCorrective(),
        entity.getReportPreventive());
  }

  private PrintReportCpkView buildCpk(WorkOrderEntity entity) {
    var cpkKey = entity.getCpkPdfObjectKey();
    String cpkUrl = null;
    if (cpkKey != null) {
      cpkUrl = presign(cpkKey);
    }
    return new PrintReportCpkView(
        entity.getCpCkLower(),
        entity.getCpCkUpper(),
        entity.getCpk(),
        cpkUrl,
        entity.getFmeaFailureType(),
        entity.getStopTimeReason(),
        entity.getStopTimeDetail());
  }

  private List<PrintReportEvidenceView> buildEvidence(String workOrderId) {
    return attachments.findByWorkOrderIdOrderByCreatedAtAsc(workOrderId).stream()
        .map(this::toEvidenceView)
        .toList();
  }

  private PrintReportEvidenceView toEvidenceView(WorkorderAttachmentEntity entity) {
    return new PrintReportEvidenceView(entity.getId(), entity.getFilename(), entity.getContentType(),
        presign(entity.getObjectKey()));
  }

  private List<PrintReportPartView> buildParts(String workOrderId) {
    return sparepartRequests.findByWorkOrderIdOrderByRequestedAtAsc(workOrderId).stream()
        .map(r -> new PrintReportPartView(r.getId(), r.getMaterialCode(), r.getQuantity(),
            r.getStatus().name(), r.getNotes()))
        .toList();
  }

  private PrintReportSignatureView buildSignature(String workOrderId) {
    var sig = signatureService.getSignature(workOrderId);
    if (sig == null) {
      return null;
    }
    // Presigning a deleted/missing Garage object can return null (storage no longer
    // has the bytes) — the print page must not break; the image is simply skipped.
    var presignedUrl = signatureService.presignSignature(sig.signatureObjectKey());
    if (presignedUrl == null) {
      return null;
    }
    return new PrintReportSignatureView(presignedUrl, sig.signerIdentity(), sig.signedBy(), sig.signedAt());
  }

  // -------------------------------------------------------------------------
  // Storage helper
  // -------------------------------------------------------------------------

  private String presign(String objectKey) {
    try {
      return objectStorage.presignGetUrl(objectKey);
    } catch (ObjectStorageException exception) {
      throw new PrintReportStorageException(exception);
    }
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  public static class PrintReportWorkOrderNotFoundException extends RuntimeException {
  }

  public static class PrintReportStorageException extends RuntimeException {
    public PrintReportStorageException(Throwable cause) {
      super(cause);
    }
  }
}