package com.syncro.maintenance.preventive.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ChecklistItemView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.CreateChecklistItemRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.UpdateChecklistItemRequest;
import com.syncro.maintenance.preventive.application.PmChecklistService;
import com.syncro.maintenance.preventive.application.PmChecklistService.CreateItemCommand;
import com.syncro.maintenance.preventive.application.PmChecklistService.UpdateItemCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PM checklist item API (story 19-2, blueprint F4). Items are defined and edited
 * against an UNAPPROVED checksheet revision — approved revisions are frozen
 * (409 INVALID_CHECKSHEET_TRANSITION). MEASUREMENT items carry unit + lsl/nominal/usl
 * bounds; OK_NG items carry the boolean outcome. sequence defaults to max+1 within
 * the checksheet when omitted.
 */
@Tag(name = "pm-checklist-items")
@Validated
@RestController
@RequestMapping("/api/v1/pm-checklist-items")
public class PmChecklistItemController {

  private final PmChecklistService checklist;

  public PmChecklistItemController(PmChecklistService checklist) {
    this.checklist = checklist;
  }

  @Operation(operationId = "createPmChecklistItem", summary = "Create a checklist item on an unapproved checksheet revision")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Item created",
          content = @Content(schema = @Schema(implementation = ChecklistItemView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Checksheet, category or calibration instrument not found"),
      @ApiResponse(responseCode = "409", description = "Checksheet revision is approved (immutable)")
  })
  @PostMapping
  public ResponseEntity<ChecklistItemView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateChecklistItemRequest request) {
    var view = checklist.createItem(user, new CreateItemCommand(request.checksheetId(),
        request.categoryId(), request.sequence(), request.parameterText(),
        request.checkMethod(), request.inputType(), request.unit(), request.lsl(),
        request.nominal(), request.usl(), request.isCriticalFlag(),
        request.referenceDocument(), request.calibrationInstrumentId()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/pm-checklist-items/" + view.id()))
        .body(toView(view));
  }

  @Operation(operationId = "listPmChecklistItems", summary = "List a checksheet's items (sequence asc; optional categoryId filter)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Items returned",
          content = @Content(schema = @Schema(implementation = ChecklistItemView.class))),
      @ApiResponse(responseCode = "400", description = "Missing or invalid checksheetId"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "PM checksheet not found")
  })
  @GetMapping
  public List<ChecklistItemView> list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID checksheetId,
      @RequestParam(required = false) UUID categoryId) {
    return checklist.listItems(user, checksheetId, categoryId).stream()
        .map(PmChecklistItemController::toView).toList();
  }

  @Operation(operationId = "getPmChecklistItem", summary = "Get a checklist item")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Item returned",
          content = @Content(schema = @Schema(implementation = ChecklistItemView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Item or checksheet not found")
  })
  @GetMapping("/{id}")
  public ChecklistItemView get(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return toView(checklist.getItem(user, id));
  }

  @Operation(operationId = "updatePmChecklistItem", summary = "Edit a checklist item (unapproved revision only)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Item updated",
          content = @Content(schema = @Schema(implementation = ChecklistItemView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Item, checksheet, category or calibration instrument not found"),
      @ApiResponse(responseCode = "409", description = "Checksheet revision is approved (immutable)")
  })
  @PutMapping("/{id}")
  public ChecklistItemView update(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody UpdateChecklistItemRequest request) {
    return toView(checklist.updateItem(user, id, new UpdateItemCommand(request.categoryId(),
        request.sequence(), request.parameterText(), request.checkMethod(), request.inputType(),
        request.unit(), request.lsl(), request.nominal(), request.usl(),
        request.isCriticalFlag(), request.referenceDocument(),
        request.calibrationInstrumentId())));
  }

  @Operation(operationId = "deletePmChecklistItem", summary = "Delete a checklist item")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Item deleted"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Item or checksheet not found"),
      @ApiResponse(responseCode = "409", description = "Checksheet revision is approved (immutable)")
  })
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    checklist.deleteItem(user, id);
    return ResponseEntity.noContent().build();
  }

  private static ChecklistItemView toView(
      com.syncro.maintenance.preventive.application.PmChecklistService.ItemView v) {
    return new ChecklistItemView(v.id(), v.checksheetId(), v.categoryId(), v.sequence(),
        v.parameterText(), v.checkMethod(), v.inputType(), v.unit(), v.lsl(), v.nominal(),
        v.usl(), v.criticalFlag(), v.referenceDocument(), v.calibrationInstrumentId(),
        v.createdAt(), v.updatedAt());
  }
}
