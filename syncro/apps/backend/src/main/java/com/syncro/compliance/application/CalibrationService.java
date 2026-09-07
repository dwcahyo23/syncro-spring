package com.syncro.compliance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceValidationException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.domain.CalibrationStatus;
import com.syncro.compliance.infrastructure.db.CalibrationInstrumentEntity;
import com.syncro.compliance.infrastructure.db.CalibrationInstrumentRepository;
import com.syncro.compliance.infrastructure.db.CalibrationRecordEntity;
import com.syncro.compliance.infrastructure.db.CalibrationRecordRepository;
import com.syncro.config.CalibrationProperties;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calibration instrument CRUD + recalibration (story 21-2, blueprint H3).
 * Mutations are gated to the 21-1 six-role set (rego
 * {@code compliance_calibration_paths} carries the identical coarse set) and
 * audit-logged with previous/new values. Reads filter by plant scope:
 * SUPER_ADMIN unrestricted, everyone else sees {@code plant_id IS NULL OR
 * plant_id IN :plantIds} — a null plant marks a global instrument visible to
 * all authenticated users (the NC-unlinked precedent; scope is plant-only,
 * instruments carry no machine link to derive groups from).
 *
 * <p>Calibration status is DERIVED on read from {@code next_calibration_date}
 * vs the server clock: before today → EXPIRED; within
 * {@code syncro.compliance.calibration.expiring-window-days} → EXPIRING_SOON;
 * else the stored snapshot. The stored column stays a write-time snapshot
 * (create/recalibrate) — no scheduler ever persists status, so a passing
 * midnight cannot hide an overdue instrument. Recalibration appends an
 * append-only {@code calibration_records} row (no update path) and advances the
 * instrument's dates + snapshot status; the record's next date must be after
 * its calibration date. {@code instrument_code} is a client-supplied immutable
 * business id → duplicate → 409 DUPLICATE_IDENTIFIER. The entity carries
 * {@code @Version} (V17) — lost updates surface as 409 VERSION_CONFLICT.
 */
@Service
public class CalibrationService {

  private final CalibrationInstrumentRepository instruments;
  private final CalibrationRecordRepository records;
  private final OperationalScopeService scopes;
  private final AuditLogWriter auditLog;
  private final CalibrationProperties properties;
  private final Clock clock;

  public CalibrationService(CalibrationInstrumentRepository instruments,
      CalibrationRecordRepository records, OperationalScopeService scopes,
      AuditLogWriter auditLog, CalibrationProperties properties, Clock clock) {
    this.instruments = instruments;
    this.records = records;
    this.scopes = scopes;
    this.auditLog = auditLog;
    this.properties = properties;
    this.clock = clock;
  }

  public record CreateInstrumentCommand(String instrumentCode, String name, String model,
      String serialNumber, String location, int calibrationFrequencyDays,
      LocalDate lastCalibrationDate, LocalDate nextCalibrationDate, String calibrationBody,
      UUID plantId) {
  }

  /** Partial update (null keeps the stored value); code and dates are immutable here. */
  public record UpdateInstrumentCommand(String name, String model, String serialNumber,
      String location, Integer calibrationFrequencyDays, String calibrationBody, UUID plantId) {
  }

  public record RecalibrateCommand(LocalDate calibrationDate, LocalDate nextCalibrationDate,
      String calibrationBody, String certificateNumber, String certificateUrl, String result,
      String notes) {
  }

  /** {@code version} exposed (review 21-2 L11) so clients can interpret VERSION_CONFLICT. */
  public record InstrumentView(UUID id, String instrumentCode, String name, String model,
      String serialNumber, String location, int calibrationFrequencyDays,
      LocalDate lastCalibrationDate, LocalDate nextCalibrationDate, String calibrationBody,
      CalibrationStatus status, UUID plantId, Instant createdAt, Instant updatedAt, long version) {
  }

  public record RecordView(UUID id, UUID instrumentId, LocalDate calibrationDate,
      LocalDate nextCalibrationDate, String calibrationBody, String certificateNumber,
      String certificateUrl, String result, String notes, UUID recordedBy, Instant createdAt) {
  }

  @Transactional(readOnly = true)
  public List<InstrumentView> list(AuthenticatedUser user, CalibrationStatus status) {
    var scope = scopes.derive(user);
    var today = LocalDate.now(clock);
    return instruments.findScoped(scope.plantIds() == null,
            NonConformanceService.plantIds(scope)).stream()
        .map(entity -> toView(entity, today))
        .filter(view -> status == null || view.status() == status)
        .toList();
  }

  @Transactional(readOnly = true)
  public InstrumentView get(AuthenticatedUser user, UUID id) {
    return toView(loadVisible(user, id), LocalDate.now(clock));
  }

