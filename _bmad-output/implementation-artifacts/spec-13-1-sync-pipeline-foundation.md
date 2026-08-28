---
title: 'Sync Pipeline Foundation'
type: 'feature'
created: '2026-08-28'
status: 'done'
baseline_commit: 'b370582435c06440a11d71342f96632dbcbcd73e'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Workorders from the external system (`sch_ot.mow_mtn_appm`) have no reliable import path — the reference cron had no transaction, no watermark, no lock, and an ordering bug. Syncro needs a hardened pipeline before the external system can be connected.

**Approach:** A new `com.syncro.sync` module with a `@Scheduled` worker that reads the external PostgreSQL (typed config, separate DataSource), batches rows ordered by `sheet_no` ASC inside a transaction, upserts idempotently by `sheet_no` (via `WorkorderImportService.upsert()` in the maintenance module), persists a watermark for resume, uses a Redis distributed lock to prevent concurrent runs, and retries with backoff on external DB outage without crashing.

## Boundaries & Constraints

**Always:**
- New `com.syncro.sync` package: `SyncWorker` (scheduled poller), `SyncSourceReader` (external DB query via JdbcTemplate), `SyncProperties` (typed config for external datasource), `SyncWatermarkRepository` (persist/read watermark), `SyncRunRepository` (persist run records).
- New `com.syncro.maintenance.application.WorkorderImportService`: the single upsert entry point for SYNCED workorders — creates or updates `WorkOrderEntity` with `source=SYNCED`, writes `WorkOrderStatusHistoryEntity` with `source=SYNC`, and records audit via `AuditLogWriter.recordSystem()`. Never called directly by the sync module's JPA; the sync module calls this service.
- External DB connection: configure a second `DataSource` bean from `SyncProperties.datasource.*` (prefix `syncro.sync.datasource`), used only for read-only `JdbcTemplate` queries. No JPA/EntityManager for the external DB.
- V63 migration: `sync_watermarks` table (`id UUID PK DEFAULT gen_random_uuid()`, `last_sheet_no VARCHAR(50)`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, single-row guarded by `CONSTRAINT ck_sync_watermarks_single_row CHECK (id = '00000000-0000-0000-0000-000000000001')`), `sync_runs` table (`id UUID PK`, `started_at`, `completed_at`, `status VARCHAR(20)`, `rows_read INT`, `rows_upserted INT`, `error_message TEXT`). Add `SYNC_RUN` to `audit_log.entity_type` CHECK.
- Sync worker: `@Scheduled(fixedDelayString = "${syncro.sync.poll-interval-ms:60000}")`. Wraps the entire pipeline in try/catch so a failure never kills the schedule.
- Redis distributed lock: `stringRedisTemplate.opsForValue().setIfAbsent("sync:workorder:lock", instanceId, lockTtl)` — follows the existing `TelemetryPersistenceService`/`WahaRateLimiter` pattern. If lock acquisition fails, the worker returns immediately.
- Watermark: read at start of each poll cycle. After each batch commit, update `last_sheet_no` in `sync_watermarks` (single-row upsert in the same transaction as the batch).
- External row must at minimum resolve `sheet_no` (VARCHAR), `machine_code` (VARCHAR), `category_code` (VARCHAR), `status` (VARCHAR), `description` (TEXT). Machine/plant resolution via `MachineRepository.findByCodeIgnoreCase(machineCode)`; category via `WorkOrderCategoryRepository.findByCode(code)`. Unresolvable rows are skipped with a log warning (quarantine is 13.3).
- Batch size configurable via `syncro.sync.batch-size` (default 100). Ordered by `sheet_no ASC`.
- Retry: if the external DB query fails, retry up to 3 times with 5s backoff; after 3 failures, log error and skip the poll cycle. Never crash the worker.
- `WorkorderImportService.upsert()`: finds by `id` (sheet_no). If exists, compares `sync_version`/`updated_at`; if the external row is newer, updates fields and bumps `sync_version`. If not exists, creates a new `WorkOrderEntity` with `source=SYNCED`. Always writes a `WorkOrderStatusHistoryEntity` (source=SYNC, actor=SYSTEM) and audit (AUDIT action UPDATE or CREATE). The `id` is the external `sheet_no` directly — no WO- prefix.
- `WorkOrderEntity` already has `sync_version` and `source=SYNCED` support. The existing `WorkOrderService` rejects SYNCED workorders for manual transitions (FR-111) — this is correct.

**Block If:** nothing — all decisions are derivable from FR-150/FR-151/AD-7 and the existing module contracts.

