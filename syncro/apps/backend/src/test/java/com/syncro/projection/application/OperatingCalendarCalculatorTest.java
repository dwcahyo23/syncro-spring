package com.syncro.projection.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.shiftconfig.application.ShiftConfigService.ShiftWindowCommand;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OperatingCalendarCalculatorTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");
  private final OperatingCalendarCalculator calculator = new OperatingCalendarCalculator();

  private static ShiftWindowCommand window(String start, String end) {
    return new ShiftWindowCommand(LocalTime.parse(start), LocalTime.parse(end));
  }

  @Test
  @DisplayName("dailyOperatingHours sums single-window durations to scale-2 hours")
  void dailyHoursSingleWindow() {
    var hours = calculator.dailyOperatingHours(List.of(window("07:00", "15:00")));

    assertThat(hours).isEqualByComparingTo("8.00");
  }

  @Test
  @DisplayName("dailyOperatingHours counts a cross-midnight window as next-day segment")
  void dailyHoursCrossMidnight() {
    var hours = calculator.dailyOperatingHours(List.of(window("23:00", "06:00")));

    assertThat(hours).isEqualByComparingTo("7.00");
  }

  @Test
  @DisplayName("dailyOperatingHours matches the golden example of two windows summing 15h")
  void dailyHoursGoldenExample() {
    var hours = calculator.dailyOperatingHours(List.of(window("07:00", "15:00"), window("23:00", "06:00")));

    assertThat(hours).isEqualByComparingTo("15.00");
  }

  @Test
  @DisplayName("dailyOperatingHours is zero without windows")
  void dailyHoursZeroWindows() {
    assertThat(calculator.dailyOperatingHours(List.of())).isEqualByComparingTo("0.00");
    assertThat(calculator.dailyOperatingHours(null)).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("effectiveOperatingHours counts one fully covered shift day once")
  void effectiveSingleDay() {
    // Local Jakarta day 2026-08-24 with an 07:00–15:00 shift spans [24T00:00Z, 24T08:00Z].
    var start = Instant.parse("2026-08-24T00:30:00Z");
    var end = Instant.parse("2026-08-24T05:00:00Z");

    var hours = calculator.effectiveOperatingHours(start, end,
        List.of(window("07:00", "15:00")), ZONE);

    assertThat(hours).isEqualByComparingTo("4.50");
  }

  @Test
  @DisplayName("effectiveOperatingHours attributes post-midnight spill to the anchor local day")
  void effectiveCrossMidnightSpill() {
    // Range is the post-midnight segment of shift 23:00–06:00 anchored on local Aug 24
    // (local Aug 25 01:00–02:30 = [Aug24T18:00Z, Aug24T19:30Z]); the anchor day sits before
    // the range's own local day and must still be counted.
    var start = Instant.parse("2026-08-24T18:00:00Z");
    var end = Instant.parse("2026-08-24T19:30:00Z");

    var hours = calculator.effectiveOperatingHours(start, end,
        List.of(window("23:00", "06:00")), ZONE);

    assertThat(hours).isEqualByComparingTo("1.50");
  }

  @Test
  @DisplayName("effectiveOperatingHours sums a multi-day span across whole days")
  void effectiveMultiDaySpan() {
    // Range covers local days Aug 24 and Aug 25 fully for an 07:00–15:00 shift (8h each).
    var start = Instant.parse("2026-08-23T17:00:00Z");
    var end = Instant.parse("2026-08-25T17:00:00Z");

    var hours = calculator.effectiveOperatingHours(start, end,
        List.of(window("07:00", "15:00")), ZONE);

    assertThat(hours).isEqualByComparingTo("16.00");
  }

  @Test
  @DisplayName("effectiveOperatingHours clips partial-day intersections at both range edges")
  void effectivePartialEdgeClipping() {
    // One fully covered local day (Aug 24, 8h) plus a tail day clipped at Aug25T03:30Z (3.5h).
    var start = Instant.parse("2026-08-23T17:00:00Z");
    var end = Instant.parse("2026-08-25T03:30:00Z");

    var hours = calculator.effectiveOperatingHours(start, end,
        List.of(window("07:00", "15:00")), ZONE);

    assertThat(hours).isEqualByComparingTo("11.50");
  }

  @Test
  @DisplayName("effectiveOperatingHours is zero without windows or an empty range")
  void effectiveDegenerateInputs() {
    var start = Instant.parse("2026-08-24T01:00:00Z");
    var end = Instant.parse("2026-08-24T05:00:00Z");

    assertThat(calculator.effectiveOperatingHours(start, end, List.of(), ZONE))
        .isEqualByComparingTo("0.00");
    assertThat(calculator.effectiveOperatingHours(end, start, List.of(window("07:00", "15:00")), ZONE))
        .isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("dailyOperatingHours unions overlapping windows instead of double-counting")
  void dailyOverlappingWindowsUnioned() {
    // 07:00–15:00 (8h) and 14:00–20:00 (6h) overlap 14:00–15:00 → union 13h, not 14h.
    var hours = calculator.dailyOperatingHours(
        List.of(window("07:00", "15:00"), window("14:00", "20:00")));

    assertThat(hours).isEqualByComparingTo("13.00");
  }

  @Test
  @DisplayName("effectiveOperatingHours unions overlapping windows across the span")
  void effectiveOverlappingWindowsUnioned() {
    // One fully covered local day; windows 06:00–10:00 and 08:00–12:00 overlap 08:00–10:00.
    var start = Instant.parse("2026-08-23T17:00:00Z");
    var end = Instant.parse("2026-08-24T17:00:00Z");

    var hours = calculator.effectiveOperatingHours(start, end,
        List.of(window("06:00", "10:00"), window("08:00", "12:00")), ZONE);

    assertThat(hours).isEqualByComparingTo("6.00");
  }
}
