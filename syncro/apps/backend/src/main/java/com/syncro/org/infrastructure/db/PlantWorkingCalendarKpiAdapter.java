package com.syncro.org.infrastructure.db;

import com.syncro.kpi.application.KpiCalendarReader;
import com.syncro.org.domain.WorkweekMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for the KPI working-calendar port (story 20-1, blueprint A12). Lives in
 * the org module — calendar data is owned by org; the kpi module only asks for
 * business minutes in an interval. v1 counts whole calendar days as working/non-working
 * per the plant's workweek mode minus exception dates (spec design note: shift-aware
 * minute math is a future refinement). A plant without a calendar for a year falls
 * back to FIVE_DAY — never zero, which would fabricate an infinite MTTR.
 */
@Component
public class PlantWorkingCalendarKpiAdapter implements KpiCalendarReader {

  private static final long DAY_MINUTES = Duration.ofDays(1).toMinutes();

  private final PlantWorkingCalendarRepository calendars;
  private final PlantWorkingCalendarDateRepository calendarDates;

  public PlantWorkingCalendarKpiAdapter(PlantWorkingCalendarRepository calendars,
      PlantWorkingCalendarDateRepository calendarDates) {
    this.calendars = calendars;
    this.calendarDates = calendarDates;
  }

  @Override
  @Transactional(readOnly = true)
  public long workingMinutes(UUID plantId, Instant from, Instant to) {
    if (!to.isAfter(from)) {
      return 0L;
    }
    var firstDay = LocalDate.ofInstant(from, ZoneOffset.UTC);
    var lastDay = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC);
    // One calendar + exception lookup per year in range (a month window spans ≤ 2 years).
    var yearState = new HashMap<Integer, YearState>();
    long total = 0L;
    for (var day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
      var state = yearState.computeIfAbsent(day.getYear(), y -> loadYear(plantId, y));
      if (state.isWorking(day)) {
        total += DAY_MINUTES;
      }
    }
    return total;
  }

  private YearState loadYear(UUID plantId, int year) {
    var calendar = calendars.findByPlantIdAndYear(plantId, year);
    var mode = calendar.map(PlantWorkingCalendarEntity::getWorkweekMode).orElse(WorkweekMode.FIVE_DAY);
    Set<LocalDate> exceptions = calendar
        .map(c -> calendarDates.findByWorkingCalendarIdOrderByDateAsc(c.getId()).stream()
            .map(PlantWorkingCalendarDateEntity::getDate)
            .collect(java.util.stream.Collectors.toCollection(HashSet::new)))
        .orElseGet(HashSet::new);
    return new YearState(mode, exceptions);
  }

  private record YearState(WorkweekMode mode, Set<LocalDate> exceptions) {

    boolean isWorking(LocalDate day) {
      if (exceptions.contains(day)) {
        return false;
      }
      var dow = day.getDayOfWeek();
      return mode == WorkweekMode.SIX_DAY
          ? dow != DayOfWeek.SUNDAY
          : dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }
  }
}