  @Transactional
  public InstrumentView create(AuthenticatedUser user, CreateInstrumentCommand command) {
    NonConformanceService.requireMutationRole(user);
    var scope = scopes.derive(user);
    if (command.plantId() == null) {
      // Review 21-2 H1: a null plant makes the instrument GLOBAL — visible to every
      // authenticated user. Only SUPER_ADMIN may mint global instruments; the
      // six-role set must file under a plant they can see.
      if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
        throw new ComplianceForbiddenException();
      }
    } else {
      if (instruments.countPlant(command.plantId()) == 0) {
        throw new ComplianceReferenceNotFoundException("PLANT_NOT_FOUND",
            "Referenced plant was not found.");
      }
      // 21-1 P3 parity: a non-admin may not file an instrument under a plant they
      // cannot see — the row would be invisible to its own creator.
      if (scope.plantIds() != null && !scope.plantIds().contains(command.plantId())) {
        throw new ComplianceForbiddenException();
      }
    }
    // Review 21-2 M4: create validates the date window the same way recalibrate
    // does — a last date after next is a nonsense cadence.
    if (command.lastCalibrationDate() != null
        && !command.lastCalibrationDate().isBefore(command.nextCalibrationDate())) {
      throw new ComplianceValidationException(Map.of("lastCalibrationDate",
          "Last calibration date must be before the next calibration date."));
    }
    if (instruments.existsByInstrumentCode(command.instrumentCode())) {
      throw new DuplicateIdentifierException();
    }
    var now = Instant.now(clock);
    var entity = new CalibrationInstrumentEntity(UUID.randomUUID(), command.instrumentCode(),
        command.name(), command.model(), command.serialNumber(), command.location(),
        command.calibrationFrequencyDays(), command.lastCalibrationDate(),
        command.nextCalibrationDate(), command.calibrationBody(),
        snapshotStatus(command.nextCalibrationDate()), command.plantId(), now, now);
    try {
      var saved = instruments.saveAndFlush(entity);
      auditLog.record(user, new AuditRecord(AuditAction.CREATE,
          AuditEntityType.CALIBRATION_INSTRUMENT, saved.getId(), saved.getInstrumentCode(),
          saved.getPlantId(), null, auditValues(saved), null));
      return toView(saved, LocalDate.now(clock));
    } catch (DataIntegrityViolationException race) {
      // Concurrent create won uq_calibration_instruments_code (the only constraint
      // this insert can violate — the plant FK is ON DELETE SET NULL). Classify by
      // constraint name anywhere in the cause chain (21-1 P5).
      if (NonConformanceService.causedBy(race, "uq_calibration_instruments_code")) {
        throw new DuplicateIdentifierException();
      }
      throw race;
    }
  }

  @Transactional
  public InstrumentView update(AuthenticatedUser user, UUID id, UpdateInstrumentCommand command) {
    NonConformanceService.requireMutationRole(user);
    var entity = loadVisible(user, id);
    if (command.plantId() != null && !command.plantId().equals(entity.getPlantId())) {
      // Review 21-2 H3: a plantId CHANGE (global→plant, plant→other plant) hides
      // the instrument from whoever could see it before — one-way, since null
      // keeps the stored value and can never clear it. Only SUPER_ADMIN may move
      // an instrument between visibility domains.
      if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
        throw new ComplianceForbiddenException();
      }
      if (instruments.countPlant(command.plantId()) == 0) {
        throw new ComplianceReferenceNotFoundException("PLANT_NOT_FOUND",
            "Referenced plant was not found.");
      }
    }
    // Review 21-2 M5: blank strings are rejected (null keeps the stored value).
    requireNotBlankFields(command);
    requirePositiveFrequency(command);
    var previous = auditValues(entity);
    var now = Instant.now(clock);
    entity.updateContent(command.name(), command.model(), command.serialNumber(),
        command.location(), command.calibrationFrequencyDays(), command.calibrationBody(),
        command.plantId(), now);
    var saved = instruments.saveAndFlush(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.CALIBRATION_INSTRUMENT, saved.getId(), saved.getInstrumentCode(),
        saved.getPlantId(), previous, auditValues(saved), null));
    return toView(saved, LocalDate.now(clock));
  }

  /**
   * Recalibration event (spec "Always"): appends the immutable record row and
   * advances the instrument's last/next dates + snapshot status. The record's
   * next date must be strictly after its calibration date.
   */
  @Transactional
  public RecordView recalibrate(AuthenticatedUser user, UUID instrumentId,
      RecalibrateCommand command) {
    NonConformanceService.requireMutationRole(user);
    var instrument = loadVisible(user, instrumentId);
    var fieldErrors = new LinkedHashMap<String, String>();
    if (!command.nextCalibrationDate().isAfter(command.calibrationDate())) {
      fieldErrors.put("nextCalibrationDate",
          "Next calibration date must be after the calibration date.");
    }
    // Review 21-2 M4: the append-only history must stay ordered — a calibration
    // dated in the future, or before the instrument's current last calibration,
    // would corrupt the newest-first record list.
    var today = LocalDate.now(clock);
    if (command.calibrationDate().isAfter(today)) {
      fieldErrors.put("calibrationDate", "Calibration date must not be in the future.");
    }
    if (instrument.getLastCalibrationDate() != null
        && command.calibrationDate().isBefore(instrument.getLastCalibrationDate())) {
      fieldErrors.putIfAbsent("calibrationDate",
          "Calibration date must not precede the last recorded calibration.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new ComplianceValidationException(fieldErrors);
    }
    var now = Instant.now(clock);
    var record = new CalibrationRecordEntity(UUID.randomUUID(), instrument.getId(),
        command.calibrationDate(), command.nextCalibrationDate(), command.calibrationBody(),
        command.certificateNumber(), command.certificateUrl(), command.result(),
        command.notes(), UUID.fromString(user.id()), now);
    CalibrationRecordEntity savedRecord;
    try {
      savedRecord = records.saveAndFlush(record);
    } catch (DataIntegrityViolationException race) {
      // Review 21-2 M8: a deleted user's still-valid token can violate the
      // recorded_by FK (ON DELETE SET NULL only applies to DB-side deletes of
      // existing rows; a hard-deleted actor races the insert). Classify to 403 —
      // the actor is no longer a valid principal (21-1 P5 chain walk).
      if (NonConformanceService.causedBy(race, "fk_calibration_records_recorded_by")) {
        throw new ComplianceForbiddenException();
      }
      throw race;
    }

    var previous = auditValues(instrument);
    instrument.recalibrate(command.calibrationDate(), command.nextCalibrationDate(),
        command.calibrationBody() != null ? command.calibrationBody()
            : instrument.getCalibrationBody(),
        snapshotStatus(command.nextCalibrationDate()), now);
    var savedInstrument = instruments.saveAndFlush(instrument);

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.CALIBRATION_RECORD,
        savedRecord.getId(), recordLabel(savedRecord), savedInstrument.getPlantId(),
        null, recordAuditValues(savedRecord), null));
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.CALIBRATION_INSTRUMENT, savedInstrument.getId(),
        savedInstrument.getInstrumentCode(), savedInstrument.getPlantId(), previous,
        auditValues(savedInstrument), null));
    return toRecordView(savedRecord);
  }

  @Transactional(readOnly = true)
  public List<RecordView> listRecords(AuthenticatedUser user, UUID instrumentId) {
    loadVisible(user, instrumentId);
    return records.findByInstrumentIdOrderByCalibrationDateDesc(instrumentId).stream()
        .map(CalibrationService::toRecordView)
        .toList();
  }

  @Transactional(readOnly = true)
  public RecordView getRecord(AuthenticatedUser user, UUID instrumentId, UUID recordId) {
    loadVisible(user, instrumentId);
    return records.findByInstrumentIdAndId(instrumentId, recordId)
        .map(CalibrationService::toRecordView)
        .orElseThrow(CalibrationRecordNotFoundException::new);
  }

  // -------------------------------------------------------------------------
  // Gates & derivation
  // -------------------------------------------------------------------------

  /** Loads the instrument and enforces plant-scope visibility (404 when filtered out). */
  CalibrationInstrumentEntity loadVisible(AuthenticatedUser user, UUID id) {
    var entity = instruments.findById(id)
        .orElseThrow(CalibrationInstrumentNotFoundException::new);
    var scope = scopes.derive(user);
    if (instruments.countVisible(id, scope.plantIds() == null,
        NonConformanceService.plantIds(scope)) == 0) {
      throw new CalibrationInstrumentNotFoundException();
    }
    return entity;
  }

  /**
   * Derived status (spec "Always"): before today → EXPIRED; within the typed
   * window (inclusive of both boundaries) → EXPIRING_SOON; else the stored
   * write-time snapshot.
   */
  CalibrationStatus deriveStatus(CalibrationInstrumentEntity entity, LocalDate today) {
    return deriveStatus(entity.getNextCalibrationDate(), entity.getStatus(), today);
  }

  private CalibrationStatus deriveStatus(LocalDate next, CalibrationStatus stored,
      LocalDate today) {
    if (next.isBefore(today)) {
      return CalibrationStatus.EXPIRED;
    }
    if (!next.isAfter(today.plusDays(properties.expiringWindowDays()))) {
      return CalibrationStatus.EXPIRING_SOON;
    }
    return stored;
  }

  /** Write-time snapshot: the derived status at the moment of create/recalibrate. */
  private CalibrationStatus snapshotStatus(LocalDate next) {
    return deriveStatus(next, CalibrationStatus.VALID, LocalDate.now(clock));
  }

  /** Review 21-2 M5 (21-1 P4 parity): provided-but-blank text fields are rejected; null keeps stored. */
  private static void requireNotBlankFields(UpdateInstrumentCommand command) {
    var fieldErrors = new LinkedHashMap<String, String>();
    if (command.name() != null && command.name().isBlank()) {
      fieldErrors.put("name", "Name must not be blank.");
    }
    if (command.model() != null && command.model().isBlank()) {
      fieldErrors.put("model", "Model must not be blank.");
    }
    if (command.serialNumber() != null && command.serialNumber().isBlank()) {
      fieldErrors.put("serialNumber", "Serial number must not be blank.");
    }
    if (command.location() != null && command.location().isBlank()) {
      fieldErrors.put("location", "Location must not be blank.");
    }
    if (command.calibrationBody() != null && command.calibrationBody().isBlank()) {
      fieldErrors.put("calibrationBody", "Calibration body must not be blank.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new ComplianceValidationException(fieldErrors);
    }
  }

  /** V1 ck_calibration_instruments_frequency_positive mirrored server-side. */
  private static void requirePositiveFrequency(UpdateInstrumentCommand command) {
    if (command.calibrationFrequencyDays() != null && command.calibrationFrequencyDays() < 1) {
      throw new ComplianceValidationException(
          Map.of("calibrationFrequencyDays", "Calibration frequency must be positive."));
    }
  }

  private static Map<String, Object> auditValues(CalibrationInstrumentEntity i) {
    var values = new LinkedHashMap<String, Object>();
    values.put("instrumentCode", i.getInstrumentCode());
    values.put("name", i.getName());
    values.put("model", i.getModel());
    values.put("serialNumber", i.getSerialNumber());
    values.put("location", i.getLocation());
    values.put("calibrationFrequencyDays", i.getCalibrationFrequencyDays());
    values.put("lastCalibrationDate",
        i.getLastCalibrationDate() != null ? i.getLastCalibrationDate().toString() : null);
    values.put("nextCalibrationDate", i.getNextCalibrationDate().toString());
    values.put("calibrationBody", i.getCalibrationBody());
    values.put("status", i.getStatus().name());
    values.put("plantId", i.getPlantId() != null ? i.getPlantId().toString() : null);
    return values;
  }

  /** audit_log.entity_label is NOT NULL — certificate number when present, else the record id. */
  private static String recordLabel(CalibrationRecordEntity r) {
    return r.getCertificateNumber() != null && !r.getCertificateNumber().isBlank()
        ? r.getCertificateNumber() : r.getId().toString();
  }

  private static Map<String, Object> recordAuditValues(CalibrationRecordEntity r) {
    var values = new LinkedHashMap<String, Object>();
    values.put("instrumentId", r.getInstrumentId().toString());
    values.put("calibrationDate", r.getCalibrationDate().toString());
    values.put("nextCalibrationDate", r.getNextCalibrationDate().toString());
    values.put("calibrationBody", r.getCalibrationBody());
    values.put("certificateNumber", r.getCertificateNumber());
    values.put("certificateUrl", r.getCertificateUrl());
    values.put("result", r.getResult());
    values.put("notes", r.getNotes());
    values.put("recordedBy", r.getRecordedBy() != null ? r.getRecordedBy().toString() : null);
    return values;
  }

  private InstrumentView toView(CalibrationInstrumentEntity i, LocalDate today) {
    return new InstrumentView(i.getId(), i.getInstrumentCode(), i.getName(), i.getModel(),
        i.getSerialNumber(), i.getLocation(), i.getCalibrationFrequencyDays(),
        i.getLastCalibrationDate(), i.getNextCalibrationDate(), i.getCalibrationBody(),
        deriveStatus(i, today), i.getPlantId(), i.getCreatedAt(), i.getUpdatedAt(),
        i.getVersion());
  }

  static RecordView toRecordView(CalibrationRecordEntity r) {
    return new RecordView(r.getId(), r.getInstrumentId(), r.getCalibrationDate(),
        r.getNextCalibrationDate(), r.getCalibrationBody(), r.getCertificateNumber(),
        r.getCertificateUrl(), r.getResult(), r.getNotes(), r.getRecordedBy(), r.getCreatedAt());
  }

  // -------------------------------------------------------------------------
  // Exceptions (mapped by ComplianceExceptionHandler to stable codes)
  // -------------------------------------------------------------------------

  /** Unknown or out-of-scope instrument. → 404 INSTRUMENT_NOT_FOUND. */
  public static class CalibrationInstrumentNotFoundException extends RuntimeException {
  }

  /** No record with that id under the (visible) instrument. → 404 CALIBRATION_RECORD_NOT_FOUND. */
  public static class CalibrationRecordNotFoundException extends RuntimeException {
  }
}
