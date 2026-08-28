package com.syncro.sync.application;

import com.syncro.sync.api.SyncStatusDtos.SyncQuarantineDetailView;
import com.syncro.sync.api.SyncStatusDtos.SyncQuarantineListRow;
import com.syncro.sync.infrastructure.SyncQuarantineRepository;
import com.syncro.sync.infrastructure.SyncRunRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only query service for the sync observability dashboard (story 13-3, FR-153).
 * Assembles the latest run status from {@code sync_runs} and the quarantine summary
 * from {@code sync_quarantine}.
 */
@Service
public class SyncStatusService {

  private final SyncRunRepository syncRunRepository;
  private final SyncQuarantineRepository syncQuarantineRepository;

  public SyncStatusService(SyncRunRepository syncRunRepository,
      SyncQuarantineRepository syncQuarantineRepository) {
    this.syncRunRepository = syncRunRepository;
    this.syncQuarantineRepository = syncQuarantineRepository;
  }

  /**
   * Returns the current sync status view: the latest run details (or NEVER_RUN when no
   * runs exist) plus the quarantine row count and the most recent quarantine timestamp.
   */
  @Transactional(readOnly = true)
  public SyncStatusView getStatus() {
    var latestRun = syncRunRepository.findTopByOrderByStartedAtDesc();
    if (latestRun.isEmpty()) {
      return SyncStatusView.neverRun();
    }
    var run = latestRun.get();
    var quarantinedCount = syncQuarantineRepository.count();
    var lastQuarantined = syncQuarantineRepository.findTopByOrderByCreatedAtDesc()
        .map(q -> q.getCreatedAt())
        .orElse(null);
    return new SyncStatusView(
        run.getStartedAt(),
        run.getStatus(),
        run.getRowsRead(),
        run.getRowsUpserted(),
        run.getRowsRejected(),
        run.getErrorMessage(),
        quarantinedCount,
        lastQuarantined);
  }

  /** Paginated quarantine list — list rows omit the potentially large raw_payload. */
  @Transactional(readOnly = true)
  public Page<SyncQuarantineListRow> listQuarantine(Pageable pageable) {
    return syncQuarantineRepository.findAll(pageable)
        .map(SyncQuarantineListRow::fromEntity);
  }

  /** Full quarantine row including raw_payload; empty when the id is unknown. */
  @Transactional(readOnly = true)
  public Optional<SyncQuarantineDetailView> getQuarantine(UUID id) {
    return syncQuarantineRepository.findById(id)
        .map(SyncQuarantineDetailView::fromEntity);
  }
}