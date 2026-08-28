---
title: 'Stock OP/OQ & New-Item Completion'
type: 'feature'
created: '2026-08-28'
status: 'in-review'
baseline_commit: 'd4b5f6d8dc33dd3f08f6d5f72d5a28bae873ea50'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-1-sparepart-request-creation-and-types.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-2-request-state-machine.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-3-approval-separation-of-duty-and-escalation.md'
warnings:
  - 'multiple-goals'
  - 'oversized'
deferred: []
---

<intent-contract>

## Intent

**Problem:** Requests created in 12-1/12-2/12-3 flow through the state machine, but stock is not tracked per plant — storekeepers cannot see or adjust on-hand quantities, reorder warnings never fire (FR-146), and PENDING_COMPLETION requests (unknown material codes) have no completion path to register the new part (FR-144).

**Approach:** Add `sparepart_stock` (material_code × plant_id unique, optimistic-lock, reorder rule in the application layer), stock CRUD + adjustment endpoints for INVENTORY_MAINTENANCE/STOREKEEPER, a PICKED_UP→stock-decrement hook in the request state machine (via the inventory application service, AD-11), a reorder-warning signal endpoint, and a new-item completion endpoint that registers material code + image + estimated price on PENDING_COMPLETION requests through the masterdata module's application services (patchProcurement + price-entry create + image, AD-11 never direct cross-module writes).

## Boundaries & Constraints

**Always:**
- **V61** (additive on V60): `sparepart_stock` — `material_code VARCHAR(64) NOT NULL`, `plant_id UUID NOT NULL REFERENCES plants(id)`, `stock_on_hand NUMERIC(18,2) NOT NULL DEFAULT 0`, `order_point NUMERIC(18,2) NOT NULL DEFAULT 0`, `order_qty NUMERIC(18,2) NOT NULL DEFAULT 0`, `version BIGINT NOT NULL DEFAULT 0` (optimistic lock), `created_at/updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`; PK `(material_code, plant_id)`; FK `material_code REFERENCES spareparts(material_code)` — global one-code-one-sparepart (FR-078, AD-11). No negative stock check in DB — the atomic conditional update guards it.
- **Stock entity/service:** new `com.syncro.sparepart.stock` bounded context: `SparepartStockEntity`, `SparepartStockRepository` (findById, saveAndFlush), `SparepartStockService` (create/update/adjust/reorder-check/decrement-on-pickup).
- **Stock gates (FR-146):** mutations by INVENTORY_MAINTENANCE/STOREKEEPER/SUPER_ADMIN. Plant-scoped: user must have the plant in scope (or SUPER_ADMIN). Create = upsert semantics (INSERT … ON CONFLICT (material_code, plant_id) DO UPDATE, or service-level find-then-save; version starts at 0).
- **Reorder warning (FR-146):** application-layer signal — `stock_on_hand <= order_point` → recommend purchase request of `order_qty`. Exposed via `GET /api/v1/sparepart-stock/reorder-warnings` (scoped by plant) returning rows where the rule holds. The PR action itself stays OPA-authorized — this is a signal, not an auto-action.
- **Adjust endpoints:** `PUT /api/v1/sparepart-stock/{materialCode}` body `{stockOnHand?, orderPoint?, orderQty?}` (partial update, all three optional but at least one required) — full overwrite of provided fields, version optimistic-lock (409 on mismatch). `POST /api/v1/sparepart-stock/{materialCode}/adjust` body `{delta}` — atomic conditional update: `UPDATE sparepart_stock SET stock_on_hand = stock_on_hand + :delta, version = version + 1 WHERE material_code = :code AND plant_id = :plant AND stock_on_hand + :delta >= 0`; if 0 rows → 409 NEGATIVE_STOCK_REJECTED (AD-11).
- **PICKED_UP hook (AD-11):** in `SparepartRequestService.transition()`, when `toStatus == PICKED_UP`, request type SPAREPART, and the request's material code resolves to a sparepart, call the inventory application service to decrement stock by `quantity` (atomic conditional update). Unbound machine (no plant) → skip silently (stock is per-plant; CONSUMABLE has no stock flow). If stock row missing → skip (no stock record yet; not an error). The decrement happens in the same transaction as the transition.
- **New-item completion (FR-144):** `POST /api/v1/sparepart-requests/{id}/complete` body `{materialCode, imageObjectKey?, estPriceId?}` — valid only when status is PENDING_COMPLETION. Roles: INVENTORY_MAINTENANCE/STOREKEEPER/SUPER_ADMIN. Flow (AD-11 — never direct cross-module writes): (1) resolve the request's machine/plant; (2) if no sparepart exists with that material code, create one via `SparepartService.create()` (or a new masterdata completion entry point) with the request's machine binding; (3) if exists, `patchProcurement()` to set material code + lead time; (4) attach image via `SparepartImageService.upload()` (Garage object key stored); (5) create the price entry via `SparepartPriceEntryService.create()` if estPriceId absent (or use the given estPriceId when it matches); (6) transition PENDING_COMPLETION → ACKED (state machine already allows this edge from 12-2); (7) audit + timeline.
- **Gate relaxation:** existing `patchProcurement` and `SparepartPriceEntryService.create` are MANAGER-gated; FR-144 requires INVENTORY/STOREKEEPER to complete. Relax both gates to accept INVENTORY_MAINTENANCE/STOREKEEPER when the operation is part of request completion (either a new lower-gate path in each service or a `completingRequest=true` flag). The completion endpoint itself is the OPA-enforced surface.
- **Frontend:** Sparepart Requests list (from f6ac419) gains a "Complete" button + dialog on PENDING_COMPLETION rows (material code, optional image, optional est price), driven by `allowedActions` containing `complete`. New Stock page (`/stock`) — TanStack Table server-side, columns material code/plant/on-hand/OP/OQ + reorder-warning badge, edit + adjust dialogs. Types + hooks mirror existing patterns. Non-native shadcn/Radix controls.
- **Rego:** `sparepart_request_completion_paths := {"/api/v1/sparepart-requests/*/complete"}` allow set `{SUPER_ADMIN, INVENTORY_MAINTENANCE, STOREKEEPER}` (coarse; scope service-side); `sparepart_stock_paths := {"/api/v1/sparepart-stock/**"}` allow set `{SUPER_ADMIN, INVENTORY_MAINTENANCE, STOREKEEPER}` for mutations; reads flow generic `read_allowed`. `.env.example` enforced-paths updated.
- **Audit:** stock create/update/adjust → `AUDIT` `SPAREPART_STOCK` (new entity type, add to `ck_audit_log_entity_type` in V61) with before/after values; completion → existing `SPAREPART_REQUEST` UPDATE + the underlying masterdata audits.
- **Tests:** service tests — stock CRUD/adjust/reorder-rule/decrement-on-pickup (unit + repository-level with version conflict); completion flow test (fresh sparepart + existing + duplicate material code rejected + role gates); controller tests — endpoint shapes + error codes (409 NEGATIVE_STOCK, 409 VERSION_CONFLICT, 403, 404); migration test (V61); rego parity. Reuse 12-3 patterns (Mockito service tests, WebMvc controller tests, Testcontainers migration test).

