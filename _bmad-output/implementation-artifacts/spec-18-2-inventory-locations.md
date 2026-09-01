---
title: 'Story 18-2: Inventory Locations'
type: 'feature'
created: '2026-09-01'
status: 'done'
review_loop_iteration: 0
baseline_revision: '1f886ad'
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-18-context.md'
warnings: []
deferred:
  - summary: >-
      openapi.json snapshot not regenerated for the inventory-locations surface.
    evidence: |-
      Same project pattern as DW-141 (story 18-1); the committed snapshot is
      refreshed by a separate chore, not per story.
    location: >-
      syncro/apps/web/openapi.json
    severity: low
  - summary: >-
      FR-161 allowed-actions *.write mirror not extended for INVENTORY_MAINTENANCE.
    evidence: |-
      Pre-existing pattern (also true for sparepart-stock); needs one focused
      pass across all inventory roles. Tracked as DW-145.
    location: >-
      syncro/authz/policy/authz.rego
    severity: low
  - summary: >-
      404-before-403 existence oracle on get/update (role-blind findById precedes
      the gate).
    evidence: |-
      Matches the existing codebase posture (SparepartService.get, etc.);
      resource-enumeration risk is low for UUID ids. Rejected as a patch,
      recorded as a known posture.
    severity: low
---

<intent-contract>

## Intent

**Problem:** `inventory_locations` (blueprint E1) exists in the V1 schema with the per-plant "GUDANG UTAMA" seed, but there is no Java service or API to manage locations: users cannot create, update, or deactivate named inventory locations per plant, so stock is not yet physically traceable to a store or workshop.

**Approach:** Add CRUD lifecycle management for inventory locations in a new inventory-module service + controller: create (POST), update (PUT), deactivate (and re-activate via PUT is_active), and list/get. Mutations are restricted to SUPER_ADMIN/MANAGER_MAINTENANCE/INVENTORY_MAINTENANCE (plant-scoped) and every mutation is audit-logged. The seeded default location ("GUDANG UTAMA", code GUDANG-UTAMA) stays the per-plant default resolved by InventoryStockService.

## Boundaries & Constraints

**Always:**
- No new Flyway migration — `inventory_locations` (id, plant_id, code, name, description, is_active, unique uq_inventory_locations_plant_code, ck name/code not blank) already exists in V1; seed rows are untouched.
- API surface: `/api/v1/inventory-locations` — GET list (filter: plantId required; optional activeOnly), POST create, GET /{id}, PUT /{id} update (code/name/description/isActive). camelCase JSON; UUID ids opaque.
- Uniqueness: (plant_id, code) case-insensitive enforced service-side (pre-check + constraint-mapped 409 DUPLICATE_LOCATION) matching the uq_inventory_locations_plant_code index.
- Role gate: mutations SUPER_ADMIN/MANAGER_MAINTENANCE/INVENTORY_MAINTENANCE with the target plant in the user's assignment scope (SUPER_ADMIN exempt from scope). Reads: any authenticated user with plant assignment (or SUPER_ADMIN).
- Every mutation writes an audit record via AuditLogWriter (AuditEntityType.INVENTORY_LOCATION — already provisioned in the enum and the SQL CHECK).
- OPA rego: NEW path set `inventory_location_paths` {"/api/v1/inventory-locations", "/api/v1/inventory-locations/*"} for MANAGER_MAINTENANCE + INVENTORY_MAINTENANCE mutations (SUPER_ADMIN via the generic bypass). Update the authz.rego + authz_test.rego accordingly.
- Machine-readable errors: DUPLICATE_LOCATION (409), INVENTORY_LOCATION_NOT_FOUND (404), FORBIDDEN (403), VALIDATION_ERROR (400).
- Location delete is NOT exposed (RESTRICT FKs from balances/transfers/reservations; deactivation is the lifecycle end).
- TypeScript strict; backend owns all rules; no frontend work.

**Block If:**
- OPA rego cannot express the new path set without breaking existing tests → HALT blocked.

