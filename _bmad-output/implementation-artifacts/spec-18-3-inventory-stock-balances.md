---
title: 'Story 18-3: Inventory Stock Balances (per-location)'
type: 'feature'
created: '2026-09-01'
status: 'done'
review_loop_iteration: 0
baseline_revision: '8198456'
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-18-context.md'
warnings: []
deferred:
  - summary: >-
      openapi.json snapshot not regenerated for the location-scoped stock
      surface and the additive locationId/locationCode/sparepartCode fields.
    evidence: |-
      DW-141 pattern; the committed snapshot already omits the whole
      sparepart-stock surface (repo-wide staleness since story 9-5).
    location: >-
      syncro/apps/web/openapi.json
    severity: low
  - summary: >-
      N+1 view assembly + duplicated toView mapper in both stock controllers.
    evidence: |-
      Pre-existing pattern (legacy controller already did per-row lookups);
      batch with findAllById + a shared mapper when list sizes demand it.
      Tracked as DW-146.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryLocationStockController.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** Stock lives in `inventory_stock_balances` keyed (sparepart_id, location_id) per the redesign, but the entire read/write API (SparepartStockController) still addresses stock only by plant — every balance is forced through the plant's default "GUDANG UTAMA" location. Operators cannot create, view, adjust, or reorder-check stock at a specific named location, and completion/landing of parts cannot target a non-default location.

**Approach:** Extend the inventory stock surface to be location-aware while keeping the existing material-code + plant contract backward-compatible. New/extended endpoints accept an optional locationId; the existing sparepart-stock endpoints keep resolving the plant default when locationId is absent. Reorder warning rule (available <= minimum_stock) stays backend-owned and gains per-location reporting.

## Boundaries & Constraints

**Always:**
- No new Flyway migration — inventory_stock_balances (unique (sparepart_id, location_id), available/reserved/consumed/minimum_stock, version, non-negative CHECK) exists in V1.
- Location resolution rule: request carries optional `locationId`; absent → plant default "GUDANG-UTAMA" (existing InventoryStockService.DEFAULT_LOCATION_CODE). Unknown locationId or a locationId from another plant → 404 INVENTORY_LOCATION_NOT_FOUND (never a cross-plant leak).
- New API surface `/api/v1/inventory-locations/{locationId}/stock-balances`: GET list (balances at that location, with materialCode + sparepartCode/name in the view), POST create/upsert for a material code, POST /{materialCode}/adjust signed delta. Mutations gated INVENTORY_MAINTENANCE/STOREKEEPER/SUPER_ADMIN with the location's plant in assignment scope.
- Extend existing `/api/v1/sparepart-stock` endpoints (list, reorder-warnings, POST create, PUT update, POST adjust) with optional locationId param — absent behaves exactly as today (default location) so existing frontend/e2e keeps working.
- Reorder warning: available <= minimum_stock evaluated per balance row; GET reorder-warnings returns per-location rows (locationId/locationCode in view).
- All mutations audit-logged (INVENTORY_STOCK_BALANCE entity type, previous+new values) — reuse InventoryStockService paths; the atomic conditional-update guard (NEGATIVE_STOCK_REJECTED) applies to location-targeted adjust as well.
- Optimistic-lock semantics preserved: PUT requires version, 409 VERSION_CONFLICT on mismatch.
- OPA rego: NEW path set `inventory_location_stock_paths` {"/api/v1/inventory-locations/*/stock-balances", "/api/v1/inventory-locations/*/stock-balances/*", "/api/v1/inventory-locations/*/stock-balances/*/adjust"} for INVENTORY_MAINTENANCE + STOREKEEPER mutations (SUPER_ADMIN via generic bypass). Reads flow through generic read_allowed. Update authz.rego + authz_test.rego.
- Machine-readable errors: reuse NEGATIVE_STOCK_REJECTED (409), VERSION_CONFLICT (409), SPAREPART_NOT_FOUND (404), add INVENTORY_LOCATION_NOT_FOUND (404), FORBIDDEN (403), VALIDATION_ERROR (400).
- TypeScript strict; backend owns all calculations; no frontend work.

**Block If:**
- Existing SparepartStockControllerTest semantics cannot be preserved with the optional locationId (breaking contract) → HALT blocked.

