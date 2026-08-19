---
story: 4.6
title: Allow SUPER_ADMIN Direct Resolve Override
status: done
created_at: 2026-08-19
final_revision: 2026-08-19
---

# Spec: Story 4.6 — Allow SUPER_ADMIN Direct Resolve Override

## Goal

Allow a SUPER_ADMIN to resolve an OPEN alert directly without prior acknowledgement, so that alerts can be closed administratively when operationally needed. Non-SUPER_ADMIN users are forbidden from this action at the service layer.

## Acceptance Criteria (from epics.md)

- **Given** alert status is `OPEN` and current user is `SUPER_ADMIN`
  **When** SUPER_ADMIN resolves alert directly
  **Then** backend transitions alert to `RESOLVED`
- **And** resolution is recorded as administrative override
- **And** non-SUPER_ADMIN users cannot directly resolve `OPEN` alert unless allowed by lifecycle rule
- **And** audit event captures override actor and traceId
- **And** frontend only shows direct resolve action when backend allowed actions include it

---

## Backend

### Domain — `SparepartAlertEntity.resolveOverride()`

New method on `SparepartAlertEntity` (`alert/infrastructure/SparepartAlertEntity.java:128`):

```java
public void resolveOverride(String reason, Instant now) {
    if (this.status != SparepartAlertStatus.OPEN) {
        throw new InvalidAlertTransitionException(this.status, SparepartAlertStatus.RESOLVED);
    }
    this.status = SparepartAlertStatus.RESOLVED;
    this.statusReason = reason;
    this.updatedAt = now;
}
```

Guard: only `OPEN → RESOLVED` is permitted. Any other source status throws `InvalidAlertTransitionException`.  
The method skips the `ACKNOWLEDGED` step — this is intentional and the distinguishing behavior of the override path.

### Application — `SparepartAlertCommandService.resolveOverride()`

New method (`alert/application/SparepartAlertCommandService.java:101`):

```
resolveOverride(AuthenticatedUser user, UUID alertId, String reason) → void
```

Authorization guard is the **first** check: if `user.applicationRole() != SUPER_ADMIN`, throws `AlertForbiddenException` immediately — no repository query is made.

Flow:
1. Role check → `AlertForbiddenException` if not SUPER_ADMIN
2. `alertRepository.findByIdWithDetails(alertId)` → `AlertNotFoundException` if absent
3. `alert.resolveOverride(reason, clock.instant())` → wraps `InvalidAlertTransitionException` into `AlertInvalidTransitionException`
4. `alertRepository.save(alert)`
5. `auditLogWriter.recordSystem(...)` with `previousValue = {"actorId": ..., "transition": "OPEN→RESOLVED(override)"}` and `newValue = {"status": "RESOLVED", "reason": ...}`

### New exception — `AlertForbiddenException`

Defined as static inner class of `SparepartAlertCommandService` (`alert/application/SparepartAlertCommandService.java:180`). Thrown when a non-SUPER_ADMIN attempts the override action.

### API — `SparepartAlertController`

New endpoint (`alert/api/SparepartAlertController.java`):

```
POST /api/v1/alerts/{alertId}/resolve-override
@ResponseStatus(204 NO_CONTENT)
```

Request body: `ResolveOverrideRequest(String reason)` — reason is optional (nullable).  
The controller extracts `reason` only when the request body is non-null, then delegates to `alertCommand.resolveOverride(user, alertId, reason)`.

OpenAPI responses declared: `204`, `401`, `403`, `404`, `409`.

### DTO — `ResolveOverrideRequest`

New record in `SparepartAlertDtos` (`alert/api/SparepartAlertDtos.java:58`):

```java
public record ResolveOverrideRequest(
    @Schema(nullable = true, description = "Optional reason for SUPER_ADMIN direct resolve override")
    String reason) {}
```

### Exception Handler — `SparepartAlertExceptionHandler`

New handler added (`alert/api/SparepartAlertExceptionHandler.java:41`):

```java
@ExceptionHandler(AlertForbiddenException.class)
ResponseEntity<ErrorResponse> alertForbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to perform this action.");
}
```

Maps `AlertForbiddenException` → HTTP 403, error code `FORBIDDEN`.

---

## Frontend

### `alert-detail-page-content.tsx` — Resolve Override button

The component reads `authUser` via `useAuthUser()` and derives:

```ts
const isSuperAdmin = authUser?.applicationRole === "SUPER_ADMIN";
```

