package com.syncro.maintenance.preventive.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ActiveChecksheetView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ApproveChecksheetRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ChecksheetView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.CreateChecksheetRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ReviseChecksheetRequest;
import com.syncro.maintenance.preventive.application.PmChecksheetService;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ApproveChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.CreateChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ReviseChecksheetCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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

/** PM checksheet revision/approval workflow API (story 19-1, blueprint F2/F3). */
@Validated
@RestController
@RequestMapping("/api/v1/pm-checksheets")
public class PmChecksheetController {

  private final PmChecksheetService checksheets;

  public PmChecksheetController(PmChecksheetService checksheets) {
    this.checksheets = checksheets;
  }

  @Operation(operationId = "createPmChecksheet", summary = "Create a checksheet revision 1 (unapproved)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Checksheet created",
          content = @Content(schema = @Schema(implementation = ChecksheetView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine or frequency not found"),
      @ApiResponse(responseCode = "409", description = "Checksheet already exists for this machine and frequency")
  })
  @PostMapping
  public ResponseEntity<ChecksheetView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateChecksheetRequest request) {
    var view = checksheets.create(user, new CreateChecksheetCommand(request.machineId(),
        request.frequencyId(), request.revisionReason()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/pm-checksheets/" + view.id()))
        .body(toView(view));
  }

  @Operation(operationId = "listPmChecksheets", summary = "List checksheets (filter by machineId, frequencyId)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Checksheets returned",
          content = @Content(schema = @Schema(implementation = ChecksheetView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public List<ChecksheetView> list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID machineId,
      @RequestParam(required = false) UUID frequencyId) {
    return checksheets.list(user, machineId, frequencyId).stream()
        .map(PmChecksheetController::toView).toList();
  }

  @Operation(operationId = "getPmChecksheet", summary = "Get a checksheet")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Checksheet returned",
          content = @Content(schema = @Schema(implementation = ChecksheetView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Checksheet not found")
  })
  @GetMapping("/{id}")
  public ChecksheetView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return toView(checksheets.get(user, id));
  }

  @Operation(operationId = "getActiveChecksheet", summary = "Get active checksheet pointer for (machineId, frequencyId)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Active checksheet returned",
          content = @Content(schema = @Schema(implementation = ActiveChecksheetView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "No active checksheet for this pair")
  })
  @GetMapping("/active")
  public ActiveChecksheetView getActive(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID machineId, @RequestParam UUID frequencyId) {
    var view = checksheets.getActive(user, machineId, frequencyId);
    return new ActiveChecksheetView(view.checksheetId(), view.machineId(), view.frequencyId(),
        view.revisionNo());
  }

  @Operation(operationId = "revisePmChecksheet", summary = "Create a new revision superseding the source")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "New revision created",
          content = @Content(schema = @Schema(implementation = ChecksheetView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Checksheet not found"),
      @ApiResponse(responseCode = "409", description = "Source is not the latest revision or concurrent conflict")
  })
  @PostMapping("/{id}/revise")
  public ResponseEntity<ChecksheetView> revise(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) ReviseChecksheetRequest request) {
    var reason = request == null ? null : request.revisionReason();
    var view = checksheets.revise(user, id, new ReviseChecksheetCommand(reason));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/pm-checksheets/" + view.id()))
        .body(toView(view));
  }

  @Operation(operationId = "approvePmChecksheet", summary = "Approve a checksheet revision (flips active pointer)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Checksheet approved",
          content = @Content(schema = @Schema(implementation = ChecksheetView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Checksheet not found"),
      @ApiResponse(responseCode = "409", description = "Checksheet already approved or not the latest revision")
  })
  @PostMapping("/{id}/approve")
  public ChecksheetView approve(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) ApproveChecksheetRequest request) {
    var effectiveDate = request == null ? null : request.effectiveDate();
    return toView(checksheets.approve(user, id, new ApproveChecksheetCommand(effectiveDate)));
  }

  private static ChecksheetView toView(
      com.syncro.maintenance.preventive.application.PmChecksheetService.ChecksheetView v) {
    return new ChecksheetView(v.id(), v.machineId(), v.frequencyId(), v.revisionNo(),
        v.revisionReason(), v.isActive(), v.supersedes(), v.approvedBy(), v.approvedAt(),
        v.effectiveDate(), v.createdBy(), v.createdAt(), v.updatedAt());
  }
}
