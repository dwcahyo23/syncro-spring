package com.syncro.org.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.org.application.PlantWorkingCalendarService;
import com.syncro.org.application.PlantWorkingCalendarService.CalendarListView;
import com.syncro.org.application.PlantWorkingCalendarService.CalendarView;
import com.syncro.org.application.PlantWorkingCalendarService.CreateCalendarCommand;
import com.syncro.org.domain.WorkweekMode;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plant-working-calendars")
public class PlantWorkingCalendarController {

  private final PlantWorkingCalendarService calendars;

  public PlantWorkingCalendarController(PlantWorkingCalendarService calendars) {
    this.calendars = calendars;
  }

  @Operation(operationId = "listPlantWorkingCalendars", summary = "List working calendars for a plant")
  @GetMapping
  public CalendarListView list(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam UUID plantId) {
    return calendars.list(user, plantId);
  }

  @Operation(operationId = "getPlantWorkingCalendar", summary = "Get a working calendar with dates")
  @GetMapping("/{calendarId}")
  public CalendarView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID calendarId) {
    return calendars.get(user, calendarId);
  }

  @Operation(operationId = "createPlantWorkingCalendar", summary = "Create a working calendar")
  @PostMapping
  public ResponseEntity<CalendarView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestBody CreateCalendarRequest request) {
    var created = calendars.create(user,
        new CreateCalendarCommand(request.plantId(), request.year(), request.workweekMode()));
    return ResponseEntity.created(URI.create("/api/v1/plant-working-calendars/" + created.id())).body(created);
  }

  @Operation(operationId = "addPlantWorkingCalendarDate", summary = "Add a date exception")
  @PostMapping("/{calendarId}/dates")
  public CalendarView addDate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID calendarId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) @Size(max = 255) String reason) {
    return calendars.addDate(user, calendarId, date, reason);
  }

  @Operation(operationId = "removePlantWorkingCalendarDate", summary = "Remove a date exception")
  @DeleteMapping("/{calendarId}/dates/{dateId}")
  public ResponseEntity<Void> removeDate(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID calendarId, @PathVariable UUID dateId) {
    calendars.removeDate(user, calendarId, dateId);
    return ResponseEntity.noContent().build();
  }

  public record CreateCalendarRequest(
      @NotNull UUID plantId,
      @Min(2000) @Max(2999) int year,
      @NotNull WorkweekMode workweekMode) {
  }
}