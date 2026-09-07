package com.syncro.compliance.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request DTOs for the story 21-2 calibration + ECN surface (blueprint H3/H4).
 * Bean Validation at the boundary; business rules (date ordering, transitions,
 * scope) live in the application services. {@code certificateUrl} /
 * {@code beforePhotoUrl} / {@code afterPhotoUrl} are TEXT passthrough — no
 * Garage coupling this story (8D {@code pdfArtifactUrl} precedent).
 */
public final class ComplianceDtos {

  private ComplianceDtos() {
  }

  public record CreateInstrumentRequest(
      @NotBlank @Size(max = 64) String instrumentCode,
      @NotBlank @Size(max = 255) String name,
      @Size(max = 255) String model,
      @Size(max = 255) String serialNumber,
      @Size(max = 255) String location,
      @NotNull @Positive Integer calibrationFrequencyDays,
      LocalDate lastCalibrationDate,
      @NotNull LocalDate nextCalibrationDate,
      @Size(max = 255) String calibrationBody,
      UUID plantId) {
  }

  /** Partial update — null keeps the stored value; code and dates are immutable here. */
  public record UpdateInstrumentRequest(
      @Size(max = 255) String name,
      @Size(max = 255) String model,
      @Size(max = 255) String serialNumber,
      @Size(max = 255) String location,
      @Positive Integer calibrationFrequencyDays,
      @Size(max = 255) String calibrationBody,
      UUID plantId) {
  }

  public record RecalibrateRequest(
      @NotNull LocalDate calibrationDate,
      @NotNull LocalDate nextCalibrationDate,
      @Size(max = 255) String calibrationBody,
      @Size(max = 255) String certificateNumber,
      @Size(max = 4000) String certificateUrl,
      @Size(max = 255) String result,
      @Size(max = 4000) String notes) {
  }

  public record CreateEcnRequest(
      @NotBlank @Size(max = 50) String ecnNumber,
      @NotNull UUID machineId,
      @NotBlank @Size(max = 255) String title,
      @Size(max = 4000) String description,
      @Size(max = 50) String changeType,
      @Size(max = 4000) String justification,
      @Size(max = 4000) String beforePhotoUrl) {
  }

  /** Partial update — null keeps the stored value; number and machine are immutable. */
  public record UpdateEcnRequest(
      @Size(max = 255) String title,
      @Size(max = 4000) String description,
      @Size(max = 50) String changeType,
      @Size(max = 4000) String justification,
      @Size(max = 4000) String beforePhotoUrl) {
  }

  /** effectiveDate optional — the service defaults it to the server clock's date. */
  public record ApproveEcnRequest(LocalDate effectiveDate) {
  }

  public record ExecuteEcnRequest(
      @Size(max = 50) String executedWoId,
      @Size(max = 4000) String afterPhotoUrl) {
  }
}