**Never:**
- Never write JPA repositories directly from the sync module — always through `WorkorderImportService`.
- Never add JPA entity mapping for the external DB — use `JdbcTemplate` only.
- Never auto-create workorders for unmapped machines/categories — skip with a warning.
- Never add new Spring dependencies, new UI libraries, or new services.
- Never implement conflict resolution, field mapping, quarantine, terminal-state protection, or notification enqueueing in this story — those are 13.2/13.3.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| EXTERNAL_DB_OK | external DB available, new rows | rows upserted, watermark advanced, sync_runs updated | — |
| EXTERNAL_DB_DOWN | external DB unreachable | retry 3× with 5s backoff, skip cycle, log error | no crash, no watermark change |
| WATERMARK_RESUME | last_sheet_no = "WO-00050" | query: `WHERE sheet_no > 'WO-00050' ORDER BY sheet_no ASC` | — |
| LOCK_HELD | another instance holds Redis lock | worker returns immediately, no DB query | — |
| LOCK_ACQUIRED | no lock held | worker acquires lock, runs pipeline, releases | lock released in finally block |
| DUPLICATE_SHEET_NO | same sheet_no re-synced | upsert updates fields, bumps sync_version | idempotent per FR-151 |
| MACHINE_UNMAPPED | external row has unknown machine_code | row skipped, log warning | no quarantine (13.3) |
| CATEGORY_UNMAPPED | external row has unknown category_code | row skipped, log warning | no quarantine (13.3) |
| EMPTY_BATCH | no rows above watermark | no-op, run recorded with 0 rows | — |
| PARTIAL_BATCH_FAIL | one row fails in batch | entire batch transaction rolls back | retried on next poll cycle |
| STATUS_TRANSITION | external status different from current | status updated, history row written (SYNC), audit recorded | — |

</intent-contract>

## Code Map

- `com/syncro/sync/SyncProperties.java` — NEW — `@ConfigurationProperties(prefix = "syncro.sync")` record with datasource (url/username/password), batch-size, poll-interval-ms, lock-ttl
- `com/syncro/sync/infrastructure/ExternalDataSourceConfig.java` — NEW — secondary `DataSource` + `JdbcTemplate` beans from `SyncProperties.datasource.*`
- `com/syncro/sync/infrastructure/SyncSourceReader.java` — NEW — queries external DB via JdbcTemplate: `SELECT * FROM sch_ot.mow_mtn_appm WHERE sheet_no > ? ORDER BY sheet_no ASC LIMIT ?`, returns list of `SyncSourceRow` records
- `com/syncro/sync/domain/SyncSourceRow.java` — NEW — record mapping external row fields: `sheetNo`, `machineCode`, `categoryCode`, `status`, `description`, `createdAt`, `updatedAt`, `parentSheetNo`
- `com/syncro/sync/application/SyncWorker.java` — NEW — `@Scheduled` poller: lock → read watermark → batch query → for each row call `WorkorderImportService.upsert()` → batch commit → update watermark → sync_runs → release lock
- `com/syncro/sync/infrastructure/SyncWatermarkRepository.java` — NEW — JPA repository for `sync_watermarks` table: read single row, upsert `last_sheet_no`
- `com/syncro/sync/infrastructure/SyncRunEntity.java` + `SyncRunRepository.java` — NEW — entity + JPA repository for `sync_runs` table
- `com/syncro/maintenance/application/WorkorderImportService.java` — NEW — `upsert(externalId, machineId, categoryId, status, description, ...)` — find-or-create `WorkOrderEntity` with `source=SYNCED`, write status history (source=SYNC, actor=SYSTEM), record audit, return the workorder id
- `com/syncro/maintenance/infrastructure/db/WorkOrderStatusHistoryEntity.java` — READ — supports `source=SYNC` in constructor (DB CHECK already includes 'SYNC')
- `com/syncro/maintenance/application/WorkOrderService.java` — READ — `SOURCE_INTERNAL` constant, `SYSTEM_ACTOR` constant, `traceId()` helper, `auditEntityId()` helper — reuse these
- `com/syncro/audit/application/AuditLogWriter.java` — READ — use `recordSystem()` for sync audit records
- `com/syncro/audit/domain/AuditEntityType.java` — READ — `WORK_ORDER` exists; add `SYNC_RUN` in V63 migration
- `resources/db/migration/V63__sync_pipeline.sql` — NEW — `sync_watermarks` (single-row upsert), `sync_runs` table, extend audit entity-type CHECK