The "Resolve Override" button is rendered only when **both** conditions are true:
- `alert.status === "OPEN"`
- `isSuperAdmin === true`

```tsx
{alert.status === "OPEN" && isSuperAdmin && (
  <button
    type="button"
    disabled={isResolvingOverride}
    onClick={() => resolveOverride({ alertId })}
    aria-label="Resolve this alert as SUPER_ADMIN override"
  >
    {isResolvingOverride ? "Resolving…" : "Resolve Override"}
  </button>
)}
```

Error handling for the mutation:
- HTTP 403 → `"Only SUPER_ADMIN can use resolve override."`
- HTTP 409 → `"Cannot override: alert is not in OPEN state."`
- Other → `"Failed to resolve alert."`

On success: refetches alert data and shows `toast.success("Alert resolved (override).")`.

### `syncro.ts` — Generated API hook

`useResolveAlertOverride` is the orval-generated mutation hook for `POST /api/v1/alerts/{alertId}/resolve-override`, used directly in `alert-detail-page-content.tsx`.

---

## Test Plan

All tests are in `SparepartAlertCommandServiceTest` (`alert/application/SparepartAlertCommandServiceTest.java`), Mockito unit tests with a fixed `Clock`.

| # | Test name | Scenario | Expected |
|---|-----------|----------|----------|
| 1 | `resolveOverride_superAdmin_openAlert_transitionsToResolved` | SUPER_ADMIN, alert is OPEN | Status → RESOLVED, statusReason set, updatedAt = clock instant, `save()` called |
| 2 | `resolveOverride_nonSuperAdmin_throwsForbidden` | MANAGE user attempts override | `AlertForbiddenException` thrown, no repository calls made |
| 3 | `resolveOverride_acknowledgedAlert_throwsInvalidTransition` | SUPER_ADMIN, alert is ACKNOWLEDGED | `AlertInvalidTransitionException` thrown, `save()` not called |
| 4 | `resolveOverride_writesAuditEvent` | SUPER_ADMIN, alert is OPEN | `auditLogWriter.recordSystem()` called with `previousValue["transition"] = "OPEN→RESOLVED(override)"` and `newValue["status"] = "RESOLVED"` |

---

## Files Created/Modified

### Backend

| File | Action |
|------|--------|
| `alert/infrastructure/SparepartAlertEntity.java` | Modify — add `resolveOverride()` method |
| `alert/application/SparepartAlertCommandService.java` | Modify — add `resolveOverride()` method and `AlertForbiddenException` inner class |
| `alert/api/SparepartAlertController.java` | Modify — add `POST /api/v1/alerts/{alertId}/resolve-override` endpoint |
| `alert/api/SparepartAlertDtos.java` | Modify — add `ResolveOverrideRequest` record |
| `alert/api/SparepartAlertExceptionHandler.java` | Modify — add `AlertForbiddenException` handler (HTTP 403) |

### Frontend

| File | Action |
|------|--------|
| `features/alerts/alert-detail-page-content.tsx` | Modify — add `useResolveAlertOverride` mutation, `isSuperAdmin` check, and conditional Resolve Override button |
| `lib/api/generated/syncro.ts` | Modify — orval-generated, adds `useResolveAlertOverride` hook |

### Tests

| File | Action |
|------|--------|
| `alert/application/SparepartAlertCommandServiceTest.java` | Modify — add 4 tests for `resolveOverride` |

---

## Dev Agent Record

### Agent Model Used
claude-sonnet-4-5 (retroactive spec)

### Completion Notes List
- Spec created retroactively from implemented code (commit 8bdaf21)
- Role check is intentionally done before any repository access — ensures non-SUPER_ADMIN never touches alert data via this path
- `resolveOverride` on entity only accepts `OPEN` status; no other transition is supported even for SUPER_ADMIN
- Frontend button visibility is driven by client-side role check (`isSuperAdmin`) rather than a backend `allowedActions` field — this is a known simplification vs. the AC wording "frontend only shows direct resolve action when backend allowed actions include it"

### File List
- `syncro/apps/backend/src/main/java/com/syncro/alert/infrastructure/SparepartAlertEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertCommandService.java`
- `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertController.java`
- `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertDtos.java`
- `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertExceptionHandler.java`
- `syncro/apps/backend/src/test/java/com/syncro/alert/application/SparepartAlertCommandServiceTest.java`
- `syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx`
- `syncro/apps/web/src/lib/api/generated/syncro.ts`
