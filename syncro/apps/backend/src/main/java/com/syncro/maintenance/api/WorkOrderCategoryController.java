package com.syncro.maintenance.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.api.WorkOrderCategoryDtos.CreateWorkOrderCategoryRequest;
import com.syncro.maintenance.api.WorkOrderCategoryDtos.UpdateWorkOrderCategoryRequest;
import com.syncro.maintenance.api.WorkOrderCategoryDtos.WorkOrderCategoryView;
import com.syncro.maintenance.application.WorkOrderCategoryService;
import com.syncro.maintenance.application.WorkOrderCategoryService.CreateWorkOrderCategoryCommand;
import com.syncro.maintenance.application.WorkOrderCategoryService.UpdateWorkOrderCategoryCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/work-order-categories")
public class WorkOrderCategoryController {

  private final WorkOrderCategoryService categories;

  public WorkOrderCategoryController(WorkOrderCategoryService categories) {
    this.categories = categories;
  }

  @Operation(operationId = "listWorkOrderCategories", summary = "List work-order categories")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Categories returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public List<WorkOrderCategoryView> list() {
    return categories.list().stream().map(this::toDto).toList();
  }

  @Operation(operationId = "createWorkOrderCategory", summary = "Create a work-order category")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Category created", content = @Content(schema = @Schema(implementation = WorkOrderCategoryView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or malformed JSON"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "409", description = "Duplicate category code")
  })
  @PostMapping
  public ResponseEntity<WorkOrderCategoryView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateWorkOrderCategoryRequest request) {
    var created = toDto(categories.create(user,
        new CreateWorkOrderCategoryCommand(request.code(), request.label(), request.targetResponseMinutes())));
    return ResponseEntity.created(URI.create("/api/v1/work-order-categories/" + created.code())).body(created);
  }

  @Operation(operationId = "updateWorkOrderCategory", summary = "Update a work-order category by code")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Category updated"),
      @ApiResponse(responseCode = "400", description = "Validation or malformed JSON"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Category not found"),
      @ApiResponse(responseCode = "409", description = "Duplicate category code")
  })
  @PutMapping("/{code}")
  public WorkOrderCategoryView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
      @Valid @RequestBody UpdateWorkOrderCategoryRequest request) {
    return toDto(categories.update(user, code,
        new UpdateWorkOrderCategoryCommand(request.code(), request.label(), request.targetResponseMinutes())));
  }

  private WorkOrderCategoryView toDto(com.syncro.maintenance.domain.workorder.WorkOrderCategory category) {
    return new WorkOrderCategoryView(category.code(), category.label(), category.targetResponseMinutes());
  }
}
