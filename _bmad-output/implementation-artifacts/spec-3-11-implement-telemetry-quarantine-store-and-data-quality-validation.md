---
id: SPEC-3-11
type: feature
created: 2026-08-14
status: done
review_loop_iteration: 0
baseline_revision: none
baseline_commit: 21511c5c7eb59d87e6e18b49ba8afe89bf870eec
final_revision: none
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-3-context.md'
  - '_bmad-output/implementation-artifacts/spec-3-2-validate-mqtt-topic-and-base-payload.md'
  - '_bmad-output/implementation-artifacts/spec-3-3-reject-inactive-machine-telemetry.md'
  - '_bmad-output/implementation-artifacts/spec-3-9-enforce-telemetry-payload-contract-with-schema-version-messageid-and-timestamp.md'
  - '_bmad-output/implementation-artifacts/spec-3-10-validate-payload-topic-machine-identity-match.md'
  - '_bmad-output/planning-artifacts/architecture.md'
  - '_bmad-output/planning-artifacts/epics.md'
warnings: []
sources:
  - '_bmad-output/planning-artifacts/epics.md'
  - '_bmad-output/planning-artifacts/architecture.md'
  - '_bmad-output/implementation-artifacts/spec-3-9-enforce-telemetry-payload-contract-with-schema-version-messageid-and-timestamp.md'
---

# Story 3.11: Implement Telemetry Quarantine Store and Data Quality Validation

Status: done

## Story

As a system,
I want rejected telemetry messages to be persisted in a quarantine store with their rejection reason,
so that data quality issues are observable, diagnosable, and do not silently disappear.

## Intent

**Problem:** Today, when `TelemetryValidationService` rejects a message, `MqttTelemetryIngestHandler` only logs the rejection via `mqtt_telemetry_rejected`. There is no persistent record of what was rejected, why, or from which topic. Operators have no way to diagnose device misconfiguration, schema drift, or spoofed payloads without tailing logs in real-time. The architecture calls for a `telemetry_quarantine` PostgreSQL table and a `QuarantineLogTable` frontend component accessible from the health dashboard.

**Approach:**
1. Add Flyway migration `V9__create_telemetry_quarantine.sql` with the `telemetry_quarantine` table.
2. Add `TelemetryQuarantineEntity` JPA entity + `TelemetryQuarantineRepository`.
3. Add `TelemetryQuarantineService` that persists rejected envelopes.
4. Wire `MqttTelemetryIngestHandler` to call `TelemetryQuarantineService` on every `Result.Rejected`.
5. Add REST endpoint `GET /api/v1/telemetry/quarantine` (paginated, SUPER_ADMIN only) + DTO.
6. Add frontend `QuarantineLogTable` component + page integration under `features/system-health/`.

## Boundaries & Constraints

**Always:**
- `telemetry_quarantine` table stores: `id` (UUID PK), `trace_id`, `topic`, `raw_payload` (TEXT), `rejection_reason`, `rejection_field` (nullable), `received_at` (TIMESTAMPTZ), `created_at` (TIMESTAMPTZ DEFAULT NOW()).
- Quarantine write is fire-and-forget: failure to persist must NOT crash the ingest worker or affect the rejection path. Log a warning on persistence failure, swallow the exception.
- ALL rejection reasons persist to quarantine — not just `unsupported_schema_version`. Every `Result.Rejected` from `TelemetryValidationService` that reaches `MqttTelemetryIngestHandler` must be stored.
- `raw_payload` is stored as-is (the raw MQTT payload string). No truncation in DB — `TEXT` is unbounded in PostgreSQL. However, backend MUST guard against extreme-length payloads before persisting: truncate to 65535 chars if longer, appending sentinel `[TRUNCATED]`.
- REST endpoint: `GET /api/v1/telemetry/quarantine` — paginated (page/size query params, default size=20, max size=100), returns newest-first (`received_at DESC`), SUPER_ADMIN role required.
- Response DTO fields: `id`, `traceId`, `topic`, `rawPayload`, `rejectionReason`, `rejectionField` (nullable), `receivedAt`.
- Pagination response wraps standard Spring `Page<QuarantineEntryView>` — return `content`, `totalElements`, `totalPages`, `number`, `size`.
- Flyway migration version is `V9` (last applied is `V8__create_machine_sparepart_installations.sql`).
- JPA entity in `com.syncro.telemetry.infrastructure` package, matching existing module structure.
- `TelemetryQuarantineService` in `com.syncro.telemetry.application` package.
- API controller in `com.syncro.telemetry.api` package (create if not exists).
- Frontend component `quarantine-log-table.tsx` in `syncro/apps/web/src/components/syncro/` (architecture reference: architecture.md line 597).
- Frontend page integration in `syncro/apps/web/src/features/system-health/` — add `QuarantineLogTable` to the system-health feature page or create a dedicated quarantine sub-page.
- Timestamps: `Instant` on backend, ISO-8601 string in JSON response.
- Use existing `@PreAuthorize` / Spring Security role enforcement pattern from other controllers.

