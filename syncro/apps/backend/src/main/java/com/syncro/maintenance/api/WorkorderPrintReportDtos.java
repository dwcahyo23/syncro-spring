package com.syncro.maintenance.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate DTOs for the workorder print report (story 14-3, FR-175). Mirrors the
 * {@code PreventiveReportView} pattern from 11-3: one endpoint composes WO header,
 * sessions, narrative, CP/CPK, evidence, sparepart requests, and the signature block
 * into a single response for the WYSIWYG browser-print page.
 */
public final class WorkorderPrintReportDtos {
  private WorkorderPrintReportDtos() {
  }

  /** WO header fields for the print report. */
  public record PrintReportHeaderView(
      String id,
      String source,
      String status,
      String categoryCode,
      String categoryLabel,
      UUID machineId,
      String machineCode,
      String machineName,
      String plantCode,
      String description,
      UUID assignedTechnicianId,
      String assignedTechnicianName,
      LocalDate createdAt,
      LocalDate updatedAt,
      String doneReason) {
  }

  /** One repair session row in the print report. */
  public record PrintReportSessionView(
      UUID id,
      UUID technicianId,
      String description,
      Instant startedAt,
      Instant endedAt,
      Long durationMinutes) {
  }

  /** Narrative section for the print report. */
  public record PrintReportNarrativeView(
      String reportChronological,
      String reportAnalyze,
      String reportCorrective,
      String reportPreventive) {
  }

  /** CP/CPK capability section for the print report. */
  public record PrintReportCpkView(
      BigDecimal cpCkLower,
      BigDecimal cpCkUpper,
      BigDecimal cpk,
      String cpkPdfPresignedUrl,
      String fmeaFailureType,
      String stopTimeReason,
      String stopTimeDetail) {
  }

  /** One evidence attachment for the print report. */
  public record PrintReportEvidenceView(
      UUID id,
      String filename,
      String contentType,
      String presignedUrl) {
  }

  /** One sparepart request for the print report. */
  public record PrintReportPartView(
      UUID id,
      String materialCode,
      short quantity,
      String status,
      String notes) {
  }

  /** Signature block for the print report, present when the WO has been approved. */
  public record PrintReportSignatureView(
      String signaturePresignedUrl,
      String signerIdentity,
      UUID signedBy,
      Instant signedAt) {
  }

  /**
   * Aggregate print report response. Every section is optional (null/empty list) so the
   * frontend handles absent data gracefully. The company logo is a separate settings
   * read (GET /api/v1/settings/logo), not part of this aggregate.
   */
  public record WorkorderPrintReportView(
      PrintReportHeaderView header,
      List<PrintReportSessionView> sessions,
      PrintReportNarrativeView narrative,
      PrintReportCpkView cpk,
      List<PrintReportEvidenceView> evidence,
      List<PrintReportPartView> parts,
      PrintReportSignatureView signature) {
  }
}