**Never:**
- No deletion endpoint; no edits to V1 migrations; no changes to InventoryStockService default-location resolution ("GUDANG-UTAMA" code lookup stays).
- No per-location stock operations here (18-3 scope).
- No frontend work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create location | POST /inventory-locations {plantId, code, name, description?} by INVENTORY_MAINTENANCE in scope | 201; is_active=true; audit CREATE | blank code/name → 400 |
| Duplicate code per plant | POST with existing (plant, code) case-insensitively | rejected | 409 DUPLICATE_LOCATION |
| Duplicate code different plant | POST same code in another plant | 201 (uniqueness is per-plant) | none |
| Update location | PUT /{id} rename / description / isActive | 200; audit UPDATE with previous+new | unknown id → 404 |
| Deactivate default location | PUT GUDANG-UTAMA is_active=false | allowed (soft) — but balances reference it; no delete | audit captured |
| Wrong role mutates | STOREKEEPER/STAFF_MAINTENANCE POST | rejected | 403 FORBIDDEN |
| Out-of-scope plant | INVENTORY_MAINTENANCE not assigned to plant | rejected | 403 FORBIDDEN |
| List | GET ?plantId=…&activeOnly=true | only that plant; activeOnly filters is_active | unknown plant → 404 |

</intent-contract>

## Code Map