**Block If:** nothing — all decisions are derivable from FR-144/FR-146/AD-11 and the existing module contracts.

**Never:**
- Never write `spareparts`, `sparepart_price_entries`, or images directly from the request module — always through the masterdata application services (`SparepartService`, `SparepartImageService`, `SparepartPriceEntryService`).
- Never auto-generate material codes or MRE codes — completion material codes are user-supplied.
- Never mutate stock from the workorder module or anywhere other than the `sparepart.stock` application service.
- Never use floats for money/quantities — BigDecimal only.
- Never reset or drop stock rows; adjustments are audit-logged, not destructive.
- Never add new Spring dependencies, new UI libraries, or new services.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| STOCK_CREATE | material code + plant, no row | row created with provided values | — |
| STOCK_UPSERT | existing row, same code/plant | values updated | — |
| STOCK_ADJUST_OK | on-hand 10, delta -3 | on-hand 7 | — |
| STOCK_ADJUST_NEGATIVE | on-hand 2, delta -5 | no change, NEGATIVE_STOCK_REJECTED (409) | atomic conditional 0 rows |
| STOCK_VERSION_CONFLICT | stale version | VERSION_CONFLICT (409) | optimistic lock |
| STOCK_REORDER | on-hand 4 ≤ OP 5 | row in reorder-warnings list, recommend OQ | — |
| STOCK_REORDER_OK | on-hand 6 > OP 5 | not in list | — |
| PICKUP_DECREMENT | SPAREPART request PICKED_UP, stock exists | stock decremented by qty in same transaction | — |
| PICKUP_NO_STOCK | SPAREPART request PICKED_UP, no stock row | transition succeeds, no decrement (skip) | — |
| COMPLETE_OK | PENDING_COMPLETION + new material code | sparepart created, procurement patched, price entry created, →ACKED, audits | — |
| COMPLETE_EXISTING | material code exists | patchProcurement + price entry + →ACKED | — |
| COMPLETE_DUPLICATE | material code belongs to another sparepart | 409 DUPLICATE_MATERIAL_CODE | — |
| COMPLETE_WRONG_STATE | ACKED/REQUESTED (not PENDING_COMPLETION) | 409 INVALID_STATE_TRANSITION | — |
| COMPLETE_FORBIDDEN | TECHNICIAN | 403 FORBIDDEN | — |
| COMPLETE_NO_PRICE | no estPriceId, no price-entry create payload | →ACKED with no price entry (price optional per FR-144 "when they provide...") | — |

