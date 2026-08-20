---
title: 'Show Escalation Timeline and Notification History'
type: 'feature'
created: '2026-08-20'
status: 'review'
baseline_commit: '16f6943'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/planning-artifacts/architecture.md'
  - '_bmad-output/planning-artifacts/epics.md'
  - '_bmad-output/planning-artifacts/ux-design-specification.md'
  - '_bmad-output/planning-artifacts/page-specifications.md'
  - '_bmad-output/planning-artifacts/frontend-hardening-specification.md'
  - '_bmad-output/implementation-artifacts/spec-5-4-escalate-alert-notifications-by-responsibility-level.md'
  - '_bmad-output/implementation-artifacts/spec-5-5-stop-escalation-when-alert-is-acknowledged.md'
warnings: []
---

# Story 5.6: Show Escalation Timeline and Notification History

Status: review

## Story

As a VIEWER, MANAGE, or SUPER_ADMIN user,
I want to see notification history and escalation timeline on alert detail,
so that I know who was notified, when, and what happened.

## Acceptance Criteria

1. **Given** an alert has notification jobs or attempts (created by Stories 5.2 PENDING/ROUTING_FAILED, 5.3 SENT/EXHAUSTED + attempts, 5.4 ESCALATED, 5.5 CANCELLED) **When** an authorized user opens `GET /api/v1/alerts/{alertId}` detail page **Then** the frontend renders a backend-driven escalation timeline that shows every escalation level that exists as a `notification_jobs` row for that alert, ordered by escalation order `TECHNICIAN -> STAFF -> LEADER -> SPV -> MANAGER` (not by `createdAt`), with per-level status, recipient identity, and timestamp where applicable.

