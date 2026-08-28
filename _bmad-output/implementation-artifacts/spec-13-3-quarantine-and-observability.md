---
title: 'Quarantine & Observability'
type: 'feature'
created: '2026-08-28'
status: 'done'
baseline_commit: 'b1c93e970165856ce43d2b8fc3be9e27dab89496'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-1-sync-pipeline-foundation.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-2-conflict-resolution-and-field-mapping.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 13-2 created the `sync_quarantine` table and writes rejection rows, but there is no way for a SUPER_ADMIN to observe sync run status, see rejected rows, or verify timezone handling. The `sync_runs` table tracks `rows_read` and `rows_upserted` but not `rows_rejected`. External timestamps are assumed UTC but the reference system sends Asia/Jakarta.

**Approach:** Add `rows_rejected` to `sync_runs` populated from `BatchResult.rejected()`, expose a `GET /api/v1/sync/status` endpoint for the health dashboard (last run, counts, quarantined rows), add a quarantine list endpoint, and normalize external timestamps from Asia/Jakarta to UTC in the reader. Notification hygiene is an architectural constraint verified by test — no code change needed.

## Boundaries & Constraints

**Always:**
- **V65 migration** (additive on V64): `ALTER TABLE sync_runs ADD COLUMN rows_rejected INT NOT NULL DEFAULT 0`. Extend `sync_runs` status CHECK to include the existing values (no change needed — already correct).
- `SyncWorker` already calls `batchProcessor.importBatch(batch).upserted()` — change to use the full `BatchResult`: `totalUpserted += result.upserted(); totalRejected += result.rejected()` and persist `rows_rejected` on the `SyncRunEntity`.
- `SyncRunEntity` — add `rowsRejected` field (int, default 0). `SyncRunEntity.complete()` accepts it.
- **Health endpoint**: `GET /api/v1/sync/status` — returns `{lastRunAt, status, rowsRead, rowsUpserted, rowsRejected, errorMessage, quarantinedCount, lastQuarantinedAt}`. Data from the latest `sync_runs` row + a `SELECT COUNT(*)`/`MAX(created_at)` from `sync_quarantine`. No auth gating beyond the existing auth framework (the health dashboard is SUPER_ADMIN-only in the UI; the endpoint follows the same authz pattern as other `/api/v1/sync/*` paths).
- **Quarantine list endpoint**: `GET /api/v1/sync/quarantine` — paginated list of quarantine rows (sheet_no, reason, created_at, trace_id). Returns `{content, totalElements, totalPages, ...}`. No `raw_payload` in the list view — it's a separate `GET /api/v1/sync/quarantine/{id}` detail endpoint that returns the full row including `raw_payload`.
- **Timezone normalization (FR-154)**: `SyncSourceReader` converts external timestamps from Asia/Jakarta to UTC. The `SyncSourceRow` record stores `Instant` (always UTC — the rest of the system uses UTC everywhere). The reader's `RowMapper` calls a `toInstant(java.sql.Timestamp)` helper that interprets the raw DB timestamp as Asia/Jakarta and converts to UTC: `timestamp.toLocalDateTime().atZone(ZoneId.of("Asia/Jakarta")).toInstant()`. This is safe because the external DB stores timestamps in Asia/Jakarta but the JDBC driver returns them as `java.sql.Timestamp` (which is UTC-agnostic — `toInstant()` would interpret as UTC, which is wrong).
- **Notification hygiene (AD-7/AD-8)**: the sync module never writes `notification_jobs` directly. This is already enforced by the module boundary (sync calls `WorkorderImportService`, which never enqueues notifications). A test verifies this by asserting no sync module class imports notification infrastructure.

**Block If:** nothing — all decisions are derivable from FR-153/FR-154/AD-7.

**Never:**
- Never add a frontend UI page for the sync status — the health dashboard is a separate story (Epic 14). The endpoint is API-only for now.
- Never add new Spring dependencies, new UI libraries, or new services.
- Never modify the 13-2 quarantine write logic — only add the observability layer.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| SYNC_STATUS_OK | sync has run, no quarantine | `{status: SUCCESS, rowsRead: N, rowsUpserted: M, rowsRejected: 0, quarantinedCount: 0}` | — |
| SYNC_STATUS_WITH_QUARANTINE | sync has run, some rows rejected | `{rowsRejected: 3, quarantinedCount: 3, lastQuarantinedAt: ...}` | — |
| SYNC_STATUS_NEVER_RUN | no sync_runs rows | `{status: "NEVER_RUN"}` | graceful, no error |
| SYNC_QUARANTINE_LIST | quarantine has rows | paginated list, no raw_payload | — |
| SYNC_QUARANTINE_DETAIL | specific id | full row with raw_payload | 404 if not found |
| TZ_JAKARTA_TO_UTC | external timestamp "2026-08-28T07:00:00 Asia/Jakarta" | stored as `2026-08-28T00:00:00Z` | — |
| TZ_JAKARTA_MIDNIGHT | "2026-08-28T00:00:00 Asia/Jakarta" | stored as `2026-08-27T17:00:00Z` | — |
| NOTIFICATION_HYGIENE | sync module calls notification | no sync class imports notification infrastructure | verified by test |