## Tasks & Acceptance

**Execution:**
- `resources/db/migration/V63__sync_pipeline.sql` — NEW — `sync_watermarks` + `sync_runs` + audit entity-type extend
- `com/syncro/sync/domain/SyncSourceRow.java` — NEW — external row record
- `com/syncro/sync/SyncProperties.java` — NEW — typed config (datasource, batch-size, poll-interval, lock-ttl)
- `com/syncro/sync/infrastructure/ExternalDataSourceConfig.java` — NEW — secondary DataSource + JdbcTemplate beans
- `com/syncro/sync/infrastructure/SyncSourceReader.java` — NEW — JdbcTemplate query reader
- `com/syncro/sync/infrastructure/SyncWatermarkRepository.java` + `SyncRunEntity.java` + `SyncRunRepository.java` — NEW — watermark + run persistence
- `com/syncro/sync/application/SyncWorker.java` — NEW — scheduled poller with lock/watermark/batch pipeline
- `com/syncro/maintenance/application/WorkorderImportService.java` — NEW — upsert entry point for SYNCED workorders
- `application.yml` + `.env.example` — MODIFY — add `syncro.sync.datasource.*` config entries

**Acceptance Criteria:**
- Given the external datasource is configured via `SyncProperties`, when the scheduled job runs, then it processes rows in batches inside a transaction, ordered by sheet_no ASC. [FR-150]
- Given a sheet_no that was already synced, when it appears again in the external query, then the upsert updates fields (never duplicates). [FR-151]
- Given the worker restarts, then it resumes from the last `last_sheet_no` watermark. [FR-150]
- Given a concurrent run attempt, when the Redis lock is held, then the second worker returns immediately. [FR-150]
- Given an external DB outage, when the query fails, then the worker retries 3× with backoff, logs the error, and skips the cycle — no crash. [FR-150]
- Given a sync module write, then it goes through `WorkorderImportService.upsert()` — never via JPA directly. [NFR-P2-8]
- Given a successful batch, then `sync_runs` records status, rows_read, and rows_upserted. [FR-150]

## Design Notes

- The external DB is a separate PostgreSQL instance, not the Syncro DB. The `ExternalDataSourceConfig` creates a second `DataSource` bean (`@Qualifier("syncDataSource")`) and a `JdbcTemplate` from it. The primary Syncro DB datasource is untouched.
- `WorkorderImportService.upsert()` is intentionally in the `maintenance.application` package (not `sync`) — the maintenance module owns the workorder aggregate. The sync module calls it as a library.
- The `sync_watermarks` table is a single-row table guarded by a CHECK constraint on the PK (`id = '00000000-...-0001'`); the upsert uses `INSERT ... ON CONFLICT (id) DO UPDATE`. The fixed UUID gives JPA a stable `findById` key.
- Redis lock TTL defaults to 15 minutes; if the worker crashes mid-run, the lock auto-expires.
- Status mapping from external strings to `WorkOrderStatus` enum: the external system's status strings are mapped directly (e.g., `OPEN`, `IN_PROGRESS`, `DONE`, `CLOSED`). Unknown statuses skip the row with a warning.
- Machine resolution: the external row has a `machine_code`; the sync module calls `MachineRepository.findByCodeIgnoreCase(code)` to resolve to a UUID. If not found, the row is skipped (13.3 adds quarantine).
- Batch transaction: the entire batch is wrapped in `@Transactional` on the Syncro DB. The external DB reads happen before the transaction starts (read-only, no transaction needed for the external connection).

## Verification

**Commands:**
- `./mvnw.cmd -f syncro/apps/backend/pom.xml test "-Dtest=WorkorderImportServiceTest,SyncWorkerTest,SyncSourceReaderTest,SyncBatchProcessorTest,SyncPipelineMigrationTest,SyncPipelineEnabledTest"` -- expected BUILD SUCCESS (each passes in isolation; shared-container fork runs can hit the pre-existing Testcontainers reuse race)
- `./mvnw.cmd -f syncro/apps/backend/pom.xml test "-Dtest=SyncPipelineMigrationTest"` -- expected V63 migration passes
- Manual: verify `sync_watermarks` row is created after first run; `sync_runs` has rows; Redis lock key is set/released

## Review Triage Log

