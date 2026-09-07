package com.syncro.compliance.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.compliance.application.EquipmentChangeNoticeService;
import com.syncro.compliance.domain.EcnStatus;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Equipment change notice REST surface (story 21-2, blueprint H4):
 * {@code /api/v1/equipment-change-notices} CRUD plus the forward-only approval
 * lifecycle {@code /{id}/submit|approve|execute|close}. Controllers only
 * bind/validate and delegate — role gates, transitions, scope filtering, and
 * audit live in {@link EquipmentChangeNoticeService} (spine API rule).
 */
@RestController
@RequestMapping("/api/v1/equipment-change-notices")
public class EquipmentChangeNoticeController {

  private final EquipmentChangeNoticeService notices;

  public EquipmentChangeNoticeController(EquipmentChangeNoticeService notices) {
    this.notices = notices;
  }

  @Operation(operationId = "listEquipmentChangeNotices",
      summary = "List ECNs visible in the caller's machine scope (optional ?status= filter)")
  @GetMapping
  public List<EquipmentChangeNoticeService.EcnView> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) EcnStatus status) {
    return notices.list(user, status);
  }

  @Operation(operationId = "createEquipmentChangeNotice", summary = "Create an ECN (DRAFT)")
  @PostMapping
  public ResponseEntity<EquipmentChangeNoticeService.EcnView> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody ComplianceDtos.CreateEcnRequest request) {
    var view = notices.create(user, new EquipmentChangeNoticeService.CreateEcnCommand(
        request.ecnNumber(), request.machineId(), request.title(), request.description(),
        request.changeType(), request.justification(), request.beforePhotoUrl()));
    return ResponseEntity.created(URI.create("/api/v1/equipment-change-notices/" + view.id()))
        .body(view);
  }

  @Operation(operationId = "getEquipmentChangeNotice",
      summary = "Read one ECN (machine-scope filtered)")
  @GetMapping("/{id}")
  public EquipmentChangeNoticeService.EcnView get(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return notices.get(user, id);
  }

  @Operation(operationId = "updateEquipmentChangeNotice",
      summary = "Partial update of an ECN (number and machine are immutable)")
  @PatchMapping("/{id}")
  public EquipmentChangeNoticeService.EcnView update(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody ComplianceDtos.UpdateEcnRequest request) {
    return notices.update(user, id, new EquipmentChangeNoticeService.UpdateEcnCommand(
        request.title(), request.description(), request.changeType(), request.justification(),
        request.beforePhotoUrl()));
  }

  @Operation(operationId = "submitEquipmentChangeNotice",
      summary = "Submit for review (DRAFT→UNDER_REVIEW, six-role set)")
  @PostMapping("/{id}/submit")
  public EquipmentChangeNoticeService.EcnView submit(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return notices.submit(user, id);
  }

  @Operation(operationId = "approveEquipmentChangeNotice",
      summary = "Approve (UNDER_REVIEW→APPROVED, SUPER_ADMIN/MANAGER_MAINTENANCE)")
  @PostMapping("/{id}/approve")
  public EquipmentChangeNoticeService.EcnView approve(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody(required = false) ComplianceDtos.ApproveEcnRequest request) {
    return notices.approve(user, id, new EquipmentChangeNoticeService.ApproveEcnCommand(
        request != null ? request.effectiveDate() : null));
  }

  @Operation(operationId = "executeEquipmentChangeNotice",
      summary = "Execute (APPROVED→EXECUTED) with optional workorder evidence link")
  @PostMapping("/{id}/execute")
  public EquipmentChangeNoticeService.EcnView execute(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody(required = false) ComplianceDtos.ExecuteEcnRequest request) {
    return notices.execute(user, id, new EquipmentChangeNoticeService.ExecuteEcnCommand(
        request != null ? request.executedWoId() : null,
        request != null ? request.afterPhotoUrl() : null));
  }

  @Operation(operationId = "closeEquipmentChangeNotice",
      summary = "Close (EXECUTED→CLOSED, SUPER_ADMIN/MANAGER_MAINTENANCE)")
  @PostMapping("/{id}/close")
  public EquipmentChangeNoticeService.EcnView close(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return notices.close(user, id);
  }
}
