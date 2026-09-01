package com.syncro.compliance.infrastructure.db;

import com.syncro.compliance.domain.CalibrationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted {@code calibration_instruments} row (blueprint H3, story 15-2). A
 * measurement instrument with its calibration cadence; {@code instrument_code} is
 * unique. PM checklist items reference it via the plain
 * {@code pm_checklist_items.calibration_instrument_id} column.
 */
@Entity
@Table(name = "calibration_instruments")
public class CalibrationInstrumentEntity {

  @Id
  private UUID id;

  @Column(name = "instrument_code", nullable = false, length = 64)
  private String instrumentCode;

  @Column(nullable = false, length = 255)
  private String name;

  @Column(length = 255)
  private String model;

  @Column(name = "serial_number", length = 255)
  private String serialNumber;

  @Column(length = 255)
  private String location;

  @Column(name = "calibration_frequency_days", nullable = false)
  private int calibrationFrequencyDays;

  @Column(name = "last_calibration_date")
  private LocalDate lastCalibrationDate;

  @Column(name = "next_calibration_date", nullable = false)
  private LocalDate nextCalibrationDate;

  @Column(name = "calibration_body", length = 255)
  private String calibrationBody;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private CalibrationStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected CalibrationInstrumentEntity() {
  }

  public CalibrationInstrumentEntity(UUID id, String instrumentCode, String name, String model,
      String serialNumber, String location, int calibrationFrequencyDays,
      LocalDate lastCalibrationDate, LocalDate nextCalibrationDate, String calibrationBody,
      CalibrationStatus status, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.instrumentCode = instrumentCode;
    this.name = name;
    this.model = model;
    this.serialNumber = serialNumber;
    this.location = location;
    this.calibrationFrequencyDays = calibrationFrequencyDays;
    this.lastCalibrationDate = lastCalibrationDate;
    this.nextCalibrationDate = nextCalibrationDate;
    this.calibrationBody = calibrationBody;
    this.status = status;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getInstrumentCode() {
    return instrumentCode;
  }

  public String getName() {
    return name;
  }

  public String getModel() {
    return model;
  }

  public String getSerialNumber() {
    return serialNumber;
  }

  public String getLocation() {
    return location;
  }

  public int getCalibrationFrequencyDays() {
    return calibrationFrequencyDays;
  }

  public LocalDate getLastCalibrationDate() {
    return lastCalibrationDate;
  }

  public LocalDate getNextCalibrationDate() {
    return nextCalibrationDate;
  }

  public String getCalibrationBody() {
    return calibrationBody;
  }

  public CalibrationStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Rolls the calibration window forward after a completed calibration (H3). */
  public void recalibrate(LocalDate lastCalibrationDate, LocalDate nextCalibrationDate,
      String calibrationBody, CalibrationStatus status, Instant updatedAt) {
    this.lastCalibrationDate = lastCalibrationDate;
    this.nextCalibrationDate = nextCalibrationDate;
    this.calibrationBody = calibrationBody;
    this.status = status;
    this.updatedAt = updatedAt;
  }
}
