package com.syncro.sync.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.sync.infrastructure.SyncQuarantineEntity;
import com.syncro.sync.infrastructure.SyncQuarantineRepository;
import com.syncro.sync.infrastructure.SyncRunEntity;
import com.syncro.sync.infrastructure.SyncRunRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Story 13-3 integration test for the sync observability layer (FR-153) against a real
 * Postgres: the V65 migration provides {@code rows_rejected} (Spring context boot with
 * {@code ddl-auto=validate} proves the entity mapping), the derived finders back the
 * status view, and the quarantine list/detail endpoints' data path works end-to-end.
 */
class SyncStatusIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private SyncRunRepository runs;
  @Autowired
  private SyncQuarantineRepository quarantine;
  @Autowired
  private SyncStatusService statusService;

  private static final Instant STARTED_AT = Instant.parse("2026-08-28T01:00:00Z");

  @Test
  @DisplayName("13.3-IT-001 P0 status service reads latest run and quarantine summary")
  void statusServiceReadsLatestRunAndQuarantine() {
    // Older run first — the latest-run finder must pick the newest started_at.
    runs.saveAndFlush(new SyncRunEntity(UUID.randomUUID(),
        STARTED_AT.minusSeconds(600), "SUCCESS", 5, 5, 0, null));
    runs.saveAndFlush(new SyncRunEntity(UUID.randomUUID(),
        STARTED_AT, "SUCCESS", 10, 7, 3, null));

    var quarantineRow = new SyncQuarantineEntity(UUID.randomUUID(), "EXT-00004",
        "TERMINAL_STATE_PROTECTED", Map.of("sheetNo", "EXT-00004"), "trace-1",
        STARTED_AT.plusSeconds(300));
    quarantine.saveAndFlush(quarantineRow);

    var status = statusService.getStatus();

    assertThat(status.status()).isEqualTo("SUCCESS");
    assertThat(status.lastRunAt()).isEqualTo(STARTED_AT);
    assertThat(status.rowsRead()).isEqualTo(10);
    assertThat(status.rowsUpserted()).isEqualTo(7);
    assertThat(status.rowsRejected()).isEqualTo(3);
    assertThat(status.quarantinedCount()).isEqualTo(1);
    assertThat(status.lastQuarantinedAt()).isEqualTo(STARTED_AT.plusSeconds(300));
  }

  @Test
  @DisplayName("13.3-IT-002 P0 quarantine list returns rows without raw_payload, detail returns it")
  void quarantineListAndDetail() {
    var id = UUID.randomUUID();
    quarantine.saveAndFlush(new SyncQuarantineEntity(id, "EXT-00009",
        "PARENT_CLOSED", Map.of("sheetNo", "EXT-00009", "status", "OPEN"), "trace-9",
        STARTED_AT));

    var page = statusService.listQuarantine(PageRequest.of(0, 20));
    assertThat(page.getTotalElements()).isEqualTo(1);
    var row = page.getContent().get(0);
    assertThat(row.id()).isEqualTo(id);
    assertThat(row.sheetNo()).isEqualTo("EXT-00009");
    assertThat(row.reason()).isEqualTo("PARENT_CLOSED");
    assertThat(row.traceId()).isEqualTo("trace-9");

    // List row is the payload-free view; the detail endpoint returns the full row.
    var detail = statusService.getQuarantine(id);
    assertThat(detail).isPresent();
    assertThat(detail.get().rawPayload()).containsEntry("sheetNo", "EXT-00009");

    // Unknown id → empty (the controller maps it to 404).
    assertThat(statusService.getQuarantine(UUID.randomUUID())).isEmpty();
  }
}