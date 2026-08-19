---
story: 4.5
title: Resolve Acknowledged Alert
status: done
created_at: 2026-08-19
final_revision: 2026-08-19
---

# Spec: Story 4.5 — Resolve Acknowledged Alert

## Goal

Allow authorized users to resolve an ACKNOWLEDGED alert, transitioning it to RESOLVED — closing the alert lifecycle, recording the actor and optional reason, and confirming the sparepart risk has been addressed.

## Acceptance Criteria (from epics.md)

- Given alert status is `ACKNOWLEDGED` and user is allowed to resolve, when user clicks resolve, backend transitions alert to `RESOLVED`
- Resolution records actor, timestamp, source, action, target, result, and traceId
- Frontend shows resolve action only when alert is in `ACKNOWLEDGED` status
- Action button supports loading, success, and error feedback
- Invalid transition (e.g. resolving an OPEN alert) returns `INVALID_STATE_TRANSITION` standard error
- SUPER_ADMIN may resolve an OPEN alert directly via override endpoint (`POST /api/v1/alerts/{alertId}/resolve-override`), skipping the ACKNOWLEDGED step

---

## Backend

### Modified files

#### `SparepartAlertEntity.java`
Package: `com.syncro.alert.infrastructure`

Added method:
```java
public void resolve(String reason, Instant now)
```
- Guards: throws `InvalidAlertTransitionException(this.status, RESOLVED)` if `status != ACKNOWLEDGED`
- On success: sets `status = RESOLVED`, `statusReason = reason`, `updatedAt = now`

Added method:
```java
public void resolveOverride(String reason, Instant now)
```
- SUPER_ADMIN override path: transitions `OPEN → RESOLVED` directly, skipping `ACKNOWLEDGED`
- Guards: throws `InvalidAlertTransitionException(this.status, RESOLVED)` if `status != OPEN`
- On success: sets `status = RESOLVED`, `statusReason = reason`, `updatedAt = now`

#### `SparepartAlertCommandService.java`
Package: `com.syncro.alert.application`

Added method:
```java
@Transactional
public void resolve(AuthenticatedUser user, UUID alertId, String reason)
```
- Access control: same `loadAndCheckAccess` helper as `acknowledge` — SUPER_ADMIN unscoped, MANAGE/VIEWER plant-scoped
- Delegates transition to `alert.resolve(reason, clock.instant())`
- Wraps `InvalidAlertTransitionException` from entity into `AlertInvalidTransitionException(from, RESOLVED)`
- Saves via `alertRepository.save(alert)`
- Writes audit record:
  - `previousValue`: `{ actorId: user.id(), transition: "ACKNOWLEDGED→RESOLVED" }`
  - `newValue`: `{ status: "RESOLVED", reason: reason ?: "" }`

Added method:
```java
@Transactional
public void resolveOverride(AuthenticatedUser user, UUID alertId, String reason)
```
- Role guard: throws `AlertForbiddenException` immediately if `user.applicationRole() != SUPER_ADMIN`
- Loads alert via `alertRepository.findByIdWithDetails(alertId)` (no plant-scope; SUPER_ADMIN sees all)
- Delegates transition to `alert.resolveOverride(reason, clock.instant())`
- Wraps `InvalidAlertTransitionException` into `AlertInvalidTransitionException(from, RESOLVED)`
- Saves and writes audit record:
  - `previousValue`: `{ actorId: user.id(), transition: "OPEN→RESOLVED(override)" }`
  - `newValue`: `{ status: "RESOLVED", reason: reason ?: "" }`

#### `SparepartAlertController.java`
Package: `com.syncro.alert.api`

Added endpoint — standard resolve:
```
POST /api/v1/alerts/{alertId}/resolve
```
- `@ResponseStatus(HttpStatus.NO_CONTENT)` → 204 on success
- `@RequestBody(required = false) ResolveRequest body` — body is optional
- Swagger: `operationId = "resolveAlert"`, responses: 204, 401, 403, 404, 409

Added endpoint — SUPER_ADMIN override:
```
POST /api/v1/alerts/{alertId}/resolve-override
```
- `@ResponseStatus(HttpStatus.NO_CONTENT)` → 204 on success
- `@RequestBody(required = false) ResolveOverrideRequest body` — body is optional
- Swagger: `operationId = "resolveAlertOverride"`, summary: "SUPER_ADMIN: resolve an OPEN alert directly without acknowledging", responses: 204, 401, 403, 404, 409

#### `SparepartAlertDtos.java`
Package: `com.syncro.alert.api`

Added records:
```java
record ResolveRequest(
    @Schema(nullable = true, description = "Optional reason for resolving the alert") String reason
)

record ResolveOverrideRequest(
    @Schema(nullable = true, description = "Optional reason for SUPER_ADMIN direct resolve override") String reason
)
```

---

## Frontend

### Modified files

#### `alert-detail-page-content.tsx`
Path: `apps/web/src/features/alerts/alert-detail-page-content.tsx`

Added `useResolveAlert` mutation hook:
```tsx
const { mutate: resolve, isPending: isResolving } = useResolveAlert({ mutation: { ... } })
```