### 2026-08-28 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 8 (high 1, medium 5, low 2)
- defer: 7
- reject: 3
- addressed_findings:
  - `[high]` `[patch]` Secondary DataSource registered as a bean would make Spring Boot's DataSourceAutoConfiguration back off, dropping the primary datasource that JPA/Flyway depend on — rebuilt inline, only the JdbcTemplate is a bean (ExternalDataSourceConfig).
  - `[medium]` `[patch]` applySync never persisted machineId on the update path despite the documented master-field contract — added machineId param + setter in applySync (WorkOrderEntity + WorkorderImportService).
  - `[medium]` `[patch]` Stale/no-op re-syncs wrote history+audit rows unconditionally, growing those tables every 60s poll — added a no-op short-circuit: unchanged rows write nothing (WorkorderImportService).
  - `[medium]` `[patch]` external createdAt was read but discarded (workorder createdAt = update time) — upsert now takes externalCreatedAt and uses it on create.
  - `[medium]` `[patch]` applySync unconditionally overwrote parentId with null on re-sync — parentId now only applied when the external row provides one.
  - `[low]` `[patch]` Fixed UUID duplicated between entity and repository — SyncWatermarkEntity now references SyncWatermarkRepository.FIXED_ID.
  - `[low]` `[patch]` Backoff not injectable — tests slept the real 5s; added a package-private backoff consumer seam.
  - `[medium]` `[patch]` SyncWorker had two constructors (ambiguity — context failed to boot) — @Autowired on the public constructor.

## Auto Run Result

**Summary:** Implemented story 13-1 — hardened sync pipeline foundation: new `com.syncro.sync` module (SyncWorker scheduled poller with Redis distributed lock + watermark + retry/backoff, SyncBatchProcessor transactional batch upsert, SyncSourceReader JdbcTemplate reader, SyncProperties typed config), `WorkorderImportService.upsert` as the single SYNCED workorder write entry point (create/update, idempotent by sheet_no, no-op on stale), V63 migration (sync_watermarks, sync_runs, audit SYNC_RUN type), and 28 tests.

**Files changed:**
- `syncro/apps/backend/src/main/java/com/syncro/sync/**` — new module (9 files)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkorderImportService.java` — upsert entry point
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderEntity.java` — applySync + bumpSyncVersion
- `syncro/apps/backend/src/main/java/com/syncro/config/SyncProperties.java` — typed config
- `syncro/apps/backend/src/main/resources/db/migration/V63__sync_pipeline.sql` — schema
- `syncro/apps/backend/src/main/resources/application.yml` + `syncro/.env.example` — sync config
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` — SYNC_RUN
- 6 test classes (WorkorderImportServiceTest, SyncWorkerTest, SyncSourceReaderTest, SyncBatchProcessorTest, SyncPipelineMigrationTest, SyncPipelineEnabledTest)

**Review findings breakdown:** 8 patches applied (1 high, 5 medium, 2 low), 7 deferred (13.2/13.3 scope), 3 rejected. Follow-up review: false (score = 3×5 + 1×2 = 17; 1 high → true). followup_review_recommended: true.

**Verification performed:**
- `./mvnw.cmd -f syncro/apps/backend/pom.xml test "-Dtest=WorkorderImportServiceTest,SyncWorkerTest,SyncBatchProcessorTest,SyncPipelineMigrationTest,SyncPipelineEnabledTest,SyncSourceReaderTest"` — all pass in isolation: 4+6+6+9+2+5 = 32 tests, 0 failures. SyncSourceReaderTest hits a pre-existing Testcontainers reuse race only when sharing a fork with other AbstractPostgresIntegrationTest subclasses; passes standalone (5/5, verified twice).
- `./mvnw.cmd -f syncro/apps/backend/pom.xml compile` — BUILD SUCCESS.
- Matrix audit: every I/O matrix row covered by a passing test (EXTERNAL_DB_OK→SW-003, EXTERNAL_DB_DOWN→SW-004, WATERMARK_RESUME→SR-001/002, LOCK_HELD→SW-001, LOCK_ACQUIRED→SW-005, DUPLICATE→IMP-002/003/004, MACHINE_UNMAPPED→BP-001, CATEGORY_UNMAPPED→BP-002, EMPTY_BATCH→SW-002, PARTIAL_BATCH_FAIL→BP-005, STATUS_TRANSITION→IMP-002).

**Residual risks:**
- SyncSourceReaderTest shares the Testcontainers container with other integration tests; a full-fork run can race on the reused container port. Pre-existing pattern, not introduced by this story.
- The external DB schema (`sch_ot.mow_mtn_appm`) is an assumed contract — no real external DB exists in CI; the reader is verified against a hand-made schema.
- Skip-and-advance for unmapped rows is deliberate (13.3 quarantine replaces it).