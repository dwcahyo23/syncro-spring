package com.syncro.compliance.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.compliance.application.CalibrationService;
import com.syncro.compliance.domain.CalibrationStatus;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Calibration REST surface (story 21-2, blueprint H3):
 * {@code /api/v1/calibration-instruments} CRUD, the append-only
 * {@code /{id}/records} history, and the {@code /{id}/recalibrate} event.
 * Controllers only bind/validate and delegate — role gates, derived status,
 * scope filtering, and audit live in {@link CalibrationService} (spine API rule).
 */
@RestController
@RequestMapping("/api/v1/calibration-instruments")
public class CalibrationController {

  private final CalibrationService calibration;

  public CalibrationController(CalibrationService calibration) {
    this.calibration = calibration;
  }

  @Operation(operationId = "listCalibrationInstruments",
      summary = "List calibration instruments visible in the caller's plant scope, "
          + "with status derived on read (optional ?status= filter)")
  @GetMapping
  public List<CalibrationService.InstrumentView> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) CalibrationStatus status) {
    return calibration.list(user, status);
  }

  @Operation(operationId = "createCalibrationInstrument", summary = "Register a calibration instrument")
  @PostMapping
  public ResponseEntity<CalibrationService.InstrumentView> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody ComplianceDtos.CreateInstrumentRequest request) {
    var view = calibration.create(user, new CalibrationService.CreateInstrumentCommand(
        request.instrumentCode(), request.name(), request.model(), request.serialNumber(),
        request.location(), request.calibrationFrequencyDays(), request.lastCalibrationDate(),
        request.nextCalibrationDate(), request.calibrationBody(), request.plantId()));
    return ResponseEntity.created(URI.create("/api/v1/calibration-instruments/" + view.id()))
        .body(view);
  }

  @Operation(operationId = "getCalibrationInstrument",
      summary = "Read one instrument (plant-scope filtered, status derived)")
  @GetMapping("/{id}")
  public CalibrationService.InstrumentView get(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return calibration.get(user, id);
  }

  @Operation(operationId = "updateCalibrationInstrument",
      summary = "Partial update of an instrument (code and dates are immutable)")
  @PatchMapping("/{id}")
  public CalibrationService.InstrumentView update(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody ComplianceDtos.UpdateInstrumentRequest request) {
    return calibration.update(user, id, new CalibrationService.UpdateInstrumentCommand(
        request.name(), request.model(), request.serialNumber(), request.location(),
        request.calibrationFrequencyDays(), request.calibrationBody(), request.plantId()));
  }

  @Operation(operationId = "recalibrateCalibrationInstrument",
      summary = "Record a completed calibration: appends a record and advances the due dates")
  @PostMapping("/{id}/recalibrate")
  public ResponseEntity<CalibrationService.RecordView> recalibrate(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
      @Valid @RequestBody ComplianceDtos.RecalibrateRequest request) {
    var view = calibration.recalibrate(user, id, new CalibrationService.RecalibrateCommand(
        request.calibrationDate(), request.nextCalibrationDate(), request.calibrationBody(),
        request.certificateNumber(), request.certificateUrl(), request.result(),
        request.notes()));
    return ResponseEntity.created(
        URI.create("/api/v1/calibration-instruments/" + id + "/records/" + view.id())).body(view);
  }

  @Operation(operationId = "listCalibrationRecords",
      summary = "Append-only calibration history of an instrument (newest first)")
  @GetMapping("/{id}/records")
  public List<CalibrationService.RecordView> listRecords(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return calibration.listRecords(user, id);
  }

  @Operation(operationId = "getCalibrationRecord", summary = "Read one calibration record")
  @GetMapping("/{id}/records/{recordId}")
  public CalibrationService.RecordView getRecord(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @PathVariable UUID recordId) {
    return calibration.getRecord(user, id, recordId);
  }
}