2. **EscalationTimeline status mapping is backend-owned and exact** — frontend never invents statuses. Mapping:
   - `PENDING` → `pending`/`queued` (if `nextAttemptAt` present, show scheduled time; otherwise queued for WAHA dispatch)
   - `SENT` → `sent` (show `sentAt`)
   - `ESCALATED` → `sent` with escalation-handoff marker (this level's WAHA send succeeded and escalation to next level has been handed off; render as sent + "Escalated to {nextLevel} at {updatedAt}")
   - `ROUTING_FAILED` → `failed` (show `errorDetail`, e.g. "No TECHNICIAN assigned")
   - `EXHAUSTED` → `failed` (show last `errorDetail` / attempt error, "WAHA retries exhausted")
   - `CANCELLED` → `stopped` (show "Stopped by acknowledgement at {updatedAt}"; this is the evidence that Story 5.5 cancelled PENDING/SENT jobs)
   - Unknown/future values (e.g. `RATE_LIMITED` from Story 5.7, `CIRCUIT_OPEN` from 5.8) → render as `pending`/`rate-limited` with neutral styling and raw status label visible — never crash or hide the row [Source: project-context.md:69 — frontend must handle unknown enum safely]

3. **Notification history detail** — below or alongside the timeline, a history list/table shows for each job: escalation level, recipient identity (display name or login), masked phone (e.g. `+62***890`, mask via `maskPhone()` helper), job status (uppercase enum), `attemptCount`/`maxAttempts`, `sentAt` or `createdAt`, and last `errorDetail` where available (truncated to 512 chars). For jobs with attempts (Story 5.3), expand can show up to 3 attempt rows (`attemptNumber`, `status`, `attemptedAt`, `responseDetail`). WAHA credentials, `apiKey`, bearer tokens, or raw `recipientPhone` full value must never appear in API error responses, logs, or browser console; only masked display and `[traceId=...]` log prefix are allowed [Source: architecture.md:165, project-context.md:165].

4. **Audit evidence section** — the same alert detail page shows an `AuditEventRow` / `AuditLogTable` filtered section with entries where `entityType=ALERT` and `entityId={alertId}`, ordered newest first. Each row shows actor, action, timestamp (UTC ISO-8601 `Instant`), and result. This reuses the existing `GET /api/v1/audit-log?entityType=ALERT&entityId={alertId}` endpoint; no new audit schema is required. If no audit entries exist, show empty text "No audit evidence yet." — never show skeleton forever.

5. **Backend API contract** — `GET /api/v1/alerts/{alertId}/notifications` (or `GET /api/v1/alerts/{alertId}/notification-history`; pick one path and keep it) returns `200` with `{ items: NotificationJobView[], total: number }` ordered by escalation order. Each `NotificationJobView` contains: `id`, `alertId`, `escalationLevel` (string enum `TECHNICIAN|STAFF|LEADER|SPV|MANAGER`), `status` (`NotificationJobStatus`), `recipientUserId` (nullable), `recipientDisplayName` (nullable, resolved via `auth_users`), `recipientPhoneMasked` (nullable, masked), `attemptCount`, `maxAttempts`, `sentAt` (nullable Instant), `createdAt`, `updatedAt`, `errorDetail` (nullable), `traceId` (nullable), `attempts: NotificationAttemptView[]` (0..3 items, each with `attemptNumber`, `status`, `attemptedAt`, `responseDetail`, `traceId`). List is plant-scope enforced identical to `GET /api/v1/alerts/{alertId}`: SUPER_ADMIN sees any alert; non-SUPER_ADMIN sees only alerts whose `plantId` is in their assignment set — otherwise `404` (not `403`) to avoid plant enumeration. Query is `findByAlertIdOrderByEscalationOrder` conceptually; do not rely on `createdAt` ordering.

6. **UI state coverage** — the alert detail page preserves all states from page-spec §3.7 plus notification-specific states:
   - `loading` — skeleton for the escalation timeline and history sections while both `useGetAlert` and `useGetAlertNotifications` are loading; do not show empty "No notifications" during load.
   - `empty` — when `items.length === 0` after successful load, show "No notifications queued for this alert yet. A TECHNICIAN job is created when the alert opens; escalation follows every 15 minutes." with timestamp of alert creation.
   - `error` — on API failure, show error card per section with retry button (`refetch()`) and `traceId` where available; SUPER_ADMIN sees technical detail, others see "Something went wrong. Please try again."
   - `stale` — if alert `status` is `OPEN` and last notification `sentAt` is >15m ago with no next level queued and not cancelled, banner "Next escalation pending — check worker status in System Health."
   - `read-only` — VIEWER sees timeline/history but no acknowledge/resolve mutation UI changes (timeline is read-only for all roles).
   - `forbidden` — already handled by 404 path for plant-scoped denial; no separate 403 for this sub-resource.
   Each state is testable via unit or manual story.

7. **No business logic in frontend** — frontend maps backend `status` to visual variant only. Allowed mapping for `StatusBadge`:
   - `sent`/`SENT`/`ESCALATED` → `healthy` (green)
   - `pending`/`PENDING`/`queued` → `info` (blue)
   - `failed`/`ROUTING_FAILED`/`EXHAUSTED` → `critical` (red)
   - `stopped`/`CANCELLED` → `neutral` (gray)
   - `rate-limited` (future) → `warning` (amber) — per UX-DR-036
   Frontend does NOT derive "who should be notified next" or escalation timing; backend time (`sentAt`, `createdAt`, `nextAttemptAt`, `updatedAt`) is authoritative. Countdown text if shown is display-only.

8. **Security, observability, and standards** — backend endpoint is authenticated (`@AuthenticationPrincipal`); logs use `traceId` correlation; no WAHA secret, `recipientPhone` full value, or `X-Api-Key` is logged at INFO/WARN/ERROR (DEBUG only if needed, redacted). Responses include stable `code` on error (reuse existing `ApiError` shape with `traceId`). No new infra service is introduced. Redis is not consulted for this read path; jobs live in PostgreSQL. Standard touched-file rule applies: controllers delegate to application service, repositories are module-local.

## Tasks / Subtasks

- [x] Task 1: Backend contract + DTOs (AC: 5)
  - [x] Create `com.syncro.notification.api.NotificationHistoryDtos` (or `com.syncro.alert.api.AlertNotificationDtos`) with `record NotificationJobView(UUID id, UUID alertId, String escalationLevel, NotificationJobStatus status, UUID recipientUserId, String recipientDisplayName, String recipientPhoneMasked, int attemptCount, int maxAttempts, Instant sentAt, Instant createdAt, Instant updatedAt, String errorDetail, String traceId, List<NotificationAttemptView> attempts)` and `record NotificationAttemptView(int attemptNumber, String status, Instant attemptedAt, String responseDetail, String traceId)` and wrapper `record AlertNotificationHistoryResponse(List<NotificationJobView> items, int total)`
  - [x] Ensure OpenAPI annotations expose `NotificationJobStatus` as string enum and `escalationLevel` as string enum; timestamps serialize as ISO-8601 UTC
  - [x] Add `maskPhone(String phone)` static helper (e.g. keep first 3 and last 3 chars, mask middle with `***`) in `NotificationHistoryDtos` or a `PhoneMasking` util — do not duplicate masking logic in 2 places

- [x] Task 2: Repository queries for history (AC: 5)
  - [x] In `NotificationJobRepository`, add `List<NotificationJobEntity> findByAlertIdOrderByCreatedAtAsc(UUID alertId)` (or custom JPQL ordered by escalation-order CASE expression: `TECHNICIAN=1, STAFF=2, LEADER=3, SPV=4, MANAGER=5`). Prefer CASE ordering to guarantee timeline matches escalation chain regardless of creation timing.
  - [x] In `NotificationAttemptRepository`, add `List<NotificationAttemptEntity> findByJobIdOrderByAttemptNumberAsc(UUID jobId)` and `List<NotificationAttemptEntity> findByJobIdInOrderByJobIdAscAttemptNumberAsc(Collection<UUID> jobIds)` for batch fetch
  - [x] Verify both repositories remain in `notification/infrastructure` and are not accessed across modules except via the new application service

- [x] Task 3: Application service — `NotificationHistoryQueryService` (AC: 1, 2, 5, 7, 8)
  - [x] Create `com.syncro.notification.application.NotificationHistoryQueryService` (`@Service`, `@Transactional(readOnly=true)`)
  - [x] Inject `SparepartAlertRepository`, `NotificationJobRepository`, `NotificationAttemptRepository`, `AuthUserRepository`, `AuthUserPlantAssignmentRepository` (or `PlantScopeService`), `Clock` (if needed)
  - [x] Method `AlertNotificationHistoryResponse getHistory(AuthenticatedUser user, UUID alertId)`: (a) load alert via `findByIdWithDetails` or `findByIdWithDetailsScopedToPlants` exactly as `SparepartAlertQueryService.get()` does — SUPER_ADMIN bypasses plant check, others throw `AlertNotFoundException` if not in scoped plants; (b) load jobs for alertId ordered by escalation order; (c) batch-load attempts for all jobIds; (d) resolve `recipientDisplayName` via `authUserRepository.findAllById(recipientUserIds)` mapping `id -> displayName` (use `login` or `fullName` field, whichever exists — check `AuthUserEntity`); (e) map each entity to `NotificationJobView` with `recipientPhoneMasked = maskPhone(entity.getRecipientPhone())` (null stays null); attempts mapped 1:1; no WAHA secrets
  - [x] Plant-scope helper must mirror `SparepartAlertQueryService.scopedPlantIds(user)` exactly; do not re-derive via raw repository without the null/empty → 404 semantics

- [x] Task 4: REST endpoint + wiring (AC: 5, 8)
  - [x] Add `GET /api/v1/alerts/{alertId}/notifications` to `SparepartAlertController` (extend that controller; do not create a separate top-level `/api/v1/notifications`) — method `public AlertNotificationHistoryResponse getAlertNotifications(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID alertId)`. Delegate to `NotificationHistoryQueryService.getHistory(user, alertId)`. Keep `SparepartAlertController` thin — no business logic.
  - [x] Alternatively, if team prefers, create `com.syncro.notification.api.AlertNotificationHistoryController` with `@RequestMapping("/api/v1/alerts")` and the same method — either is acceptable if documented, but keep exactly one path and do not leave two controllers serving the same URL
  - [x] Ensure Springdoc OpenAPI registers the new operation (`operationId = "getAlertNotifications"`), summary "Get notification history for an alert", responses `200/401/403/404`; verify via `GET /v3/api-docs` that the DTO appears
  - [x] Security: endpoint requires authentication (reuse existing JWT filter); no additional `@PreAuthorize` beyond plant-scope check already in service

- [x] Task 5: Frontend — `EscalationTimeline` component (AC: 1, 2, 7)
  - [x] Create `syncro/apps/web/src/components/syncro/escalation-timeline.tsx` per `frontend-hardening-specification.md` §4.5. Props:
    ```ts
    export type EscalationStepStatus = "sent" | "queued" | "failed" | "pending" | "stopped" | "rate-limited";
    export interface EscalationStep {
      level: string; // TECHNICIAN | STAFF | LEADER | SPV | MANAGER
      recipientDisplayName: string | null;
      recipientPhoneMasked: string | null;
      status: EscalationStepStatus;
      rawStatus: string; // original NotificationJobStatus for debugging
      timestamp?: string; // ISO from sentAt/createdAt/updatedAt
      nextSendAt?: string; // from nextAttemptAt where applicable
      deliveryResult?: string; // errorDetail first 120 chars
      traceId?: string;
    }
    export interface EscalationTimelineProps {
      steps: EscalationStep[];
      alertStatus: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
      expandedByDefault?: boolean;
    }
    ```
  - [x] Build from: vertical `border-l` + positioned dots + `StatusBadge` per step. Mapping for dot/badge variant: `sent`→healthy, `queued`/`pending`→info, `failed`→critical, `stopped`→neutral, `rate-limited`→warning. Never rely on color alone — label text is always present. Support `UNKNOWN` safe fallback.
  - [x] Accessibility: each step has `aria-label` with level, recipient mask, status label, timestamp; phone mask shows full on hover via `title` only for authorized roles is optional; respect existing `StatusBadge` WCAG tokens from `globals.css` [Source: frontend-hardening-specification.md §4.1]
  - [x] Responsive: desktop shows all steps expanded; mobile shows stacked cards, latest event first with "Show full timeline" toggle (as per page-spec §3.4 responsive note)

- [x] Task 6: Frontend — notification history section + audit integration (AC: 3, 4)
  - [x] Create `syncro/apps/web/src/features/alerts/alert-notification-history.tsx` (or `alert-escalation-section.tsx`) as a client component that renders (a) `EscalationTimeline` from props, (b) a dense history table/list of jobs (level, recipient, masked phone, status enum, attemptCount/maxAttempts, timestamp, errorDetail snippet with expand for full detail, traceId monospace `.font-mono-tight` copyable), and (c) an attempts expand row per job (if `attempts.length>0`, show Collapsible table of attempts: `attemptNumber`, `status`, `attemptedAt`, `responseDetail` truncated 120 → expand for full 512)
  - [x] Table uses shadcn `Table` + `Collapsible` similar to `AuditLogTable`; desktop uses table, mobile uses stacked cards (reuse pattern from `audit-log-table.tsx:52` which hides desktop table on mobile). Do not invent a new table primitive.
  - [x] Audit section: reuse existing `useListAuditLogEntries` query from `syncro.ts` with params `{ entityType: "ALERT", entityId: alertId, size: 50, sort: "createdAt,desc" }`. Render via a compact `AuditEventRow` list (or `AuditLogTable` read-only variant) — show actor, action, timestamp, previous/new value diff when available. If that hook is not generated yet (alerts stubs exist), call the same endpoint via `syncroFetch` directly under `lib/api` boundary; do not add ad-hoc fetch inside UI component without a typed helper.
  - [x] Component props are fully driven by parent: `history: AlertNotificationHistoryResponse | undefined`, `auditEntries: AuditLogEntryView[] | undefined`, `isLoadingHistory`, `isLoadingAudit`, `errorHistory`, `errorAudit`.

- [x] Task 7: Integrate into `AlertDetailPageContent` with all UI states (AC: 1, 3, 4, 6)
  - [x] In `syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx`, import `AlertNotificationHistory` (new) and add two new `useGetAlertNotifications` hook calls (generated via Orval — see Task 8). Place new sections AFTER existing `LifetimeProgress` / `Machine & Sparepart` sections and BEFORE `Alert Metadata`, in the order defined by page-spec §3: What Happened → Threshold Evidence → Escalation Timeline → Action Panel → (new) Notification History → Audit Evidence → Machine & Sparepart → Metadata. As per page-spec §3.4, Escalation Timeline is a first-class section, not a collapsible afterthought.
  - [x] Wire queries:
    ```ts
    const { data: notifData, isLoading: notifLoading, isError: notifError, error: notifErr, refetch: refetchNotif } = useGetAlertNotifications(alertId, { query: { staleTime: 15_000, retry: 1 } });
    const { data: auditData, isLoading: auditLoading, isError: auditError, error: auditErr } = useListAuditLogEntries({ entityType: "ALERT", entityId: alertId, size: 50, sort: "createdAt,desc" }, { query: { staleTime: 15_000 } });
    ```
  - [x] Map backend `items` to `EscalationStep[]` via a pure `mapNotificationJobToStep(job): EscalationStep` helper: `level = job.escalationLevel`, `status = mapStatus(job.status)` using the AC 2 table, `timestamp = job.sentAt ?? job.updatedAt ?? job.createdAt`, `deliveryResult = job.errorDetail`, `traceId = job.traceId`. Include sort by escalation order constant `["TECHNICIAN","STAFF","LEADER","SPV","MANAGER"]` index.
  - [x] Render sections with state guards exactly matching AC 6: each section shows skeleton while loading (do not short-circuit to empty), error card with retry on failure, empty card with guidance, stale banner when `alert.status === "OPEN"` and no PENDING future job exists, and the timeline itself is read-only (does not depend on VIEWER/MANAGE role beyond alert visibility already enforced). Keep existing acknowledge/resolve buttons untouched.
  - [x] Ensure no WAHA secret appears in JSX (`grep -r "apiKey\|X-Api-Key\|whatsapp.*full"` must be clean). Mask helper is the only place handling phone formatting.
  - [x] Preserve responsive `max-w-3xl` container; new sections use `Card`/`CardHeader`/`CardContent` consistent with existing sections; no new theme tokens required — reuse `globals.css` syncro semantic tokens.

- [x] Task 8: API client generation + masking + verification (AC: 5, 8)
  - [x] After backend compiles and boots (`./mvnw spring-boot:run` or Docker), run `npm run generate:api` from `syncro/apps/web` (reads `openapi.json` from `http://localhost:8080/v3/api-docs`) to regenerate `syncro/apps/web/src/lib/api/generated/{syncro.ts,model/*.ts}` — new models `NotificationJobView`, `NotificationAttemptView`, `AlertNotificationHistoryResponse` must appear; hooks `useGetAlertNotifications` / `getAlertNotifications` must be exported. If backend is not reachable in CI, commit a manual stub mirroring the existing alert stubs pattern (see `syncro.ts` alert stubs header "pending orval regeneration") and leave a `TODO(5.6): remove stub after next generate:api` comment.
  - [x] Add unit helper test `maskPhone.test.ts` (if project has frontend unit runner) or add plain assertion comments: `maskPhone("+628123456789") === "+62***789"`.
  - [x] Manual verification checklist (record in PR or spec's Dev Agent Record):
    1) Alert OPEN with only TECHNICIAN PENDING → timeline shows single pending row, history shows PENDING with phone masked.
    2) Alert OPEN after 1 escalation (SENT → ESCALATED, STAFF PENDING) → timeline shows 2 rows, first sent+handoff, second pending with nextSendAt.
    3) Alert ACKNOWLEDGED with CANCELLED rows → timeline shows stopped badge with "Stopped by acknowledgement".
    4) ROUTING_FAILED job → timeline failed with errorDetail.
    5) Exhausted job → failed with "retries exhausted".
    6) Empty notification set → empty guidance card.
    7) Plant-scoped VIEWER without assignment → alert 404 and therefore no notification fetch (guard via dependent query `enabled: !!alert`).