**Never:**
- No edits to V1 migrations; no changes to consumeOnPickup semantics (default-location consumption stays).
- No transfer/reservation logic here (18-4/18-5 scope).
- No frontend work; no removal of existing endpoints.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| List balances at location | GET /inventory-locations/{id}/stock-balances | rows for that location with material code view | unknown/foreign-plant location → 404 |
| Create balance at location | POST {materialCode, available, minimumStock…} | 201 upsert per (sparepart, location); audit CREATE | unknown material → 404; negative → 400 |
| Adjust at location | POST /{materialCode}/adjust {delta} | atomic conditional update; version bumped | underflow → 409 NEGATIVE_STOCK_REJECTED |
| Legacy contract | GET /sparepart-stock?plantId (no locationId) | identical to today (default location rows) | none |
| Legacy with locationId | GET /sparepart-stock?plantId&locationId | rows at that location | foreign plant → 404 |
| Reorder per location | available 3 ≤ minimum 5 at loc B only | warning row includes loc B (id+code), not loc A | none |
| Wrong role mutates | TECHNICIAN POST adjust | rejected | 403 FORBIDDEN |
| Optimistic lock | PUT stale version | rejected | 409 VERSION_CONFLICT |

</intent-contract>

## Code Map

### Existing (reuse/extend)
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql:1339-1358` -- inventory_stock_balances. READ-ONLY.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/application/InventoryStockService.java` -- EXTEND: location-aware overloads of list/upsert/update/adjust/reorderWarnings (locationId param; default-location fallback); keep consumeOnPickup untouched.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/infrastructure/db/InventoryStockBalanceRepository.java` -- has findBySparepartIdAndLocationId, adjustAvailableIfSufficient, consumeIfSufficient, findReorderWarnings(plantId), findAllByPlantId; ADD findAllByLocationId + location-scoped reorder query.
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/stock/api/SparepartStockController.java` -- EXTEND with optional locationId; keep URL contract.
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/stock/api/SparepartStockDtos.java` -- views gain locationId/locationCode.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/infrastructure/db/InventoryLocationRepository.java` -- lookup by id (findById) exists via JpaRepository.
- `syncro/authz/policy/authz.rego` + `authz_test.rego` -- ADD inventory_location_stock_paths.
- `syncro/apps/backend/src/test/java/com/syncro/inventory/application/InventoryStockServiceTest.java` -- unit-test anchor.
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/stock/api/SparepartStockControllerTest.java` -- MUST stay green (contract guard).

### New
- `syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryLocationStockController.java` -- location-scoped stock endpoints.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryStockExceptionHandler.java` -- error mapping for the new controller.
- `syncro/apps/backend/src/test/java/com/syncro/inventory/application/InventoryStockLocationIntegrationTest.java` -- Testcontainers tests covering the matrix.

## Tasks & Acceptance

**Execution:**
- `InventoryStockService.java` -- location-aware overloads + location guard + reorder per-location -- core
- `InventoryStockBalanceRepository.java` -- location-scoped queries -- reads
- `InventoryLocationStockController.java` + `InventoryStockExceptionHandler.java` -- new REST surface -- API
- `SparepartStockController.java` + `SparepartStockDtos.java` -- optional locationId + view fields -- backward-compatible extension
- `syncro/authz/policy/authz.rego` + `authz_test.rego` -- stock-balances path set -- OPA parity
- `InventoryStockLocationIntegrationTest.java` -- matrix coverage -- AC evidence

**Acceptance Criteria:**
- Given balances at two locations, when GET /inventory-locations/{locB}/stock-balances, then only loc B rows return with material code, and a foreign-plant location id gives 404.
- Given a POST at a specific location, when the (sparepart, location) row exists, then it is overwritten/upserted with audit; when absent it is created.
- Given available 3 at loc B, when adjust delta -5, then 409 NEGATIVE_STOCK_REJECTED and no change; delta -3 succeeds atomically with version bump and audit.
- Given no locationId on legacy endpoints, then behavior is byte-identical to today (SparepartStockControllerTest stays green).
- Given available <= minimum_stock at loc B only, when reorder-warnings with locationId or per-location view, then only loc B appears with location fields populated.
- Given STOREKEEPER/INVENTORY_MAINTENANCE allowed and TECHNICIAN denied, then 403 for the denied role on both service gate and OPA.
- Given a full `mvn test` run, then the suite is green including OPA tests.

