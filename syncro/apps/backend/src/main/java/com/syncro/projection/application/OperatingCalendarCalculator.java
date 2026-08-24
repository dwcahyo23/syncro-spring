package com.syncro.projection.application;

import com.syncro.shiftconfig.application.ShiftConfigService.ShiftWindowCommand;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Operating-time math over resolved 8-5 shift windows. Wall-clock windows are anchored in the
 * plant timezone and converted to UTC intervals per local calendar day; a cross-midnight window
 * (end &lt;= start) spills its post-midnight segment into the following day. All arithmetic is
 * done on UTC instants; the zone only anchors LocalTime to the wall clock (Asia/Jakarta has no DST).
 */
@Component
public class OperatingCalendarCalculator {

  public BigDecimal dailyOperatingHours(List<ShiftWindowCommand> windows) {
    if (windows == null || windows.isEmpty()) {
      return BigDecimal.ZERO.setScale(2);
    }
    List<long[]> segments = new ArrayList<>();
    for (ShiftWindowCommand window : windows) {
      long startSeconds = window.startTime().toSecondOfDay();
      segments.add(new long[] {startSeconds, startSeconds + windowDurationSeconds(window)});
    }
    return BigDecimal.valueOf(unionedSeconds(segments))
        .divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
  }

  /**
   * Effective operating hours inside [windowStart, windowEnd]: every local date touched by the
   * range contributes each window's UTC interval for that day, intersected with the range.
   */
  public BigDecimal effectiveOperatingHours(Instant windowStart, Instant windowEnd,
      List<ShiftWindowCommand> windows, ZoneId zone) {
    if (windows == null || windows.isEmpty() || !windowEnd.isAfter(windowStart)) {
      return BigDecimal.ZERO.setScale(2);
    }
    List<long[]> segments = new ArrayList<>();
    LocalDate firstDay = LocalDateTime.ofInstant(windowStart, zone).toLocalDate();
    // A cross-midnight window whose post-midnight segment opens the range is anchored on the
    // previous local day, so iteration starts one day early; earlier anchors cannot overlap.
    LocalDate firstAnchorDay = firstDay.minusDays(1);
    LocalDate lastDay = LocalDateTime.ofInstant(windowEnd, zone).toLocalDate();
    for (LocalDate day = firstAnchorDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
      for (ShiftWindowCommand window : windows) {
        var startOfDay = day.atTime(window.startTime());
        var intervalStart = startOfDay.atZone(zone).toInstant();
        var intervalEnd = intervalStart.plusSeconds(windowDurationSeconds(window));
        long overlap = overlapSeconds(intervalStart, intervalEnd, windowStart, windowEnd);
        if (overlap > 0) {
          long clippedStart = Math.max(intervalStart.toEpochMilli(), windowStart.toEpochMilli()) / 1000L;
          segments.add(new long[] {clippedStart, clippedStart + overlap});
        }
      }
    }
    return BigDecimal.valueOf(unionedSeconds(segments))
        .divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
  }

  /** Sums unioned [start,end) second-intervals so overlapping shift windows count once. */
  private static long unionedSeconds(List<long[]> intervals) {
    intervals.sort((a, b) -> Long.compare(a[0], b[0]));
    long total = 0;
    long currentStart = Long.MIN_VALUE;
    long currentEnd = Long.MIN_VALUE;
    for (long[] interval : intervals) {
      if (interval[1] <= currentEnd && currentStart != Long.MIN_VALUE) {
        continue;
      }
      if (interval[0] > currentEnd || currentStart == Long.MIN_VALUE) {
        total += Math.max(0, currentEnd - currentStart);
        currentStart = interval[0];
        currentEnd = interval[1];
      } else {
        currentEnd = Math.max(currentEnd, interval[1]);
      }
    }
    total += Math.max(0, currentEnd - currentStart);
    return total;
  }

  private static long windowDurationSeconds(ShiftWindowCommand window) {
    Duration direct = Duration.between(window.startTime(), window.endTime());
    if (!direct.isNegative()) {
      return direct.getSeconds();
    }
    return direct.plusDays(1).getSeconds();
  }

  private static long overlapSeconds(Instant intervalStart, Instant intervalEnd,
      Instant rangeStart, Instant rangeEnd) {
    Instant clippedStart = intervalStart.isAfter(rangeStart) ? intervalStart : rangeStart;
    Instant clippedEnd = intervalEnd.isBefore(rangeEnd) ? intervalEnd : rangeEnd;
    if (!clippedEnd.isAfter(clippedStart)) {
      return 0;
    }
    return Duration.between(clippedStart, clippedEnd).getSeconds();
  }
}