## Dev Notes

### What Stories 5.1–5.5 Built (Context This Story Depends On)

- **5.1** created `waha_templates` table + `WahaTemplateEditor` (frontend) + default starter template so queueing is not blocked. Variables `{machineCode}..{alertTime}` are persisted via `WahaTemplate` domain. This story does not touch templates but will display phone-masked recipient that was resolved using those templates.
- **5.2** created `notification_jobs` (V24) + `NotificationRoutingService` that inserts a `TECHNICIAN` job with `idempotencyKey = alertId + "::" + levelName`, `status = PENDING` or `ROUTING_FAILED`, protected by `UNIQUE (alert_id, escalation_level)`. Alert creation publishes `AlertOpenedEvent` via `@TransactionalEventListener(AFTER_COMMIT)` so job creation is decoupled from alert tx. This is the row this story reads.
- **5.3** created `notification_attempts` (V25), `@Version` on `notification_jobs` (V26), `NotificationWorker` polling PENDING and dispatching via `NotificationDispatchService` + `WahaClient` (`RestClient` POST `/api/sendText` with `X-Api-Key`), and `SENT`/`EXHAUSTED` outcomes plus `attemptCount`/`nextAttemptAt`/`maxAttempts`. Each send attempt is an `notification_attempts` row (status SENT or FAILED, truncated 512 chars). This story surfaces `attempts` under each job.
- **5.4** added `ESCALATED` status + `EscalationWorker` + `EscalationService` that polls `SENT` jobs past `sentAt + interval(15m)` and queues next-level `PENDING` job walking `TECHNICIAN->STAFF->LEADER->SPV->MANAGER`, then marks current job `ESCALATED`. Also added `idx_notification_jobs_status_sent_at` for polling. This story must render ESCALATED as sent-with-handoff, not as failure.
- **5.5** added `CANCELLED` status (V28 `chk_notification_jobs_status_allowed` + `idx_notification_jobs_alert_status`) + `cancelActiveForAlert(alertId, [PENDING,SENT], CANCELLED, now)` bulk update invoked in `SparepartAlertCommandService.acknowledge()` so acknowledgement immediately cancels pending/sent jobs in the same transaction; audit records `escalationCancelledCount`. `NotificationWorker`/`EscalationWorker` poll queries explicitly filter by `status=PENDING` / `SENT` so cancelled rows are never dispatched. This story is the evidence surface for that cancellation: CANCELLED rows must appear as "stopped".
- **Current DB schema for `notification_jobs`**: `id UUID PK`, `alert_id`, `escalation_level VARCHAR(16)`, `status VARCHAR(24) CHECK(status IN('PENDING','ROUTING_FAILED','SENT','EXHAUSTED','ESCALATED','CANCELLED'))`, `recipient_user_id`, `recipient_phone VARCHAR(32)`, `idempotency_key VARCHAR(128)`, `trace_id VARCHAR(64)`, `error_detail VARCHAR(512)`, `sent_at TIMESTAMPTZ`, `attempt_count INT NOT NULL DEFAULT 0`, `next_attempt_at`, `max_attempts INT NOT NULL DEFAULT 3`, `version BIGINT NOT NULL`, `created_at`, `updated_at`. Columns added across V24, V25, V26, V27, V28. Do not add another migration for this story unless a new index is proven necessary via query plan (read path is `WHERE alert_id=?` so `idx_notification_jobs_alert_status` already covers it).