</intent-contract>

## Code Map

**Investigation anchors (verified 2026-08-28):**

- `resources/db/migration/V60__escalation_configs.sql` — READ — latest migration is V60; V61 is next.
- `com/syncro/sparepart/infrastructure/SparepartEntity.java` — READ — has `materialCode`, `leadTimeHours`, `imageObjectKey` (lines 53-59), `updateProcurement()` (line 163).
- `com/syncro/sparepart/infrastructure/SparepartRepository.java` — READ — `findByMaterialCodeIgnoreCase`, `existsByMaterialCodeIgnoreCaseAndIdNot`.
- `com/syncro/sparepart/application/SparepartService.java` — READ — `create()` (line 78), `patchProcurement()` (line 149, MANAGER-gated via `requireMutationRole` line 177), `rejectDuplicateMaterialCode` (line 324).
- `com/syncro/sparepart/application/SparepartImageService.java` — READ — upload via ObjectStorageService, `imageObjectKey` persisted; MANAGER/SUPER_ADMIN gated (job scope LEADER).
- `com/syncro/sparepart/application/SparepartPriceEntryService.java` — READ — `create()` (line 67) MANAGER-gated (`requireMutationRole` line 97); `findAllBySparepartIdOrderByEnteredAtDesc` on repo.
- `com/syncro/sparepart/request/application/SparepartRequestService.java` — READ — `transition()` (line 165) is where the PICKED_UP hook goes; `resolveSparepart`, `machineForRequest` helpers exist; `recordMre` pattern for the completion endpoint; `allowedActionsFor` for `complete` action.
- `com/syncro/sparepart/request/infrastructure/db/SparepartRequestEntity.java` — READ — has `materialCode`, `quantity`, `machineId`, `status` columns.
- `com/syncro/sparepart/request/domain/SparepartRequestStateMachine.java` — READ — `PENDING_COMPLETION→ACKED` edge exists (12-2).
- `com/syncro/sparepart/request/api/SparepartRequestController.java` — READ — add `POST /{id}/complete` next to `/approve` (line 124).
- `com/syncro/sparepart/request/api/SparepartRequestDtos.java` — READ — add `CompleteRequest` record; extend `SparepartRequestView` with `stockInfo`/`reorderWarning` (optional).
- `com/syncro/sparepart/request/api/SparepartRequestExceptionHandler.java` — READ — add `DUPLICATE_MATERIAL_CODE` (409), reuse INVALID_STATE_TRANSITION.
- `com/syncro/auth/domain/ApplicationRole.java` — READ — INVENTORY_MAINTENANCE, STOREKEEPER exist.
- `com/syncro/auth/infrastructure/AuthUserPlantAssignmentRepository.java` — READ — `findByAuthUserId` for plant scoping.
- `com/syncro/auth/infrastructure/AuthUserRepository.java` — READ — has `findAllByApplicationRoleInWithWhatsapp`.
- `com/syncro/org/application/OperationalScopeService.java` — READ — `derive(user)` gives plantIds/machineGroupIds.
- `syncro/authz/policy/authz.rego` — READ — add stock + completion paths.
- `syncro/apps/web/src/features/sparepart-requests/*` — READ — list page + hooks + types from f6ac419/12-2/12-3; add Complete button/dialog.
- `syncro/apps/web/src/features/*` — READ — existing TanStack Table patterns for the Stock page.

