package com.syncro.report.infrastructure;

import com.syncro.report.domain.ReportPrintTemplateType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportPrintTemplateRepository
    extends JpaRepository<ReportPrintTemplateEntity, UUID> {

  Optional<ReportPrintTemplateEntity> findByReportType(ReportPrintTemplateType reportType);

  @Modifying(clearAutomatically = true)
  @Query(value = """
      INSERT INTO report_print_templates (id, report_type, body_html, updated_by, updated_at, created_at)
      VALUES (:id, :reportType, :bodyHtml, :updatedBy, :now, :now)
      ON CONFLICT (report_type)
      DO UPDATE SET body_html = :bodyHtml, updated_by = :updatedBy, updated_at = :now
      """, nativeQuery = true)
  void upsert(@Param("id") UUID id, @Param("reportType") String reportType,
      @Param("bodyHtml") String bodyHtml, @Param("updatedBy") UUID updatedBy,
      @Param("now") Instant now);
}
