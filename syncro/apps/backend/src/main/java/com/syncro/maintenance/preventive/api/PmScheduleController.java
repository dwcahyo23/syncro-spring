package com.syncro.maintenance.preventive.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.preventive.api.PreventiveDtos.CreateScheduleRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ScheduleDateTransitionRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ScheduleDateView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ScheduleView;
import com.syncro.maintenance.preventive.application.PmScheduleService;
import com.syncro.maintenance.preventive.application.PmScheduleService.CreateScheduleCommand;
import com.syncro.maintenance.preventive.domain.PmScheduleStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/** PM yearly schedule & schedule-date API (story 19-3, blueprint F5). */
@Tag(name = "pm-schedules")
@Validated
@RestController
@RequestMapping("/api/v1/pm-schedules")
public class PmScheduleController {

  private final PmScheduleService schedules;

  public PmScheduleController(PmScheduleService schedules) {
    this.schedules = schedules;
  }

  @Operation(operationId = "createPmSchedule", summary = "Create a yearly schedule (DRAFT) with materialized dates")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Schedule created",
          content = @Content(schema = @Schema(implementation = ScheduleView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine, checksheet or frequency not found"),
      @ApiResponse(responseCode = "409", description = "Schedule already exists, invalid checksheet state, or invalid transition")
  })
  @PostMapping
  public ResponseEntity<ScheduleView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateScheduleRequest request) {
    var view = schedules.create(user, new CreateScheduleCommand(
        request.plantId(), request.machineId(), request.checksheetId(), request.year()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/pm-schedules/" + view.id()))
        .body(toView(view));
  }

  @Operation(operationId = "listPmSchedules", summary = "List schedules (filter by year, status)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Schedules returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public List<ScheduleView> list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) @Min(2000) @Max(2999) Integer year,
      @RequestParam(required = false) PmScheduleStatus status) {
    return schedules.list(user, year, status).stream()
        .map(PmScheduleController::toView).toList();
  }

  @Operation(operationId = "getPmSchedule", summary = "Get a schedule with its dates")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Schedule returned",
          content = @Content(schema = @Schema(implementation = ScheduleView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Schedule not found")
  })
  @GetMapping("/{id}")
  public ScheduleView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return toView(schedules.get(user, id));
  }

  @Operation(operationId = "submitPmSchedule", summary = "Submit a DRAFT schedule for SPV approval")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Schedule submitted",
          content = @Content(schema = @Schema(implementation = ScheduleView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Schedule not found"),
      @ApiResponse(responseCode = "409", description = "Invalid transition")
  })
  @PostMapping("/{id}/submit")
  public ScheduleView submit(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return toView(schedules.submit(user, id));
  }

  @Operation(operationId = "approveSpvPmSchedule", summary = "Approve schedule (SPV step)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Schedule approved by SPV",
          content = @Content(schema = @Schema(implementation = ScheduleView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Schedule not found"),
      @ApiResponse(responseCode = "409", description = "Invalid transition")
  })
  @PostMapping("/{id}/approve-spv")
  public ScheduleView approveSpv(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return toView(schedules.approveSpv(user, id));
  }

  @Operation(operationId = "approveProdPmSchedule", summary = "Approve schedule (production step)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Schedule approved by production",
          content = @Content(schema = @Schema(implementation = ScheduleView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Schedule not found"),
      @ApiResponse(responseCode = "409", description = "Invalid transition")
  })
  @PostMapping("/{id}/approve-prod")
  public ScheduleView approveProd(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return toView(schedules.approveProd(user, id));
  }

  @Operation(operationId = "activatePmSchedule", summary = "Activate an approved schedule")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Schedule activated",
          content = @Content(schema = @Schema(implementation = ScheduleView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Schedule not found"),
      @ApiResponse(responseCode = "409", description = "Invalid transition")
  })
  @PostMapping("/{id}/activate")
  public ScheduleView activate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return toView(schedules.activate(user, id));
  }

  @Operation(operationId = "transitionPmScheduleDate", summary = "Transition a schedule date to SCHEDULED/EXECUTED/MISSED/RESCHEDULED")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Date transitioned",
          content = @Content(schema = @Schema(implementation = ScheduleDateView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Schedule or date not found"),
      @ApiResponse(responseCode = "409", description = "Invalid date transition")
  })
  @PostMapping("/{scheduleId}/dates/{dateId}/transition")
  public ScheduleDateView transitionDate(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID scheduleId, @PathVariable UUID dateId,
      @Valid @RequestBody ScheduleDateTransitionRequest request) {
    return toDateView(schedules.transitionDate(user, scheduleId, dateId, request.status()));
  }

  private static ScheduleView toView(
      com.syncro.maintenance.preventive.application.PmScheduleService.ScheduleView v) {
    return new ScheduleView(v.id(), v.plantId(), v.machineId(), v.checksheetId(),
        v.checksheetRevisionNo(), v.frequencyId(), v.frequencyCode(), v.frequencyName(),
        v.year(), v.status(), v.submittedBy(), v.submittedAt(), v.approvedBySpv(),
        v.approvedAtSpv(), v.approvedByProd(), v.approvedAtProd(), v.warnings(),
        v.createdAt(), v.updatedAt(),
        v.dates().stream().map(PmScheduleController::toDateView).toList());
  }

  private static ScheduleDateView toDateView(
      com.syncro.maintenance.preventive.application.PmScheduleService.ScheduleDateView v) {
    return new ScheduleDateView(v.id(), v.scheduleId(), v.plannedDate(), v.status(),
        v.createdAt(), v.updatedAt());
  }
}
