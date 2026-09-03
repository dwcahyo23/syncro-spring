package com.syncro.maintenance.preventive.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ChecklistCategoryView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.CreateChecklistCategoryRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.UpdateChecklistCategoryRequest;
import com.syncro.maintenance.preventive.application.PmChecklistService;
import com.syncro.maintenance.preventive.application.PmChecklistService.CreateCategoryCommand;
import com.syncro.maintenance.preventive.application.PmChecklistService.UpdateCategoryCommand;
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
 * PM checklist category API (story 19-2, blueprint F4). Categories are defined and
 * edited against an UNAPPROVED checksheet revision — approved revisions are frozen
 * (409 INVALID_CHECKSHEET_TRANSITION); corrections go through 19-1's revise flow.
 */
@Tag(name = "pm-checklist-categories")
@Validated
@RestController
@RequestMapping("/api/v1/pm-checklist-categories")
public class PmChecklistCategoryController {

  private final PmChecklistService checklist;

  public PmChecklistCategoryController(PmChecklistService checklist) {
    this.checklist = checklist;
  }

  @Operation(operationId = "createPmChecklistCategory", summary = "Create a checklist category on an unapproved checksheet revision")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Category created",
          content = @Content(schema = @Schema(implementation = ChecklistCategoryView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "PM checksheet not found"),
      @ApiResponse(responseCode = "409", description = "Checksheet revision is approved (immutable)")
  })
  @PostMapping
  public ResponseEntity<ChecklistCategoryView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateChecklistCategoryRequest request) {
    var view = checklist.createCategory(user, new CreateCategoryCommand(request.checksheetId(),
        request.name(), request.sortOrder()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/pm-checklist-categories/" + view.id()))
        .body(toView(view));
  }

  @Operation(operationId = "listPmChecklistCategories", summary = "List a checksheet's categories (sort_order asc)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Categories returned",
          content = @Content(schema = @Schema(implementation = ChecklistCategoryView.class))),
      @ApiResponse(responseCode = "400", description = "Missing or invalid checksheetId"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "PM checksheet not found")
  })
  @GetMapping
  public List<ChecklistCategoryView> list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID checksheetId) {
    return checklist.listCategories(user, checksheetId).stream()
        .map(PmChecklistCategoryController::toView).toList();
  }

  @Operation(operationId = "updatePmChecklistCategory", summary = "Rename/reorder a category (unapproved revision only)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Category updated",
          content = @Content(schema = @Schema(implementation = ChecklistCategoryView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Category or checksheet not found"),
      @ApiResponse(responseCode = "409", description = "Checksheet revision is approved (immutable)")
  })
  @PutMapping("/{id}")
  public ChecklistCategoryView update(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody UpdateChecklistCategoryRequest request) {
    return toView(checklist.updateCategory(user, id,
        new UpdateCategoryCommand(request.name(), request.sortOrder())));
  }

  @Operation(operationId = "deletePmChecklistCategory", summary = "Delete a category (its items are orphaned, categoryId nulled)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Category deleted"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Category or checksheet not found"),
      @ApiResponse(responseCode = "409", description = "Checksheet revision is approved (immutable)")
  })
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    checklist.deleteCategory(user, id);
    return ResponseEntity.noContent().build();
  }

  private static ChecklistCategoryView toView(
      com.syncro.maintenance.preventive.application.PmChecklistService.CategoryView v) {
    return new ChecklistCategoryView(v.id(), v.checksheetId(), v.name(), v.sortOrder(),
        v.createdAt(), v.updatedAt());
  }
}
