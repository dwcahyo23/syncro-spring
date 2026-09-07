package com.syncro.compliance.infrastructure.db;

import com.syncro.compliance.domain.CalibrationStatus;
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
 * Persisted {@code calibration_instruments} row (blueprint H3, story 15-2). A
 * measurement instrument with its calibration cadence; {@code instrument_code} is
 * unique. PM checklist items reference it via the plain
 * {@code pm_checklist_items.calibration_instrument_id} column. Story 21-2 adds
 * {@code plant_id} (V17 — null means a global instrument visible to every
 * authenticated user, the NC-unlinked precedent) and the optimistic-lock
 * {@code version} (V13/V15 precedent). The stored {@code status} is a write-time
 * snapshot (create/recalibrate); reads derive the live status from
 * {@code next_calibration_date} — no scheduler touches it.
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

  /** Owning plant (V17); null = global instrument, visible to all authenticated users. */
  @Column(name = "plant_id")
  private UUID plantId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Optimistic lock (V17): concurrent updates → 409 VERSION_CONFLICT (21-1 P6 precedent). */
  @Version
  @Column(nullable = false)
  private long version;

  protected CalibrationInstrumentEntity() {
  }

  /** Story 15-2 shape (no plant) — delegates with a null (global) plant. */
  public CalibrationInstrumentEntity(UUID id, String instrumentCode, String name, String model,
      String serialNumber, String location, int calibrationFrequencyDays,
      LocalDate lastCalibrationDate, LocalDate nextCalibrationDate, String calibrationBody,
      CalibrationStatus status, Instant createdAt, Instant updatedAt) {
    this(id, instrumentCode, name, model, serialNumber, location, calibrationFrequencyDays,
        lastCalibrationDate, nextCalibrationDate, calibrationBody, status, null, createdAt,
        updatedAt);
  }

  public CalibrationInstrumentEntity(UUID id, String instrumentCode, String name, String model,
      String serialNumber, String location, int calibrationFrequencyDays,
      LocalDate lastCalibrationDate, LocalDate nextCalibrationDate, String calibrationBody,
      CalibrationStatus status, UUID plantId, Instant createdAt, Instant updatedAt) {
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
    this.plantId = plantId;
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

  public UUID getPlantId() {
    return plantId;
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

  /** Rolls the calibration window forward after a completed calibration (H3). */
  public void recalibrate(LocalDate lastCalibrationDate, LocalDate nextCalibrationDate,
      String calibrationBody, CalibrationStatus status, Instant updatedAt) {
    this.lastCalibrationDate = lastCalibrationDate;
    this.nextCalibrationDate = nextCalibrationDate;
    this.calibrationBody = calibrationBody;
    this.status = status;
    this.updatedAt = updatedAt;
  }

  /**
   * Partial update (story 21-2 PATCH, 21-1 precedent): a null argument keeps the
   * stored value. {@code instrumentCode} is immutable after creation; the date
   * fields move through {@link #recalibrate}, not here.
   */
  public void updateContent(String name, String model, String serialNumber, String location,
      Integer calibrationFrequencyDays, String calibrationBody, UUID plantId, Instant updatedAt) {
    if (name != null) {
      this.name = name;
    }
    if (model != null) {
      this.model = model;
    }
    if (serialNumber != null) {
      this.serialNumber = serialNumber;
    }
    if (location != null) {
      this.location = location;
    }
    if (calibrationFrequencyDays != null) {
      this.calibrationFrequencyDays = calibrationFrequencyDays;
    }
    if (calibrationBody != null) {
      this.calibrationBody = calibrationBody;
    }
    if (plantId != null) {
      this.plantId = plantId;
    }
    this.updatedAt = updatedAt;
  }
}
