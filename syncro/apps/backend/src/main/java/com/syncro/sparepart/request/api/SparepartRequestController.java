package com.syncro.sparepart.request.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.sparepart.request.api.SparepartRequestDtos.ApproveRequest;
import com.syncro.sparepart.request.api.SparepartRequestDtos.CompleteRequest;
import com.syncro.sparepart.request.api.SparepartRequestDtos.CreateSparepartRequestRequest;
import com.syncro.sparepart.request.api.SparepartRequestDtos.MreRequest;
import com.syncro.sparepart.request.api.SparepartRequestDtos.SparepartRequestListView;
import com.syncro.sparepart.request.api.SparepartRequestDtos.SparepartRequestView;
import com.syncro.sparepart.request.api.SparepartRequestDtos.TransitionRequest;
import com.syncro.sparepart.request.application.SparepartRequestService;
import com.syncro.sparepart.request.application.SparepartRequestService.ApproveCommand;
import com.syncro.sparepart.request.application.SparepartRequestService.CompleteCommand;
import com.syncro.sparepart.request.application.SparepartRequestService.CreateRequestCommand;
import com.syncro.sparepart.request.application.SparepartRequestService.MreCommand;
import com.syncro.sparepart.request.application.SparepartRequestService.TransitionCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/sparepart-requests")
public class SparepartRequestController {

  private final SparepartRequestService service;

  public SparepartRequestController(SparepartRequestService service) {
    this.service = service;
  }

  @Operation(operationId = "listSparepartRequests", summary = "List sparepart requests (scoped, paginated)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Requests returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public SparepartRequestListView list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    var result = service.list(user, page, size);
    return new SparepartRequestListView(
        result.items().stream().map(r -> toView(r, user)).toList(),
        result.total(), result.page(), result.size());
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
        request.estUnitPrice(), request.purchaseReferenceUrl(), request.notes(),
        request.categoryId(), request.brandId(), request.kindId(), request.typeId());
    var created = service.create(user, command);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(toView(created, user));
  }

  @Operation(operationId = "transitionSparepartRequest", summary = "Transition a sparepart request (FR-141)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Request transitioned",
          content = @Content(schema = @Schema(implementation = SparepartRequestView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Request not found"),
      @ApiResponse(responseCode = "409", description = "Invalid state transition")
  })
  @PostMapping("/{id}/transition")
  public SparepartRequestView transition(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
    var command = new TransitionCommand(request.toStatus(), request.note());
    return toView(service.transition(user, id, command), user);
  }

  @Operation(operationId = "recordSparepartRequestMre", summary = "Record a manual MRE code (FR-145)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "MRE code recorded",
          content = @Content(schema = @Schema(implementation = SparepartRequestView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Request not found"),
      @ApiResponse(responseCode = "409", description = "Invalid state transition")
  })
  @PostMapping("/{id}/mre")
  public SparepartRequestView recordMre(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody MreRequest request) {
    var command = new MreCommand(request.mreCode(), request.note());
    return toView(service.recordMre(user, id, command), user);
  }

  @Operation(operationId = "approveSparepartRequest", summary = "Approve a sparepart request (FR-142, AD-16)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Request approved (ACKED)",
          content = @Content(schema = @Schema(implementation = SparepartRequestView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden (incl. SELF_APPROVAL_FORBIDDEN)"),
      @ApiResponse(responseCode = "404", description = "Request not found"),
      @ApiResponse(responseCode = "409", description = "Invalid state transition")
  })
  @PostMapping("/{id}/approve")
  public SparepartRequestView approve(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody(required = false) ApproveRequest request) {
    var command = new ApproveCommand(request == null ? null : request.note());
    return toView(service.approve(user, id, command), user);
  }

  @Operation(operationId = "completeSparepartRequest",
      summary = "Complete a new-item request (FR-144): register material code, image and est price, then ACK")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Request completed (ACKED)",
          content = @Content(schema = @Schema(implementation = SparepartRequestView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Request or price entry not found"),
      @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION or DUPLICATE_MATERIAL_CODE")
  })
  @PostMapping("/{id}/complete")
  public SparepartRequestView complete(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody CompleteRequest request) {
    var command = new CompleteCommand(request.materialCode(), request.imageObjectKey(), request.estPriceId());
    return toView(service.complete(user, id, command), user);
  }

  private SparepartRequestView toView(com.syncro.sparepart.request.domain.SparepartRequest r,
      AuthenticatedUser user) {
    var allowed = service.allowedActionsFor(user, r);
    return new SparepartRequestView(r.id(), r.requestType(), r.workOrderId(), r.machineId(), r.sparepartId(),
        r.materialCode(), r.quantity(), r.estPriceId(), r.estUnitPrice(), r.purchaseReferenceUrl(), r.status(),
        r.requestedBy(), r.requestedAt(), r.notes(), r.createdAt(), r.updatedAt(),
        allowed.allowedActions(), allowed.requiredApprovalRole());
  }
}