**Block If:**
- Do NOT persist quarantine entries for deduplicated messages — dedupe happens in `TelemetryPersistenceService` AFTER validation passes, so duplicate messages are `Accepted` (not `Rejected`) and must not enter quarantine.
- Do NOT add quarantine logic for `mqtt_telemetry_ingest_failed` (the outer `catch` in handler) — that path covers infrastructure failures (NPE, broker issues), not payload validation rejections.

**Never:**
- No DELETE or PATCH endpoint for quarantine entries — quarantine is append-only / read-only from API.
- No quarantine-based auto-retry logic — quarantine is observability only.
- No InfluxDB or Redis writes for quarantine entries.
- Do not change `TelemetryValidationService` or `TelemetryPayload` — this story only wires the persistence side.
- Do not change existing `MqttTelemetryIngestHandler` log statements — only ADD a call to `TelemetryQuarantineService` after the existing log.warn.
- No frontend delete/clear/dismiss actions on quarantine log — read-only table only.
- No new `application.yml` properties required (no configurable quarantine retention in this story).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH_REJECT | Valid MQTT envelope, validation returns `Rejected(reason, field)` | Quarantine row inserted: topic, raw_payload, rejection_reason, rejection_field, received_at, trace_id | No error |
| HAPPY_PATH_ACCEPT | Valid MQTT envelope, validation returns `Accepted` | No quarantine row inserted | No error |
| QUARANTINE_WRITE_FAILS | DB unavailable when saving quarantine entry | Warning logged: `mqtt_telemetry_quarantine_failed traceId=... reason=...`; ingest worker continues normally | Exception swallowed, no crash |
| PAYLOAD_OVERSIZED | Raw payload > 65535 chars | Stored truncated to 65535 chars + `[TRUNCATED]` sentinel; rejection_reason preserved | No error |
| NULL_FIELD | `rejection_field` is null (e.g. `malformed_topic`) | `rejection_field` column is NULL in DB; JSON response field is null | No error |
| PAGINATION_DEFAULT | `GET /api/v1/telemetry/quarantine` no params | Returns page 0, size 20, newest first | No error |
| PAGINATION_CUSTOM | `GET /api/v1/telemetry/quarantine?page=2&size=10` | Returns page 2, size 10, newest first | No error |
| PAGINATION_EXCEED_MAX | `GET /api/v1/telemetry/quarantine?size=500` | Returns size capped at 100 | No error |
| UNAUTHORIZED | Non-SUPER_ADMIN accesses quarantine endpoint | 403 Forbidden | Standard Spring Security response |
| EMPTY_QUARANTINE | No quarantine entries | Returns empty page with totalElements=0 | No error |

## Capabilities

### CAP-1: Quarantine Table Migration

**intent:** Persist rejected telemetry messages in PostgreSQL.

**success:**
- `V9__create_telemetry_quarantine.sql` creates `telemetry_quarantine` table with all required columns.
- `created_at` defaults to `NOW()`.
- Index on `received_at DESC` for pagination performance.
- Index on `trace_id` for lookup.

**test:** `TelemetryQuarantineRepositoryTest` or verified via integration test.

---

### CAP-2: Quarantine Write on Rejection

**intent:** Every `Result.Rejected` from the validation pipeline is persisted to quarantine.

**success:**
- `TelemetryQuarantineService.quarantine(TelemetryEnvelope envelope, TelemetryValidationService.Result.Rejected rejected)` saves a row.
- Called from `MqttTelemetryIngestHandler` in the `Result.Rejected` case, after the existing `log.warn`.
- `TelemetryQuarantineService` wraps DB write in try/catch; on exception logs `mqtt_telemetry_quarantine_failed` at WARN and returns without rethrowing.
- Raw payload truncated to 65535 chars if longer.

**test:** `TelemetryQuarantineServiceTest` — happy path save, DB failure swallowed, payload truncation.

---

### CAP-3: Quarantine Query API

**intent:** SUPER_ADMIN can list quarantine entries via REST API.