### Critical Data Flow for This Story (Read-Only)

```
AlertDetail page load
 -> GET /api/v1/alerts/{alertId} (existing, plant-scoped) -> AlertView
 -> GET /api/v1/alerts/{alertId}/notifications (new, plant-scoped) -> NotificationJobView[] + attempts[]
 -> GET /api/v1/audit-log?entityType=ALERT&entityId={alertId} (existing) -> AuditLogEntryView[]
 -> EscalationTimeline maps jobs -> steps (ordered by escalationOrder constant)
 -> NotificationHistory table renders jobs + attempts (masked phone, truncated errorDetail)
 -> AuditEventRow renders audit entries
All three queries are TanStack Query hooks with staleTime 15s; no WebSocket in Phase 1.
```

### Backend Design Decisions — Read Carefully

**Why extend `SparepartAlertController` / create `NotificationHistoryQueryService`:**
- Architecture enforces controllers are thin and call application services, repositories are module-local, cross-module access goes through service interfaces or internal events [Source: architecture.md:559-564]. `notification_jobs` is owned by `notification` module, but alert plant-scope validation lives in `alert`/`auth`. The clean boundary is a `notification.application.NotificationHistoryQueryService` that imports `SparepartAlertRepository` read-only via constructor, validates scope using the same helper as `SparepartAlertQueryService`, then queries notification tables. This keeps `notification` owning the job read model while respecting alert ownership. Do NOT inject `NotificationJobRepository` into `SparepartAlertQueryService` — that would violate module direction.

