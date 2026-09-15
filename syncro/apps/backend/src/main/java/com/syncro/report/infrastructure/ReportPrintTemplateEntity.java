package com.syncro.report.infrastructure;

import com.syncro.report.domain.ReportPrintTemplateType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "report_print_templates")
public class ReportPrintTemplateEntity {

  @Id
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "report_type", nullable = false, length = 32, unique = true)
  private ReportPrintTemplateType reportType;

  @Column(name = "body_html", nullable = false, columnDefinition = "TEXT")
  private String bodyHtml;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ReportPrintTemplateEntity() {
  }

  public ReportPrintTemplateEntity(UUID id, ReportPrintTemplateType reportType, String bodyHtml,
      UUID updatedBy, Instant updatedAt, Instant createdAt) {
    this.id = id;
    this.reportType = reportType;
    this.bodyHtml = bodyHtml;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public ReportPrintTemplateType getReportType() {
    return reportType;
  }

  public String getBodyHtml() {
    return bodyHtml;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
