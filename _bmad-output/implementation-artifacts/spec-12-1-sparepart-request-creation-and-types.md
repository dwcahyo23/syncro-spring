---
title: 'Sparepart Request Creation & Types'
type: 'feature'
created: '2026-08-27'
baseline_commit: 404fd83fd4738367f2f5c008baa3916d244ad717
status: 'review'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-2-create-and-assign-workorders.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** Maintenance teams have no way to formally request spareparts against a workorder — needed parts are tracked verbally or informally, so the storekeeper cannot see what to procure, and requests are not auditable or tied to the workorder lifecycle (FR-140).

**Approach:** Add a `sparepart_requests` table (V57) and a create endpoint that enforces type rules — SERVICE_EXTERNAL requires a workorder, CONSUMABLE does not require a machine, SPAREPART uses the electric/mechanic taxonomy — records an optional purchase reference URL (http/https) and, when the material code is unknown, starts the request in PENDING_COMPLETION for inventory to finish (FR-144). Request creation is audit-logged. The request state machine, ON_PROCUREMENT wiring, approval/SoD, and stock OP/OQ are later stories (12-2/12-3/12-4); 12-1 only creates requests.

## Boundaries & Constraints

**Always:**
- **V57** (additive, on V56):
  - `sparepart_requests` — `id UUID PK DEFAULT gen_random_uuid()`, `request_type VARCHAR(20) NOT NULL CHECK (request_type IN ('SPAREPART','CONSUMABLE','SERVICE_EXTERNAL'))`, `work_order_id VARCHAR(50) NULL REFERENCES work_orders(id) ON DELETE CASCADE`, `machine_id UUID NULL`, `sparepart_id UUID NULL REFERENCES spareparts(id) ON DELETE SET NULL`, `material_code VARCHAR(64) NULL` (denormalized snapshot at creation; the source of truth stays the `spareparts` table), `quantity SMALLINT NOT NULL CHECK (quantity > 0)`, `est_price_id UUID NULL REFERENCES sparepart_price_entries(id) ON DELETE SET NULL`, `est_unit_price NUMERIC(18,2) NULL`, `purchase_reference_url VARCHAR(2048) NULL`, `status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','PENDING_COMPLETION'))`, `requested_by UUID NOT NULL`, `requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `notes TEXT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. Indexes `idx_sparepart_requests_work_order (work_order_id)`, `idx_sparepart_requests_status (status)`, `idx_sparepart_requests_machine (machine_id)`.
  - Audit: drop/re-add `ck_audit_log_entity_type` adding `'SPAREPART_REQUEST'` (preserve all existing types); add `SPAREPART_REQUEST` to `AuditEntityType` enum.
