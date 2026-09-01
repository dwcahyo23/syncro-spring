package com.syncro.maintenance.preventive.infrastructure.db;

import com.syncro.maintenance.preventive.domain.PmScheduleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted {@code pm_schedules} row (blueprint F5, story 15-2). The yearly plan for
 * one (plant, machine, checksheet, year): approval chain SPV → production with
 * timestamps, plus the import {@code warnings} JSONB. Frequency code/name are
 * denormalized copies of the referenced {@code pm_frequencies} row.
 */
@Entity
@Table(name = "pm_schedules")
public class PmScheduleEntity {

  @Id
  private UUID id;

  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(name = "checksheet_id", nullable = false)
  private UUID checksheetId;

  @Column(name = "checksheet_revision_no", nullable = false)
  private int checksheetRevisionNo;

  @Column(name = "frequency_id", nullable = false)
  private UUID frequencyId;

  @Column(name = "frequency_code", nullable = false, length = 50)
  private String frequencyCode;

  @Column(name = "frequency_name", nullable = false, length = 200)
  private String frequencyName;

  @Column(nullable = false)
  private int year;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 28)
  private PmScheduleStatus status;

  @Column(name = "submitted_by")
  private UUID submittedBy;

  @Column(name = "submitted_at")
  private Instant submittedAt;

  @Column(name = "approved_by_spv")
  private UUID approvedBySpv;

  @Column(name = "approved_at_spv")
  private Instant approvedAtSpv;

  @Column(name = "approved_by_prod")
  private UUID approvedByProd;

  @Column(name = "approved_at_prod")
  private Instant approvedAtProd;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> warnings;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PmScheduleEntity() {
  }

  public PmScheduleEntity(UUID id, UUID plantId, UUID machineId, UUID checksheetId,
      int checksheetRevisionNo, UUID frequencyId, String frequencyCode, String frequencyName,
      int year, PmScheduleStatus status, UUID submittedBy, Instant submittedAt,
      UUID approvedBySpv, Instant approvedAtSpv, UUID approvedByProd, Instant approvedAtProd,
      Map<String, Object> warnings, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plantId = plantId;
    this.machineId = machineId;
    this.checksheetId = checksheetId;
    this.checksheetRevisionNo = checksheetRevisionNo;
    this.frequencyId = frequencyId;
    this.frequencyCode = frequencyCode;
    this.frequencyName = frequencyName;
    this.year = year;
    this.status = status;
    this.submittedBy = submittedBy;
    this.submittedAt = submittedAt;
    this.approvedBySpv = approvedBySpv;
    this.approvedAtSpv = approvedAtSpv;
    this.approvedByProd = approvedByProd;
    this.approvedAtProd = approvedAtProd;
    this.warnings = warnings;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPlantId() {
    return plantId;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public UUID getChecksheetId() {
    return checksheetId;
  }

  public int getChecksheetRevisionNo() {
    return checksheetRevisionNo;
  }

  public UUID getFrequencyId() {
    return frequencyId;
  }

  public String getFrequencyCode() {
    return frequencyCode;
  }

  public String getFrequencyName() {
    return frequencyName;
  }

  public int getYear() {
    return year;
  }

  public PmScheduleStatus getStatus() {
    return status;
  }

  public UUID getSubmittedBy() {
    return submittedBy;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }

  public UUID getApprovedBySpv() {
    return approvedBySpv;
  }

  public Instant getApprovedAtSpv() {
    return approvedAtSpv;
  }

  public UUID getApprovedByProd() {
    return approvedByProd;
  }

  public Instant getApprovedAtProd() {
    return approvedAtProd;
  }

  public Map<String, Object> getWarnings() {
    return warnings;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** SPV approval step (F5): stamps the approver and their timestamp. */
  public void approveBySpv(UUID approvedBySpv, Instant approvedAtSpv, Instant updatedAt) {
    this.approvedBySpv = approvedBySpv;
    this.approvedAtSpv = approvedAtSpv;
    this.updatedAt = updatedAt;
  }

  /** Production approval step (F5): stamps the approver and their timestamp. */
  public void approveByProd(UUID approvedByProd, Instant approvedAtProd, Instant updatedAt) {
    this.approvedByProd = approvedByProd;
    this.approvedAtProd = approvedAtProd;
    this.updatedAt = updatedAt;
  }

  /** Status transition with the actor (F5 submit/approve/activate paths). */
  public void transitionTo(PmScheduleStatus status, Instant updatedAt) {
    this.status = status;
    this.updatedAt = updatedAt;
  }
}
