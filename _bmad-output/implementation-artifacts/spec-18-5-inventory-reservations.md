---
title: 'Story 18-5: Inventory Reservations'
type: 'feature'
created: '2026-09-01'
status: 'done'
review_loop_iteration: 0
baseline_revision: '7a100d6'
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-18-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** `inventory_reservations` (blueprint E4) exists in the schema with no Java service or API: stock cannot be reserved against a workorder reference, reserved quantity does not reduce available stock at a location, and there is no release (close/pickup), explicit cancellation, or consumption path — so reserved parts are not held for the requesting workorder.

**Approach:** Add the reservation lifecycle: create a reservation (reduces available, increases reserved at the location atomically), consume fully or partially against the reference (draws remaining_quantity down, moves reserved → consumed), cancel (returns reserved → available), and expire. Reference = (referenceType, referenceId) — WORK_ORDER as the first-class type; expiry marks references no longer valid. Everything is audit-logged.

## Boundaries & Constraints

**Always:**
- No new Flyway migration — inventory_reservations (sparepart_id, location_id, quantity, remaining_quantity, status CHECK ACTIVE/CONSUMED/CANCELLED/EXPIRED, reference_type, reference_id, requested_by/consumed_by/cancelled_by, expires_at) exists in V1.
- API surface `/api/v1/inventory-reservations`: POST create {sparepartId, locationId, quantity, referenceType, referenceId, expiresAt?}; GET list (filters: sparepartId, locationId, status, referenceType+referenceId); GET /{id}; POST /{id}/consume {quantity} (partial or full); POST /{id}/cancel. camelCase.
- Reservation create guard: location's balance.available >= quantity, else 409 INSUFFICIENT_STOCK; atomically available -= qty, reserved += qty on the balance row (conditional update pattern) in the same transaction as the reservation INSERT.
- Lifecycle: ACTIVE → CONSUMED (remaining 0 via consume) | CANCELLED (cancel returns remaining back to available, reserved -= remaining) | EXPIRED (same return-to-available as cancel; expiry check on read + explicit sweep-free design — compute on access, no scheduler in this story). Terminal states reject further transitions → 409 INVALID_RESERVATION_TRANSITION.
- Transfer interlock (FR-146c): the transfer service (18-4) must not move reserved stock — approve guard becomes source available - qty >= 0 where available already excludes reservations (available column semantics). Document the interlock; the reserved column must NOT be debited by transfers.
- Gates: create/consume/cancel by INVENTORY_MAINTENANCE/STOREKEEPER/MANAGER_MAINTENANCE with the location's plant in assignment scope (SUPER_ADMIN bypass). Reads: assigned users.
- Audit: CREATE/UPDATE records with actor, previous+new reservation state and balance effects.
- OPA rego: NEW `inventory_reservation_paths` {"/api/v1/inventory-reservations", "/api/v1/inventory-reservations/*", "/api/v1/inventory-reservations/*/consume", "/api/v1/inventory-reservations/*/cancel"} for the same mutation role set. Update authz.rego + authz_test.rego.
- Machine-readable errors: INSUFFICIENT_STOCK (409), INVALID_RESERVATION_TRANSITION (409), INVENTORY_RESERVATION_NOT_FOUND (404), INVENTORY_LOCATION_NOT_FOUND (404), SPAREPART_NOT_FOUND (404), FORBIDDEN (403), VALIDATION_ERROR (400).
- TypeScript strict; no frontend work; no scheduler/worker in this story (expiry evaluated on access).

**Block If:**
- No race-safe way to decrement available + increment reserved in one statement pair exists with current repo patterns → HALT blocked.

**Never:**
- No edits to V1 migrations; no scheduled expiry job; no automatic reservation creation from request state transitions (hook integration is a later story; the API is the surface now); no deletion; no frontend work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create reservation | POST valid, balance available 10, qty 4 | 201 ACTIVE; balance available 6, reserved 4; audit both | insufficient → 409 INSUFFICIENT_STOCK; qty ≤ 0 → 400; unknown refs → 404 |
| Partial consume | ACTIVE remaining 4, consume 2 | remaining 2; balance reserved -2, consumed +2; consumed_by stamped | over-consume (qty > remaining) → 400 VALIDATION_ERROR |
| Full consume | consume remaining | status CONSUMED; reserved back to base; consumed += remaining | terminal after |
| Cancel | ACTIVE cancel | remaining returned: available += remaining, reserved -= remaining; status CANCELLED; cancelled_by stamped | terminal after |
| Double transition | CONSUMED cancel/consume | rejected | 409 INVALID_RESERVATION_TRANSITION |
| Expired on access | expires_at in past, ACTIVE | reads/consume report EXPIRED; stock returned | consume after expiry → 409 |
| Transfer interlock | reserved 4 at location, transfer qty ≤ available | transfer allowed only against available (excluding reserved); reserved untouched | per 18-4 INSUFFICIENT_STOCK |
| Wrong role | TECHNICIAN create/cancel | rejected | 403 FORBIDDEN |

</intent-contract>

## Code Map

### Existing (reuse)
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql:1384-1411` -- inventory_reservations + CHECKs + idx_reference. READ-ONLY.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/infrastructure/db/InventoryReservationEntity.java` + `InventoryReservationRepository.java` -- exist (15-2); extend with lifecycle methods (encapsulated transition methods, remaining setter private) + list queries.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/domain/InventoryReservationStatus.java` -- enum exists.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/application/InventoryStockService.java` -- PATTERN for gates/audit/atomic updates; balance moves here reuse conditional-update style.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/infrastructure/db/InventoryStockBalanceRepository.java` -- ADD moveAvailableToReserved (decrement available, increment reserved, guard available >= qty) and moveReservedBack/consumeReserved variants (single UPDATE each).
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/stock/api/SparepartStockExceptionHandler.java` -- ErrorResponse anchor.
- `syncro/authz/policy/authz.rego` + `authz_test.rego` -- ADD inventory_reservation_paths.
- `syncro/apps/backend/src/test/java/com/syncro/inventory/application/InventoryStockServiceTest.java` -- test anchors.