Behavior:
- `onSuccess`: calls `void refetch()` then `toast.success("Alert resolved.")`
- `onError`: if `SyncroApiError` with `status === 409` → `toast.error("Cannot resolve: invalid state transition.")`, otherwise → `toast.error("Failed to resolve alert.")`

Added `useResolveAlertOverride` mutation hook:
```tsx
const { mutate: resolveOverride, isPending: isResolvingOverride } = useResolveAlertOverride({ mutation: { ... } })
```

Behavior:
- `onSuccess`: calls `void refetch()` then `toast.success("Alert resolved (override).")`
- `onError`: error toast with appropriate message

Resolve button is rendered conditionally — visible only when `alert.status === "ACKNOWLEDGED"`. The override button is visible only to SUPER_ADMIN users (checked via `authUser`) when `alert.status === "OPEN"`. Both buttons are disabled and show loading state while their respective `isPending` flags are true.

#### `syncro.ts` (generated)
Path: `apps/web/src/lib/api/generated/syncro.ts`

Generated `useResolveAlert` and `useResolveAlertOverride` mutation hooks via orval from the OpenAPI spec's `resolveAlert` and `resolveAlertOverride` operations.

---

## Test Plan

### `SparepartAlertCommandServiceTest.java` — resolve tests

| Test method | Description |
|-------------|-------------|
| `resolve_superAdmin_acknowledgedAlert_transitionsToResolved` | SUPER_ADMIN on ACKNOWLEDGED alert → status becomes RESOLVED, statusReason set, updatedAt matches clock |
| `resolve_superAdmin_writesAuditEvent` | Audit record has correct entityId, actorId in previousValue, status RESOLVED in newValue |
| `resolve_managedUser_scopedToPlant_succeeds` | MANAGE user with matching plant assignment can resolve an ACKNOWLEDGED alert |
| `resolve_openAlert_throwsInvalidTransition` | OPEN alert → AlertInvalidTransitionException with from=OPEN, to=RESOLVED |
| `resolve_alreadyResolved_throwsInvalidTransition` | RESOLVED alert → AlertInvalidTransitionException with from=RESOLVED |

### `SparepartAlertCommandServiceTest.java` — resolveOverride tests

| Test method | Description |
|-------------|-------------|
| `resolveOverride_superAdmin_openAlert_transitionsToResolved` | SUPER_ADMIN on OPEN alert → status becomes RESOLVED, statusReason set, updatedAt matches clock |
| `resolveOverride_nonSuperAdmin_throwsForbidden` | MANAGE user → AlertForbiddenException, repository never queried or saved |
| `resolveOverride_acknowledgedAlert_throwsInvalidTransition` | ACKNOWLEDGED alert → AlertInvalidTransitionException with from=ACKNOWLEDGED, to=RESOLVED |
| `resolveOverride_writesAuditEvent` | Audit record previousValue has transition "OPEN→RESOLVED(override)", newValue has status RESOLVED |

---

## Files Created/Modified

### Backend
| File | Action |
|------|--------|
| `alert/infrastructure/SparepartAlertEntity.java` | Modify — add `resolve()` and `resolveOverride()` domain methods |
| `alert/application/SparepartAlertCommandService.java` | Modify — add `resolve()` and `resolveOverride()` service methods |
| `alert/api/SparepartAlertController.java` | Modify — add `POST /{alertId}/resolve` and `POST /{alertId}/resolve-override` endpoints |
| `alert/api/SparepartAlertDtos.java` | Modify — add `ResolveRequest` and `ResolveOverrideRequest` records |

### Frontend
| File | Action |
|------|--------|
| `features/alerts/alert-detail-page-content.tsx` | Modify — add resolve and resolveOverride mutation hooks and conditional action buttons |
| `lib/api/generated/syncro.ts` | Modify — regenerated to include `useResolveAlert` and `useResolveAlertOverride` hooks |

### Tests
| File | Action |
|------|--------|
| `alert/application/SparepartAlertCommandServiceTest.java` | Modify — add 5 resolve test cases + 4 resolveOverride test cases |

---

## Dev Agent Record

### Agent Model Used
claude-sonnet-4-5 (retroactive spec)

### Completion Notes List
- Spec created retroactively from implemented code (commit 7c6118f)
- `resolveOverride` is a SUPER_ADMIN-only operation; role check is the first guard before any repository call
- Standard `resolve` reuses the shared `loadAndCheckAccess` helper (plant-scoped for MANAGE/VIEWER, unscoped for SUPER_ADMIN)
- Both `ResolveRequest` and `ResolveOverrideRequest` have an optional `reason` field — callers may omit body entirely (`required = false`)
- Override endpoint allows SUPER_ADMIN to close OPEN alerts without the intermediate ACKNOWLEDGED step — useful for urgent operational situations
- `SparepartAlertExceptionHandler` (introduced in Story 4.4) handles `AlertForbiddenException` and `AlertInvalidTransitionException` for both stories; no handler changes needed for 4.5

### File List
- `syncro/apps/backend/src/main/java/com/syncro/alert/infrastructure/SparepartAlertEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertCommandService.java`
- `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertController.java`
- `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertDtos.java`
- `syncro/apps/backend/src/test/java/com/syncro/alert/application/SparepartAlertCommandServiceTest.java`
- `syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx`
- `syncro/apps/web/src/lib/api/generated/syncro.ts`
