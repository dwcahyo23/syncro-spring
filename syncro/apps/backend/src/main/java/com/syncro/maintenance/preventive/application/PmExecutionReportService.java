package com.syncro.maintenance.preventive.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.UserSignatureRepository;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PmExecutionReportHeaderView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PmExecutionReportItemView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PmExecutionReportSignatureView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PmExecutionReportSignaturesView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PmExecutionReportView;
import com.syncro.maintenance.preventive.application.PmExecutionService.ExecutionItemView;
import com.syncro.maintenance.preventive.application.PmExecutionService.ExecutionView;
import com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PM preventive print report assembly (story 19-6, FR-133). Composes the execution
 * header (machine/plant + WO period info + derived lifecycle status), the sequence-
 * ordered snapshotted checklist rows with results and NG photo URLs, and the
 * technician/SPV signature blocks into one aggregate DTO for the WYSIWYG
 * browser-print page. Mirrors the 14-3 {@code WorkorderPrintReportService} pattern:
 * JSON payload only, no server-side PDF/HTML; the company logo is a separate
 * {@code GET /api/v1/settings/logo} read.
 *
 * <p>Read gate: the 19-5 {@link PmExecutionService#get} posture is reused verbatim
 * (execution's technician / machine-scope readers / SUPER_ADMIN; unknown id 404,
 * out-of-scope 403, same load-then-gate ordering) — no second gate here.
 *
 * <p>{@code ng_photo_url} is free text (19-5 stores it as-is): it is echoed verbatim
 * only when URL-like (contains "://", starts with "//", or a slash whose first
 * segment contains a dot, e.g. photos.example.com/x.jpg); everything else is treated
 * as a storage object key and presigned — slash-less dotted keys like "photo.jpg"
 * included.
 * Presign failures (deleted Garage object) never break the report — the URL is
 * skipped as null (14-3 buildSignature precedent).
 */
@Service
public class PmExecutionReportService {

  private final PmExecutionService executions;
  private final PmWorkOrderRepository workOrders;
  private final MachineRepository machines;
  private final UserSignatureRepository signatures;
  private final AuthUserRepository users;
  private final ObjectStorageService objectStorage;

  public PmExecutionReportService(PmExecutionService executions, PmWorkOrderRepository workOrders,
      MachineRepository machines, UserSignatureRepository signatures, AuthUserRepository users,
      ObjectStorageService objectStorage) {
    this.executions = executions;
    this.workOrders = workOrders;
    this.machines = machines;
    this.signatures = signatures;
    this.users = users;
    this.objectStorage = objectStorage;
  }

  @Transactional(readOnly = true)
  public PmExecutionReportView get(AuthenticatedUser user, UUID id) {
    // 19-5 read posture reused verbatim: load-then-gate, 404/403 semantics included.
    var execution = executions.get(user, id);
    var workOrder = workOrders.findById(execution.pmWoId())
        .orElseThrow(PmWorkOrderService.PmWorkOrderNotFoundException::new);
    var machine = machines.findByIdWithPlantAndGroup(workOrder.getMachineId())
        .orElseThrow(PmWorkOrderService.MachineNotFoundException::new);

    return new PmExecutionReportView(
        buildHeader(execution, workOrder, machine),
        buildItems(execution.items()),
        buildSignatures(execution));
  }

  // -------------------------------------------------------------------------
  // Section builders
  // -------------------------------------------------------------------------

  private static PmExecutionReportHeaderView buildHeader(ExecutionView execution,
      PmWorkOrderEntity workOrder, MachineEntity machine) {
    return new PmExecutionReportHeaderView(
        execution.id(),
        execution.pmWoId(),
        machine.getId(),
        machine.getCode(),
        machine.getName(),
        machine.getPlant().getCode(),
        workOrder.getScheduledDate(),
        workOrder.getFrequencyCode(),
        workOrder.getFrequencyName(),
        workOrder.getTemplateRevision(),
        execution.startedAt(),
        execution.completedAt(),
        deriveStatus(execution),
        execution.hasNgItems(),
        execution.ngCount(),
        execution.findingWoId());
  }

  /**
   * Execution lifecycle status is derived, not stored (19-6 design note): NOT_STARTED
   * is impossible (an execution row exists once started), RUNNING = not completed,
   * COMPLETED = completed but not SPV-signed, VERIFIED = both.
   */
  private static String deriveStatus(ExecutionView execution) {
    if (execution.completedAt() == null) {
      return "RUNNING";
    }
    return execution.spvSignedAt() == null ? "COMPLETED" : "VERIFIED";
  }

  private List<PmExecutionReportItemView> buildItems(List<ExecutionItemView> items) {
    return items.stream()
        .map(i -> new PmExecutionReportItemView(i.sequence(), i.categoryName(), i.parameterText(),
            i.checkMethod(), i.inputType() != null ? i.inputType().name() : null,
            i.isCriticalFlag(), i.unit(), i.lsl(), i.nominal(), i.usl(), i.actualValue(),
            i.isOk(), i.isNg(), i.ngNotes(), ngPhotoUrl(i.ngPhotoUrl()), i.isBlocked(),
            i.blockedWoCode()))
        .toList();
  }

  /**
   * ng_photo_url is free text. Classification: EXTERNAL — echoed verbatim (trimmed) —
   * when the value contains "://", starts with "//" (protocol-relative, checked
   * before the leading-'/' strip), or has a slash whose first segment contains a dot
   * (domain-like, e.g. photos.example.com/x.jpg). Everything else is a storage
   * object key (one leading '/' stripped) and is presigned; slash-less keys are
   * presigned even when dotted ("photo.jpg"). Presign failure on a genuine key
   * skips the URL (null).
   */
  private String ngPhotoUrl(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    var value = raw.trim();
    if (value.contains("://") || value.startsWith("//")) {
      return value;
    }
    if (value.startsWith("/")) {
      value = value.substring(1);
    }
    var slash = value.indexOf('/');
    if (slash >= 0 && value.substring(0, slash).contains(".")) {
      return value;
    }
    return presign(value);
  }

  private PmExecutionReportSignaturesView buildSignatures(ExecutionView execution) {
    return new PmExecutionReportSignaturesView(
        buildSignature(execution.technicianId(), execution.technicianSignatureId(),
            execution.technicianSignedAt()),
        buildSignature(execution.spvVerifierId(), execution.spvSignatureId(),
            execution.spvSignedAt()));
  }

  /** One signer block; null when that side has not signed. */
  private PmExecutionReportSignatureView buildSignature(UUID userId, UUID signatureId,
      Instant signedAt) {
    if (signedAt == null) {
      return null;
    }
    // Presigning a deleted/missing Garage object can fail — the print page must not
    // break; the signature image is simply skipped (null URL).
    var url = signatureId == null ? null : signatures.findById(signatureId)
        .map(sig -> presign(sig.getObjectKey())).orElse(null);
    var displayName = userId == null ? null : users.findById(userId)
        .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank()
            ? u.getDisplayName().trim() : u.getLoginIdentifier())
        .orElse(null);
    return new PmExecutionReportSignatureView(userId, displayName, url, signedAt);
  }

  private String presign(String objectKey) {
    try {
      return objectStorage.presignGetUrl(objectKey);
    } catch (ObjectStorageException exception) {
      return null;
    }
  }
}