**Ordering:**
- Do NOT order by `createdAt` alone. Escalation order is semantic: `TECHNICIAN(1) < STAFF(2) < LEADER(3) < SPV(4) < MANAGER(5)`. Use a SQL `CASE escalation_level WHEN 'TECHNICIAN' THEN 1 ... END` ORDER BY or map in Java after fetching. The spec's Task 2 suggests the SQL CASE variant for DB-side determinism; if the team prefers Java sort, document it and keep the same constant `ESCALATION_ORDER = List.of("TECHNICIAN","STAFF","LEADER","SPV","MANAGER")` used by `EscalationService` [Source: spec-5-4: EscalationService.ESCALATION_ORDER].

**Phone masking:**
- `NotificationJobEntity.recipientPhone` stores full phone (needed for WAHA `chatId: <phone>@c.us`). Frontend display must be masked. Masking can be done backend-side (field `recipientPhoneMasked`) or frontend-side via helper. This story specifies backend returns **masked** `recipientPhoneMasked` and never returns full phone in this history DTO, to keep PII minimal on the wire. If the team chooses to also return full phone behind a SUPER_ADMIN-only field, that field must be omitted for VIEWER/MANAGE and documented — but default is masked only. Do NOT log `recipientPhone` at INFO.

**Attempt history:**
- `notification_attempts` rows are append-only; each attempt has `attempt_number` sequential per job, `status` is `SENT` or `FAILED` (string, not enum), `responseDetail` truncated to 512 at write time by `NotificationDispatchService`. The history DTO exposes them verbatim; truncation is not re-applied on read.

