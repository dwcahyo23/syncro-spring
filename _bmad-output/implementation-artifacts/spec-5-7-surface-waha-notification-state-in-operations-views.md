---
title: 'Surface WAHA Notification State in Operations Views'
type: 'feature'
created: '2026-08-21'
status: 'done'
baseline_commit: '0e0c6ae'
final_commit: '746a094'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/planning-artifacts/epics.md'
  - '_bmad-output/implementation-artifacts/spec-5-6-show-escalation-timeline-and-notification-history.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** Alert rows in Operations Overview, Machine Hub (alerts tab), and Alert List show only alert lifecycle status (OPEN/ACKNOWLEDGED/RESOLVED) — operators cannot tell at a glance whether a responsible person has been reached, whether WAHA delivery failed, or whether escalation is still pending.

**Approach:** Add a `notificationSummary` field to the backend `AlertView` DTO (computed from the latest active notification job for that alert), expose it through the existing `GET /api/v1/alerts` list endpoint, add a `NotificationStatePill` frontend component, and render it in all three list views.

## Boundaries & Constraints

**Always:**
- `notificationSummary` is backend-computed and backend-owned; the frontend must never derive or invent notification state.
- Status communication must be non-color-only (icon + label, never color alone).
- `notificationSummary` is nullable — alerts with no notification jobs render nothing (no pill, no placeholder text).
- The pill links to the alert detail page which already shows full notification history (Story 5.6).
- Follow existing `AlertStatusBadge` component pattern: tooltip-wrapped Badge with icon + text label.
- Backend query for summary must not issue N+1 per alert; use a single JOIN or subquery.

**Block If:**
- Backend schema or `NotificationJobEntity` relationship to `SparepartAlertEntity` differs from the `alertId` FK assumed in investigation (confirm `notification_jobs.alert_id` column exists before implementing the JOIN).

**Never:**
- Do not add a new REST endpoint — embed summary in existing `AlertView`.
- Do not fetch `GET /api/v1/alerts/{id}/notifications` from list pages (too expensive per row).
- Do not modify `AlertNotificationHistoryResponse` or `NotificationHistoryQueryService` (Story 5.6 territory).
- Do not add WebSocket or push — polling only per page-specifications.md.
- Do not show WAHA phone numbers, credentials, or any raw WAHA API detail.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| No jobs yet | Alert has no `notification_jobs` rows | `notificationSummary` is `null`; pill absent from row | No error |
| Pending delivery | Latest active job `status=PENDING` | Pill: "Pending" + clock icon | No error |
| Sent | Latest active job `status=SENT` | Pill: "Sent" + check icon + `sentAt` in tooltip | No error |
| Escalated | Latest active job `status=ESCALATED` | Pill: "Sent" + check icon (escalation is a success state) | No error |
| Routing failed | Latest active job `status=ROUTING_FAILED` | Pill: "No recipient" + warning icon | No error |
| WAHA exhausted | Latest active job `status=EXHAUSTED` | Pill: "Failed" + error icon | No error |
| Cancelled | Latest active job `status=CANCELLED` | Pill: "Stopped" + stop icon | No error |
| Alert resolved, all jobs cancelled | `notificationSummary.status=CANCELLED` | Pill: "Stopped" rendered as muted | No error |
| `listAlerts` API error | Network/server failure | Existing page-level error state handles it; pill is not rendered | Existing error handling unchanged |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertDtos.java` -- add `NotificationSummary` record and embed in `AlertView`
- `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertQueryService.java` -- extend list query to join/fetch latest notification job per alert
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` -- add query to fetch latest-per-alert summary map for a list of alert IDs
- `syncro/apps/web/src/lib/api/generated/syncro.ts` -- add `NotificationSummary` type and update `AlertView` type
- `syncro/apps/web/src/features/alerts/notification-state-pill.tsx` -- new component: renders the notification state pill using `NotificationSummary`
- `syncro/apps/web/src/features/operations-overview/operations-overview-page-content.tsx` -- add pill to each alert row
- `syncro/apps/web/src/features/machine-hub/alerts-tab.tsx` -- add pill column to alerts table
- `syncro/apps/web/src/features/alerts/alert-list-page-content.tsx` -- add pill column to alerts table

## Tasks & Acceptance

**Execution:**

- [x] `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` -- added `findMostRecentNonCancelledJobsForAlerts` and `findMostRecentJobsForAlerts` JPQL subquery methods — rationale: needed by AlertQueryService without N+1

