package com.syncro.kpi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.kpi.domain.KpiAggregateRefreshStatus;
import com.syncro.kpi.infrastructure.db.KpiAggregateRefreshLogEntity;
import com.syncro.kpi.infrastructure.db.KpiAggregateRefreshLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Story 20-1 review tests for {@link KpiRefreshGate}: the claim is taken under a row
 * lock, a live RUNNING marker blocks a concurrent pass, and a stale RUNNING marker
 * (crashed pass) is reclaimed so a key cannot wedge forever.
 */
class KpiRefreshGateTest {

  private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private KpiAggregateRefreshLogEntity row(KpiAggregateRefreshStatus status, Instant refreshedAt) {
    return new KpiAggregateRefreshLogEntity(UUID.randomUUID(), "mtbf:2026-08", refreshedAt, status, null);
  }

  @Test
  @DisplayName("20.1-GATE-001 live RUNNING blocks the claim (concurrent pass skipped)")
  void liveRunningBlocks() {
    var logs = mock(KpiAggregateRefreshLogRepository.class);
    var gate = new KpiRefreshGate(logs, CLOCK);
    when(logs.findByRefreshKeyForUpdate("mtbf:2026-08"))
        .thenReturn(Optional.of(row(KpiAggregateRefreshStatus.RUNNING, NOW.minusSeconds(60))));

    assertThat(gate.tryStart("mtbf:2026-08")).isFalse();
    verify(logs, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("20.1-GATE-002 stale RUNNING is reclaimed after the timeout (crashed pass)")
  void staleRunningReclaimed() {
    var logs = mock(KpiAggregateRefreshLogRepository.class);
    var gate = new KpiRefreshGate(logs, CLOCK);
    var stale = row(KpiAggregateRefreshStatus.RUNNING,
        NOW.minus(KpiRefreshGate.STALE_RUNNING_AFTER).minusSeconds(1));
    when(logs.findByRefreshKeyForUpdate("mtbf:2026-08")).thenReturn(Optional.of(stale));

    assertThat(gate.tryStart("mtbf:2026-08")).isTrue();
    assertThat(stale.getStatus()).isEqualTo(KpiAggregateRefreshStatus.RUNNING);
    verify(logs).saveAndFlush(stale);
  }

  @Test
  @DisplayName("20.1-GATE-003 SUCCESS/FAILED terminal rows allow retry")
  void terminalAllowsRetry() {
    var logs = mock(KpiAggregateRefreshLogRepository.class);
    var gate = new KpiRefreshGate(logs, CLOCK);
    when(logs.findByRefreshKeyForUpdate("mtbf:2026-08"))
        .thenReturn(Optional.of(row(KpiAggregateRefreshStatus.FAILED, NOW.minusSeconds(60))));

    assertThat(gate.tryStart("mtbf:2026-08")).isTrue();
  }

  @Test
  @DisplayName("20.1-GATE-004 insert race against the unique constraint skips (winner owns the key)")
  void insertRaceSkips() {
    var logs = mock(KpiAggregateRefreshLogRepository.class);
    var gate = new KpiRefreshGate(logs, CLOCK);
    when(logs.findByRefreshKeyForUpdate("mtbf:2026-08")).thenReturn(Optional.empty());
    when(logs.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq race"));

    assertThat(gate.tryStart("mtbf:2026-08")).isFalse();
  }
}