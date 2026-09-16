package com.syncro.maintenance.application;

import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.UserSignatureRepository;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportCpkView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportEvidenceView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportHeaderView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportLeaderSignatureView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportNarrativeView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportPartView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportSessionView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.PrintReportSignatureView;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.WorkorderPrintReportView;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.RepairSessionRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderAttachmentEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderAttachmentRepository;
import com.syncro.org.infrastructure.SectionRepository;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestRepository;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
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

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkorderPrintReportService.class);

  /** Terminal statuses whose print reports may carry a leader signature (spec Always block). */
  private static final Set<WorkOrderStatus> SIGNATURE_STATUSES =
      Set.of(WorkOrderStatus.PENDING_REVIEW, WorkOrderStatus.CLOSED);

  private final WorkOrderRepository workOrders;
  private final WorkOrderCategoryRepository categories;
  private final MachineRepository machines;
  private final RepairSessionRepository sessions;
  private final WorkorderAttachmentRepository attachments;
  private final SparepartRequestRepository sparepartRequests;
  private final WorkorderSignatureService signatureService;
  private final ObjectStorageService objectStorage;
  private final AuthUserRepository users;
  private final SectionRepository sections;
  private final UserSignatureRepository userSignatures;
  private final MachineResponsibilityRepository responsibilities;

  public WorkorderPrintReportService(WorkOrderRepository workOrders, WorkOrderCategoryRepository categories,
      MachineRepository machines, RepairSessionRepository sessions, WorkorderAttachmentRepository attachments,
      SparepartRequestRepository sparepartRequests, WorkorderSignatureService signatureService,
      ObjectStorageService objectStorage, AuthUserRepository users, SectionRepository sections,
      UserSignatureRepository userSignatures, MachineResponsibilityRepository responsibilities) {
    this.workOrders = workOrders;
    this.categories = categories;
    this.machines = machines;
    this.sessions = sessions;
    this.attachments = attachments;
    this.sparepartRequests = sparepartRequests;
    this.signatureService = signatureService;
    this.objectStorage = objectStorage;
    this.users = users;
    this.sections = sections;
    this.userSignatures = userSignatures;
    this.responsibilities = responsibilities;
  }

  @Transactional(readOnly = true)
  public WorkorderPrintReportView get(String workOrderId) {
    var entity = workOrders.findById(workOrderId)
        .orElseThrow(PrintReportWorkOrderNotFoundException::new);

    var machine = entity.getMachineId() != null
        ? machines.findByIdWithPlantAndGroup(entity.getMachineId()).orElse(null)
        : null;
    var header = buildHeader(entity, machine);
    var sessionViews = buildSessions(workOrderId);
    var narrative = buildNarrative(entity);
    var cpkView = buildCpk(entity);
    var evidenceViews = buildEvidence(workOrderId);
    var partViews = buildParts(workOrderId);
    var signatureView = buildSignature(workOrderId);
    var leaderSignatureView = buildLeaderSignature(entity, machine);

    return new WorkorderPrintReportView(header, sessionViews, narrative, cpkView, evidenceViews, partViews,
        signatureView, leaderSignatureView);
  }

  // -------------------------------------------------------------------------
  // Section builders
  // -------------------------------------------------------------------------

  private PrintReportHeaderView buildHeader(WorkOrderEntity entity, MachineEntity machine) {
    var category = entity.getCategoryId() != null
        ? categories.findById(entity.getCategoryId()).orElse(null)
        : null;

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

  /**
   * Leader signature for template stamping (leader-signature spec): resolve the WO's
   * machine to its section leader via machine → group → section → leader_user_id,
   * falling back to the earliest LEADER machine responsibility when the chain is
   * incomplete, then presign that user's stored signature. Null (token renders as
   * nothing) for non-terminal WOs, unresolvable leaders, inactive leaders, missing
   * stored signatures, or presign failures — a missing image must never break the
   * print page.
   */
  private PrintReportLeaderSignatureView buildLeaderSignature(WorkOrderEntity entity, MachineEntity machine) {
    if (!SIGNATURE_STATUSES.contains(entity.getStatus()) || machine == null) {
      return null;
    }
    var leaderUserId = resolveLeaderUserId(machine);
    if (leaderUserId == null) {
      return null;
    }
    var leader = users.findById(leaderUserId).orElse(null);
    // A deactivated (or reassigned-then-disabled) leader never stamps new reports.
    if (leader == null || !leader.isEnabled()) {
      return null;
    }
    var stored = userSignatures.findByUserId(leaderUserId).orElse(null);
    if (stored == null) {
      return null;
    }
    String url;
    try {
      url = objectStorage.presignGetUrl(stored.getObjectKey());
    } catch (RuntimeException exception) {
      // Null-tolerant for ANY storage failure — unlike evidence presign, a stamp
      // must never take down the whole print report.
      log.warn("Leader signature presign failed key={}", stored.getObjectKey(), exception);
      return null;
    }
    if (url == null) {
      return null;
    }
    var displayName = leader.getDisplayName() != null && !leader.getDisplayName().isBlank()
        ? leader.getDisplayName().trim()
        : leader.getLoginIdentifier();
    return new PrintReportLeaderSignatureView(leaderUserId, displayName, url);
  }

  /** Active-section leader first, earliest LEADER responsibility as fallback. */
  private UUID resolveLeaderUserId(MachineEntity machine) {
    var group = machine.getMachineGroup();
    var section = group != null && group.getSection() != null
        ? group.getSection()
        : group != null && group.getSectionId() != null
            ? sections.findById(group.getSectionId()).orElse(null)
            : null;
    if (section != null && section.isActive() && section.getLeaderUserId() != null) {
      return section.getLeaderUserId();
    }
    return responsibilities
        .findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machine.getId(), ResponsibilityLevel.LEADER)
        .map(MachineResponsibilityEntity::getUserId)
        .orElse(null);
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