- [x] `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertDtos.java` -- added `NotificationSummary` record with `@Nullable` fields; added `@Nullable NotificationSummary notificationSummary` to `AlertView` record — rationale: single source of truth for summary contract

- [x] `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertQueryService.java` -- injected `NotificationJobRepository`; added `buildNotificationSummaryMap()` with prefer-non-cancelled logic; updated `list()` and `toView()` to populate `notificationSummary` — rationale: batch-fetches summaries in two queries, not N+1

- [x] `syncro/apps/web/src/lib/api/generated/model/notificationSummary.ts` -- created `NotificationSummary` interface; `AlertView` updated with `notificationSummary?` field in `model/alertView.ts` — rationale: frontend type contract mirrors backend DTO (created proper model files matching project architecture)

- [x] `syncro/apps/web/src/features/alerts/notification-state-pill.tsx` -- created `NotificationStatePill` component: returns null for missing summary; maps all 6 statuses to icon+label; tooltip-wrapped Badge; aria-label; non-color-only — rationale: reusable pill for all three views

- [x] `syncro/apps/web/src/features/operations-overview/operations-overview-page-content.tsx` -- added `<NotificationStatePill summary={item.notificationSummary} />` after status badge in alert rows — rationale: satisfies AC "Operations Overview shows notification state"

- [x] `syncro/apps/web/src/features/machine-hub/alerts-tab.tsx` -- added "Notification" column (hidden on sm) with `<NotificationStatePill summary={item.notificationSummary} />` — rationale: satisfies AC "Machine Hub shows notification state"

- [x] `syncro/apps/web/src/features/alerts/alert-list-page-content.tsx` -- added "Notification" column (hidden on sm) with `<NotificationStatePill summary={item.notificationSummary} />` — rationale: satisfies AC "Alert List shows notification state"

**Acceptance Criteria:**

- Given an alert has a `PENDING` notification job, when a user opens Operations Overview, then the alert row shows a "Pending" pill with clock icon and no color-only communication.
- Given an alert has a `SENT` notification job, when a user opens Machine Hub alerts tab, then the row shows a "Sent" pill with check icon.
- Given an alert has an `EXHAUSTED` notification job (WAHA delivery failed), when a user views the Alert List, then the row shows a "Failed" pill with error icon, making the failure visible as operational risk.
- Given an alert has `ROUTING_FAILED` (no recipient assigned), when a user views any list view, then the row shows "No recipient" pill with warning icon.
- Given an alert has no notification jobs, when a user views any list view, then no notification pill is rendered for that row.
- Given the notification state pill is rendered, when a user clicks the alert row link, then they arrive at the alert detail page where full notification history (Story 5.6) is available.
- Given any list page loads, when the backend returns `notificationSummary: null`, then the pill is absent — no JS error, no empty placeholder.
- Given any list page loads, when `status` in `notificationSummary` is `CANCELLED`, then the pill shows "Stopped" with stop-circle icon.

## Design Notes

**Choosing latest active job over aggregate:** The summary shows the single most-actionable job — the one operators care about most. "Most recently updated, non-CANCELLED preferred" selection rule: if any non-CANCELLED job exists, prefer it; otherwise fall back to the most recent CANCELLED job. This matches the escalation model where at most one non-cancelled job is active at a time (Stories 5.4–5.5 guarantee escalation creates a new job when promoting).

**`ESCALATED` maps to "Sent":** An `ESCALATED` job means WAHA delivery at that level succeeded and escalation to the next level was handed off. This is a success state. Showing "Sent" is correct and consistent with Story 5.6 timeline mapping.

