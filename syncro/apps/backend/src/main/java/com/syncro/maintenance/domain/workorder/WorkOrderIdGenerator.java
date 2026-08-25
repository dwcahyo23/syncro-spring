package com.syncro.maintenance.domain.workorder;

import com.syncro.maintenance.infrastructure.db.WorkOrderIdSequenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal workorder ID generator (AD-3): {@code WO-YYMM-XXXXX}. The prefix derives
 * from the injected clock, so a new month naturally yields a new prefix row and the
 * sequence restarts at 00001. {@code nextId()} holds a pessimistic write lock on the
 * prefix row for the whole transaction, so concurrent calls stay gapless.
 */
@Service
public class WorkOrderIdGenerator {

  static final int MAX_SEQUENCE = 99999;
  private static final DateTimeFormatter PREFIX_FORMAT = DateTimeFormatter.ofPattern("yyMM");

  private final WorkOrderIdSequenceRepository sequences;
  private final Clock clock;

  public WorkOrderIdGenerator(WorkOrderIdSequenceRepository sequences, Clock clock) {
    this.sequences = sequences;
    this.clock = clock;
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
    return "WO-%s-%05d".formatted(prefix, nextSeq);
  }

  private String derivePrefix() {
    return YearMonth.now(clock).format(PREFIX_FORMAT);
  }

  /** The 99999-per-month budget for a prefix is exhausted (unlikely at one site). */
  public static class WorkorderIdExhaustedException extends RuntimeException {
  }
}