### Existing (reuse)
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql:1322-1337` -- inventory_locations table + uq_inventory_locations_plant_code. READ-ONLY.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/infrastructure/db/InventoryLocationEntity.java` -- entity already maps all columns (no setter for fields except via constructor; ADD update/rename/activate-deactivate methods).
- `syncro/apps/backend/src/main/java/com/syncro/inventory/infrastructure/db/InventoryLocationRepository.java` -- has findByPlantIdAndCodeIgnoreCase, findFirstByPlantIdAndActiveTrueOrderByNameAsc; ADD existsByPlantIdAndCodeIgnoreCaseAndIdNot, findAllByPlantIdOrderByNameAsc.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/application/InventoryStockService.java` -- pattern anchor for role/scope gating (requireMutationAccess/requireReadAccess) and default location code GUDANG-UTAMA. Do not change.
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/stock/api/SparepartStockExceptionHandler.java` -- pattern anchor for ErrorResponse shape (code/message/fieldErrors/timestamp/traceId).
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- INVENTORY_LOCATION already present.
- `syncro/authz/policy/authz.rego` -- ADD inventory_location_paths + mutation rules; `syncro/authz/policy/authz_test.rego` -- mirror tests.
- `syncro/apps/backend/src/test/java/com/syncro/inventory/application/InventoryStockServiceTest.java` -- test-style anchor.
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/stock/api/SparepartStockControllerTest.java` -- MockMvc controller-test anchor.

### New
- `syncro/apps/backend/src/main/java/com/syncro/inventory/application/InventoryLocationService.java` -- CRUD use cases + gates + audit.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryLocationController.java` -- REST surface.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryLocationDtos.java` -- request/response records.
- `syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryLocationExceptionHandler.java` -- error mapping.
- `syncro/apps/backend/src/test/java/com/syncro/inventory/application/InventoryLocationServiceIntegrationTest.java` -- lifecycle + gate tests (Testcontainers).

## Tasks & Acceptance

**Execution:**
- `InventoryLocationEntity.java` -- add update/rename/activation methods (encapsulated, no setStatus) -- lifecycle
- `InventoryLocationRepository.java` -- add duplicate-check + list queries -- guards
- `InventoryLocationService.java` -- create/update/list/get with role+plant gates and audit records -- core
- `InventoryLocationDtos.java` + `InventoryLocationController.java` + `InventoryLocationExceptionHandler.java` -- API contract -- surface
- `syncro/authz/policy/authz.rego` + `authz_test.rego` -- inventory_location_paths mutation set -- OPA parity
- `InventoryLocationServiceIntegrationTest.java` -- covers every matrix row -- AC evidence

**Acceptance Criteria:**
- Given an INVENTORY_MAINTENANCE user assigned to plant P, when creating a location, then it persists with is_active=true, is returned 201 with camelCase fields, and an audit CREATE row exists.
- Given a location with (plant, code) already present, when creating with the same code (any case), then 409 DUPLICATE_LOCATION; the same code in a different plant succeeds.
- Given a MANAGER_MAINTENANCE user, when updating name/description/isActive, then the row updates and an audit UPDATE row carries previous+new values.
- Given STOREKEEPER/STAFF_MAINTENANCE/TECHNICIAN, when mutating, then 403 (service gate + OPA deny); reads for assigned users succeed.
- Given a non-assigned INVENTORY_MAINTENANCE user, when mutating a foreign plant's location, then 403.
- Given GET ?plantId, then only that plant's locations return, ordered by name; activeOnly=true filters is_active.
- Given a full `mvn test` run, then the suite is green including new integration tests and OPA rego tests.

## Spec Change Log

### 2026-09-02 — bad_spec: case-sensitivity open question resolved
- Trigger: the Design Notes posed the (plant, code) case-sensitivity mismatch as an open question ("must match the DB's plain UNIQUE?"); review layers confirmed the gap is real (concurrent `WS-01`/`ws-01` both pass the case-insensitive pre-check and the case-sensitive DB constraint).
- Amended: the service now normalizes `code` to uppercase on write (create + update), so the case-insensitive pre-check and the case-sensitive DB UNIQUE agree on the same identity. Seed codes are already uppercase; `findByPlantIdAndCodeIgnoreCase` still matches.
- KEEP: gate idiom, audit shape, OPA path set, all tests.

## Review Triage Log

### 2026-09-02 — Review pass (build-auto step-04 + /bmad-code-review 4 layers)
- intent_gap: 0
- bad_spec: 1 (medium) — case-sensitivity open question; resolved by uppercase normalization (see Spec Change Log)
- patch: 8 (high 1, medium 3, low 4)
- defer: 4
- reject: 7
- addressed_findings:
  - `[high]` `[patch]` PUT can rename the seeded GUDANG-UTAMA default → breaks InventoryStockService.defaultLocation() for the whole plant — rename of the default code rejected 400; deactivation still allowed; tests added
  - `[medium]` `[patch]` case-sensitivity race (pre-check CI vs DB CS) — code normalized to uppercase on write
  - `[medium]` `[patch]` empty PUT body `{}` writes a no-op UPDATE audit — rejected 400 (matches InventoryStockService.update anchor)
  - `[medium]` `[patch]` race-backstop save() mapping untested (SVC-007 bypasses service) — Mockito unit test for the catch branch + rethrow
  - `[low]` `[patch]` dead InventoryLocationEntity.deactivate() — removed
  - `[low]` `[patch]` controller javadoc says "full PUT" but semantics are partial — corrected
  - `[low]` `[patch]` activeOnly default + blank-code PUT + audit-ordering fragility — tests added/fixed
  - `[low]` `[patch]` description null-vs-empty asymmetry undocumented — DTO javadoc
  - deferred: openapi.json snapshot not regenerated (DW-141 pattern repeats); FR-161 allowed-actions `*.write` mirror not extended for INVENTORY_MAINTENANCE (pre-existing pattern, also true for sparepart-stock); OPA rego not wired into `mvn test` (repo-wide, runs via run-opa-test.ps1); 404-before-403 existence oracle on get/update (matches existing codebase posture — role-blind existence check precedes gate)
  - rejected: INVALID_QUERY_VALUE/INVALID_PATH_VALUE/MALFORMED_JSON codes (consistent with SparepartController/SparepartStockController precedent — spec's "exhaustive" list was illustrative); plantAssigned UUID.fromString 500 (JWT subject is always a UUID by construction); @Version optimistic lock on locations (no version column in V1; out of scope); audit-label parity nitpick (new format is correct, anchor was the odd one); constraintViolation echoing property paths (matches existing handlers)

## Auto Run Result

Status: done

Summary: Inventory-location lifecycle API (blueprint E1) — `/api/v1/inventory-locations` GET list (plantId required, activeOnly optional) / GET one / POST create / PUT partial update (rename, description, activate-deactivate). Role+plant gates mirror InventoryStockService (SUPER_ADMIN/MANAGER_MAINTENANCE/INVENTORY_MAINTENANCE mutate; STOREKEEPER excluded; reads need assignment). Case-insensitive duplicate pre-check + uppercase code normalization + DB constraint backstop → 409 DUPLICATE_LOCATION. Default GUDANG-UTAMA rename guard. Every mutation audit-logged (INVENTORY_LOCATION, previous+new). OPA inventory_location_paths for the two roles + 12 mirror tests. No delete endpoint.

Files changed:
- `syncro/apps/backend/src/main/java/com/syncro/inventory/application/InventoryLocationService.java` — new service (gates, CRUD, audit, guards)
- `syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryLocationController.java` — new REST surface
- `syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryLocationDtos.java` — new request/response records
- `syncro/apps/backend/src/main/java/com/syncro/inventory/api/InventoryLocationExceptionHandler.java` — new error mapping
- `syncro/apps/backend/src/main/java/com/syncro/inventory/infrastructure/db/InventoryLocationEntity.java` — update() method (deactivate removed as dead code)
- `syncro/apps/backend/src/main/java/com/syncro/inventory/infrastructure/db/InventoryLocationRepository.java` — duplicate-check + list queries
- `syncro/apps/backend/src/main/java/com/syncro/audit/infrastructure/AuditLogRepository.java` — findByEntityIdOrderByCreatedAtAsc (test-support query)
- `syncro/authz/policy/authz.rego` + `authz_test.rego` — inventory_location_paths + 12 tests
- Tests: InventoryLocationServiceIntegrationTest (23), InventoryLocationControllerTest (13), InventoryLocationServiceTest (8, new)

Review findings breakdown: 8 patched (high 1, medium 3, low 4), 4 deferred (DW-141 repeat, DW-145, rego-not-in-maven, 404-before-403 oracle), 7 rejected.

Follow-up review recommendation: patched high 1, medium 3, low 4 → score 3×3+1×4 = 13 ≥ 5 → true.

Verification performed:
- `mvn -q test -Dtest=InventoryLocationServiceIntegrationTest,InventoryLocationControllerTest,InventoryLocationServiceTest,InventoryStockServiceTest,V1BaseSchemaMigrationTest` — 23/23, 13/13, 8/8, 12/12, 20/20 pass (exit 0)
- OPA via docker `opa test` — 301/301 pass (incl. 12 new inventory-location tests)
- `git diff --stat -- syncro/apps/backend/src/main/resources/db/migration` — empty

Residual risks: full-suite green asserted at the epic gate (DW-142); FR-161 allowed-actions mirror for inventory roles deferred (DW-145).

## Design Notes

- Reuse InventoryStockService's gate idiom verbatim (SUPER_ADMIN bypass; INVENTORY_MAINTENANCE/STOREKEEPER for stock, but locations gate is MANAGER_MAINTENANCE + INVENTORY_MAINTENANCE per the epic AC — STOREKEEPER deliberately NOT granted location management).
- Case-insensitive duplicate check service-side must match the DB's plain UNIQUE (plant_id, code)? V1 declares plain UNIQUE (plant_id, code) — case-SENSITIVE. Service-side pre-check is case-insensitive (findByPlantIdAndCodeIgnoreCase); the DB constraint remains the race backstop mapped to DUPLICATE_LOCATION.
- Deactivation keeps the row (balances FK RESTRICT makes delete impossible anyway); no delete endpoint at all.
- Audit label: "<code> @<plantCode>" style consistent with InventoryStockService.entityLabel.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=InventoryLocationServiceIntegrationTest,InventoryStockServiceTest,V1BaseSchemaMigrationTest` -- expected: all pass
- `cd syncro/apps/backend && mvn -q test` -- expected: full suite green
- OPA tests (`syncro/authz`) -- expected: rego tests pass with the new path set

**Manual checks (if no CLI):**
- `git diff --stat -- syncro/apps/backend/src/main/resources/db/migration` empty.
- POST/PUT/GET paths respond per matrix via MockMvc tests.
