package com.syncro.org.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.org.api.DepartmentDtos.CreateDepartmentRequest;
import com.syncro.org.api.DepartmentDtos.DepartmentListResponse;
import com.syncro.org.api.DepartmentDtos.DepartmentView;
import com.syncro.org.api.DepartmentDtos.SetDepartmentMembersRequest;
import com.syncro.org.api.DepartmentDtos.UpdateDepartmentRequest;
import com.syncro.org.application.DepartmentService;
import com.syncro.org.application.DepartmentService.CreateDepartmentCommand;
import com.syncro.org.application.DepartmentService.UpdateDepartmentCommand;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/departments")
public class DepartmentController {

  private final DepartmentService departments;

  public DepartmentController(DepartmentService departments) {
    this.departments = departments;
  }

  @Operation(operationId = "listDepartments", summary = "List departments for a plant")
  @GetMapping
  public DepartmentListResponse list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID plantId,
      @RequestParam(defaultValue = "false") boolean includeInactive) {
    var result = departments.list(user, plantId, includeInactive);
    return new DepartmentListResponse(result.items().stream().map(this::toDto).toList());
  }

  @Operation(operationId = "getDepartment", summary = "Get a department")
  @GetMapping("/{departmentId}")
  public DepartmentView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID departmentId) {
    return toDto(departments.get(user, departmentId));
  }

  @Operation(operationId = "createDepartment", summary = "Create a department")
  @PostMapping
  public ResponseEntity<DepartmentView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateDepartmentRequest request) {
    var created = toDto(departments.create(user,
        new CreateDepartmentCommand(request.plantId(), request.name(), request.spvId(), request.mgId())));
    return ResponseEntity.created(URI.create("/api/v1/departments/" + created.id())).body(created);
  }

  @Operation(operationId = "updateDepartment", summary = "Update a department")
  @PutMapping("/{departmentId}")
  public DepartmentView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID departmentId,
      @Valid @RequestBody UpdateDepartmentRequest request) {
    return toDto(departments.update(user, departmentId,
        new UpdateDepartmentCommand(request.name(), request.spvId(), request.mgId(), request.active())));
  }

  /** Deactivate-style delete: rejected while members exist, otherwise soft-inactive. */
  @Operation(operationId = "deleteDepartment", summary = "Deactivate a department")
  @DeleteMapping("/{departmentId}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID departmentId) {
    departments.delete(user, departmentId);
    return ResponseEntity.noContent().build();
  }

  @Operation(operationId = "setDepartmentMembers", summary = "Replace department members")
  @PutMapping("/{departmentId}/members")
  public DepartmentView replaceMembers(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID departmentId, @Valid @RequestBody SetDepartmentMembersRequest request) {
    return toDto(departments.replaceMembers(user, departmentId, request.userIds()));
  }

  private DepartmentView toDto(DepartmentService.DepartmentView department) {
    return new DepartmentView(
        department.id(),
        department.plantId(),
        department.plantCode(),
        department.plantName(),
        department.name(),
        department.spvId(),
        department.mgId(),
        department.active(),
        department.memberCount(),
        department.createdAt(),
        department.updatedAt());
  }
}