## Spec Change Log

### 2026-09-02 — bad_spec: "byte-identical" wording vs additive fields
- Trigger: review layers found the AC "behavior is byte-identical to today" contradicts the "Always" clause that adds `locationCode`/`sparepartCode`/`sparepartName` to the view.
- Resolution: the intent is backward-COMPATIBLE (additive JSON fields, existing consumers unaffected), not byte-identical. The legacy code PATH (no locationId → plant-wide `findAllByPlantId`) is unchanged; only the response gains fields.
- KEEP: additive view fields, optional locationId, default-location fallback.

### 2026-09-02 — bad_spec: legacy no-locationId list scope
- Trigger: the I/O matrix parenthetical "(default location rows)" was written when only the default location could hold balances; after 18-3 named locations exist, so the unchanged plant-wide code path returns all locations.
- Resolution: legacy no-locationId = plant-wide aggregate (all locations in the plant) — the literal "identical to today's code path". Operators wanting one location pass locationId. The matrix parenthetical is superseded by this note.
- KEEP: `findAllByPlantId` for the no-locationId path.

## Review Triage Log

### 2026-09-02 — Review pass (build-auto step-04 + /bmad-code-review 4 layers)
- intent_gap: 0
- bad_spec: 2 (low) — "byte-identical" wording; legacy list scope parenthetical (both resolved above; code already correct)
- patch: 6 (high 1, medium 3, low 2)
- defer: 2
- reject: 6
- addressed_findings:
  - `[high]` `[patch]` cross-plant sparepart hole — location-scoped upsert/adjust resolve sparepart globally by material code, so a plant-A user can attach a plant-B sparepart's balance to a plant-A location; the "cross-plant spoofing impossible" javadoc overstates the invariant — added sparepart-plant == location-plant guard (404 SparepartNotFoundException) + tests
  - `[medium]` `[patch]` inactive (deactivated) locations accept stock mutations — mutation path now requires isActive (404 otherwise); reads still allowed (historical balances)
  - `[medium]` `[patch]` existence oracle on location-scoped surface (403 for foreign existing vs 404 unknown) contradicts the no-leak rule — plant-scope failure now 404, role failure stays 403
  - `[medium]` `[patch]` headline feature untested at HTTP boundary — added MockMvc locationId param/body cases + service.adjust-with-locationId integration case (the dropped AdjustBalanceCommand import) + adjust 200 happy path + LOC-004 UPDATE audit
  - `[low]` `[patch]` dead VersionConflictException mapping on the PUT-less location surface — removed
  - `[low]` `[patch]` malformed-body contract diverges between surfaces — new advice mirrors legacy InvalidFormatException→VALIDATION_ERROR refinement
  - deferred: openapi.json snapshot not regenerated (DW-141 pattern; the file already omits the whole stock surface — repo-wide staleness); N+1 view assembly + duplicated toView mapper (perf, pre-existing pattern, batch later)
  - rejected: "test tree doesn't compile at HEAD" (false positive — targeted run compiles the whole test tree, exit 0; telemetry MachineView arity is fine); ErrorResponse duplicated across packages (bounded-context convention, matches every module); LOCATION_NOT_FOUND vs INVENTORY_LOCATION_NOT_FOUND prefix (distinct concepts: default-missing vs explicit-unknown); command parameterization inconsistency (LocationUpsertCommand vs raw params — both readable, no behavior impact); spec Problem "completion/landing" wording (consumeOnPickup default-location is an explicit Never-clause; landing at named locations is 18-4/18-5 scope); contract-guard "edited not preserved" (the guard was red at HEAD from story 15-1; repairing it is required for AC4, assertions unchanged)

## Auto Run Result

Status: done

