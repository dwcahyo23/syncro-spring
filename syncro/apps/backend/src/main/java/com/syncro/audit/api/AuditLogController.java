package com.syncro.audit.api;

import com.syncro.audit.application.AuditLogService;
import com.syncro.audit.application.AuditLogService.AuditLogQuery;
import com.syncro.audit.api.AuditLogDtos.AuditLogListResponse;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-log")
public class AuditLogController {
  private final AuditLogService service;

  public AuditLogController(AuditLogService service) {
    this.service = service;
  }

  @Operation(operationId = "listGlobalAuditLogEntries", summary = "List global audit log entries")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Audit log entries returned"),
      @ApiResponse(responseCode = "400", description = "Invalid filter"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @GetMapping
  public AuditLogListResponse list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) AuditEntityType entityType,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) UUID entityId,
      @RequestParam(required = false) UUID plantId,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return service.list(user, new AuditLogQuery(entityType, actor, plantId, from, to, page, size, sort));
  }
}