## Tasks & Acceptance

**Execution:**
- `resources/db/migration/V61__sparepart_stock.sql` — NEW — table + PK + FKs + audit entity-type CHECK extend.
- `com/syncro/sparepart/stock/domain/SparepartStock.java` — NEW — record.
- `com/syncro/sparepart/stock/infrastructure/db/SparepartStockEntity.java` + `SparepartStockRepository.java` — NEW — entity + repository (findById, save, `decrementIfSufficient` native query or lock-based).
- `com/syncro/sparepart/stock/application/SparepartStockService.java` — NEW — upsert/adjust/reorder-check/decrement, gates + audit.
- `com/syncro/sparepart/stock/api/SparepartStockController.java` + `SparepartStockDtos.java` + `SparepartStockExceptionHandler.java` — NEW — REST surface + error codes.
- `com/syncro/sparepart/request/application/SparepartRequestService.java` — MODIFY — PICKED_UP decrement hook (calls stock service), `complete()` method, `allowedActionsFor` + `complete`, gate relaxation.
- `com/syncro/sparepart/application/SparepartService.java` — MODIFY — relax `patchProcurement` gate to INVENTORY/STOREKEEPER (or add completion entry point).
- `com/syncro/sparepart/application/SparepartPriceEntryService.java` — MODIFY — relax `create` gate similarly.
- `com/syncro/sparepart/request/api/SparepartRequestController.java` + `Dtos.java` + `ExceptionHandler.java` — MODIFY — complete endpoint.
- `authz.rego` + `authz_test.rego` + `.env.example` — MODIFY — stock + completion paths.
- Frontend — `src/features/sparepart-requests/*` (Complete dialog/button), `src/features/sparepart-stock/*` (Stock page, TanStack Table, edit/adjust dialogs).
- Tests — stock service/controller/migration, completion flow, rego parity, frontend typecheck.

**Acceptance Criteria:**
- Given a material code and plant, when inventory records stock, then a row exists unique per (material_code, plant_id). [FR-146]
- Given stock_on_hand <= order_point, when the reorder query runs, then the row appears with a recommended purchase qty = order_qty (business-rule signal; PR action stays OPA-authorized). [FR-146]
- Given a stock adjustment would go negative, when applied, then it is rejected server-side (409 NEGATIVE_STOCK_REJECTED) via atomic conditional update; concurrent updates conflict on the version column. [AD-11, NFR-P2-6]
- Given a SPAREPART request reaches PICKED_UP, when the transition completes, then stock for that material code/plant is decremented by the request quantity in the same transaction. [AD-11]
- Given a PENDING_COMPLETION request, when INVENTORY_MAINTENANCE/STOREKEEPER completes it with material code, image, and estimated price, then the sparepart record is created/updated via masterdata services, duplicate material codes are rejected, and the request transitions to ACKED with audit. [FR-144]
- Given the request module, then it never writes spareparts/price-entries/images directly — only via masterdata application services. [AD-11, NFR-P2-8]

## Spec Change Log

<!-- Append-only. Populated by step-04 during review loops. -->

## Review Triage Log

<!-- Append-only. Populated by step-04 on EVERY review pass. -->

## Design Notes

- Stock decrement on PICKED_UP happens in the same transaction as the request transition — if the decrement fails, the whole transition rolls back. This is deliberate (AD-11: no negative stock, no partial pickup).
- Missing stock row on PICKED_UP is a silent skip, not an error: stock is optional per plant; the request lifecycle must not block on an absent record.
- The completion flow's price-entry step is optional — FR-144 says "when they provide"; a request can be completed with just a material code.
- The reorder rule is a pure query/derived signal, not a persisted flag — always recomputed from current stock so it can't go stale.
- New-item completion creates a bare sparepart (machine-bound, minimal category) via `SparepartService.create` when no row exists; full taxonomy is out of scope (a later masterdata story can enrich).

## Verification

**Commands:**
- `./mvnw.cmd -f syncro/apps/backend/pom.xml test "-Dtest=SparepartStockServiceTest,SparepartStockControllerTest,SparepartRequestServiceTest,SparepartRequestControllerTest,SparepartStockMigrationTest"` -- expected BUILD SUCCESS.
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS incl. stock + completion parity.
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green.
- `cd syncro/apps/web && npx biome check src/features/sparepart-requests src/features/sparepart-stock` -- expected clean.