**success:**
- `GET /api/v1/telemetry/quarantine` returns paginated `QuarantineEntryView` list, newest first.
- `page` and `size` query params accepted; size capped at 100.
- Returns 403 for non-SUPER_ADMIN callers.
- Returns 200 + empty page when no quarantine entries exist.

**test:** `TelemetryQuarantineControllerTest` — list returns entries, empty state, pagination, unauthorized.

---

### CAP-4: Frontend QuarantineLogTable Component

**intent:** SUPER_ADMIN can view quarantine log entries on the system-health page.

**success:**
- `quarantine-log-table.tsx` renders columns: Received At, Topic, Rejection Reason, Field (nullable), Trace ID, Payload (truncated preview with expand).
- Component handles loading, empty, error states.
- Integrated into `features/system-health/` page (new section or tab).
- Uses existing shadcn/Tailwind tokens — no hardcoded colors or spacing.
- Desktop table + mobile card pattern (consistent with `audit-log-table.tsx`).
- Pagination controls (previous/next, page indicator).
- Polling interval: 30s auto-refresh (consistent with telemetry dashboard pattern).

**test:** Visual inspection; component renders loading/empty/error states without throwing.

## Tasks / Subtasks

- [ ] **T1: Flyway Migration** (AC: CAP-1)
  - [ ] Create `V9__create_telemetry_quarantine.sql` in `syncro/apps/backend/src/main/resources/db/migration/`
  - [ ] Columns: `id UUID PK`, `trace_id VARCHAR(255) NOT NULL`, `topic TEXT NOT NULL`, `raw_payload TEXT NOT NULL`, `rejection_reason VARCHAR(100) NOT NULL`, `rejection_field VARCHAR(100)`, `received_at TIMESTAMPTZ NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
  - [ ] Index: `idx_telemetry_quarantine_received_at` on `received_at DESC`
  - [ ] Index: `idx_telemetry_quarantine_trace_id` on `trace_id`

- [ ] **T2: JPA Entity + Repository** (AC: CAP-1, CAP-2)
  - [ ] Create `TelemetryQuarantineEntity` in `com.syncro.telemetry.infrastructure`
  - [ ] Fields match migration columns; use `@Column(columnDefinition = "TEXT")` for `rawPayload`
  - [ ] Create `TelemetryQuarantineRepository extends JpaRepository<TelemetryQuarantineEntity, UUID>` with `findAllByOrderByReceivedAtDesc(Pageable pageable)`

- [ ] **T3: TelemetryQuarantineService** (AC: CAP-2)
  - [ ] Create `TelemetryQuarantineService` in `com.syncro.telemetry.application`
  - [ ] Method: `void quarantine(TelemetryEnvelope, Result.Rejected)`
  - [ ] Truncate `rawPayload` to 65535 chars if needed, append `[TRUNCATED]`
  - [ ] Wrap DB save in try/catch; log `mqtt_telemetry_quarantine_failed` at WARN on failure; do NOT rethrow
  - [ ] Write unit tests in `TelemetryQuarantineServiceTest`

- [ ] **T4: Wire into MqttTelemetryIngestHandler** (AC: CAP-2)
  - [ ] Inject `TelemetryQuarantineService` into `MqttTelemetryIngestHandler`
  - [ ] In `Result.Rejected` branch: after existing `log.warn`, call `quarantineService.quarantine(envelope, rejected)`
  - [ ] Do NOT change existing log statements

- [ ] **T5: REST Endpoint** (AC: CAP-3)
  - [ ] Create `TelemetryQuarantineController` in `com.syncro.telemetry.api`
  - [ ] Create `QuarantineEntryView` DTO record in `com.syncro.telemetry.api`
  - [ ] `GET /api/v1/telemetry/quarantine` — `@PreAuthorize("hasRole('SUPER_ADMIN')")`, paginated, size capped at 100
  - [ ] Map `TelemetryQuarantineEntity` → `QuarantineEntryView`
  - [ ] Write `TelemetryQuarantineControllerTest` (unit + integration)

- [ ] **T6: Frontend QuarantineLogTable** (AC: CAP-4)
  - [ ] Create `quarantine-log-table.tsx` in `syncro/apps/web/src/components/syncro/`
  - [ ] Columns: Received At, Topic, Reason, Field, Trace ID, Payload preview (expand on click)
  - [ ] Loading, empty, error states
  - [ ] Pagination controls
  - [ ] 30s auto-refresh via `refetchInterval`
  - [ ] Desktop table + mobile card layout (follow `audit-log-table.tsx` pattern)

- [ ] **T7: Wire into system-health page** (AC: CAP-4)
  - [ ] Add `QuarantineLogTable` to `features/system-health/` page
  - [ ] Add quarantine API fetch hook `useQuarantineLog` (or use generated orval hook if available after OpenAPI update)
  - [ ] Add OpenAPI spec annotation to controller so orval can generate the hook (use `@Operation` + `@Tag`)

- [ ] **T8: Verify** 
  - [ ] Run full test suite: `mvn -f syncro/apps/backend/pom.xml test`
  - [ ] Confirm 0 failures (pre-existing errors: `SyncroBackendApplicationTests.contextLoads` and `DbIndexHygieneAtddUpgradePathScaffoldTest` are pre-existing — not introduced by this story)
  - [ ] Update sprint-status.yaml: 3-11 → `review`

## Dev Notes

### Backend

- **Package layout:** Follow existing telemetry module structure:
  - `com.syncro.telemetry.application` — `TelemetryQuarantineService`
  - `com.syncro.telemetry.infrastructure` — `TelemetryQuarantineEntity`, `TelemetryQuarantineRepository`
  - `com.syncro.telemetry.api` — `TelemetryQuarantineController`, `QuarantineEntryView`
  - `com.syncro.telemetry.api` may not exist yet — create it.

- **Entity naming convention:** Singular — `TelemetryQuarantine` entity, `telemetry_quarantine` table. Matches existing pattern: `Machine` → `machines`, `Sparepart` → `spareparts`.

- **Flyway version:** Must be `V9`. Last migration is `V8__create_machine_sparepart_installations.sql`. Do NOT edit applied migrations.

- **TelemetryEnvelope** fields available for quarantine: `traceId`, `topic`, `payload` (raw string), `receivedAt`. These map directly to quarantine columns.

- **Fire-and-forget pattern:**
```java
// In TelemetryQuarantineService
try {
    var entity = new TelemetryQuarantineEntity();
    entity.setId(UUID.randomUUID());
    entity.setTraceId(envelope.traceId());
    entity.setTopic(envelope.topic());
    entity.setRawPayload(truncate(envelope.payload(), 65535));
    entity.setRejectionReason(rejected.reason());
    entity.setRejectionField(rejected.field()); // nullable
    entity.setReceivedAt(envelope.receivedAt());
    repository.save(entity);
} catch (Exception e) {
    log.warn("mqtt_telemetry_quarantine_failed traceId={} reason={}", envelope.traceId(), rejected.reason(), e);
}
```

- **Role enforcement:** Use `@PreAuthorize("hasRole('SUPER_ADMIN')")` — consistent with other SUPER_ADMIN-only endpoints in the project. Do NOT use `hasAuthority` unless that is the existing pattern — check existing controllers before writing.

- **Pagination cap pattern:** 
```java
int effectiveSize = Math.min(size, 100);
Pageable pageable = PageRequest.of(page, effectiveSize, Sort.by("receivedAt").descending());
```

- **Test containers:** Integration tests use Testcontainers for PostgreSQL (already configured in the project). Follow existing integration test pattern from other `*IntegrationTest` classes.

### Frontend

- **Component location:** `syncro/apps/web/src/components/syncro/quarantine-log-table.tsx` (referenced in architecture.md line 597 as a shared Syncro UI component).

- **Pattern to follow:** `audit-log-table.tsx` — desktop table + mobile card, pagination, loading/empty/error states.

- **API fetch:** Check if orval generates a `useListTelemetryQuarantine` hook after T7 annotation. If not available yet, write a manual `useQuarantineLog` hook using `fetch` + `useQuery` with `API_BASE_URL` and `getAuthToken()` (same pattern as other hooks in the project).

- **Payload preview:** Raw payload strings can be long. Show first 120 chars in the table cell with a "Show full" expand toggle. Do not truncate on mobile card — use a scrollable code block instead.

- **Received At column:** Format as local datetime using the project's existing date formatting utility. Show relative time (e.g. "3 minutes ago") on hover or as secondary line.

- **Rejection Field column:** Show `—` (em dash) when null, not empty string.

- **30s polling:** `refetchInterval: 30_000` in `useQuery` options — consistent with telemetry dashboard.

- **System-health page integration:** Add a "Data Quality / Quarantine Log" section below existing health cards. Use a collapsible panel or a tab if the page already has tabs; otherwise add as a new section with a section heading.

### Project Structure Notes

- `com.syncro.telemetry.api` package does not exist yet — create it with the controller.
- `features/system-health/` frontend feature folder exists (referenced in architecture.md line 583). The actual page file path is `syncro/apps/web/src/app/dashboard/system-health/page.tsx` (Next.js App Router) — the feature folder holds hooks/components that the page imports.
- OpenAPI annotation `@Tag(name = "telemetry-quarantine")` + `@Operation(operationId = "listTelemetryQuarantine")` on controller endpoint enables orval code generation. Run `npm run generate` in `syncro/apps/web` after adding the annotation to check if it succeeds before writing a manual hook.

### References

- Quarantine table architecture intent: `_bmad-output/planning-artifacts/architecture.md` lines 80, 596–598 (`quarantine-log-table.tsx`, `data-quality-panel.tsx`)
- Frontend component inventory: `_bmad-output/planning-artifacts/architecture.md` lines 591–599
- Epic 3 quarantine context: `_bmad-output/implementation-artifacts/epic-3-context.md` line 60–61
- Story 3.9 quarantine intent: `_bmad-output/implementation-artifacts/spec-3-9-...md` lines 30, 50–53 ("No quarantine table/entity/endpoint in this story — that is Story 3.11")
- `TelemetryEnvelope` fields: `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryEnvelope.java`
- `MqttTelemetryIngestHandler` rejection branch: `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java:54–62`
- `TelemetryValidationService.Result.Rejected`: `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryValidationService.java:21–23`
- V8 migration (last applied): `syncro/apps/backend/src/main/resources/db/migration/V8__create_machine_sparepart_installations.sql`
- Flyway naming convention: `V{n}__{description}.sql` — snake_case description, double underscore separator

## Code Review Checklist

- [ ] Quarantine write failure does NOT crash ingest worker
- [ ] Raw payload truncated before DB write (max 65535 chars)
- [ ] No quarantine row for `Accepted` messages
- [ ] No quarantine row for deduplicated messages (dedupe is post-validation)
- [ ] REST endpoint returns 403 for non-SUPER_ADMIN
- [ ] Pagination size capped at 100
- [ ] Results ordered newest-first
- [ ] `rejection_field` nullable in DB and response
- [ ] Frontend handles loading/empty/error states
- [ ] No hardcoded colors or inline styles in component

## Dev Agent Record

### Agent Model Used

kiro

### Debug Log References

### Completion Notes List

### File List

- `syncro/apps/backend/src/main/resources/db/migration/V9__create_telemetry_quarantine.sql`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/TelemetryQuarantineEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/TelemetryQuarantineRepository.java`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryQuarantineService.java`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java` (modified)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/api/TelemetryQuarantineController.java`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/api/QuarantineEntryView.java`
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryQuarantineServiceTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/api/TelemetryQuarantineControllerTest.java`
- `syncro/apps/web/src/components/syncro/quarantine-log-table.tsx`
- `syncro/apps/web/src/features/system-health/hooks/use-quarantine-log.ts` (if manual hook needed)
- `syncro/apps/web/src/app/dashboard/system-health/page.tsx` (modified)

## Deferred Work Items

- [x] [Review][Defer] `quarantinePage` state not reset when data changes — deferred, no filter/search in current UI so page desync cannot occur yet. Revisit when filter is added.

## Post-Review Resolutions

### Review Findings (2026-08-18)

- [x] [Review][Patch] `TRUNCATED_SENTINEL` appended after cut makes total length `MAX_PAYLOAD_LENGTH + 11` — fixed: constant redefined as `65535 - TRUNCATED_SENTINEL.length()` so stored string is always ≤ 65535 chars [TelemetryQuarantineService.java]
- [x] [Review][Patch] `rejection_reason` / `rejection_field` not truncated before `VARCHAR(100)` insert — fixed: added `truncateField()` guarding both fields to 100 chars before persistence [TelemetryQuarantineService.java]
- [x] [Review][Patch] Surrogate pair may be split at `substring(0, MAX_PAYLOAD_LENGTH)` boundary — fixed: replaced with `offsetByCodePoints`-safe boundary [TelemetryQuarantineService.java]
- [x] [Review][Patch] `page` param not capped; very large value could overflow in `PageRequest` — fixed: added `MAX_PAGE = 10_000` cap on `effectivePage` [TelemetryQuarantineController.java]
- [x] [Review][Defer] `quarantinePage` state not reset when data changes [system-health-page.tsx] — deferred, pre-existing: no filter/search in current UI, desync cannot occur
