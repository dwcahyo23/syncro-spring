package com.syncro.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.syncro.sync.infrastructure.SyncQuarantineEntity;
import com.syncro.sync.infrastructure.SyncQuarantineRepository;
import com.syncro.sync.infrastructure.SyncRunEntity;
import com.syncro.sync.infrastructure.SyncRunRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 13-3 unit tests for {@link SyncStatusService} — the sync observability
 * view (FR-153): latest run + quarantine summary, including the NEVER_RUN state.
 */
@ExtendWith(MockitoExtension.class)
class SyncStatusServiceTest {

  private static final Instant STARTED_AT = Instant.parse("2026-08-28T01:00:00Z");
  private static final Instant QUARANTINED_AT = Instant.parse("2026-08-28T01:30:00Z");

  @Mock
  private SyncRunRepository syncRunRepository;
  @Mock
  private SyncQuarantineRepository syncQuarantineRepository;

  private SyncStatusService service;

  @BeforeEach
  void setUp() {
    service = new SyncStatusService(syncRunRepository, syncQuarantineRepository);
  }

  @Test
  @DisplayName("13.3-ST-001 P0 no runs: status NEVER_RUN, zero counts, no timestamps")
  void neverRun() {
    when(syncRunRepository.findTopByOrderByStartedAtDesc()).thenReturn(Optional.empty());

    var status = service.getStatus();

    assertThat(status.status()).isEqualTo("NEVER_RUN");
    assertThat(status.lastRunAt()).isNull();
    assertThat(status.rowsRead()).isZero();
    assertThat(status.rowsUpserted()).isZero();
    assertThat(status.rowsRejected()).isZero();
    assertThat(status.errorMessage()).isNull();
    assertThat(status.quarantinedCount()).isZero();
    assertThat(status.lastQuarantinedAt()).isNull();
  }

  @Test
  @DisplayName("13.3-ST-002 P0 successful run with no quarantine")
  void successfulRunNoQuarantine() {
    var run = new SyncRunEntity(UUID.randomUUID(), STARTED_AT, "SUCCESS", 10, 8, 0, null);
    when(syncRunRepository.findTopByOrderByStartedAtDesc()).thenReturn(Optional.of(run));
    when(syncQuarantineRepository.count()).thenReturn(0L);
    when(syncQuarantineRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());

    var status = service.getStatus();

    assertThat(status.status()).isEqualTo("SUCCESS");
    assertThat(status.lastRunAt()).isEqualTo(STARTED_AT);
    assertThat(status.rowsRead()).isEqualTo(10);
    assertThat(status.rowsUpserted()).isEqualTo(8);
    assertThat(status.rowsRejected()).isZero();
    assertThat(status.errorMessage()).isNull();
    assertThat(status.quarantinedCount()).isZero();
    assertThat(status.lastQuarantinedAt()).isNull();
  }

  @Test
  @DisplayName("13.3-ST-003 P0 run with rejections: rowsRejected + quarantine summary surfaced")
  void runWithRejectionsAndQuarantine() {
    var run = new SyncRunEntity(UUID.randomUUID(), STARTED_AT, "SUCCESS", 10, 7, 3, null);
    var quarantine = new SyncQuarantineEntity(UUID.randomUUID(), "EXT-00004",
        "TERMINAL_STATE_PROTECTED", Map.of("sheetNo", "EXT-00004"), "trace-1", QUARANTINED_AT);
    when(syncRunRepository.findTopByOrderByStartedAtDesc()).thenReturn(Optional.of(run));
    when(syncQuarantineRepository.count()).thenReturn(3L);
    when(syncQuarantineRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.of(quarantine));

    var status = service.getStatus();

    assertThat(status.status()).isEqualTo("SUCCESS");
    assertThat(status.rowsRejected()).isEqualTo(3);
    assertThat(status.quarantinedCount()).isEqualTo(3);
    assertThat(status.lastQuarantinedAt()).isEqualTo(QUARANTINED_AT);
  }

  @Test
  @DisplayName("13.3-ST-004 P1 failed run surfaces error message")
  void failedRunSurfacesError() {
    var run = new SyncRunEntity(UUID.randomUUID(), STARTED_AT, "FAILED", 0, 0, 0,
        "connection refused");
    when(syncRunRepository.findTopByOrderByStartedAtDesc()).thenReturn(Optional.of(run));
    when(syncQuarantineRepository.count()).thenReturn(0L);
    when(syncQuarantineRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());

    var status = service.getStatus();

    assertThat(status.status()).isEqualTo("FAILED");
    assertThat(status.errorMessage()).isEqualTo("connection refused");
    assertThat(status.rowsRead()).isZero();
  }
}