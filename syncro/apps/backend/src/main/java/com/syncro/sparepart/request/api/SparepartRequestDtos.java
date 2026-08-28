package com.syncro.sparepart.request.api;

import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import com.syncro.sparepart.request.domain.SparepartRequestType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SparepartRequestDtos {
  private SparepartRequestDtos() {
  }

  public record CreateSparepartRequestRequest(
      @NotNull SparepartRequestType requestType,
      String workOrderId,
      UUID machineId,
      UUID sparepartId,
      @Size(max = 64) String materialCode,
      @NotNull @Min(1) @Max(32767) Integer quantity,
      UUID estPriceId,
      @Digits(integer = 16, fraction = 2) BigDecimal estUnitPrice,
      @Size(max = 2048) String purchaseReferenceUrl,
      @Size(max = 4000) String notes) {
  }

  public record SparepartRequestView(UUID id, SparepartRequestType requestType, String workOrderId, UUID machineId,
      UUID sparepartId, String materialCode, int quantity, UUID estPriceId, BigDecimal estUnitPrice,
      String purchaseReferenceUrl, SparepartRequestStatus status, UUID requestedBy, Instant requestedAt,
      String notes, Instant createdAt, Instant updatedAt,
      Set<String> allowedActions, String requiredApprovalRole) {
  }

  /** Story 12-3 approval request body (FR-142): optional note. */
  public record ApproveRequest(
      @Size(max = 500) @Schema(description = "Optional note attached to the approval", nullable = true) String note) {
  }

  /** Story 12-2 status transition request body (FR-141). */
  public record TransitionRequest(
      @NotNull @Schema(description = "Target status for the transition", nullable = false) SparepartRequestStatus toStatus,
      @Size(max = 500) @Schema(description = "Optional note attached to the transition", nullable = true) String note) {
  }

  /** Story 12-2 manual MRE code request body (FR-145). */
  public record MreRequest(
      @NotBlank @Size(max = 64) @Schema(description = "Manual MRE code (no auto-generation)", nullable = false, maxLength = 64) String mreCode,
      @Size(max = 500) @Schema(description = "Optional note attached to the MRE record", nullable = true) String note) {
  }

  public record SparepartRequestListView(List<SparepartRequestView> items, long total, int page, int size) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }
}
