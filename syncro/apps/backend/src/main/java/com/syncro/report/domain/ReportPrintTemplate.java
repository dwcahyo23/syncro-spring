package com.syncro.report.domain;

import java.time.Instant;
import java.util.UUID;

public record ReportPrintTemplate(
    UUID id,
    ReportPrintTemplateType reportType,
    String bodyHtml,
    UUID updatedBy,
    Instant updatedAt) {
}
