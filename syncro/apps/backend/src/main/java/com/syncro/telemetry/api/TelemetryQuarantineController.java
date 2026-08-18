package com.syncro.telemetry.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.telemetry.infrastructure.TelemetryQuarantineEntity;
import com.syncro.telemetry.infrastructure.TelemetryQuarantineRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "telemetry-quarantine")
@RestController
@RequestMapping("/api/v1/telemetry/quarantine")
public class TelemetryQuarantineController {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_PAGE = 10_000;

  private final TelemetryQuarantineRepository repository;

  public TelemetryQuarantineController(TelemetryQuarantineRepository repository) {
    this.repository = repository;
  }

  @Operation(operationId = "listTelemetryQuarantine", summary = "List telemetry quarantine entries")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Quarantine entries returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only")
  })
  @GetMapping
  public Page<QuarantineEntryView> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    int effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int effectivePage = Math.min(Math.max(page, 0), MAX_PAGE);
    var pageable = PageRequest.of(effectivePage, effectiveSize, Sort.by("receivedAt").descending());
    return repository.findAllByOrderByReceivedAtDesc(pageable)
        .map(this::toView);
  }

  private QuarantineEntryView toView(TelemetryQuarantineEntity entity) {
    return new QuarantineEntryView(
        entity.getId(),
        entity.getTraceId(),
        entity.getTopic(),
        entity.getRawPayload(),
        entity.getRejectionReason(),
        entity.getRejectionField(),
        entity.getReceivedAt()
    );
  }
}
