---
title: 'Conflict Resolution & Field Mapping'
type: 'feature'
created: '2026-08-28'
status: 'done'
baseline_commit: 'e2e919be34097e7f5e63d64e64f22e52b6362991'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-1-sync-pipeline-foundation.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 13-1 applies all external fields unconditionally (when newer), but the AD-8 contract requires field classification: MASTER fields (status, machine, category) take external values, while OPERATIONAL fields (report, evidence, ratings, sessions) are always preserved. Terminal-state (DONE/CLOSED) workorders must never be regressed by sync, and a child must not be upserted when its parent is CLOSED. No quarantine mechanism exists yet for these protection violations.

**Approach:** Add a `sync_field_mappings` configuration table for field classification (MASTER vs OPERATIONAL), gate the `WorkorderImportService.upsert` update path by field domain, add terminal-state guards (DONE/CLOSED workorders and CLOSED parents), quarantine rejected rows in a new `sync_quarantine` table, and audit-log the resolution. The quarantine table is shared with story 13-3, which adds the observability layer.

## Boundaries & Constraints

**Always:**
- **V64 migration**: `sync_field_mappings` table (`field_name VARCHAR(64) PK`, `domain VARCHAR(16) NOT NULL CHECK domain IN ('MASTER','OPERATIONAL')`, seed defaults: `status`/`machine_id`/`category_id`/`parent_id`/`description`/`sync_version` = MASTER, `report_*`/`cp_*`/`cpk`/`cpk_pdf_object_key`/`fmea_failure_type`/`stop_time_*`/`mttr_minutes`/`response_time_minutes`/`done_reason` = OPERATIONAL). `sync_quarantine` table (`id UUID PK`, `sheet_no VARCHAR(50)`, `reason VARCHAR(64) NOT NULL`, `raw_payload JSONB`, `trace_id VARCHAR(36)`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `INDEX idx_sync_quarantine_sheet_no`). Extend `audit_log.entity_type` CHECK with `SYNC_QUARANTINE`.
- **Field classification**: `WorkorderImportService.upsert` reads `sync_field_mappings` and applies only MASTER fields. OPERATIONAL fields are never touched on update. On create, all fields are set (no classification needed — no local data to preserve).
- **Terminal-state protection (NFR-P2-9)**: before `applySync`, check if the existing entity's status is `DONE` or `CLOSED`. If so, write the row to `sync_quarantine` with reason `TERMINAL_STATE_PROTECTED` and return. Never regress.
- **ON_PROCUREMENT preservation**: external status never overrides a locally-derived `ON_PROCUREMENT` state. Check `fromStatus == ON_PROCUREMENT && toStatus != ON_PROCUREMENT` → quarantine with reason `ON_PROCUREMENT_PROTECTED`.
- **Parent-close gate**: before upsert (both create and update), if the row has a `parentSheetNo`, check if the parent workorder exists and its status is `CLOSED`. If so, quarantine with reason `PARENT_CLOSED`. This prevents creating or updating a child of a closed workorder.
- **Resolution audit-logged per run**: `WorkorderImportService.upsert` returns a result record with action (CREATED/UPDATED/REJECTED) and reason (null for success, TERMINAL_STATE_PROTECTED/ON_PROCUREMENT_PROTECTED/PARENT_CLOSED for rejections). `SyncBatchProcessor` collects these and logs them to `sync_runs.error_message` via refinement (or a separate `sync_runs_issues` approach — see Design Notes).
- `SyncSourceRow` already has `parentSheetNo`. The `SyncBatchProcessor.upsertRow` now handles rejections: rather than returning boolean, return a result that includes the quarantine write. The processor calls `syncQuarantineRepository.save()` for rejected rows.
- A configurable fallback category (via `SyncProperties.fallbackCategoryCode`) replaces the current hard skip when an external category code is unmapped — if a fallback is configured, use it instead of skipping. No fallback = skip as before.