Summary: Location-aware inventory stock balances (blueprint E2). Legacy /api/v1/sparepart-stock endpoints gained an optional locationId (query for list/reorder-warnings, body for create/update/adjust) — absent keeps the plant-default path byte-identical in behavior; present validates the location exists, is active (mutations), belongs to the plant, and that the sparepart's own plant matches the location's plant. New /api/v1/inventory-locations/{locationId}/stock-balances surface (GET list, POST upsert, POST /{materialCode}/adjust) derives the plant from the location, never the body. Per-location reorder warnings (available <= minimum_stock, derived). View gained additive locationCode/sparepartCode/sparepartName. OPA inventory_location_stock_paths (3 depths) for INVENTORY_MAINTENANCE/STOREKEEPER + 11 mirror tests. consumeOnPickup untouched.

Files changed:
- `syncro/apps/backend/src/main/java/com/syncro/inventory/application/InventoryStockService.java` — location-aware overloads, resolveLocation/requireActiveLocation/requireSparepartInPlant/requireMutationRole/requirePlantScopeForLocation guards, shared upsert/adjust internals
- `syncro/apps/backend/src/main/java/com/syncro/inventory/infrastructure/db/InventoryStockBalanceRepository.java` — findAllByLocationIdOrderBySparepartIdAsc + findReorderWarningsByLocationId
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/stock/api/SparepartStockController.java` — optional locationId params/bodies
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/stock/api/SparepartStockDtos.java` — additive view fields + optional locationId in requests
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/stock/api/SparepartStockExceptionHandler.java` — INVENTORY_LOCATION_NOT_FOUND (404)
- `syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryLocationStockController.java` — new location-scoped surface
- `syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryStockExceptionHandler.java` — new advice (no dead version mapping; InvalidFormatException refinement)
- `syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryLocationDtos.java` — location-stock request records
- `syncro/authz/policy/authz.rego` + `authz_test.rego` — inventory_location_stock_paths + 11 tests
- Tests: InventoryStockLocationIntegrationTest (24, new), InventoryLocationStockControllerTest (11, new), SparepartStockControllerTest (12, +3 locationId cases + pre-existing stub repair)

Review findings breakdown: 6 patched (high 1, medium 3, low 2 — see Review Triage Log), 2 bad_spec wording resolutions (code already correct), 2 deferred (DW-141 repeat, DW-146), 6 rejected.

Follow-up review recommendation: patched high 1, medium 3, low 2 → score 3×3+1×2 = 11 ≥ 5 → true.

Verification performed:
- `mvn -q test -Dtest=InventoryStockLocationIntegrationTest,InventoryLocationStockControllerTest,InventoryStockServiceTest,SparepartStockControllerTest,V1BaseSchemaMigrationTest` — 24+11+12+12+20 = 79/79 pass, exit 0 (run independently by the coordinator after patches)
- OPA `opa test /policy` — 312/312 pass
- `git diff --stat -- syncro/apps/backend/src/main/resources/db/migration` — empty

Residual risks: full-suite green asserted at the epic gate (DW-142); N+1 view assembly deferred (DW-146).

## Design Notes

- Upsert semantics: create at a location mirrors InventoryStockService.upsert (missing row → create with provided values; existing → full overwrite of provided fields with version advance). Reuse, don't fork.
- Location guard: resolve location first, then plant scope from location.plantId — the plant never comes from the request body for location-scoped calls (prevents cross-plant spoofing). The sparepart's own plant (via its machine) is also validated to equal the location's plant (404 SparepartNotFoundException on mismatch), so a global material code can't attach a foreign-plant part's balance to a local location. Mutations additionally require the location to be active; reads do not.
- Legacy list currently joins location→plant for the view; adding locationId/locationCode to the view is additive (new JSON fields only).
- Adjust uses the same single conditional UPDATE (adjustAvailableIfSufficient) — extend the query with a locationId parameter variant.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=InventoryStockLocationIntegrationTest,InventoryStockServiceTest,SparepartStockControllerTest,V1BaseSchemaMigrationTest` -- expected: all pass
- `cd syncro/apps/backend && mvn -q test` -- expected: full suite green
- OPA tests -- expected: pass with new path set

**Manual checks (if no CLI):**
- `git diff --stat -- syncro/apps/backend/src/main/resources/db/migration` empty.
- Legacy GET /sparepart-stock response shape unchanged (only additive fields).
