package com.syncro.report.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.report.domain.ReportPrintTemplate;
import com.syncro.report.domain.ReportPrintTemplateType;
import com.syncro.report.infrastructure.ReportPrintTemplateEntity;
import com.syncro.report.infrastructure.ReportPrintTemplateRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Global print-layout templates (spec "Template Laporan Cetak"). One row per report
 * type, written through the same atomic {@code INSERT ... ON CONFLICT (report_type)}
 * recipe proven for waha_templates (DW-77): two concurrent PUTs for the same type
 * leave exactly one row, never a duplicate. GET is open to every authenticated user
 * (the print pages read it); PUT is restricted server-side to SUPER_ADMIN /
 * MANAGER_MAINTENANCE — UI hiding is never the gate. The HTML body is stored
 * verbatim; sanitization happens at render time in the client (DOMPurify).
 */
@Service
public class ReportPrintTemplateService {

  /** Server-side size guard from the spec's Always block: body_html capped at 200_000 chars. */
  public static final int MAX_BODY_HTML_LENGTH = 200_000;

  private final ReportPrintTemplateRepository repository;
  private final Clock clock;

  public ReportPrintTemplateService(ReportPrintTemplateRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Optional<ReportPrintTemplate> findTemplate(ReportPrintTemplateType reportType) {
    return repository.findByReportType(reportType).map(this::toDomain);
  }

  @Transactional
  public ReportPrintTemplate upsertTemplate(
      ReportPrintTemplateType reportType, String bodyHtml, AuthenticatedUser actor) {
    if (actor.applicationRole() != ApplicationRole.SUPER_ADMIN
        && actor.applicationRole() != ApplicationRole.MANAGER_MAINTENANCE) {
      throw new ReportPrintTemplateForbiddenException();
    }
    if (bodyHtml == null || bodyHtml.length() > MAX_BODY_HTML_LENGTH) {
      throw new ReportPrintTemplateTooLargeException();
    }

    Instant now = Instant.now(clock);
    repository.upsert(UUID.randomUUID(), reportType.name(), bodyHtml, UUID.fromString(actor.id()), now);
    ReportPrintTemplateEntity saved = repository.findByReportType(reportType)
        .orElseThrow(ReportPrintTemplateNotFoundException::new);
    return toDomain(saved);
  }

  private ReportPrintTemplate toDomain(ReportPrintTemplateEntity entity) {
    return new ReportPrintTemplate(
        entity.getId(),
        entity.getReportType(),
        entity.getBodyHtml(),
        entity.getUpdatedBy(),
        entity.getUpdatedAt());
  }

  public static class ReportPrintTemplateForbiddenException extends RuntimeException {
    public ReportPrintTemplateForbiddenException() {
      super("You do not have permission to modify report print templates.");
    }
  }

  public static class ReportPrintTemplateNotFoundException extends RuntimeException {
    public ReportPrintTemplateNotFoundException() {
      super("Report print template not found.");
    }
  }

  public static class ReportPrintTemplateTooLargeException extends RuntimeException {
    public ReportPrintTemplateTooLargeException() {
      super("Report print template body exceeds the maximum allowed length.");
    }
  }
}
