package com.syncro.maintenance.preventive.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.preventive.api.PreventiveDtos.MachineShiftConfigView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PreventiveScheduleView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ShiftWindowView;
import com.syncro.maintenance.preventive.application.PreventiveScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/preventive-schedules")
public class PreventiveScheduleController {

  private final PreventiveScheduleService schedules;

  public PreventiveScheduleController(PreventiveScheduleService schedules) {
    this.schedules = schedules;
  }

  @Operation(operationId = "listPreventiveSchedules", summary = "Scope-filtered preventive schedules with derived status and shift context")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Schedules returned",
          content = @Content(schema = @Schema(implementation = PreventiveScheduleView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public List<PreventiveScheduleView> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return schedules.list(user).stream().map(PreventiveScheduleController::toView).toList();
  }

  private static PreventiveScheduleView toView(PreventiveScheduleService.ScheduleView sv) {
    var shiftView = sv.shiftConfig() == null ? null : new MachineShiftConfigView(sv.shiftConfig().source(),
        sv.shiftConfig().inheritedFromGroup(),
        sv.shiftConfig().shifts().stream()
            .map(s -> new ShiftWindowView(s.shiftNumber(), s.startTime().toString(), s.endTime().toString()))
            .toList());
    return new PreventiveScheduleView(sv.id(), sv.programId(), sv.machineId(), sv.dueDate(), sv.status(),
        sv.derivedStatus(), sv.completedAt(), sv.performedBy(), sv.category(), sv.scheduleType(), shiftView,
        sv.today());
  }
}