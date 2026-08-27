package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code work_orders} row (AD-3/AD-4). The dual-source id is the VARCHAR PK
 * (external sheet_no for SYNCED, WO-YYMM-XXXXX for INTERNAL); machine/category/created_by
 * are plain UUID columns — no cross-aggregate JPA associations. Grown out of the 10-1
 * schema-only pass-through into a real persisted aggregate with the create/assign flow.
 */
@Entity
@Table(name = "work_orders")
public class WorkOrderEntity {

  @Id
  @Column(length = 50)
  private String id;

  @Column(nullable = false, length = 8)
  private String source;

  @Column(name = "parent_id", length = 50)
  private String parentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private WorkOrderStatus status;

  @Column(name = "category_id")
  private UUID categoryId;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "sync_version", nullable = false)
  private long syncVersion;

  @Column(name = "idempotency_key", length = 64)
  private String idempotencyKey;

  @Column(name = "assigned_technician_id")
  private UUID assignedTechnicianId;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "mttr_minutes")
  private Long mttrMinutes;

  @Column(name = "response_time_minutes")
  private Long responseTimeMinutes;

  @Column(name = "done_reason", length = 1000)
  private String doneReason;

  @Column(name = "preventive_schedule_id")
  private UUID preventiveScheduleId;

  // -------------------------------------------------------------------------
  // Report fields (10-6, FR-117/FR-118/FR-122)
  // -------------------------------------------------------------------------

  @Column(name = "report_chronological", columnDefinition = "text")
  private String reportChronological;

  @Column(name = "report_analyze", columnDefinition = "text")
  private String reportAnalyze;

  @Column(name = "report_corrective", columnDefinition = "text")
  private String reportCorrective;

  @Column(name = "report_preventive", columnDefinition = "text")
  private String reportPreventive;

  @Column(name = "cp_cp_lower", precision = 8, scale = 4)
  private BigDecimal cpCkLower;

  @Column(name = "cp_cp_upper", precision = 8, scale = 4)
  private BigDecimal cpCkUpper;

  @Column(name = "cpk", precision = 8, scale = 4)
  private BigDecimal cpk;

  @Column(name = "cpk_pdf_object_key", length = 255)
  private String cpkPdfObjectKey;

  @Column(name = "fmea_failure_type", length = 30)
  private String fmeaFailureType;

  @Column(name = "stop_time_reason", length = 30)
  private String stopTimeReason;

  @Column(name = "stop_time_detail", length = 500)
  private String stopTimeDetail;

  protected WorkOrderEntity() {
  }

  public WorkOrderEntity(String id, String source, String parentId, WorkOrderStatus status, UUID categoryId,
      UUID machineId, String description, long syncVersion, String idempotencyKey, UUID assignedTechnicianId,
      UUID createdBy, Instant createdAt, Instant updatedAt) {
    this(id, source, parentId, status, categoryId, machineId, description, syncVersion, idempotencyKey,
        assignedTechnicianId, createdBy, createdAt, updatedAt, null, null, null);
  }

  public WorkOrderEntity(String id, String source, String parentId, WorkOrderStatus status, UUID categoryId,
      UUID machineId, String description, long syncVersion, String idempotencyKey, UUID assignedTechnicianId,
      UUID createdBy, Instant createdAt, Instant updatedAt, Long mttrMinutes, Long responseTimeMinutes,
      String doneReason) {
    this.id = id;
    this.source = source;
    this.parentId = parentId;
    this.status = status;
    this.categoryId = categoryId;
    this.machineId = machineId;
    this.description = description;
    this.syncVersion = syncVersion;
    this.idempotencyKey = idempotencyKey;
    this.assignedTechnicianId = assignedTechnicianId;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.mttrMinutes = mttrMinutes;
    this.responseTimeMinutes = responseTimeMinutes;
    this.doneReason = doneReason;
  }

  public String getId() {
    return id;
  }

  public String getSource() {
    return source;
  }

  public String getParentId() {
    return parentId;
  }

  public WorkOrderStatus getStatus() {
    return status;
  }

  public UUID getCategoryId() {
    return categoryId;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public String getDescription() {
    return description;
  }

  public long getSyncVersion() {
    return syncVersion;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public UUID getAssignedTechnicianId() {
    return assignedTechnicianId;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /**
   * OPEN → ASSIGNED (FR-113): records the executing technician and bumps the timestamp.
   */
  public void assign(UUID assignedTechnicianId, Instant updatedAt) {
    this.assignedTechnicianId = assignedTechnicianId;
    transitionTo(WorkOrderStatus.ASSIGNED, updatedAt);
  }

  /** Applies a status transition (AD-4/10.3); validity is owned by the state machine. */
  public void transitionTo(WorkOrderStatus status, Instant updatedAt) {
    this.status = status;
    this.updatedAt = updatedAt;
  }

  public Long getMttrMinutes() {
    return mttrMinutes;
  }

  public void setMttrMinutes(Long mttrMinutes) {
    this.mttrMinutes = mttrMinutes;
  }

  public Long getResponseTimeMinutes() {
    return responseTimeMinutes;
  }

  public void setResponseTimeMinutes(Long responseTimeMinutes) {
    this.responseTimeMinutes = responseTimeMinutes;
  }

  public String getDoneReason() {
    return doneReason;
  }

  public void setDoneReason(String doneReason) {
    this.doneReason = doneReason;
  }

  public UUID getPreventiveScheduleId() {
    return preventiveScheduleId;
  }

  public void setPreventiveScheduleId(UUID preventiveScheduleId) {
    this.preventiveScheduleId = preventiveScheduleId;
  }

  public String getReportChronological() {
    return reportChronological;
  }

  public String getReportAnalyze() {
    return reportAnalyze;
  }

  public String getReportCorrective() {
    return reportCorrective;
  }

  public String getReportPreventive() {
    return reportPreventive;
  }

  public BigDecimal getCpCkLower() {
    return cpCkLower;
  }

  public BigDecimal getCpCkUpper() {
    return cpCkUpper;
  }

  public BigDecimal getCpk() {
    return cpk;
  }

  public String getCpkPdfObjectKey() {
    return cpkPdfObjectKey;
  }

  public String getFmeaFailureType() {
    return fmeaFailureType;
  }

  public String getStopTimeReason() {
    return stopTimeReason;
  }

  public String getStopTimeDetail() {
    return stopTimeDetail;
  }

  /** Applies the four-section report narrative + optional CP/CPK/FMEA/stop-time fields. */
  public void applyReport(String reportChronological, String reportAnalyze, String reportCorrective,
      String reportPreventive, BigDecimal cpCkLower, BigDecimal cpCkUpper, BigDecimal cpk,
      String fmeaFailureType, String stopTimeReason, String stopTimeDetail) {
    this.reportChronological = reportChronological;
    this.reportAnalyze = reportAnalyze;
    this.reportCorrective = reportCorrective;
    this.reportPreventive = reportPreventive;
    this.cpCkLower = cpCkLower;
    this.cpCkUpper = cpCkUpper;
    this.cpk = cpk;
    this.fmeaFailureType = fmeaFailureType;
    this.stopTimeReason = stopTimeReason;
    this.stopTimeDetail = stopTimeDetail;
  }

  public void setCpkPdfObjectKey(String cpkPdfObjectKey) {
    this.cpkPdfObjectKey = cpkPdfObjectKey;
  }
}
