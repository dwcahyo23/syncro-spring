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
 * Persisted {@code pm_checklist_items} row (blueprint F4, story 15-2). One assessment
 * parameter of a checksheet revision: MEASUREMENT items carry unit + lsl/nominal/usl
 * bounds, OK_NG items carry the boolean outcome. The calibration instrument link is
 * a plain UUID column (AD-3) with the FK added in V1 after calibration_instruments.
 */
@Entity
@Table(name = "pm_checklist_items")
public class PmChecklistItemEntity {

  @Id
  private UUID id;

  @Column(name = "checksheet_id", nullable = false)
  private UUID checksheetId;

  @Column(name = "category_id")
  private UUID categoryId;

  @Column(nullable = false)
  private int sequence;

  @Column(name = "parameter_text", nullable = false, length = 500)
  private String parameterText;

  @Column(name = "check_method", length = 200)
  private String checkMethod;

  @Enumerated(EnumType.STRING)
  @Column(name = "input_type", nullable = false, length = 16)
  private PmItemInputType inputType;

  @Column(length = 50)
  private String unit;

  @Column(precision = 20, scale = 6)
  private BigDecimal lsl;

  @Column(precision = 20, scale = 6)
  private BigDecimal nominal;

  @Column(precision = 20, scale = 6)
  private BigDecimal usl;

  @Column(name = "is_critical_flag", nullable = false)
  private boolean criticalFlag;

  @Column(name = "reference_document", length = 255)
  private String referenceDocument;

  @Column(name = "calibration_instrument_id")
  private UUID calibrationInstrumentId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PmChecklistItemEntity() {
  }

  public PmChecklistItemEntity(UUID id, UUID checksheetId, UUID categoryId, int sequence,
      String parameterText, String checkMethod, PmItemInputType inputType, String unit,
      BigDecimal lsl, BigDecimal nominal, BigDecimal usl, boolean criticalFlag,
      String referenceDocument, UUID calibrationInstrumentId, Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.checksheetId = checksheetId;
    this.categoryId = categoryId;
    this.sequence = sequence;
    this.parameterText = parameterText;
    this.checkMethod = checkMethod;
    this.inputType = inputType;
    this.unit = unit;
    this.lsl = lsl;
    this.nominal = nominal;
    this.usl = usl;
    this.criticalFlag = criticalFlag;
    this.referenceDocument = referenceDocument;
    this.calibrationInstrumentId = calibrationInstrumentId;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getChecksheetId() {
    return checksheetId;
  }

  public UUID getCategoryId() {
    return categoryId;
  }

  public int getSequence() {
    return sequence;
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

  public boolean isCriticalFlag() {
    return criticalFlag;
  }

  public String getReferenceDocument() {
    return referenceDocument;
  }

  public UUID getCalibrationInstrumentId() {
    return calibrationInstrumentId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
