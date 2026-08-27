package com.syncro.sparepart.request.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.sparepart.request.api.SparepartRequestDtos.CreateSparepartRequestRequest;
import com.syncro.sparepart.request.api.SparepartRequestDtos.SparepartRequestView;
import com.syncro.sparepart.request.application.SparepartRequestService;
import com.syncro.sparepart.request.application.SparepartRequestService.CreateRequestCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/sparepart-requests")
public class SparepartRequestController {

  private final SparepartRequestService service;

  public SparepartRequestController(SparepartRequestService service) {
    this.service = service;
  }

  @Operation(operationId = "createSparepartRequest", summary = "Create a sparepart request with type rules (FR-140/FR-143/FR-144)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Request created",
          content = @Content(schema = @Schema(implementation = SparepartRequestView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Resource not found")
  })
  @PostMapping
  public ResponseEntity<SparepartRequestView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateSparepartRequestRequest request) {
    var command = new CreateRequestCommand(request.requestType(), request.workOrderId(), request.machineId(),
        request.sparepartId(), request.materialCode(), request.quantity(), request.estPriceId(),
        request.estUnitPrice(), request.purchaseReferenceUrl(), request.notes());
    var created = service.create(user, command);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(toView(created));
  }

  private static SparepartRequestView toView(com.syncro.sparepart.request.domain.SparepartRequest r) {
    return new SparepartRequestView(r.id(), r.requestType(), r.workOrderId(), r.machineId(), r.sparepartId(),
        r.materialCode(), r.quantity(), r.estPriceId(), r.estUnitPrice(), r.purchaseReferenceUrl(), r.status(),
        r.requestedBy(), r.requestedAt(), r.notes(), r.createdAt(), r.updatedAt());
  }
}