</intent-contract>

## Code Map

- `resources/db/migration/V65__sync_runs_rejected.sql` — NEW — ALTER TABLE sync_runs ADD COLUMN rows_rejected
- `com/syncro/sync/infrastructure/SyncRunEntity.java` — MODIFY — add rowsRejected field, update complete() signature
- `com/syncro/sync/application/SyncWorker.java` — MODIFY — use full BatchResult, persist totalRejected
- `com/syncro/sync/domain/BatchResult.java` — READ — already has rejected() count
- `com/syncro/sync/api/SyncStatusController.java` — NEW — GET /api/v1/sync/status + GET /api/v1/sync/quarantine + GET /api/v1/sync/quarantine/{id}
- `com/syncro/sync/api/SyncStatusDtos.java` — NEW — response DTOs
- `com/syncro/sync/application/SyncStatusService.java` — NEW — queries latest sync_runs + quarantine counts
- `com/syncro/sync/infrastructure/SyncSourceReader.java` — MODIFY — timezone conversion in RowMapper
- `com/syncro/sync/infrastructure/SyncRunRepository.java` — READ — add `findTopByOrderByStartedAtDesc` for latest run
- `com/syncro/sync/infrastructure/SyncQuarantineRepository.java` — READ — add `count()` + `findTopByOrderByCreatedAtDesc` for summary
- Tests — SyncStatusServiceTest, SyncStatusControllerTest, SyncSourceReader timezone test, notification-hygiene test, SyncWorker rowsRejected test, V65 migration test

## Tasks & Acceptance

**Execution:**
- `resources/db/migration/V65__sync_runs_rejected.sql` — NEW — ALTER TABLE + rows_rejected
- `com/syncro/sync/infrastructure/SyncRunEntity.java` — MODIFY — add rowsRejected
- `com/syncro/sync/application/SyncWorker.java` — MODIFY — use result.rejected()
- `com/syncro/sync/application/SyncStatusService.java` — NEW — query latest run + quarantine summary
- `com/syncro/sync/api/SyncStatusController.java` + `SyncStatusDtos.java` — NEW — REST endpoints
- `com/syncro/sync/infrastructure/SyncSourceReader.java` — MODIFY — Asia/Jakarta → UTC conversion
- `com/syncro/sync/infrastructure/SyncRunRepository.java` — MODIFY — add `findTopByOrderByStartedAtDesc`
- `com/syncro/sync/infrastructure/SyncQuarantineRepository.java` — MODIFY — add count + latest finders
- Tests — 5 test classes (new + extended)

**Acceptance Criteria:**
- Given a row fails mapping or conflicts terminally, when the sync processes it, then it is persisted in sync_quarantine with reason, raw payload, and traceId. [FR-153] (13-2 already implemented)
- Given sync_runs, then it records rows_rejected in addition to rows_read and rows_upserted. [FR-153]
- Given the health endpoint, then it shows last run, counts, and last run timestamp. [FR-153]
- Given external timestamps in Asia/Jakarta, when they are persisted, then they are normalized to UTC. [FR-154]
- Given the sync module, then it never notifies independently — verified by test. [AD-7/AD-8]

## Design Notes

- The quarantine list endpoint omits `raw_payload` from the list view because JSONB can be large. The detail endpoint returns the full row.
- Timezone conversion: the external DB stores `TIMESTAMPTZ` but the JDBC driver returns `java.sql.Timestamp` in UTC regardless of the DB's timezone setting. Since the reference system stores Asia/Jakarta times as `TIMESTAMP` (not `TIMESTAMPTZ`), the conversion needs to interpret the raw value as Jakarta. The `SyncSourceReader` uses `TIMESTAMP` (no timezone) columns in the external query AND the `sch_ot.mow_mtn_appm` schema. The `toInstant()` helper: `timestamp.toLocalDateTime().atZone(ZoneId.of("Asia/Jakarta")).toInstant()`. This is correct for `TIMESTAMP` (without timezone) columns that the DB stores as Jakarta local time.
- The sync module does not notify independently — this is already enforced by the module boundary. The test verifies that no `com.syncro.sync` class imports from `com.syncro.notification` (except the allowed `com.syncro.notification.domain.NotificationJobStatus` for the AC test itself).

## Verification

**Commands:**
- `./mvnw.cmd -f syncro/apps/backend/pom.xml test "-Dtest=*SyncStatus*,*SyncWorker*,*SyncSourceReader*,*V65MigrationTest*"` -- expected BUILD SUCCESS
- `./mvnw.cmd -f syncro/apps/backend/pom.xml compile` -- expected BUILD SUCCESS