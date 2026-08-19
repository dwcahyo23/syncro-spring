---
story: 4.4
title: Acknowledge Open Alert
status: done
created_at: 2026-08-19
final_revision: 2026-08-19
---

# Spec: Story 4.4 — Acknowledge Open Alert

## Goal

Allow authorized users to acknowledge an OPEN alert, transitioning it to ACKNOWLEDGED — recording the actor, timestamp, and optional reason — so the system can track human response and stop further escalation.

## Acceptance Criteria (from epics.md)

- Given alert status is `OPEN` and user is allowed to acknowledge, when user clicks acknowledge, backend transitions alert to `ACKNOWLEDGED`
- Acknowledgement records actor, timestamp, source, action, target, result, and traceId
- Frontend action panel explains: "Stops further escalation and records your action. Alert remains visible until resolved."
- Action button supports disabled reason, loading, success, and error feedback
- Invalid transition returns `INVALID_STATE_TRANSITION` standard error
- Mobile alert view provides sticky acknowledge action visible without scrolling where practical
- Mobile acknowledge target is touch-friendly, around 44px where layout allows

---

## Backend

### Modified files

#### `SparepartAlertEntity.java`
Package: `com.syncro.alert.infrastructure`

Added method:
```java
public void acknowledge(String reason, Instant now)
```
- Guards: throws `InvalidAlertTransitionException(this.status, ACKNOWLEDGED)` if `status != OPEN`
- On success: sets `status = ACKNOWLEDGED`, `statusReason = reason`, `updatedAt = now`

#### `SparepartAlertCommandService.java`
Package: `com.syncro.alert.application`

Added method:
```java
@Transactional
public void acknowledge(AuthenticatedUser user, UUID alertId, String reason)
```
- Access control: SUPER_ADMIN uses `findByIdWithDetails`; MANAGE/VIEWER uses `findByIdWithDetailsScopedToPlants` scoped to user's plant assignments. Empty plant list → `AlertNotFoundException`.
- Delegates transition to `alert.acknowledge(reason, clock.instant())`
- Wraps `InvalidAlertTransitionException` from entity into `AlertInvalidTransitionException(from, ACKNOWLEDGED)`
- Saves via `alertRepository.save(alert)`
- Writes audit record:
  - `previousValue`: `{ actorId: user.id(), transition: "OPEN→ACKNOWLEDGED" }`
  - `newValue`: `{ status: "ACKNOWLEDGED", reason: reason ?: "" }`

New exception classes (inner static):
- `AlertInvalidTransitionException(SparepartAlertStatus from, SparepartAlertStatus to)` — carries `from` and `to` for error message construction
- `AlertForbiddenException` — thrown when role check fails (used by resolveOverride in Story 4.6, handler registered here)

#### `SparepartAlertController.java`
Package: `com.syncro.alert.api`

Added endpoint:
```
POST /api/v1/alerts/{alertId}/acknowledge
```
- `@ResponseStatus(HttpStatus.NO_CONTENT)` → 204 on success
- `@RequestBody(required = false) AcknowledgeRequest body` — body is optional; `reason` extracted as `body != null ? body.reason() : null`
- Swagger: `operationId = "acknowledgeAlert"`, responses: 204, 401, 403, 404, 409

#### `SparepartAlertDtos.java`
Package: `com.syncro.alert.api`

Added record:
```java
record AcknowledgeRequest(
    @Schema(nullable = true, description = "Optional reason for acknowledging the alert") String reason
)
```

#### `SparepartAlertExceptionHandler.java`
Package: `com.syncro.alert.api`

New file. `@RestControllerAdvice` scoped to `SparepartAlertController`, `@Order(HIGHEST_PRECEDENCE)`.

Handlers registered:

| Exception | HTTP Status | Code |
|-----------|-------------|------|
| `AlertNotFoundException` | 404 | `ALERT_NOT_FOUND` |
| `AlertInvalidTransitionException` | 409 | `INVALID_STATE_TRANSITION` |
| `AlertForbiddenException` | 403 | `FORBIDDEN` |
| `PlantAccessDeniedException` | 403 | `FORBIDDEN` |
| `MethodArgumentTypeMismatchException` (query param) | 400 | `INVALID_QUERY_VALUE` |
| `MethodArgumentTypeMismatchException` (path param) | 400 | `INVALID_PATH_VALUE` |

Error response body:
```java
record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp, String traceId)
```
`timestamp` uses injected `Clock`; `traceId` is a fresh `UUID.randomUUID()`.

The `AlertInvalidTransitionException` handler includes `from → to` in the message: `"Invalid alert state transition: {from} → {to}."`.

---

## Frontend

### Modified files

#### `alert-detail-page-content.tsx`
Path: `apps/web/src/features/alerts/alert-detail-page-content.tsx`

Added `useAcknowledgeAlert` mutation hook:
```tsx
const { mutate: acknowledge, isPending: isAcknowledging } = useAcknowledgeAlert({ mutation: { ... } })
```

Behavior:
- `onSuccess`: calls `void refetch()` then `toast.success("Alert acknowledged.")`
- `onError`: if `SyncroApiError` with `status === 409` → `toast.error("Cannot acknowledge: invalid state transition.")`, otherwise → `toast.error("Failed to acknowledge alert.")`

Acknowledge button is rendered conditionally in the action panel — visible only when `alert.status === "OPEN"`. Button is disabled and shows loading state while `isAcknowledging` is true.

