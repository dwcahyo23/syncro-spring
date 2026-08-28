# Epic 13 Context: External Sync & Hardening

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Synced workorders from the internal system's PostgreSQL arrive reliably and safely: transactional batches, watermark resume, quarantine, deterministic conflict resolution, and notification hygiene. This hardens the reference system's sync (`iot.gm.api.remastered` cron + `PgQuery`), which had no transaction, no watermark, no lock, an ordering bug, and notification storms.

## Stories

- Story 13.1: Sync Pipeline Foundation
- Story 13.2: Conflict Resolution & Field Mapping
- Story 13.3: Quarantine & Observability

## Requirements & Constraints

- A scheduled job imports new/updated workorders from a configured external PostgreSQL via typed config (`SyncProperties`) — no hardcoded host/credentials. Rows are processed in batches inside a transaction, ordered by `sheet_no` ASC, and the job resumes from a watermark (`last_synced_sheet_no`) after restart. A distributed lock (Redis/DB advisory) prevents concurrent runs.
- Upserts are idempotent by external id: re-sync of the same `sheet_no` updates, never duplicates. Duplicate or otherwise problematic rows land in quarantine with a reason — never silently dropped.
- Conflict resolution is deterministic and audit-logged per run: external master fields (status, timestamps, machine, category) take external values; local operational fields (report, evidence, ratings) are preserved.
- Terminal-state protection: a DONE/CLOSED workorder must never be reopened by sync (regression quarantines the row with `TERMINAL_STATE_PROTECTED`); external status never overrides a locally-derived ON_PROCUREMENT state; sync upsert of a child workorder is rejected (quarantined) when its parent is CLOSED.
- Failed records are persisted in `sync_quarantine` with reason, raw payload, and traceId; `sync_runs` records status, counts (created/updated/failed), timestamps, and error detail. The health dashboard shows last run, counts, and last run timestamp. An external DB outage degrades gracefully (retry/backoff, no crash).
- External timestamps arrive in Asia/Jakarta and are normalized to UTC at write; display converts at the boundary.
- Notifications are deduplicated and limited to newly created workorders / important transitions (no notification storm); the sync module never notifies independently.
- Module boundary: the sync module writes workorders only via `maintenance.workorder.application.WorkorderImportService` — never via JPA directly.
- Sync reliability: the job completes within a 15-minute window; zero duplicate workorders from re-sync.

## Technical Decisions

- **AD-7 — Hardened sync module:** batch-in-transaction, ordered by `sheet_no` ASC, upsert-idempotent by external id, watermark resume, distributed lock, quarantine with reason+payload, `sync_runs` audit, typed config, retry/backoff, Asia/Jakarta → UTC conversion.
- Every synced row passes through `WorkorderImportService.upsert()` — the single upsert path that owns status history, audit, ON_PROCUREMENT evaluation, and notification enqueueing. Sync does not bypass it and does not notify independently.
- **Mapping step:** external machine code → `machine_id` and external plant code → `plant_id` resolve through configurable mapping tables; unmapped machines/plants quarantine the row with a clear reason (no stub machines, no silent skip). External category maps via configurable mapping with a configurable fallback (pending OQ-4).
- **AD-8 — Field classification is configuration:** a `sync_field_mappings` table (`field_name`, `domain=MASTER|OPERATIONAL`) decides which fields sync may overwrite; unmapped fields default to MASTER. A `sync_version` field tracks which fields sync last updated.
- **Terminal-state protection mechanics:** sync must not transition DONE/CLOSED out of state (regressed external status → `TERMINAL_STATE_PROTECTED` quarantine); external status does not override derived ON_PROCUREMENT; parent-child close in the workorder module uses `SELECT FOR UPDATE` on children; sync child upsert rejects when parent CLOSED.
- New schema ships via Flyway (V41+), `ddl-auto=validate`, additive migrations only.
- Observability reuses the Phase 1 Epic 6 health-dashboard pattern.

## UX & Interaction Patterns

- The health dashboard surfaces sync observability for SUPER_ADMIN: last run, counts (created/updated/failed), and last run timestamp; quarantined records are viewable with reason, raw payload, and traceId.

## Cross-Story Dependencies

- Sync mutations invalidate analytics caches — MTBF/MTTR must recompute from the current dataset (`last_analytics_at` watermark), so sync runs affect the analytics/dashboard epic (AD-6).
- Notification enqueueing flows through `WorkorderImportService` into the notification module's outbox (AD-9 polymorphic target machinery) — the sync module must not write `notification_jobs` directly.
- OQ-4 (external priority → local category mapping) affects both AD-6 MTBF and AD-8 category mapping and must be resolved before sync implementation.
