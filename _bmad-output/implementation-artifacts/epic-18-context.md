# Epic 18 Context: Sparepart BOM & Inventory Maturation

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Mature the sparepart master into a full BOM master — BOM identity (code, serial, hierarchy key, version) plus a review/approval lifecycle on the existing `spareparts` table — and rebuild inventory on a per-location model: named inventory locations, per-location stock balances replacing the legacy per-plant stock record, approval-gated transfers between locations, and stock reserved against workorders. This epic is part of the adopted schema redesign: fresh unified Flyway migrations with a dev-phase database reset and reseed, not incremental patching of the old schema.

## Stories

- Story 18.1: Sparepart BOM Master Extension
- Story 18.2: Inventory Locations
- Story 18.3: Inventory Stock Balances
- Story 18.4: Inventory Transfers
- Story 18.5: Inventory Reservations

## Requirements & Constraints

**BOM master (spareparts).** `spareparts` gains `bom_code` (unique), `hierarchy_identity_key` (unique), `bom_serial`, `bom_code_version`, `review_status` (`PENDING_REVIEW`/`ACTIVE`/`REJECTED`), and `rejection_reason`, with a composite unique constraint on (machine, category, kind, bom_serial). A `PENDING_REVIEW` sparepart becomes `ACTIVE` on approval or `REJECTED` with a reason; the change is audit-logged. The established BOM code rule holds: the code excludes type, while duplicate identity includes type.

**Locations.** Named locations per plant (`plant_id`, `code`, `name`, `description`, `is_active`) unique per (plant, code). Create/update/deactivate is restricted to the inventory/maintenance leadership roles (SUPER_ADMIN, MANAGER_MAINTENANCE, INVENTORY_MAINTENANCE) and audit-logged. Seeding must create one default location per plant named "GUDANG UTAMA".

**Stock balances.** One balance row per (sparepart master, location) carrying `available`, `reserved`, `consumed`, and `minimum_stock`. Stock on hand is reported per material code per location, and a reorder warning fires when available falls to or below `minimum_stock` — a business signal only; the resulting purchase request stays separately authorized. Every stock mutation is audit-logged and kept atomically consistent with transfers and reservations.

**Transfers.** A transfer (sparepart, source location, destination location, quantity) moves through `PENDING_APPROVAL`/`APPROVED`/`REJECTED` with requester, reviewer, rejection reason, and review timestamp. Approval debits the source and credits the destination in one transaction; insufficient source stock is rejected with a machine-readable code. Requester/reviewer separation of duties is enforced server-side; transfers are audit-logged.

**Reservations.** A reservation (sparepart, location, quantity with remaining quantity, status `ACTIVE`/`CONSUMED`/`CANCELLED`/`EXPIRED`) references a workorder via reference type/id, with requested/consumed/cancelled-by actors. It reduces available (not on-hand) stock at the location; reserved stock cannot be transferred away or consumed by another workorder. It is released when the request is closed/picked up or explicitly cancelled, and expires when its reference is no longer valid. Audit-logged.

**Completion linkage.** Completing a new-item sparepart request may record a target inventory location, and the stock balance updates at that location — inventory is the landing point for newly completed parts.

**Non-functional.** The backend owns all stock, state-machine, and related calculations; the frontend renders results only.

## Technical Decisions

- **Fresh migration set, reset + reseed.** All five stories assume the redesign migration set (V1.. fresh) runs with a dev-phase database reset; there is no data migration from the legacy stock table.
- **`inventory_locations` + `inventory_stock_balances` replace `sparepart_stock`.** The legacy stock keyed by (material_code, plant_id) is dropped outright in the reset; balances are keyed to sparepart master identity and location instead of plant.
- **Existing taxonomy retained.** `sparepart_taxonomy` (dimension CATEGORY) stays as the category source — no new sparepart category table (a resolved planning decision point).
- **`spareparts` is redesigned in place.** The existing sparepart entity is extended with the BOM columns and a `BomReviewStatus` enum rather than replaced.
- **New inventory tables.** `inventory_locations`, `inventory_stock_balances`, `inventory_transfers`, `inventory_reservations` per the ORM target blueprint's module E designs, implemented in a dedicated inventory package/module (`InventoryLocation`, `InventoryStockBalance`, `InventoryTransfer`, `InventoryReservation` entities) alongside the masterdata package that owns the extended sparepart.
- **Atomicity and server-side rules.** Transfer approval adjusts source and destination balances in a single transaction; reservations, transfers, and balance mutations must compose atomically. Business rules (insufficient stock, requester ≠ reviewer, reservation release/expiry, duplicate identity) are enforced in the backend with machine-readable error codes, and mutations carry actor + traceId audit records.

## Cross-Story Dependencies

- **18.1 is the foundation.** It establishes the BOM identity and review lifecycle on `spareparts` and the shared migration/reseed baseline all other stories assume.
- **18.3 depends on 18.2** — stock balances reference a location — and on 18.1 for the sparepart master identity they are keyed to.
- **18.4 depends on 18.3** — transfers debit and credit per-location balances.
- **18.5 depends on 18.3** — reservations reduce `available` and populate `reserved`, and their release/consumption hooks into the workorder/request lifecycle built by earlier epics.