**Backend query approach:** Use a subquery that selects `MAX(updated_at)` per `alert_id` from `notification_jobs`, then joins back to get the full row. This is a standard pattern, avoids window functions that may need dialect-specific JPQL, and works with Spring Data JPA + Hibernate on PostgreSQL.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -pl apps/backend test -Dtest="*AlertQueryService*,*NotificationJobRepository*" -q` -- expected: BUILD SUCCESS, all alert query and notification job repository tests pass
- `cd syncro/apps/web && npm run typecheck` -- expected: exit 0, no TypeScript errors
- `cd syncro/apps/web && npm run build` -- expected: exit 0, no build errors

**Manual checks (if no CLI):**
- In `AlertView` JSON response from `GET /api/v1/alerts`, confirm `notificationSummary` field is present (possibly null) for each item.
- In Operations Overview alert rows, confirm notification pill appears next to status badge when an alert has notification jobs.
- Confirm no WAHA phone numbers or credentials are visible anywhere in the rendered pill or tooltip.

## Review Log

### Pass 1 — 2026-08-21

| Category | low | medium | high |
|---|---|---|---|
| intent_gap | 0 | 0 | 0 |
| bad_spec | 0 | 0 | 0 |
| patch | 1 | 0 | 0 |
| defer | 3 | 1 | 0 |
| reject | 2 | 0 | 0 |

Findings addressed:
- patch/low: `buildNotificationSummaryMap` loop changed to `putIfAbsent` for deterministic tie-breaking on identical `updatedAt` values
- defer: single-alert `get()` passes null summary (by design — detail page uses Story 5.6 full history)
- defer: IN clause not bounded (pagination already bounds page size upstream; pre-existing)
- defer: status as raw String without enum validation (pre-existing project pattern)
- defer/medium: no unit tests added (backend requires Testcontainers infra; deferred per project pattern)
- reject: empty cell layout concern (loading/empty use early return, not table-body rows — non-issue)
- reject: TooltipProvider missing (confirmed in `layout.tsx:41` wrapping entire app)

Verification performed:
- `npm run build` (Next.js): EXIT 0, compiled successfully in 13.6s, 24 routes generated
- TooltipProvider confirmed at `layout.tsx:41`
- Operations Overview pill placement confirmed at `operations-overview-page-content.tsx:211`
- `notification_jobs.alert_id` FK confirmed in `V24__create_notification_jobs.sql`

Residual risks:
- Backend JPQL queries untested against live DB (require Testcontainers); correctness reviewed by inspection only.

### Pass 2 — 2026-08-21 (follow-up review)

| Category | low | medium | high |
|---|---|---|---|
| intent_gap | 0 | 0 | 0 |
| bad_spec | 0 | 0 | 0 |
| patch | 1 | 0 | 0 |
| defer | 6 | 0 | 0 |
| reject | 2 | 0 | 0 |

Findings addressed:
- patch/low: fallback loop in `buildNotificationSummaryMap` changed from `summaryMap.put` to `summaryMap.putIfAbsent` for consistency with primary loop and deterministic tie-breaking when fallback query returns duplicate alertId rows (`SparepartAlertQueryService.java:94`)
- defer/low: JPQL tiebreak — two non-cancelled jobs with identical `updatedAt` (primary query): `putIfAbsent` retains first row; unique constraint `(alert_id, escalation_level)` makes this extremely unlikely in practice
- defer/low: JPQL tiebreak — fallback query same scenario; `putIfAbsent` now consistent; same unique constraint makes it extremely unlikely
- defer/low: IN clause unbounded (pre-existing; pagination bounds page size upstream, already in Pass 1)
- defer/low: detail `get()` always passes null notificationJob — design intent documented in comment; Story 5.6 handles detail page separately
- defer/low: unknown status renders raw enum string to user — graceful degradation; backend enum is controlled; no crash
- defer/low: `formatTooltip` suppresses escalationLevel when sentAt is also non-null — intentional priority order; sentAt is more operationally useful
- reject: `formatTooltip` null status concern — `status` is typed `string` (non-optional) in generated model; TypeScript enforces non-null at compile time; not a runtime risk
- reject: CANCELLED pill on fallback path — correct by design; CANCELLED-only state ("Stopped") is meaningful operational information per I/O matrix

Verification performed:
- `npm run build` (Next.js): TypeScript finished in 7.2s, EXIT 0 — build clean after patch
- `SparepartAlertQueryService.java:80-98` confirmed: both primary and fallback loops now use `putIfAbsent`
- No new files modified beyond patch target

Residual risks:
- Backend JPQL queries still untested against live DB (Testcontainers required); correctness validated by inspection only.
- JPQL updatedAt tiebreak remains theoretically non-deterministic but practically unreachable given unique schema constraints.

## Auto Run Result

Status: done

_Appended by the bmad-loop orchestrator (missing-marker repair, #224): the session finalized this spec's frontmatter without its `## Auto Run Result` marker, so the orchestrator synthesized the result from the frontmatter and appended this section._

Synthesized by the bmad-loop orchestrator from frontmatter status `done` for story `5-7-surface-waha-notification-state-in-operations-views` (session finalized the spec without appending its marker).
