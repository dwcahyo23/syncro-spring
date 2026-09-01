package com.syncro.org.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.org.domain.WorkweekMode;
import com.syncro.org.infrastructure.db.PlantWorkingCalendarDateEntity;
import com.syncro.org.infrastructure.db.PlantWorkingCalendarDateRepository;
import com.syncro.org.infrastructure.db.PlantWorkingCalendarEntity;
import com.syncro.org.infrastructure.db.PlantWorkingCalendarRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plant working calendars (blueprint A12, story 16-4): one calendar per
 * (plant, year) with date-level exceptions. Mutations require
 * SUPER_ADMIN/MANAGER_MAINTENANCE with plant access; reads any authenticated
 * user. Audit-logged as PLANT_WORKING_CALENDAR.
 */
@Service
public class PlantWorkingCalendarService {

  private static final String CALENDAR_UNIQUE_CONSTRAINT = "uq_plant_working_calendars_plant_year";
  private static final String DATE_UNIQUE_CONSTRAINT = "uq_plant_working_calendar_dates_calendar_date";

  private final PlantWorkingCalendarRepository calendars;
  private final PlantWorkingCalendarDateRepository dates;
  private final PlantRepository plants;
  private final PlantScopeService plantScopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public PlantWorkingCalendarService(PlantWorkingCalendarRepository calendars,
      PlantWorkingCalendarDateRepository dates, PlantRepository plants,
      PlantScopeService plantScopes, AuditLogWriter auditLog, Clock clock) {
    this.calendars = calendars;
    this.dates = dates;
    this.plants = plants;
    this.plantScopes = plantScopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public CalendarListView list(AuthenticatedUser user, UUID plantId) {
    requirePlantOrSuperAdmin(user, plantId);
    var all = calendars.findAll().stream()
        .filter(c -> c.getPlantId().equals(plantId))
        .map(c -> toView(c, dates.findByWorkingCalendarIdOrderByDateAsc(c.getId())))
        .toList();
    return new CalendarListView(all);
  }

  @Transactional(readOnly = true)
  public CalendarView get(AuthenticatedUser user, UUID calendarId) {
    var calendar = calendars.findById(calendarId).orElseThrow(CalendarNotFoundException::new);
    requirePlantOrSuperAdmin(user, calendar.getPlantId());
    return toView(calendar, dates.findByWorkingCalendarIdOrderByDateAsc(calendarId));
  }

  @Transactional
  public CalendarView create(AuthenticatedUser user, CreateCalendarCommand command) {
    requireMutationRole(user);
    requirePlantOrSuperAdmin(user, command.plantId());
    if (!plants.existsById(command.plantId())) {
      throw new PlantNotFoundForCalendarException();
    }
    var existing = calendars.findByPlantIdAndYear(command.plantId(), command.year());
    if (existing.isPresent()) {
      throw new DuplicateCalendarException();
    }
    var now = Instant.now(clock);
    var saved = saveCalendar(new PlantWorkingCalendarEntity(UUID.randomUUID(),
        command.plantId(), command.year(), command.workweekMode(), now, now));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT_WORKING_CALENDAR,
        saved.getId(), String.valueOf(saved.getYear()), saved.getPlantId(), null,
        calendarAuditValues(saved, List.of()), null));
    return toView(saved, List.of());
  }

  @Transactional
  public CalendarView addDate(AuthenticatedUser user, UUID calendarId, LocalDate date, String reason) {
    requireMutationRole(user);
    var calendar = calendars.findById(calendarId).orElseThrow(CalendarNotFoundException::new);
    requirePlantOrSuperAdmin(user, calendar.getPlantId());
    var now = Instant.now(clock);
    var existing = dates.findAll().stream()
        .filter(d -> d.getWorkingCalendarId().equals(calendarId) && d.getDate().equals(date))
        .findFirst();
    if (existing.isPresent()) {
      throw new DuplicateCalendarDateException();
    }
    var saved = saveDate(new PlantWorkingCalendarDateEntity(UUID.randomUUID(), calendarId, date,
        reason, now, now));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT_WORKING_CALENDAR,
        saved.getId(), date.toString(), calendar.getPlantId(), null,
        Map.of("date", date.toString(), "reason", reason), null));
    return get(user, calendarId);
  }

  @Transactional
  public void removeDate(AuthenticatedUser user, UUID calendarId, UUID dateId) {
    requireMutationRole(user);
    var calendar = calendars.findById(calendarId).orElseThrow(CalendarNotFoundException::new);
    requirePlantOrSuperAdmin(user, calendar.getPlantId());
    var dateEntity = dates.findById(dateId).orElseThrow(CalendarDateNotFoundException::new);
    if (!dateEntity.getWorkingCalendarId().equals(calendarId)) {
      throw new CalendarDateNotFoundException();
    }
    dates.delete(dateEntity);
    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.PLANT_WORKING_CALENDAR,
        dateId, dateEntity.getDate().toString(), calendar.getPlantId(), null, null, null));
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN
        && user.applicationRole() != ApplicationRole.MANAGER_MAINTENANCE) {
      throw new CalendarMutationForbiddenException();
    }
  }

  private void requirePlantOrSuperAdmin(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    }
  }

  private PlantWorkingCalendarEntity saveCalendar(PlantWorkingCalendarEntity calendar) {
    try {
      return calendars.saveAndFlush(calendar);
    } catch (DataIntegrityViolationException exception) {
      if (String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase()
          .contains(CALENDAR_UNIQUE_CONSTRAINT)) {
        throw new DuplicateCalendarException();
      }
      throw new CalendarDataIntegrityException();
    }
  }

  private PlantWorkingCalendarDateEntity saveDate(PlantWorkingCalendarDateEntity date) {
    try {
      return dates.saveAndFlush(date);
    } catch (DataIntegrityViolationException exception) {
      if (String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase()
          .contains(DATE_UNIQUE_CONSTRAINT)) {
        throw new DuplicateCalendarDateException();
      }
      throw new CalendarDataIntegrityException();
    }
  }

  private CalendarView toView(PlantWorkingCalendarEntity calendar,
      List<PlantWorkingCalendarDateEntity> dateEntities) {
    var plant = plants.findById(calendar.getPlantId()).orElse(null);
    return new CalendarView(
        calendar.getId(),
        calendar.getPlantId(),
        plant == null ? null : plant.getCode(),
        plant == null ? null : plant.getName(),
        calendar.getYear(),
        calendar.getWorkweekMode(),
        dateEntities.stream().map(d -> new CalendarDateView(d.getId(), d.getDate(), d.getReason())).toList());
  }

  private static Map<String, Object> calendarAuditValues(PlantWorkingCalendarEntity calendar,
      List<PlantWorkingCalendarDateEntity> dateEntities) {
    var m = new LinkedHashMap<String, Object>();
    m.put("plantId", calendar.getPlantId().toString());
    m.put("year", calendar.getYear());
    m.put("workweekMode", calendar.getWorkweekMode().name());
    m.put("dateCount", dateEntities.size());
    return m;
  }

  public record CreateCalendarCommand(UUID plantId, int year, WorkweekMode workweekMode) {
  }

  public record CalendarDateView(UUID id, LocalDate date, String reason) {
  }

  public record CalendarView(UUID id, UUID plantId, String plantCode, String plantName, int year,
      WorkweekMode workweekMode, List<CalendarDateView> dates) {
  }

  public record CalendarListView(List<CalendarView> items) {
  }

  public static class CalendarMutationForbiddenException extends RuntimeException {
  }

  public static class CalendarNotFoundException extends RuntimeException {
  }

  public static class CalendarDateNotFoundException extends RuntimeException {
  }

  public static class DuplicateCalendarException extends RuntimeException {
  }

  public static class DuplicateCalendarDateException extends RuntimeException {
  }

  public static class CalendarDataIntegrityException extends RuntimeException {
  }

  public static class PlantNotFoundForCalendarException extends RuntimeException {
  }
}