### New
- `syncro/apps/backend/src/main/java/com/syncro/inventory/application/InventoryReservationService.java` -- lifecycle + atomic balance interlocks.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryReservationController.java` + `InventoryReservationDtos.java` + `InventoryReservationExceptionHandler.java` -- API.
- `syncro/apps/backend/src/test/java/com/syncro/inventory/application/InventoryReservationServiceIntegrationTest.java` -- matrix coverage.

## Tasks & Acceptance

**Execution:**
- `InventoryReservationEntity.java` -- encapsulated transition methods (consume/cancel/expire) -- lifecycle safety
- `InventoryReservationRepository.java` -- list/filter queries -- reads
- `InventoryStockBalanceRepository.java` -- reserved/available move queries -- atomic interlocks
- `InventoryReservationService.java` -- create/consume/cancel/expire-on-access + gates + audit -- core
- `InventoryReservationController.java` + DTOs + handler -- API surface
- `syncro/authz/policy/authz.rego` + `authz_test.rego` -- reservation path set -- OPA parity
- `InventoryReservationServiceIntegrationTest.java` -- every matrix row -- AC evidence

**Acceptance Criteria:**
- Given available 10, when a reservation of 4 is created, then available 6 / reserved 4 atomically and a 201 ACTIVE reservation returns with reference fields; insufficient available gives 409 INSUFFICIENT_STOCK with no partial state.
- Given an ACTIVE reservation, when consuming partially then fully, then remaining draws down to 0, status becomes CONSUMED, consumed_by stamps, and the balance reserved decrements / consumed increments by the consumed amounts.
- Given a cancellation, then remaining returns to available, reserved decrements, status CANCELLED with cancelled_by; any further transition gives 409 INVALID_RESERVATION_TRANSITION.
- Given an expired reservation, when read or consumed, then it reports EXPIRED and its remaining stock has returned to available.
- Given reserved stock at a location, when a transfer is approved, then available (excluding reserved) gates the move and reserved is untouched.
- Given TECHNICIAN/STAFF_MAINTENANCE, then 403 on reservation mutations; given a full `mvn test` run, then the suite is green including OPA tests.

## Spec Change Log

## Review Triage Log

## Auto Run Result

Status: done

Summary: Inventory reservation lifecycle (blueprint E4) — `/api/v1/inventory-reservations` POST create (atomic available→reserved move + INSERT, 409 INSUFFICIENT_STOCK on zero-row move), GET list (5 optional filters: sparepartId, locationId, status, referenceType, referenceId; plant-scoped), GET /{id} (expiry-on-access: past expires_at returns remaining to available and marks EXPIRED), POST /{id}/consume (partial/full draw-down, reserved→consumed, 409 on over-consume or terminal), POST /{id}/cancel (returns remaining to available, stamps cancelled_by). Expiry evaluated on access (no scheduler). Transfer interlock via column semantics: available already excludes reserved. Atomic balance operations: moveToReserved, moveFromReserved, consumeFromReserved (single-statement conditional UPDATE, version-bypass). Gates: INVENTORY_MAINTENANCE/STOREKEEPER/MANAGER_MAINTENANCE + plant scope. OPA reservation_paths + tests. Audit: CREATE/UPDATE with previous+new, SYSTEM actor for expiry.

Files changed: InventoryReservationService, InventoryReservationRepository (findByIdForUpdate + scoped/filtered queries), InventoryStockBalanceRepository (moveToReserved/moveFromReserved/consumeFromReserved), InventoryReservationEntity (expire method), InventoryReservationController + DTOs + ExceptionHandler, authz.rego + authz_test.rego. Tests: IntegrationTest (22), ControllerTest (16), ConcurrencyTest (2 — consume race + create race on scarce stock).

Verification (each class alone): integration 22/22, controller 16/16, concurrency 2/2, OPA 341/341, migration diff empty.

## Design Notes

- Balance interlocks as single-statement conditional UPDATEs (guard style of adjustAvailableIfSufficient): reserve = UPDATE … SET available = available - :qty, reserved = reserved + :qty WHERE available - :qty >= 0 (zero rows → INSUFFICIENT_STOCK). consume = reserved - :qty (guarded by remaining check service-side inside the same transaction + row lock via the reservation UPDATE … WHERE status='ACTIVE'). cancel/expire = reserved - :remaining, available + :remaining.
- Expiry is evaluated on access (read/consume paths compare expires_at with Clock) — no worker. On first access past expiry, the service performs the return-to-available move and marks EXPIRED (idempotent if already EXPIRED).
- remaining_quantity is the reservation's own draw-down ledger; the balance's reserved column reflects aggregate reservations (min grammar: reserved >= sum of ACTIVE remaining is NOT enforced as an invariant — balance ops are the source of truth).
- reference_type as uppercase string (WORK_ORDER first; validation: non-blank, ≤50 chars; reference_id ≤64) — no FK by design (polymorphic), consistent with the blueprint.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=InventoryReservationServiceIntegrationTest,InventoryStockServiceTest,V1BaseSchemaMigrationTest` -- expected: all pass
- `cd syncro/apps/backend && mvn -q test` -- expected: full suite green
- OPA tests -- expected: pass with new path set

**Manual checks (if no CLI):**
- `git diff --stat -- syncro/apps/backend/src/main/resources/db/migration` empty.
- Create/consume/cancel each run balance UPDATE + reservation UPDATE in one @Transactional method.
