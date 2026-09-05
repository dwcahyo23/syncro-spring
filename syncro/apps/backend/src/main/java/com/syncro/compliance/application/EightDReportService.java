package com.syncro.compliance.application;

import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.application.NonConformanceService.InvalidStateTransitionException;
import com.syncro.compliance.application.NonConformanceService.NonConformanceNotFoundException;
import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.compliance.domain.EightDStatus;
import com.syncro.compliance.infrastructure.db.EightDReportEntity;
import com.syncro.compliance.infrastructure.db.EightDReportRepository;
import com.syncro.compliance.infrastructure.db.NonConformanceEntity;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 8D report surface (story 21-1, blueprint H2): one report per NC
 * ({@code uq_eight_d_reports_nc} → second create = 409 EIGHT_D_CONFLICT), D1–D8
 * sections, and post-closure effectiveness verification. Mutations reuse the NC
 * machine-scope visibility gate (the report inherits its NC's scope) plus the
 * workorder-create role set; effectiveness verification narrows to
 * SUPER_ADMIN/MANAGER_MAINTENANCE only (spec "Always"). Status flow
 * DRAFT→IN_PROGRESS→CLOSED→EFFECTIVE|INEFFECTIVE; the verdict transition stamps
 * {@code effectiveness_verified_at}. All mutations audit-log previous/new values.
 */
@Service
public class EightDReportService {

  private final EightDReportRepository reports;
  private final NonConformanceService nonConformances;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public EightDReportService(EightDReportRepository reports,
      NonConformanceService nonConformances, AuditLogWriter auditLog, Clock clock) {
    this.reports = reports;
    this.nonConformances = nonConformances;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  public record CreateEightDCommand(String reportNumber, Map<String, Object> d1Team,
      String d2Description, String d3Containment, Map<String, Object> d4RootCause,
      String d5CaPermanent, String d6Implementation, String d7LessonLearned,
      String d8ClosureNotes) {
  }

  /** Partial section update (null keeps the stored value); {@code status} rides the gate. */
  public record UpdateEightDCommand(EightDStatus status, Map<String, Object> d1Team,
      String d2Description, String d3Containment, Map<String, Object> d4RootCause,
      String d5CaPermanent, String d6Implementation, String d7LessonLearned,
      String d8ClosureNotes, String pdfArtifactUrl) {
  }

  public record VerifyEffectivenessCommand(EightDStatus verdict) {
  }

  public record EightDView(UUID id, UUID ncId, String reportNumber, Map<String, Object> d1Team,
      String d2Description, String d3Containment, Map<String, Object> d4RootCause,
      String d5CaPermanent, String d6Implementation, String d7LessonLearned,
      String d8ClosureNotes, EightDStatus status, Instant effectivenessVerifiedAt,
      String pdfArtifactUrl, Instant createdAt, Instant updatedAt) {
  }

  /** Reads require the caller to see the parent NC (machine-scope filter, 404 otherwise). */
  @Transactional(readOnly = true)
  public EightDView getForNc(AuthenticatedUser user, UUID ncId) {
    nonConformances.loadVisible(user, ncId);
    return reports.findByNcId(ncId).map(EightDReportService::toView)
        .orElseThrow(EightDReportNotFoundException::new);
  }

  @Transactional
  public EightDView create(AuthenticatedUser user, UUID ncId, CreateEightDCommand command) {
    NonConformanceService.requireMutationRole(user);
    // loadVisible validates NC existence + scope; the NC row is the scope anchor and
    // the audit plant dimension source (review 21-1 P2).
    var nc = nonConformances.loadVisible(user, ncId);
    var plantId = nonConformances.resolvePlantId(nc);
    if (reports.findByNcId(ncId).isPresent()) {
      throw new EightDConflictException();
    }
    if (reports.existsByReportNumber(command.reportNumber())) {
      throw new DuplicateIdentifierException();
    }
    var now = Instant.now(clock);
    var entity = new EightDReportEntity(UUID.randomUUID(), ncId, command.reportNumber(),
        command.d1Team(), command.d2Description(), command.d3Containment(),
        command.d4RootCause(), command.d5CaPermanent(), command.d6Implementation(),
        command.d7LessonLearned(), command.d8ClosureNotes(), EightDStatus.DRAFT, null, null,
        now, now);
    try {
      var saved = reports.saveAndFlush(entity);
      auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.EIGHT_D_REPORT,
          saved.getId(), saved.getReportNumber(), plantId, null, auditValues(saved), null));
      return toView(saved);
    } catch (DataIntegrityViolationException race) {
      // Concurrent create won one of the unique constraints, or the parent NC was
      // deleted mid-flight (fk_eight_d_reports_nc). The session is rollback-only, so
      // classify by the constraint name anywhere in the cause chain (review 21-1 P5).
      if (NonConformanceService.causedBy(race, "uq_eight_d_reports_nc")) {
        throw new EightDConflictException();
      }
      if (NonConformanceService.causedBy(race, "uq_eight_d_reports_report_number")) {
        throw new DuplicateIdentifierException();
      }
      if (NonConformanceService.causedBy(race, "fk_eight_d_reports_nc")) {
        throw new NonConformanceNotFoundException();
      }
      throw race;
    }
  }

  @Transactional
  public EightDView update(AuthenticatedUser user, UUID ncId, UpdateEightDCommand command) {
    NonConformanceService.requireMutationRole(user);
    var anchor = loadForNc(user, ncId);
    var report = anchor.report();
    var previous = auditValues(report);
    var now = Instant.now(clock);

    if (command.status() != null) {
      requireTransition(report.getStatus(), command.status());
      report.transitionTo(command.status(), now);
    }
    report.updateSections(command.d1Team(), command.d2Description(), command.d3Containment(),
        command.d4RootCause(), command.d5CaPermanent(), command.d6Implementation(),
        command.d7LessonLearned(), command.d8ClosureNotes(), command.pdfArtifactUrl(), now);

    var saved = reports.saveAndFlush(report);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.EIGHT_D_REPORT,
        saved.getId(), saved.getReportNumber(), anchor.plantId(), previous, auditValues(saved),
        null));
    return toView(saved);
  }

  /**
   * Effectiveness verdict (spec "Always": SUPER_ADMIN/MANAGER_MAINTENANCE only) —
   * legal only from CLOSED; stamps {@code effectiveness_verified_at} with the server clock.
   */
  @Transactional
  public EightDView verifyEffectiveness(AuthenticatedUser user, UUID ncId,
      VerifyEffectivenessCommand command) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN
        && user.applicationRole() != ApplicationRole.MANAGER_MAINTENANCE) {
      throw new ComplianceForbiddenException();
    }
    if (command.verdict() != EightDStatus.EFFECTIVE && command.verdict() != EightDStatus.INEFFECTIVE) {
      throw new InvalidStateTransitionException();
    }
    var anchor = loadForNc(user, ncId);
    var report = anchor.report();
    if (report.getStatus() != EightDStatus.CLOSED) {
      throw new InvalidStateTransitionException();
    }
    var previous = auditValues(report);
    var now = Instant.now(clock);
    report.verifyEffectiveness(command.verdict(), now, now);
    var saved = reports.saveAndFlush(report);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.EIGHT_D_REPORT,
        saved.getId(), saved.getReportNumber(), anchor.plantId(), previous, auditValues(saved),
        null));
    return toView(saved);
  }

  // -------------------------------------------------------------------------
  // Gates
  // -------------------------------------------------------------------------

  /** The NC (scope anchor + audit plant source) and its report, loaded together. */
  private record NcAnchor(NonConformanceEntity nc, EightDReportEntity report, UUID plantId) {
  }

  private NcAnchor loadForNc(AuthenticatedUser user, UUID ncId) {
    var nc = nonConformances.loadVisible(user, ncId);
    var report = reports.findByNcId(ncId).orElseThrow(EightDReportNotFoundException::new);
    return new NcAnchor(nc, report, nonConformances.resolvePlantId(nc));
  }

  /** DRAFT→IN_PROGRESS→CLOSED→EFFECTIVE|INEFFECTIVE; the verdict rides verifyEffectiveness. */
  private static void requireTransition(EightDStatus from, EightDStatus to) {
    var legal = (from == EightDStatus.DRAFT && to == EightDStatus.IN_PROGRESS)
        || (from == EightDStatus.IN_PROGRESS && to == EightDStatus.CLOSED);
    if (!legal) {
      throw new InvalidStateTransitionException();
    }
  }

  private static Map<String, Object> auditValues(EightDReportEntity r) {
    var values = new LinkedHashMap<String, Object>();
    values.put("reportNumber", r.getReportNumber());
    values.put("ncId", r.getNcId().toString());
    values.put("d1Team", r.getD1Team());
    values.put("d2Description", r.getD2Description());
    values.put("d3Containment", r.getD3Containment());
    values.put("d4RootCause", r.getD4RootCause());
    values.put("d5CaPermanent", r.getD5CaPermanent());
    values.put("d6Implementation", r.getD6Implementation());
    values.put("d7LessonLearned", r.getD7LessonLearned());
    values.put("d8ClosureNotes", r.getD8ClosureNotes());
    values.put("status", r.getStatus().name());
    values.put("effectivenessVerifiedAt",
        r.getEffectivenessVerifiedAt() != null ? r.getEffectivenessVerifiedAt().toString() : null);
    values.put("pdfArtifactUrl", r.getPdfArtifactUrl());
    return values;
  }

  static EightDView toView(EightDReportEntity r) {
    return new EightDView(r.getId(), r.getNcId(), r.getReportNumber(), r.getD1Team(),
        r.getD2Description(), r.getD3Containment(), r.getD4RootCause(), r.getD5CaPermanent(),
        r.getD6Implementation(), r.getD7LessonLearned(), r.getD8ClosureNotes(), r.getStatus(),
        r.getEffectivenessVerifiedAt(), r.getPdfArtifactUrl(), r.getCreatedAt(), r.getUpdatedAt());
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  /** No report for the (visible) NC. → 404 EIGHT_D_REPORT_NOT_FOUND. */
  public static class EightDReportNotFoundException extends RuntimeException {
  }

  /** Second report for one NC (uq_eight_d_reports_nc). → 409 EIGHT_D_CONFLICT. */
  public static class EightDConflictException extends RuntimeException {
  }
}
