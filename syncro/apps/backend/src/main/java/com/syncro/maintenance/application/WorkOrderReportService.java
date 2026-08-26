package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.config.WorkorderEvidenceProperties;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.domain.workorder.FmeaFailureType;
import com.syncro.maintenance.domain.workorder.StopTimeReason;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workorder report & CP/CPK capability sheet (FR-117/FR-118/FR-122, story 10-6).
 * The report is a single aggregate of nullable columns on the workorder row — a local
 * operational field (AD-3 "preserved") that never touches status or sync_version, so no
 * row lock is needed (same rationale as 10-5 attachments). The CP/CPK PDF follows the
 * 10-5 object-key-only Garage pattern with a fixed {@code {uuid}.pdf} suffix and
 * replace-deletes-old semantics.
 *
 * <p>Mutation gate mirrors {@code WorkOrderService.requireSessionAccess} exactly
 * (in-scope leader OR assigned executor, both sources) — duplicated inline on purpose,
 * the 10.4/10.5 gates stay untouched. Reads are any-authenticated (workorder read
 * posture).
 */
@Service
public class WorkOrderReportService {

  /** CP/CPK PDFs are stored under a per-workorder prefix inside the shared GARAGE_BUCKET. */
  private static final String CPK_PREFIX = "workorders/";
  private static final String CPK_SUFFIX = "/cpk/";
  private static final String PDF_CONTENT_TYPE = "application/pdf";

  private final WorkOrderRepository workOrders;
  private final MachineRepository machines;
  private final AuditLogWriter auditLog;
  private final Clock clock;
  private final ObjectStorageService objectStorage;
  private final OperationalScopeService scopes;
  private final WorkorderEvidenceProperties properties;

  public WorkOrderReportService(WorkOrderRepository workOrders, MachineRepository machines,
      AuditLogWriter auditLog, Clock clock, ObjectStorageService objectStorage,
      OperationalScopeService scopes, WorkorderEvidenceProperties properties) {
    this.workOrders = workOrders;
    this.machines = machines;
    this.auditLog = auditLog;
    this.clock = clock;
    this.objectStorage = objectStorage;
    this.scopes = scopes;
    this.properties = properties;
  }

  /** Writes the report narrative + optional CP/CPK/FMEA/stop-time (PUT /{id}/report). */
  @Transactional
  public WorkOrderReportView saveReport(AuthenticatedUser user, String workOrderId, SaveReportCommand command) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireAccess(user, entity, machine);
    validateReport(command);