**Block If:** nothing — FR-152/AD-8 decisions are clear.

**Never:**
- Never add new Spring dependencies, new UI libraries, or new services.
- Never modify the `WorkOrderEntity.applySync` to skip OPERATIONAL fields — the classification is done at the service layer, not the entity (the entity is a plain data holder).
- Never auto-create machines or categories for unmapped external codes.
- Never delete quarantine rows — they are append-only evidence.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| MASTER_FIELD_UPDATED | external row newer, status changed | status updated, sync_version++ | — |
| OPERATIONAL_FIELD_IGNORED | external row has new report text | report text NOT applied (local preserved) | — |
| TERMINAL_STATE_PROTECTED | existing DONE workorder, external status OPEN | row quarantined TERMINAL_STATE_PROTECTED, no update | never regresses |
| ON_PROCUREMENT_PROTECTED | existing ON_PROCUREMENT, external CLOSED | row quarantined ON_PROCUREMENT_PROTECTED, no update | derived state preserved |
| PARENT_CLOSED | child sheet_no, parent CLOSED | row quarantined PARENT_CLOSED, no upsert | — |
| PARENT_CLOSED_CREATE | new child sheet_no, parent CLOSED | row quarantined PARENT_CLOSED, no create | — |
| CATEGORY_UNMAPPED_FALLBACK | external category unknown, fallback configured | fallback category used, upsert proceeds | — |
| CATEGORY_UNMAPPED_NO_FALLBACK | external category unknown, no fallback | row skipped (13-1 behavior, unchanged) | — |
| SYNC_FIELD_MAPPING_EMPTY | sync_field_mappings table empty | all fields treated as MASTER (AD-8 default) | — |
| BATCH_WITH_REJECTIONS | 5 rows: 2 success, 3 rejected | 2 upserted, 3 quarantined, watermark advances past last row | — |

</intent-contract>

## Code Map

- `resources/db/migration/V64__sync_field_mappings_and_quarantine.sql` — NEW — sync_field_mappings + sync_quarantine + audit CHECK extend
- `com/syncro/sync/domain/SyncFieldDomain.java` — NEW — enum `MASTER, OPERATIONAL`
- `com/syncro/sync/infrastructure/SyncFieldMappingEntity.java` + `SyncFieldMappingRepository.java` — NEW — JPA entity + repository
- `com/syncro/sync/infrastructure/SyncQuarantineEntity.java` + `SyncQuarantineRepository.java` — NEW — JPA entity + repository
- `com/syncro/sync/application/FieldClassificationService.java` — NEW — loads `sync_field_mappings`, caches in-memory, provides `isMaster(fieldName): boolean`
- `com/syncro/maintenance/application/WorkorderImportService.java` — MODIFY — gate update path by field classification; add terminal-state/parent-close guards; return `UpsertResult` record
- `com/syncro/sync/domain/UpsertResult.java` — NEW — record: `action (CREATED|UPDATED|REJECTED)`, `reason (optional)`, `quarantineReason (optional)`
- `com/syncro/sync/application/SyncBatchProcessor.java` — MODIFY — handle `UpsertResult`, write quarantine rows for rejections, use `SyncProperties.fallbackCategoryCode()`
- `com/syncro/config/SyncProperties.java` — MODIFY — add `fallbackCategoryCode` field
- `com/syncro/maintenance/application/WorkOrderService.java` — READ — `SOURCE_INTERNAL` constant, `recomputeProcurementState` pattern for ON_PROCUREMENT check
- `com/syncro/maintenance/domain/workorder/WorkOrderStatus.java` — READ — DONE, CLOSED, ON_PROCUREMENT enum values

## Tasks & Acceptance

