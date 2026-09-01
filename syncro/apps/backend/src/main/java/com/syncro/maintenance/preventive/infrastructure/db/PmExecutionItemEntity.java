package com.syncro.maintenance.preventive.infrastructure.db;

import com.syncro.maintenance.preventive.domain.PmItemInputType;
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
 * Persisted {@code pm_execution_items} row (blueprint F8, story 15-2). Filled result
 * of one checklist item during a PM execution. Row snapshots the item's
 * category/parameter/method/input-type at execution time (the checksheet may be
 * revised later). NG handling: {@code is_ng} + notes/photo, optional
 * {@code is_blocked} with the {@code blocking_wo_id} VARCHAR(50) work_orders
 * reference.
 */
@Entity
@Table(name = "pm_execution_items")
public class PmExecutionItemEntity {

  @Id
  private UUID id;

  @Column(name = "execution_id", nullable = false)
  private UUID executionId;

  @Column(name = "checklist_item_id")
  private UUID checklistItemId;

  @Column(nullable = false)
  private int sequence;

  @Column(name = "category_name", length = 200)
  private String categoryName;

  @Column(name = "parameter_text", length = 500)
  private String parameterText;

  @Column(name = "check_method", length = 200)
  private String checkMethod;

  @Enumerated(EnumType.STRING)
  @Column(name = "input_type", length = 16)
  private PmItemInputType inputType;

  @Column(name = "is_critical_flag", nullable = false)
  private boolean criticalFlag;

  @Column(length = 50)
  private String unit;

  @Column(precision = 20, scale = 6)
  private BigDecimal lsl;

  @Column(precision = 20, scale = 6)
  private BigDecimal nominal;

  @Column(precision = 20, scale = 6)
  private BigDecimal usl;

  @Column(name = "actual_value", precision = 20, scale = 6)
  private BigDecimal actualValue;

  @Column(name = "is_ok")
  private Boolean ok;

  @Column(name = "is_ng", nullable = false)
  private boolean ng;

  @Column(name = "ng_notes", columnDefinition = "text")
  private String ngNotes;

  @Column(name = "ng_photo_url", columnDefinition = "text")
  private String ngPhotoUrl;

  @Column(name = "is_blocked", nullable = false)
  private boolean blocked;

  @Column(name = "blocked_wo_code", length = 50)
  private String blockedWoCode;

  @Column(name = "blocking_wo_id", length = 50)
  private String blockingWoId;

  @Column(name = "ng_resolved_at")
  private Instant ngResolvedAt;

  @Column(name = "ng_resolution_notes", columnDefinition = "text")
  private String ngResolutionNotes;

  @Column(name = "spv_verified_at")
  private Instant spvVerifiedAt;

  @Column(name = "spv_verifier_id")
  private UUID spvVerifierId;

  @Column(name = "spv_signature_id")
  private UUID spvSignatureId;

  @Column(name = "filled_at")
  private Instant filledAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PmExecutionItemEntity() {
  }

  public PmExecutionItemEntity(UUID id, UUID executionId, UUID checklistItemId, int sequence,
      String categoryName, String parameterText, String checkMethod, PmItemInputType inputType,
      boolean criticalFlag, String unit, BigDecimal lsl, BigDecimal nominal, BigDecimal usl,
      BigDecimal actualValue, Boolean ok, boolean ng, String ngNotes, String ngPhotoUrl,
      boolean blocked, String blockedWoCode, String blockingWoId, Instant ngResolvedAt,
      String ngResolutionNotes, Instant spvVerifiedAt, UUID spvVerifierId, UUID spvSignatureId,
      Instant filledAt, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.executionId = executionId;
    this.checklistItemId = checklistItemId;
    this.sequence = sequence;
    this.categoryName = categoryName;
    this.parameterText = parameterText;
    this.checkMethod = checkMethod;
    this.inputType = inputType;
    this.criticalFlag = criticalFlag;
    this.unit = unit;
    this.lsl = lsl;
    this.nominal = nominal;
    this.usl = usl;
    this.actualValue = actualValue;
    this.ok = ok;
    this.ng = ng;
    this.ngNotes = ngNotes;
    this.ngPhotoUrl = ngPhotoUrl;
    this.blocked = blocked;
    this.blockedWoCode = blockedWoCode;
    this.blockingWoId = blockingWoId;
    this.ngResolvedAt = ngResolvedAt;
    this.ngResolutionNotes = ngResolutionNotes;
    this.spvVerifiedAt = spvVerifiedAt;
    this.spvVerifierId = spvVerifierId;
    this.spvSignatureId = spvSignatureId;
    this.filledAt = filledAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getExecutionId() {
    return executionId;
  }

  public UUID getChecklistItemId() {
    return checklistItemId;
  }

  public int getSequence() {
    return sequence;
  }

  public String getCategoryName() {
    return categoryName;
  }

  public String getParameterText() {
    return parameterText;
  }

  public String getCheckMethod() {
    return checkMethod;
  }

  public PmItemInputType getInputType() {
    return inputType;
  }

  public boolean isCriticalFlag() {
    return criticalFlag;
  }

  public String getUnit() {
    return unit;
  }

  public BigDecimal getLsl() {
    return lsl;
  }

  public BigDecimal getNominal() {
    return nominal;
  }

  public BigDecimal getUsl() {
    return usl;
  }

  public BigDecimal getActualValue() {
    return actualValue;
  }

  public Boolean getOk() {
    return ok;
  }

  public boolean isNg() {
    return ng;
  }

  public String getNgNotes() {
    return ngNotes;
  }

  public String getNgPhotoUrl() {
    return ngPhotoUrl;
  }

  public boolean isBlocked() {
    return blocked;
  }

  public String getBlockedWoCode() {
    return blockedWoCode;
  }

  public String getBlockingWoId() {
    return blockingWoId;
  }

  public Instant getNgResolvedAt() {
    return ngResolvedAt;
  }

  public String getNgResolutionNotes() {
    return ngResolutionNotes;
  }

  public Instant getSpvVerifiedAt() {
    return spvVerifiedAt;
  }

  public UUID getSpvVerifierId() {
    return spvVerifierId;
  }

  public UUID getSpvSignatureId() {
    return spvSignatureId;
  }

  public Instant getFilledAt() {
    return filledAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Fills the result (F8): outcome value(s), NG/blocked flags, fill timestamp. */
  public void fill(BigDecimal actualValue, Boolean ok, boolean ng, String ngNotes,
      boolean blocked, String blockedWoCode, String blockingWoId, Instant filledAt,
      Instant updatedAt) {
    this.actualValue = actualValue;
    this.ok = ok;
    this.ng = ng;
    this.ngNotes = ngNotes;
    this.blocked = blocked;
    this.blockedWoCode = blockedWoCode;
    this.blockingWoId = blockingWoId;
    this.filledAt = filledAt;
    this.updatedAt = updatedAt;
  }
}