    var previous = reportValues(entity);
    entity.applyReport(normalize(command.reportChronological()), normalize(command.reportAnalyze()),
        normalize(command.reportCorrective()), normalize(command.reportPreventive()),
        command.cpCkLower(), command.cpCkUpper(), command.cpk(),
        command.fmeaFailureType() != null ? command.fmeaFailureType().name() : null,
        command.stopTimeReason() != null ? command.stopTimeReason().name() : null,
        normalize(command.stopTimeDetail()));
    var saved = workOrders.saveAndFlush(entity);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER,
        auditEntityId(workOrderId), workOrderId, machine.getPlant().getId(), previous, reportValues(saved), null));
    return toView(saved);
  }

  /** Reads the report for any authenticated user (GET /{id}/report); fresh short-TTL URL. */
  @Transactional(readOnly = true)
  public WorkOrderReportView getReport(String workOrderId) {
    var entity = loadWorkOrder(workOrderId);
    return toView(entity);
  }

  /**
   * Uploads (or replaces) the CP/CPK PDF (PUT /{id}/report/cpk). PDF-only, size-capped
   * by {@code WorkorderEvidenceProperties.maxBytes()}; replace deletes the old Garage
   * object before storing the new one (8-4/10-5 pattern). A storage failure after the
   * delete leaves the old object orphaned — retry is an idempotent no-op (delete is
   * idempotent).
   */
  @Transactional
  public WorkOrderReportView uploadCpkPdf(AuthenticatedUser user, String workOrderId, CpkPdfCommand command) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireAccess(user, entity, machine);
    validateCpkPdf(command);

    var previousKey = entity.getCpkPdfObjectKey();
    if (previousKey != null) {
      deleteObject(previousKey);
    }
    var newKey = buildCpkKey(workOrderId);
    storeObject(newKey, command.data(), PDF_CONTENT_TYPE);
    entity.setCpkPdfObjectKey(newKey);
    var saved = workOrders.saveAndFlush(entity);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER,
        auditEntityId(workOrderId), workOrderId, machine.getPlant().getId(),
        cpkKeyValues(previousKey), cpkKeyValues(newKey), null));
    return toView(saved);
  }

  /** Deletes the CP/CPK PDF (DELETE /{id}/report/cpk): object first, then clears the key. */
  @Transactional
  public WorkOrderReportView deleteCpkPdf(AuthenticatedUser user, String workOrderId) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireAccess(user, entity, machine);

    var previousKey = entity.getCpkPdfObjectKey();
    if (previousKey != null) {
      deleteObject(previousKey);
    }
    entity.setCpkPdfObjectKey(null);
    var saved = workOrders.saveAndFlush(entity);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER,
        auditEntityId(workOrderId), workOrderId, machine.getPlant().getId(),
        cpkKeyValues(previousKey), cpkKeyValues(null), null));
    return toView(saved);
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

  private void validateReport(SaveReportCommand command) {
    var fieldErrors = new LinkedHashMap<String, String>();
    validateNarrative(fieldErrors, "reportChronological", command.reportChronological());
    validateNarrative(fieldErrors, "reportAnalyze", command.reportAnalyze());
    validateNarrative(fieldErrors, "reportCorrective", command.reportCorrective());
    validateNarrative(fieldErrors, "reportPreventive", command.reportPreventive());
    validateNonNegative(fieldErrors, "cpCkLower", command.cpCkLower());
    validateNonNegative(fieldErrors, "cpCkUpper", command.cpCkUpper());
    validateNonNegative(fieldErrors, "cpk", command.cpk());
    var detail = command.stopTimeDetail() == null ? "" : command.stopTimeDetail().trim();
    if (detail.length() > 500) {
      fieldErrors.put("stopTimeDetail", "Stop-time detail must be at most 500 characters.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new WorkOrderReportValidationException(fieldErrors);
    }
  }

  private static void validateNarrative(Map<String, String> fieldErrors, String field, String value) {
    if (value != null && value.trim().length() > 4000) {
      fieldErrors.put(field, "Report section must be at most 4000 characters.");
    }
  }

  private static void validateNonNegative(Map<String, String> fieldErrors, String field, BigDecimal value) {
    if (value == null) {
      return;
    }
    if (value.signum() < 0) {
      fieldErrors.put(field, "Capability index must not be negative.");
    } else if (value.precision() - value.scale() > 4 || value.scale() > 4) {
      // NUMERIC(8,4) bound: max 9999.9999, 4 fractional digits.
      fieldErrors.put(field, "Capability index exceeds the allowed range (9999.9999 max, 4 decimal places).");
    }
  }

  private void validateCpkPdf(CpkPdfCommand command) {
    var fieldErrors = new LinkedHashMap<String, String>();
    var filename = command.filename() == null ? "" : command.filename().trim();
    if (filename.isEmpty()) {
      fieldErrors.put("filename", "Filename must not be blank.");
    } else if (filename.length() > 255) {
      fieldErrors.put("filename", "Filename must be at most 255 characters.");
    }
    var contentType = command.contentType() == null ? "" : command.contentType().trim();
    if (contentType.isEmpty()) {
      fieldErrors.put("contentType", "Content type must not be blank.");
    } else if (!PDF_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
      // The capability sheet is PDF-only (unlike evidence, which accepts any IANA type).
      fieldErrors.put("contentType", "Content type must be application/pdf.");
    }
    if (command.data() == null || command.data().length == 0) {
      fieldErrors.put("data", "CP/CPK PDF file must not be empty.");
    } else if (command.data().length > properties.maxBytes()) {
      fieldErrors.put("data", "CP/CPK PDF file exceeds the maximum allowed size.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new WorkOrderReportValidationException(fieldErrors);
    }
  }

  // -------------------------------------------------------------------------
  // Storage & helpers
  // -------------------------------------------------------------------------

  private WorkOrderEntity loadWorkOrder(String workOrderId) {
    return workOrders.findById(workOrderId).orElseThrow(ReportWorkOrderNotFoundException::new);
  }

  private MachineEntity loadMachine(WorkOrderEntity workOrder) {
    return machines.findByIdWithPlantAndGroup(workOrder.getMachineId())
        .orElseThrow(ReportWorkOrderMachineNotFoundException::new);
  }

  /**
   * Same gate as {@code WorkOrderService.requireSessionAccess} (10.4) and
   * {@code WorkOrderEvidenceService.requireAccess} (10.5): in-scope leader OR assigned
   * executor, both sources allowed. Report/CPK are local operational fields (AD-3
   * "preserved"). Duplicated inline — the existing gates stay untouched.
   */
  private void requireAccess(AuthenticatedUser user, WorkOrderEntity entity, MachineEntity machine) {
    if (isInScopeLeader(user, machine) || isExecutor(user, entity)) {
      return;
    }
    throw new ReportForbiddenException();
  }

  private boolean isExecutor(AuthenticatedUser user, WorkOrderEntity entity) {
    return entity.getAssignedTechnicianId() != null
        && UUID.fromString(user.id()).equals(entity.getAssignedTechnicianId())
        && (user.applicationRole() == ApplicationRole.TECHNICIAN
            || user.applicationRole() == ApplicationRole.STAFF_MAINTENANCE);
  }

  private boolean isInScopeLeader(AuthenticatedUser user, MachineEntity machine) {
    switch (user.applicationRole()) {
      case SUPER_ADMIN -> {
        return true;
      }
      case SECTION_LEADER -> {
        return groupInScope(scopes.derive(user), machine);
      }
      case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
        var scope = scopes.derive(user);
        var plantInScope = scope.plantIds() != null && scope.plantIds().contains(machine.getPlant().getId());
        return groupInScope(scope, machine) || plantInScope;
      }
      default -> {
        return false;
      }
    }
  }

  private boolean groupInScope(OperationalScope scope, MachineEntity machine) {
    return scope.machineGroupIds().contains(machine.getMachineGroup().getId())
        || scope.activeTeamIds().contains(machine.getMachineGroup().getId());
  }

  /** Fixed {@code {uuid}.pdf} suffix — every replacement writes a fresh object URL. */
  private String buildCpkKey(String workOrderId) {
    return CPK_PREFIX + workOrderId + CPK_SUFFIX + UUID.randomUUID() + ".pdf";
  }

  /** Blank → null so a cleared field persists as NULL, not an empty string. */
  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private Map<String, Object> reportValues(WorkOrderEntity entity) {
    var values = new HashMap<String, Object>();
    values.put("reportChronological", entity.getReportChronological());
    values.put("reportAnalyze", entity.getReportAnalyze());
    values.put("reportCorrective", entity.getReportCorrective());
    values.put("reportPreventive", entity.getReportPreventive());
    values.put("cpCkLower", entity.getCpCkLower());
    values.put("cpCkUpper", entity.getCpCkUpper());
    values.put("cpk", entity.getCpk());
    values.put("cpkPdfObjectKey", entity.getCpkPdfObjectKey());
    values.put("fmeaFailureType", entity.getFmeaFailureType());
    values.put("stopTimeReason", entity.getStopTimeReason());
    values.put("stopTimeDetail", entity.getStopTimeDetail());
    return values;
  }

  /** Workorder ids are VARCHAR PKs; audit needs a stable UUID per id for correlation. */
  private UUID auditEntityId(String workOrderId) {
    return UUID.nameUUIDFromBytes(workOrderId.getBytes(StandardCharsets.UTF_8));
  }

  /** {@code Map.of} rejects null values, so build the key map safely (prev/new may be null). */
  private static Map<String, Object> cpkKeyValues(String key) {
    var values = new HashMap<String, Object>();
    values.put("cpkPdfObjectKey", key);
    return values;
  }

  private WorkOrderReportView toView(WorkOrderEntity entity) {
    var key = entity.getCpkPdfObjectKey();
    return new WorkOrderReportView(entity.getId(), entity.getReportChronological(), entity.getReportAnalyze(),
        entity.getReportCorrective(), entity.getReportPreventive(), entity.getCpCkLower(), entity.getCpCkUpper(),
        entity.getCpk(), key != null ? presignedGetUrl(key) : null, entity.getFmeaFailureType(),
        entity.getStopTimeReason(), entity.getStopTimeDetail());
  }

  private String presignedGetUrl(String key) {
    try {
      return objectStorage.presignGetUrl(key);
    } catch (ObjectStorageException exception) {
      throw new StorageException(exception);
    }
  }

  private void deleteObject(String key) {
    try {
      objectStorage.delete(key);
    } catch (ObjectStorageException exception) {
      throw new StorageException(exception);
    }
  }

  private void storeObject(String key, byte[] data, String contentType) {
    try {
      objectStorage.store(key, data, contentType);
    } catch (ObjectStorageException exception) {
      throw new StorageException(exception);
    }
  }

  public record SaveReportCommand(String reportChronological, String reportAnalyze, String reportCorrective,
      String reportPreventive, BigDecimal cpCkLower, BigDecimal cpCkUpper, BigDecimal cpk,
      FmeaFailureType fmeaFailureType, StopTimeReason stopTimeReason, String stopTimeDetail) {
  }

  public record CpkPdfCommand(String filename, String contentType, byte[] data) {
  }

  public record WorkOrderReportView(String workOrderId, String reportChronological, String reportAnalyze,
      String reportCorrective, String reportPreventive, BigDecimal cpCkLower, BigDecimal cpCkUpper, BigDecimal cpk,
      String cpkPdfPresignedUrl, String fmeaFailureType, String stopTimeReason, String stopTimeDetail) {
  }

  public static class WorkOrderReportValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public WorkOrderReportValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new HashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }

  public static class ReportForbiddenException extends RuntimeException {
  }

  public static class ReportWorkOrderNotFoundException extends RuntimeException {
  }

  public static class ReportWorkOrderMachineNotFoundException extends RuntimeException {
  }

  /** Object-storage failure; wraps the SDK cause but never leaks it to callers. */
  public static class StorageException extends RuntimeException {
    public StorageException(Throwable cause) {
      super(cause);
    }
  }
}
