package com.syncro.sync.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.sync.api.SyncStatusDtos.SyncQuarantineDetailView;
import com.syncro.sync.api.SyncStatusDtos.SyncQuarantineListRow;
import com.syncro.sync.application.SyncStatusService;
import com.syncro.sync.application.SyncStatusView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for sync observability (story 13-3, FR-153). Exposes sync run status,
 * quarantine list, and quarantine detail for the health dashboard — SUPER_ADMIN only
 * (the health dashboard is SUPER_ADMIN-only in the UI; the endpoint enforces the same
 * role server-side, following the {@code NotificationWorkerStatusController} pattern).
 *
 * <p>The health dashboard itself is Epic 14; this is the API-only layer for now.
 */
@Tag(name = "sync-status")
@RestController
@RequestMapping("/api/v1/sync")
public class SyncStatusController {

  private final SyncStatusService statusService;

  public SyncStatusController(SyncStatusService statusService) {
    this.statusService = statusService;
  }

  @Operation(operationId = "getSyncStatus", summary = "Sync run status and quarantine summary")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Sync status returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only")
  })
  @GetMapping("/status")
  public SyncStatusView status(@AuthenticationPrincipal AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return statusService.getStatus();
  }

  @Operation(operationId = "listSyncQuarantine", summary = "Paginated quarantine list (no raw_payload)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Paginated quarantine list returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only")
  })
  @GetMapping("/quarantine")
  public Page<SyncQuarantineListRow> listQuarantine(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
      Pageable pageable) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return statusService.listQuarantine(pageable);
  }

  @Operation(operationId = "getSyncQuarantineDetail", summary = "Quarantine detail with raw_payload")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Quarantine detail returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only"),
      @ApiResponse(responseCode = "404", description = "Quarantine row not found")
  })
  @GetMapping("/quarantine/{id}")
  public SyncQuarantineDetailView getQuarantine(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable java.util.UUID id) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return statusService.getQuarantine(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Quarantine row not found"));
  }
}