#### `syncro.ts` (generated)
Path: `apps/web/src/lib/api/generated/syncro.ts`

Generated `useAcknowledgeAlert` mutation hook via orval from the OpenAPI spec's `acknowledgeAlert` operation (`POST /api/v1/alerts/{alertId}/acknowledge`).

---

## Test Plan

### `SparepartAlertCommandServiceTest.java` — acknowledge tests

| Test method | Description |
|-------------|-------------|
| `acknowledge_superAdmin_openAlert_transitionsToAcknowledged` | SUPER_ADMIN on OPEN alert → status becomes ACKNOWLEDGED, statusReason set, updatedAt matches clock |
| `acknowledge_superAdmin_writesAuditEvent` | Audit record has correct entityId, actorId in previousValue, status ACKNOWLEDGED in newValue |
| `acknowledge_managedUser_scopedToPlant_succeeds` | MANAGE user with matching plant assignment can acknowledge |
| `acknowledge_managedUser_noPlantAssignment_throwsNotFound` | MANAGE user with no plant assignments → AlertNotFoundException, repository.save never called |
| `acknowledge_alertNotFound_throwsNotFound` | Alert not found in repository → AlertNotFoundException |
| `acknowledge_alreadyAcknowledged_throwsInvalidTransition` | Alert already ACKNOWLEDGED → AlertInvalidTransitionException with from=ACKNOWLEDGED |
| `acknowledge_resolvedAlert_throwsInvalidTransition` | RESOLVED alert → AlertInvalidTransitionException with from=RESOLVED |
| `acknowledge_nullReason_allowed` | null reason accepted → statusReason remains null after transition |

---

## Files Created/Modified

### Backend
| File | Action |
|------|--------|
| `alert/infrastructure/SparepartAlertEntity.java` | Modify — add `acknowledge()` domain method |
| `alert/application/SparepartAlertCommandService.java` | Modify — add `acknowledge()` service method + exception classes |
| `alert/api/SparepartAlertController.java` | Modify — add `POST /{alertId}/acknowledge` endpoint |
| `alert/api/SparepartAlertDtos.java` | Modify — add `AcknowledgeRequest` record |
| `alert/api/SparepartAlertExceptionHandler.java` | Create — scoped exception handler for alert controller |

### Frontend
| File | Action |
|------|--------|
| `features/alerts/alert-detail-page-content.tsx` | Modify — add acknowledge mutation hook and action button |
| `lib/api/generated/syncro.ts` | Modify — regenerated to include `useAcknowledgeAlert` hook |

### Tests
| File | Action |
|------|--------|
| `alert/application/SparepartAlertCommandServiceTest.java` | Modify — add 8 acknowledge test cases |

---

## Dev Agent Record

### Agent Model Used
claude-sonnet-4-5 (retroactive spec)

### Completion Notes List
- Spec created retroactively from implemented code (commit 96ad023)
- `SparepartAlertExceptionHandler` is a new file shared by stories 4.4, 4.5, and 4.6 — first introduced in this story
- `AlertInvalidTransitionException` and `AlertForbiddenException` are inner static classes on `SparepartAlertCommandService`
- Request body for acknowledge is optional (`required = false`) — allows callers to omit body entirely
- Plant-scope enforcement reuses the same `loadAndCheckAccess` helper shared with Story 4.5

### File List
- `syncro/apps/backend/src/main/java/com/syncro/alert/infrastructure/SparepartAlertEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertCommandService.java`
- `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertController.java`
- `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertDtos.java`
- `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertExceptionHandler.java`
- `syncro/apps/backend/src/test/java/com/syncro/alert/application/SparepartAlertCommandServiceTest.java`
- `syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx`
- `syncro/apps/web/src/lib/api/generated/syncro.ts`

### Review Findings

- [x] [Review][Patch] `actorId` written to `previousValue` instead of transition metadata field [SparepartAlertCommandService.java:66] — fixed, moved to `newValue` alongside transition and status
- [x] [Review][Patch] No test for wrong-plant scoped access (user has plant A, alert belongs to plant B) [SparepartAlertCommandServiceTest.java] — fixed, added `acknowledge_managedUser_wrongPlant_throwsNotFound`
- [x] [Review][Patch] `PlantScopeService` injected but never used — dead dependency [SparepartAlertCommandService.java:28] — fixed, removed field, constructor param, and import
- [x] [Review][Patch] `Separator` unused import on frontend [alert-detail-page-content.tsx:9] — fixed, import removed
- [x] [Review][Defer] `auditLogWriter.recordSystem()` has no try/catch — audit failure behavior depends on @Transactional boundary [SparepartAlertCommandService.java:68] — deferred, pre-existing design pattern across codebase
- [x] [Review][Defer] Alert list query not invalidated after acknowledge — stale status possible in list view [alert-detail-page-content.tsx] — deferred, pre-existing UX pattern; AC8 only requires toast
- [x] [Review][Defer] Double-submit race condition — no `@Version` on entity, no frontend debounce [SparepartAlertCommandService.java, alert-detail-page-content.tsx] — deferred, pre-existing; entity guard prevents corrupt state
- [x] [Review][Defer] Empty string reason bypasses nullable contract — no `@NotBlank` or max-length guard [SparepartAlertDtos.java, SparepartAlertEntity.java] — deferred, pre-existing design across all alert mutations
- [x] [Review][Defer] Frontend only handles 409 specifically — 403/404 indistinguishable to user [alert-detail-page-content.tsx] — deferred, AC8 only requires 409-specific message
