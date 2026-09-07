package com.syncro.compliance.infrastructure.db;

import com.syncro.compliance.domain.EcnStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted {@code equipment_change_notices} row (blueprint H4, story 15-2). A
 * reviewed machine change (improvement/retrofit); {@code executed_wo_id} is the
 * VARCHAR(50) work_orders reference of the workorder that implemented it.
 * Story 21-2 adds the optimistic-lock {@code version} (V17, V13/V15 precedent)
 * and the submit/close lifecycle helpers — the approval flow is
 * DRAFT→UNDER_REVIEW→APPROVED→EXECUTED→CLOSED, forward-only.
 */
@Entity
@Table(name = "equipment_change_notices")
public class EquipmentChangeNoticeEntity {

  @Id
  private UUID id;

  @Column(name = "ecn_number", nullable = false, length = 50)
  private String ecnNumber;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(nullable = false, length = 255)
  private String title;

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "change_type", length = 50)
  private String changeType;

  @Column(columnDefinition = "text")
  private String justification;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private EcnStatus status;

  @Column(name = "submitted_by")
  private UUID submittedBy;

  @Column(name = "reviewed_by")
  private UUID reviewedBy;

  @Column(name = "approved_by")
  private UUID approvedBy;

  @Column(name = "effective_date")
  private LocalDate effectiveDate;

  @Column(name = "executed_wo_id", length = 50)
  private String executedWoId;

  @Column(name = "sign_off_at")
  private Instant signOffAt;

  @Column(name = "before_photo_url", columnDefinition = "text")
  private String beforePhotoUrl;

  @Column(name = "after_photo_url", columnDefinition = "text")
  private String afterPhotoUrl;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Optimistic lock (V17): concurrent updates → 409 VERSION_CONFLICT (21-1 P6 precedent). */
  @Version
  @Column(nullable = false)
  private long version;

  protected EquipmentChangeNoticeEntity() {
  }

  public EquipmentChangeNoticeEntity(UUID id, String ecnNumber, UUID machineId, String title,
      String description, String changeType, String justification, EcnStatus status,
      UUID submittedBy, UUID reviewedBy, UUID approvedBy, LocalDate effectiveDate,
      String executedWoId, Instant signOffAt, String beforePhotoUrl, String afterPhotoUrl,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.ecnNumber = ecnNumber;
    this.machineId = machineId;
    this.title = title;
    this.description = description;
    this.changeType = changeType;
    this.justification = justification;
    this.status = status;
    this.submittedBy = submittedBy;
    this.reviewedBy = reviewedBy;
    this.approvedBy = approvedBy;
    this.effectiveDate = effectiveDate;
    this.executedWoId = executedWoId;
    this.signOffAt = signOffAt;
    this.beforePhotoUrl = beforePhotoUrl;
    this.afterPhotoUrl = afterPhotoUrl;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getEcnNumber() {
    return ecnNumber;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public String getChangeType() {
    return changeType;
  }

  public String getJustification() {
    return justification;
  }

  public EcnStatus getStatus() {
    return status;
  }

  public UUID getSubmittedBy() {
    return submittedBy;
  }

  public UUID getReviewedBy() {
    return reviewedBy;
  }

  public UUID getApprovedBy() {
    return approvedBy;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public String getExecutedWoId() {
    return executedWoId;
  }

  public Instant getSignOffAt() {
    return signOffAt;
  }

  public String getBeforePhotoUrl() {
    return beforePhotoUrl;
  }

  public String getAfterPhotoUrl() {
    return afterPhotoUrl;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }

  /** Submission (story 21-2): DRAFT→UNDER_REVIEW, stamps the submitting actor. */
  public void submit(UUID submittedBy, Instant updatedAt) {
    this.status = EcnStatus.UNDER_REVIEW;
    this.submittedBy = submittedBy;
    this.updatedAt = updatedAt;
  }

  /** Review/approval transition (H4): stamps reviewer/approver, date, sign-off. */
  public void review(EcnStatus status, UUID reviewedBy, UUID approvedBy, LocalDate effectiveDate,
      Instant signOffAt, Instant updatedAt) {
    this.status = status;
    this.reviewedBy = reviewedBy;
    this.approvedBy = approvedBy;
    this.effectiveDate = effectiveDate;
    this.signOffAt = signOffAt;
    this.updatedAt = updatedAt;
  }

  /** Execution closure (H4): links the implementing workorder and photos. */
  public void execute(String executedWoId, String afterPhotoUrl, Instant updatedAt) {
    this.executedWoId = executedWoId;
    this.afterPhotoUrl = afterPhotoUrl;
    this.status = EcnStatus.EXECUTED;
    this.updatedAt = updatedAt;
  }

  /** Final sign-off (story 21-2): EXECUTED→CLOSED. */
  public void close(Instant updatedAt) {
    this.status = EcnStatus.CLOSED;
    this.updatedAt = updatedAt;
  }

  /**
   * Partial update (story 21-2 PATCH, 21-1 precedent): a null argument keeps the
   * stored value. {@code ecnNumber} and {@code machineId} are immutable after
   * creation; lifecycle fields move through the transition helpers, not here.
   */
  public void updateContent(String title, String description, String changeType,
      String justification, String beforePhotoUrl, Instant updatedAt) {
    if (title != null) {
      this.title = title;
    }
    if (description != null) {
      this.description = description;
    }
    if (changeType != null) {
      this.changeType = changeType;
    }
    if (justification != null) {
      this.justification = justification;
    }
    if (beforePhotoUrl != null) {
      this.beforePhotoUrl = beforePhotoUrl;
    }
    this.updatedAt = updatedAt;
  }
}
