package com.syncro.sparepart.request.api;

import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import com.syncro.sparepart.request.domain.SparepartRequestType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
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
      @NotNull @Min(1) Integer quantity,
      UUID estPriceId,
      BigDecimal estUnitPrice,
      @Size(max = 2048) String purchaseReferenceUrl,
      @Size(max = 4000) String notes) {
  }

  public record SparepartRequestView(UUID id, SparepartRequestType requestType, String workOrderId, UUID machineId,
      UUID sparepartId, String materialCode, int quantity, UUID estPriceId, BigDecimal estUnitPrice,
      String purchaseReferenceUrl, SparepartRequestStatus status, UUID requestedBy, Instant requestedAt,
      String notes, Instant createdAt, Instant updatedAt) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }
}