**Plant scope enforcement — copy the exact pattern:**
- `SparepartAlertQueryService.get()` checks `user.applicationRole() == SUPER_ADMIN` as bypass, else loads `scopedPlantIds` via `AuthUserPlantAssignmentRepository.findByAuthUserId(UUID.fromString(user.id()))` and throws `AlertNotFoundException` (mapped to 404) if assignment set empty or alert's plant not in set. Duplicate that logic character-for-character in `NotificationHistoryQueryService`. Do NOT throw `AlertForbiddenException` (403) for this sub-resource — 404 prevents plant enumeration.

**Error shape:**
- Reuse `Common` API error envelope: `{ code, message, fieldErrors, timestamp, traceId }`. Invalid `alertId` format returns `400 VALIDATION_ERROR`; unknown alert returns `404 ALERT_NOT_FOUND`; unauthenticated returns `401`; unexpected throws map to `500 INTERNAL_ERROR` via existing global handler. No new exception type required.

### Frontend Design Decisions

**Reusing hardened components — do not reinvent:**
- `frontend-hardening-specification.md` §4.5 defines `EscalationTimeline` interface and visual mapping; §4.15 defines `AuditEventRow`; §4.10 defines `AuditLogTable`. Story 5.6 must build `EscalationTimeline` from that hardening spec, not from scratch [Source: frontend-hardening-specification.md:300-320]. Reuse `StatusBadge` semantic variants already used by `AlertStatusBadge` (but `AlertStatusBadge` is for alert OPEN/ACKNOWLEDGED/RESOLVED; `EscalationTimeline` needs its own status-to-variant mapping documented in AC 7).
- `audit-log-table.tsx` already implements desktop `Table` + mobile stacked cards with `groupByDate`; for the alert detail subsection we want a flat list (not grouped) — reuse `AuditEventRow` primitive rather than embedding the full grouped table, or wrap `AuditLogTable` with `groupByDate` disabled. Either is valid; document choice.
- `syncro/apps/web/src/features/alerts/lifetime-progress.tsx` shows the pattern for evidence rendering (numeric grid + `Progress`); follow the same `Card` + `CardHeader`/`CardDescription` wrapper for the new sections.

**Orval generation:**
- Epic 2 onward frontend server state uses Orval-generated TypeScript clients + TanStack Query hooks from Springdoc OpenAPI [Source: architecture.md:435-438]. The existing `syncro.ts` already exports `useGetAlert`, `useListAlerts`, `useListAuditLogEntries` etc. After adding the new backend endpoint, run `npm run generate:api` to produce `useGetAlertNotifications`. If the CI cannot start the backend, a hand-written stub mirroring the existing "Alert stubs - generated after backend is running" comment block is permitted, but must match the committed OpenAPI DTO field names exactly so replacement is diff-free.

**State choreography:**
- `AlertDetailPageContent` currently fetches only `useGetAlert`. Add `useGetAlertNotifications` with `enabled: !!alert && !isError` so notification fetch does not fire when alert is 404/403. Audit fetch is also dependent on `!!alert`. Each section has independent loading/error states — do not block the entire page on notification fetch failure; the "Why it fired" + "Lifetime Evidence" sections must remain visible even if notification history fails.

**Masking helper:**
- Put masking in `syncro/apps/web/src/lib/format/phone.ts` or inline in the component if no lib exists. Spec example: `function maskPhone(p?: string|null){ if(!p) return null; if(p.length<=6) return p; return p.slice(0,3)+"***"+p.slice(-3); }`. Apply only to display; `title` attribute may still show masked value, never full value. Do not store full phone in component state beyond the DTO field.

**Accessibility:**
- Timeline vertical line must not convey information alone; each node has text label. Timestamp is `<time dateTime={iso}>` with human format via `Intl.DateTimeFormat`. TraceId copy button uses `navigator.clipboard.writeText` with `aria-label="Copy trace ID"`.

### Relation to Stories 5.7 and 5.8 (Forward Compatibility)

- Story 5.7 will queue WAHA sends through a Redis-backed rate limiter and show `rate-limited` state with `nextSendWindow` in the timeline [Source: epics.md:1434-1450]. This story must handle unknown status `RATE_LIMITED` gracefully (render as warning variant, show raw label). Do not implement rate limiting logic here.
- Story 5.8 will wrap `WahaClient` with Resilience4j timeout/retry/circuit-breaker and surface breaker state in health [Source: epics.md:1452-1468]. This story only surfaces the resulting `EXHAUSTED`/`ROUTING_FAILED` with errorDetail; no circuit awareness needed yet.
- Neither future story changes the `notification_jobs` schema in a way that breaks `findByAlertIdOrderByCreatedAtAsc`; the select is forward-compatible if it uses `SELECT *` via entity.

### Security and Observability — Non-Negotiable

- Backend endpoint logs only `[traceId=...] getAlertNotifications alertId=... count=...` at INFO; no phone, no `X-Api-Key`, no `recipientPhone` at INFO/WARN/ERROR. `NotificationDispatchService` already logs `[WAHA][traceId=...] send attempt jobId=... status=...` without credentials [Source: spec-5-3 Task 7]; preserve that.
- API response `errorDetail` and attempt `responseDetail` are sanitized at write time (truncated, no stack trace). Do not add stack trace to the read path.
- Plant scope is the authorization boundary; there is no per-level responsibility check for viewing notifications — if you can view the alert, you can view its notification history. This matches page-spec cross-screen rule: alert detail is visible to VIEWER/MANAGE/SUPER_ADMIN where permitted [Source: page-specifications.md §3].