**Execution:**
- `resources/db/migration/V64__sync_field_mappings_and_quarantine.sql` — NEW — sync_field_mappings + sync_quarantine + audit CHECK extend + seed default mappings
- `com/syncro/sync/domain/SyncFieldDomain.java` — NEW — enum
- `com/syncro/sync/domain/UpsertResult.java` — NEW — result record
- `com/syncro/sync/infrastructure/SyncFieldMappingEntity.java` + `SyncFieldMappingRepository.java` — NEW — config + repo
- `com/syncro/sync/infrastructure/SyncQuarantineEntity.java` + `SyncQuarantineRepository.java` — NEW — quarantine + repo
- `com/syncro/sync/application/FieldClassificationService.java` — NEW — read + cache field mappings
- `com/syncro/maintenance/application/WorkorderImportService.java` — MODIFY — field classification gate, terminal-state guard, parent-close guard, UpsertResult return
- `com/syncro/sync/application/SyncBatchProcessor.java` — MODIFY — handle UpsertResult, quarantine write, fallback category
- `com/syncro/config/SyncProperties.java` — MODIFY — add fallbackCategoryCode
- `application.yml` + `.env.example` — MODIFY — add fallback category config
- Tests — WorkorderImportService extended (terminal-state, parent-close, field classification, ON_PROCUREMENT), SyncBatchProcessor extended (quarantine writes, fallback category), FieldClassificationService test, V64 migration test

**Acceptance Criteria:**
- Given a synced workorder is upserted, when external master fields arrive, then master fields take external values and local operational fields are preserved. [FR-152]
- Given the field classification is configuration, via sync_field_mappings, then unmapped fields default to MASTER. [AD-8]
- Given a DONE/CLOSED workorder receives a regressed external status, then the row is quarantined with TERMINAL_STATE_PROTECTED and the workorder is not reopened. [NFR-P2-9]
- Given a workorder is in ON_PROCUREMENT, then external status does not override it. [NFR-P2-9]
- Given a child workorder is upserted when the parent is CLOSED, then the child is rejected (quarantined). [NFR-P2-9]
- Given a batch completes, then resolution is deterministic and audit-logged per run. [FR-152]

## Design Notes

- Field classification is applied at the service layer, not the entity. The `applySync` method on `WorkOrderEntity` is a bulk setter; the service decides which fields to call it with based on the field domain.
- `FieldClassificationService` loads the full `sync_field_mappings` table into a `Map<String, FieldDomain>` at startup (or on first use with a cache). The cache is invalidated by a `@PostConstruct` or a reasonable TTL — since the table changes infrequently, a simple `@PostConstruct` load + `@Cacheable` is sufficient.
- The `sync_quarantine` table is intentionally simple in 13-2: `sheet_no`, `reason`, `raw_payload`, `trace_id`. Story 13-3 adds the observability (health dashboard, view page). The `raw_payload` is the external `SyncSourceRow` serialized as JSON so the operator can diagnose the rejection.
- `UpsertResult` drives the `importBatch` return to include both upserted and rejected counts. The signature changes from `int importBatch(List<SyncSourceRow>)` to a `BatchResult` record with `int upserted, int rejected, List<QuarantineEntry> entries`.
- Fallback category: a simple `String fallbackCategoryCode` in `SyncProperties`. If set and the external category code is unmapped, `SyncBatchProcessor` uses the fallback category instead of skipping. This resolves OQ-4's minimum viable path.
- Parent-close check: read the parent workorder by `parentSheetNo` (same `WorkOrderRepository.findById` used for upsert). If the parent is CLOSED, reject. This is a lightweight check; the parent's `SELECT FOR UPDATE` is not needed here since the parent's state is already terminal.

## Verification

**Commands:**
- `./mvnw.cmd -f syncro/apps/backend/pom.xml test "-Dtest=*WorkorderImport*,*FieldClassification*,*SyncBatchProcessor*,*V64MigrationTest*"` -- expected BUILD SUCCESS
- `./mvnw.cmd -f syncro/apps/backend/pom.xml compile` -- expected BUILD SUCCESS