package com.syncro.maintenance.domain.workorder;

import com.syncro.config.ProjectionProperties;
import com.syncro.maintenance.infrastructure.db.WorkOrderIdSequenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal workorder ID generator (AD-3): {@code WO-YYMMXXXX} (no dash after the
 * month block, blueprint B2 id format). The prefix derives
 * from the plant timezone (DW-134) so the month rolls at plant-local midnight rather
 * than UTC midnight — a UTC+7 plant at 2024-10-01T00:00 local correctly gets {@code 2410},
 * not the prior month's prefix. The sequence restarts at 00001 per prefix.
 * {@code nextId()} holds a pessimistic write lock on the prefix row for the whole
 * transaction, so concurrent calls stay gapless.
 */
@Service
public class WorkOrderIdGenerator {

  static final int MAX_SEQUENCE = 99999;
  private static final DateTimeFormatter PREFIX_FORMAT = DateTimeFormatter.ofPattern("yyMM");

  private final WorkOrderIdSequenceRepository sequences;
  private final Clock clock;
  private final ZoneId plantZone;

  public WorkOrderIdGenerator(WorkOrderIdSequenceRepository sequences, Clock clock,
      ProjectionProperties properties) {
    this.sequences = sequences;
    this.clock = clock;
    this.plantZone = properties.plantZoneId();
  }

  @Transactional
  public String nextId() {
    var prefix = derivePrefix();
    sequences.insertIfAbsent(prefix);
    var row = sequences.lockByPrefix(prefix)
        .orElseThrow(() -> new IllegalStateException("Sequence row missing for prefix " + prefix));
    var nextSeq = row.getLastSeq() + 1;
    if (nextSeq > MAX_SEQUENCE) {
      throw new WorkorderIdExhaustedException();
    }
    row.setLastSeq(nextSeq);
    row.setUpdatedAt(Instant.now(clock));
    sequences.saveAndFlush(row);
    return "WO-%s%04d".formatted(prefix, nextSeq);
  }

  private String derivePrefix() {
    return YearMonth.now(clock.withZone(plantZone)).format(PREFIX_FORMAT);
  }

  /** The 99999-per-month budget for a prefix is exhausted (unlikely at one site). */
  public static class WorkorderIdExhaustedException extends RuntimeException {
  }
}