- **Type rules (FR-140):** `SPAREPART` requires `machine_id` + `sparepart_id` (the sparepart's taxonomy category must be ELECTRIC or MECHANIC — the `category` dimension); `CONSUMABLE` requires neither work_order nor machine (no machine binding); `SERVICE_EXTERNAL` requires `work_order_id` (bound to the parent workorder, no stock flow). Validation rejects a mismatched combination with `VALIDATION_ERROR` fieldErrors.
- **Create endpoint:** `POST /api/v1/sparepart-requests` — body `{requestType, workOrderId?, machineId?, sparepartId?, materialCode?, quantity, estPriceId?, purchaseReferenceUrl?, notes?}`. Returns 201 `SparepartRequestView`. Scope gate: SUPER_ADMIN exempt; otherwise in-scope leader (group in derived scope, `isInScopeLeader` semantics from 11-1/10-5) OR the workorder's assigned executor/technician OR STAFF_MAINTENANCE with plant access to the target machine (or the workorder's machine when bound). Authoritative service gate; rego is coarse.
- **Material code & PENDING_COMPLETION (FR-144):** when the request is SPAREPART/CONSUMABLE with NO `materialCode` (new/unknown part), the request starts `PENDING_COMPLETION` instead of `REQUESTED`; inventory completes it in 12-4 via the masterdata `patchProcurement` (never a direct cross-module write). When `materialCode` IS provided, it is validated against the `spareparts.material_code` unique identity — if it matches an existing sparepart, `sparepart_id` is auto-resolved; if it matches nothing, the request still starts `PENDING_COMPLETION` (the code is a hint for inventory).
- **Purchase reference URL (FR-143):** `purchase_reference_url` optional; must be `http`/`https` (validated) and ≤2048 chars; stored verbatim and rendered for the storekeeper in the request view.
- **Estimated price:** `est_price_id` (reference to `sparepart_price_entries`) OR a free-form `est_unit_price` (BigDecimal, precision 18/2). Both nullable; at least one must be present for the request to be priced later — 12-1 does not require it, but `estPriceId`/`estUnitPrice` are carried so 12-3 can compute approval thresholds (qty × est. price).
- **Audit:** request create → `SPAREPART_REQUEST` CREATE with `entityLabel = "<requestType> <materialCode-or-'new'>"`, `plantId` from the machine (or workorder's machine when bound). Uses `AuditLogWriter.record(user, ...)`.
- **Errors:** unknown workorder → 404 `WORKORDER_NOT_FOUND`; unknown sparepart → 404 `SPAREPART_NOT_FOUND`; unknown price entry → 404 `PRICE_ENTRY_NOT_FOUND`; access → 403 `FORBIDDEN`; validation (type-rule mismatch, bad url, quantity ≤0, unknown requestType enum) → 400 `VALIDATION_ERROR` fieldErrors.
- **Rego:** `sparepart_request_paths := {"/api/v1/sparepart-requests"}` — mutation allow set `{MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE, TECHNICIAN}` (five-role, mirroring workorder-create parity 10-2). Reads/list flow through generic `read_allowed`. `.env.example` enforced-paths += `/api/v1/sparepart-requests`.
- **Frontend:** add a "Request part" action to the workorder card/kanban surface (no workorder detail view exists — the card is the per-workorder surface) opening a dialog form: request type select (SPAREPART/CONSUMABLE/SERVICE_EXTERNAL), machine/sparepart pickers (SPAREPART), material code (optional, for new parts), quantity, purchase URL, est price, notes. Reuse shadcn + `syncroFetch`; loading/empty/error/forbidden states. Non-native shadcn/Radix select for request type (project rule: prefer non-native selects).
- **Module placement:** `com.syncro.sparepart.request` sub-package (sparepart domain) with api/application/infrastructure layering; reuses the `org` scope service + `SparepartRequestReadinessPort` is NOT touched (that's 12-2).

**Block If:** nothing.

**Never:**
- Never implement the request state machine (12-2), approval/SoD thresholds (12-3), or stock OP/OQ (12-4) in 12-1 — only creation.
- Never replace the `NoopSparepartRequestReadinessPort` or wire ON_PROCUREMENT — that is 12-2.
- Never write a direct cross-module sparepart write — completion goes through the masterdata application service (12-4).
- Never auto-generate an MRE code or material code — manual only (12-2/12-4).
- Never touch V47-V56 or add V58 — V57 is the only migration for 12-1.
- Never add new Spring dependencies or a new frontend table/UI library.
- Never require telemetry, InfluxDB, Redis, or WAHA for request creation.
- Never mutate workorder status from request creation.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| SPAREPART_CREATE_OK | leader/staff/tech in scope, SPAREPART with machine + sparepart, materialCode matches | 201 view; status REQUESTED; sparepart_id resolved; audit CREATE | — |
| CONSUMABLE_CREATE_OK | any authorized, CONSUMABLE no machine, materialCode present | 201 view; status REQUESTED | — |
| SERVICE_EXTERNAL_CREATE_OK | any authorized, SERVICE_EXTERNAL with workOrderId | 201 view; status REQUESTED; no stock flow | — |
| NEW_PART_PENDING | SPAREPART/CONSUMABLE, no materialCode | 201 view; status PENDING_COMPLETION | — |
| NEW_PART_UNKNOWN_CODE | materialCode provided but no sparepart match | 201 view; status PENDING_COMPLETION; code stored as hint | — |
| TYPE_RULE_VIOLATION | SPAREPART without machine, or SERVICE_EXTERNAL without workOrder | 400 VALIDATION_ERROR fieldErrors.requestType | — |
| BAD_URL | purchaseReferenceUrl not http/https | 400 VALIDATION_ERROR fieldErrors.purchaseReferenceUrl | — |
| BAD_QUANTITY | quantity ≤ 0 | 400 VALIDATION_ERROR fieldErrors.quantity | — |
| UNKNOWN_WORKORDER | SERVICE_EXTERNAL with bad workOrderId | 404 WORKORDER_NOT_FOUND | — |
| UNKNOWN_SPAREPART | SPAREPART with bad sparepartId | 404 SPAREPART_NOT_FOUND | — |
| FORBIDDEN | out-of-scope user | 403 FORBIDDEN | — |

</intent-contract>

## Code Map

**Migration:**
- `resources/db/migration/V57__sparepart_requests.sql` -- NEW -- sparepart_requests table + audit entity_type extension.

**Domain:**
- `com/syncro/sparepart/request/domain/SparepartRequestType.java` -- NEW -- enum SPAREPART, CONSUMABLE, SERVICE_EXTERNAL.
- `com/syncro/sparepart/request/domain/SparepartRequestStatus.java` -- NEW -- enum REQUESTED, PENDING_COMPLETION (12-1 only; 12-2 extends).
- `com/syncro/sparepart/request/domain/SparepartRequest.java` -- NEW -- record of all fields.
- `com/syncro/audit/domain/AuditEntityType.java` -- MODIFY -- + SPAREPART_REQUEST.

**Persistence:**
- `com/syncro/sparepart/request/infrastructure/db/SparepartRequestEntity.java` / `SparepartRequestRepository.java` -- NEW -- save/findById/findByWorkOrderId.

**Application:**
- `com/syncro/sparepart/request/application/SparepartRequestService.java` -- NEW -- create + type-rule validation + material-code resolution + scope gate + audit.
- `com/syncro/sparepart/request/application/SparepartRequestMapper.java` -- NEW -- entity ↔ domain ↔ view.

**API:**
- `com/syncro/sparepart/request/api/SparepartRequestController.java` -- NEW -- POST /api/v1/sparepart-requests.
- `com/syncro/sparepart/request/api/SparepartRequestDtos.java` -- NEW -- request/view DTOs.
- `com/syncro/sparepart/request/api/SparepartRequestExceptionHandler.java` -- NEW -- 404/403/400 mapping.

**Enforcement:**
- `syncro/authz/policy/authz.rego` -- MODIFY -- sparepart_request_paths + five-role allow set.
- `syncro/authz/policy/authz_test.rego` -- MODIFY -- parity cases.
- `syncro/.env.example` -- MODIFY -- sparepart-requests path in enforced-paths.

**Frontend:**
- `src/features/sparepart-requests/types.ts` -- NEW -- request contract types.
- `src/features/sparepart-requests/hooks/use-sparepart-requests.ts` -- NEW -- create-request hook.
- `src/features/sparepart-requests/components/request-part-dialog.tsx` -- NEW -- create-request dialog (type select, machine/sparepart, material code, URL, price, notes).
- `src/features/workorders/components/workorder-card.tsx` -- MODIFY -- add "Request part" action opening the dialog.

**Tests:**
- `com/syncro/sparepart/request/application/SparepartRequestServiceTest.java` -- NEW -- type rules, material resolution, PENDING_COMPLETION, scope gate, audit.
- `com/syncro/sparepart/request/api/SparepartRequestControllerTest.java` -- NEW -- endpoint shapes + error mapping.
- `com/syncro/db/SparepartRequestMigrationTest.java` -- NEW -- V57 table/constraints/indexes/audit (Testcontainers).

## Tasks & Acceptance

**Execution:**
- [x] `resources/db/migration/V57__sparepart_requests.sql` -- table + audit extension.
- [x] Domain enums + records + `AuditEntityType` -- sparepart request types.
- [x] Entity + repository.
- [x] `SparepartRequestService` -- create + type rules + material resolution + gate + audit.
- [x] Controller + DTOs + exception handler.
- [x] `authz.rego` + `authz_test.rego` + `.env.example` -- sparepart_request_paths + parity.
- [x] Frontend: request-part dialog + workorder card action + hooks + types.
- [x] Tests (service + controller + migration).

**Acceptance Criteria:**
- Given an authorized user creates a request on a workorder, when submitted, then type rules are enforced (SERVICE_EXTERNAL requires a workorder; CONSUMABLE does not require a machine; SPAREPART uses the electric/mechanic taxonomy) and creation is audit-logged. [FR-140]
- Given a purchase reference URL, when stored, then it is http/https-validated and rendered for the storekeeper. [FR-143]
- Given a new-item request without a material code (or with an unmatched code), when created, then it starts in PENDING_COMPLETION. [FR-144]
- Given an out-of-scope user, when they create a request, then they are rejected server-side (403). [FR-160]
- Given OPA enforcement, then sparepart-request mutations are default-deny with the five-role allow set and reads are any-authenticated, with parity tests. [FR-160]

## Design Notes

- **Material-code resolution is a lookup, not a mutation.** When `materialCode` is provided, the service resolves it against `spareparts.material_code` (unique identity). Match → `sparepart_id` set + status REQUESTED; no match → status PENDING_COMPLETION (the code is stored as a hint for inventory). No sparepart is created in 12-1 — completion is 12-4 via `patchProcurement`.
- **`sparepart_requests.material_code` is a snapshot** for storekeeper display and reorder reference; the authoritative code lives on `spareparts`. This avoids an FK join for the request list while keeping the contract simple.
- **`est_price_id` + `est_unit_price` are carried, not enforced** — 12-1 stores what the requester knows (a linked price entry or a free-form unit price); 12-3 computes qty × est. price for approval tiers. Both nullable so an un-priced request can still be created.
- **Five-role rego parity** mirrors workorder-create (10-2): `{MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE, TECHNICIAN}`. The service gate is authoritative for scope (leader group/plant, technician assigned executor, staff plant access).
- **ON_PROCUREMENT is explicitly out of scope.** The readiness port stays Noop; 12-2 replaces the bean and wires `recomputeProcurementState`. 12-1 must not touch `WorkOrderService.transition` or the state machine.
- **Request status enum in 12-1 is minimal** (REQUESTED, PENDING_COMPLETION) because 12-2 owns the full state machine (ACKED/PROCESSING/READY/PURCHASE_REQUESTED/PART_RECEIVED/PICKED_UP/CLOSED). The migration CHECK lists only the 12-1 statuses; 12-2 re-adds the CHECK with the full set (additive drop/re-add, same pattern as audit types).

## Verification

**Commands:**
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=SparepartRequestServiceTest,SparepartRequestControllerTest,SparepartRequestMigrationTest"` -- expected BUILD SUCCESS.
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrder*Test,Sparepart*Test"` -- expected no regressions.
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS incl. new sparepart-request parity cases.
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green.
- `cd syncro/apps/web && npx biome check src/features/sparepart-requests src/features/workorders` -- expected clean.

## Spec Change Log

<!-- Empty until review loop. -->

## Review Triage Log

<!-- Empty until first review pass. -->