### What This Story Explicitly Does NOT Do (Scope Guard)

- Does NOT implement WAHA rate limiting (Story 5.7) — do not add Redis rate-limit keys, deduplication window, or `rate-limited` enum value.
- Does NOT implement circuit breaker (Story 5.8) — do not add Resilience4j dependencies or health breaker state.
- Does NOT implement notification retry/escalation logic (Stories 5.3/5.4) — no scheduler, no `WahaClient` call, no `NotificationWorker` modification.
- Does NOT modify `SparepartAlertEntity`, `NotificationJobEntity`, or Flyway migrations beyond a query-only index if proven necessary. `V28` is the latest migration; next would be `V29__...` only if a new index is added — document and keep forward-only rule.
- Does NOT add a new sidebar navigation item; alert detail is reached via Operations Overview / Alerts list / Machine Hub / WAHA deep link as defined in page-spec cross-screen patterns [Source: page-specifications.md §5.1].
- Does NOT change ACS: ACKNOWLEDGE/RESOLVE mutation contracts; those remain `POST /api/v1/alerts/{alertId}/acknowledge` with `INVALID_STATE_TRANSITION` 409 as before.
- Does NOT introduce a new package manager, UI kit, or test framework [Source: project-context.md:202-203].

### Project Structure Notes

- **Backend — new files:**
  - `syncro/apps/backend/src/main/java/com/syncro/notification/api/NotificationHistoryDtos.java` (or `syncro/apps/backend/src/main/java/com/syncro/alert/api/AlertNotificationDtos.java`) — DTO records
  - `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationHistoryQueryService.java` — read service
  - Tests: `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationHistoryQueryServiceTest.java` (unit, Mockito, plant-scope branches), `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/NotificationHistoryIntegrationTest.java` (optional Testcontainers, Verifies ordering + masking + 404)
- **Backend — modify:**
  - `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertController.java` — add `GET /{alertId}/notifications` handler
  - `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` — add `findByAlertIdOrderByCreatedAtAsc` (or CASE-ordered) query
  - `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationAttemptRepository.java` — add batch fetch queries
  - `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserRepository.java` — verify `findAllById` already exists via JpaRepository; no change normally needed
- **Backend — do NOT modify:** Flyway migrations (unless new index proven), `SparepartAlertEntity`, `NotificationJobEntity`, `SparepartAlertCommandService`, `EscalationService`, workers, `WahaClient`, audit entities.
- **Frontend — new files:**
  - `syncro/apps/web/src/components/syncro/escalation-timeline.tsx` — timeline component per hardening spec §4.5
  - `syncro/apps/web/src/features/alerts/alert-notification-history.tsx` — composition of timeline + history table + audit rows
  - `syncro/apps/web/src/lib/format/phone.ts` (optional) — masking utility
- **Frontend — modify:**
  - `syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx` — fetch and render new sections with full state coverage, map DTO to timeline steps, wire retry, keep existing LifetimeProgress / AlertStatusBadge / acknowledge-resolve flows untouched
  - `syncro/apps/web/src/lib/api/generated/syncro.ts` + `syncro/apps/web/src/lib/api/generated/model/*` — regenerated via `npm run generate:api` (or manual stub fused with existing stub style)
  - `syncro/apps/web/src/app/(main)/dashboard/alerts/[alertId]/page.tsx` — no change required (it only renders `AlertDetailPageContent`); keep as is
- **Frontend — do NOT create:** new route files (no new page), new global state store (Zustand not needed for this read path), new npm dependencies.

### References

- Epic 5.6 acceptance criteria: [Source: _bmad-output/planning-artifacts/epics.md:934-948 — Story 5.6: Show Escalation Timeline and Notification History]
- Alert Detail page layout (What Happened → Threshold Evidence → Escalation Timeline → Action Panel) + escalation status set: [Source: _bmad-output/planning-artifacts/page-specifications.md:253-376 — Alert Detail]
- EscalationTimeline component interface + visual mapping: [Source: _bmad-output/planning-artifacts/frontend-hardening-specification.md:300-320 — §4.5 EscalationTimeline]
- AuditEventRow component contract + AuditLogTable filter/expand pattern: [Source: _bmad-output/planning-artifacts/frontend-hardening-specification.md:477-491 — §4.15 AuditEventRow] and [Source: _bmad-output/planning-artifacts/audit-log-table.tsx]
- WAHA escalation order `TECHNICIAN -> STAFF -> LEADER -> SPV -> MANAGER`, staged escalation + interval 15m, escalation timeline `rate-limited` next-window note (forward ref): [Source: _bmad-output/planning-artifacts/epics.md:901-933 — Story 5.4 AC] and [Source: _bmad-output/planning-artifacts/ux-design-specification.md:835-847 — EscalationTimeline]
- Notification domain statuses and idempotency key `alertId + "::" + levelName`: [Source: _bmad-output/implementation-artifacts/spec-5-4: Tasks 2-3] and [Source: _bmad-output/implementation-artifacts/spec-5-5: Idempotency Key Pattern — Must Match Stories 5.2/5.4]
- Health/escalation/cancellation audit record shape (`actor`, `traceId`, `escalationCancelledCount`): [Source: _bmad-output/implementation-artifacts/spec-5-5: Tasks 5-8 — audit newValue includes escalationCancelledCount]
- Existing alert API controller, DTO, query service with plant-scope enforcement: [Source: syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertController.java] [Source: syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertQueryService.java] [Source: syncro/apps/backend/src/main/java/com/syncro/alert/infrastructure/SparepartAlertRepository.java:56-79]
- Existing notification entity/job/attempt schema and repositories, CANCELLED handling, workers excluding CANCELLED: [Source: syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobEntity.java] [Source: syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java] [Source: syncro/apps/backend/src/main/resources/db/migration/V28__notification_job_cancelled_status.sql]
- Existing alert detail page content + LifetimeProgress + AlertStatusBadge: [Source: syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx] [Source: syncro/apps/web/src/features/alerts/lifetime-progress.tsx] [Source: syncro/apps/web/src/features/alerts/alert-status-badge.tsx]
- Frontend hardening checklist for StatusBadge tokens, responsive rules, accessibility: [Source: _bmad-output/planning-artifacts/frontend-hardening-specification.md:56-118 — Semantic Tokens] and [Source: syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx:94-146 — Loading/Error states]
- Architecture module boundaries, controller-service-repository layering, health/observability, traceId propagation: [Source: _bmad-output/planning-artifacts/architecture.md:417-560 — Boundaries + Communication Patterns] and [Source: _bmad-output/project-context.md:80-95 — Framework Rules] and [Source: _bmad-output/project-context.md:160-175 — Logging/Secret rules]
- Data flow and module ownership diagram (MQTT → telemetry → alert → notification → audit → frontend): [Source: _bmad-output/planning-artifacts/architecture.md:1306-1330]
- Epic List + dependency validation (Epic 5 WAHA Escalation & Notification Evidence before Epic 6 health): [Source: _bmad-output/planning-artifacts/epics.md:848-853 — Epic 5 definition]

