package com.syncro.compliance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted {@code calibration_records} row (blueprint H3, story 15-2). One performed
 * calibration of an instrument (append-only history); the certificate URL points at
 * the Garage object. No mutation methods — records are never edited.
 */
@Entity
@Table(name = "calibration_records")
public class CalibrationRecordEntity {

  @Id
  private UUID id;

  @Column(name = "instrument_id", nullable = false)
  private UUID instrumentId;

  @Column(name = "calibration_date", nullable = false)
  private LocalDate calibrationDate;

  @Column(name = "next_calibration_date", nullable = false)
  private LocalDate nextCalibrationDate;

  @Column(name = "calibration_body", length = 255)
  private String calibrationBody;

  @Column(name = "certificate_number", length = 255)
  private String certificateNumber;

  @Column(name = "certificate_url", columnDefinition = "text")
  private String certificateUrl;

  @Column(length = 255)
  private String result;

  @Column(columnDefinition = "text")
  private String notes;

  @Column(name = "recorded_by")
  private UUID recordedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected CalibrationRecordEntity() {
  }

  public CalibrationRecordEntity(UUID id, UUID instrumentId, LocalDate calibrationDate,
      LocalDate nextCalibrationDate, String calibrationBody, String certificateNumber,
      String certificateUrl, String result, String notes, UUID recordedBy, Instant createdAt) {
    this.id = id;
    this.instrumentId = instrumentId;
    this.calibrationDate = calibrationDate;
    this.nextCalibrationDate = nextCalibrationDate;
    this.calibrationBody = calibrationBody;
    this.certificateNumber = certificateNumber;
    this.certificateUrl = certificateUrl;
    this.result = result;
    this.notes = notes;
    this.recordedBy = recordedBy;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getInstrumentId() {
    return instrumentId;
  }

  public LocalDate getCalibrationDate() {
    return calibrationDate;
  }

  public LocalDate getNextCalibrationDate() {
    return nextCalibrationDate;
  }

  public String getCalibrationBody() {
    return calibrationBody;
  }

  public String getCertificateNumber() {
    return certificateNumber;
  }

  public String getCertificateUrl() {
    return certificateUrl;
  }

  public String getResult() {
    return result;
  }

  public String getNotes() {
    return notes;
  }

  public UUID getRecordedBy() {
    return recordedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
