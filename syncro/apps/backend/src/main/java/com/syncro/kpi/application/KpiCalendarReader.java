package com.syncro.kpi.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Plant working-calendar port for the actual-working MTTR variant (story 20-1,
 * blueprint A12). Implemented by the org module's JPA adapter — calendar data is
 * owned by org, the kpi module only asks "how many business minutes fall in this
 * interval for this plant". v1 counts whole calendar days as working/non-working per
 * the plant's workweek mode minus exception dates; shift-aware minute math is a
 * documented future refinement (spec design note).
 */
public interface KpiCalendarReader {

  /**
   * Business minutes between the two instants for the plant's working calendar.
   * Falls back to a five-day workweek when the plant has no calendar for a year in
   * range (documented default — never zero, which would fabricate an infinite MTTR).
   */
  long workingMinutes(UUID plantId, Instant from, Instant to);
}