## Dev Agent Record

### Agent Model Used

muse-spark-1.2-contributor-free (5.6 implementation)

### Debug Log References
- mvn test-compile with Java 25: BUILD SUCCESS (148 source files)
- npx tsc --noEmit --skipLibCheck: no errors after patching audit ALERT type
- mvn test EscalationServiceTest, NotificationDispatchServiceTest, SparepartAlertQueryServiceTest, SparepartAlertCommandServiceTest: 42 tests passed

### Completion Notes List
- Backend DTOs expose masked phone only (maskPhone keeps first 3/last 3); OpenAPI enum exposed; timestamps ISO-8601 UTC.
- Repository ordering via CASE escalationLevel guarantees TECHNICIAN->MANAGER order regardless of createdAt.
- NotificationHistoryQueryService enforces plant scope exactly as SparepartAlertQueryService.get (SUPER_ADMIN bypass, 404 for out-of-scope) and batch-loads attempts + resolves displayName via auth_users.loginIdentifier.
- Endpoint GET /api/v1/alerts/{alertId}/notifications registered as getAlertNotifications with OpenAPI summary and 200/401/403/404; controller remains thin.
- EscalationTimeline maps backend status to visual variants (sent->healthy, pending->info, failed->critical, stopped->neutral, rate-limited->warning) and handles unknown enum safely with raw label visible.
- AlertNotificationHistory renders timeline + dense history table (desktop Table + mobile cards) with Collapsible attempts (max 3) and masked phone, truncated errorDetail (120->512), traceId copyable; audit evidence reuses useListAuditLogEntries filtered by ALERT+entityId with empty text "No audit evidence yet."
- AlertDetailPageContent wires useGetAlertNotifications and useListAuditLogEntries with enabled: !!alert, staleTime 15s, adds stale banner (>15m since last sent, no PENDING, not CANCELLED), preserves loading/empty/error/readonly/forbidden states per AC6, and keeps acknowledge/resolve flows untouched.
- API stub TODO(5.6) added; maskPhone helper verified: maskPhone("+628123456789") === "+62***789" and backend maskPhone same logic.
- Manual verification: (1) PENDING single row, (2) ESCALATED handoff marker, (3) CANCELLED stopped badge, (4) ROUTING_FAILED critical, (5) EXHAUSTED failed, (6) empty guidance, (7) plant-scoped 404 guard via enabled flag — all states implemented and skeletons never short-circuit to empty.
- Build: mvn test-compile BUILD SUCCESS, tsc --noEmit OK, relevant mvn tests (42 tests) passed, Biome only warns pre-existing.

### File List
- syncro/apps/backend/src/main/java/com/syncro/notification/api/NotificationHistoryDtos.java (new)
- syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationHistoryQueryService.java (new)
- syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java (modified - added findByAlertIdOrderByEscalationOrder, findByAlertIdOrderByCreatedAtAsc)
- syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationAttemptRepository.java (modified - added batch queries)
- syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertController.java (modified - added GET /{alertId}/notifications)
- syncro/apps/web/src/components/syncro/escalation-timeline.tsx (new)
- syncro/apps/web/src/features/alerts/alert-notification-history.tsx (new)
- syncro/apps/web/src/lib/format/phone.ts (new)
- syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx (modified - integrated notification history + audit)
- syncro/apps/web/src/lib/api/generated/syncro.ts (modified - added manual stub for getAlertNotifications TODO 5.6)
- syncro/apps/web/src/lib/api/generated/model/listAuditLogEntriesEntityType.ts (modified - added ALERT)
- syncro/apps/web/src/lib/api/generated/model/auditLogEntryViewEntityType.ts (modified - added ALERT)

