# Epic 11 Context: Preventive Maintenance

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Leaders and technicians run routine preventive maintenance without relying on machine telemetry: define preventive programs per machine, generate monthly/annual schedules on a calendar/shift basis, complete checklists with assessment and signature approval, print WYSIWYG reports, and let due schedules generate internal preventive workorders that flow into the existing workorder pipeline.

## Stories

- Story 11.1: Preventive Programs & Schedules
- Story 11.2: Checklist, Assessment & Signature
- Story 11.3: Preventive Report & Auto-Workorder

## Requirements & Constraints

- **Program definition:** STAFF_MAINTENANCE or a leader creates a preventive program per machine with a category (mechanical/electrical) and a schedule type limited to MONTHLY or ANNUAL (no daily/weekly/hourly for now). Programs are scoped to the machine's section/group.
- **Schedule generation:** Schedules are generated on a calendar/shift basis (using shift configuration V40) and must work entirely without telemetry. The next due date rolls forward from completion (floating interval), never from the original anchor date.
- **Due/overdue tracking:** The calendar view lists due/overdue items computed from the server clock (never the client clock). Overdue items surface on the preventive dashboard and must be visibly distinct in a non-color-only way.
- **Checklist completion:** A technician/staff completes the checklist; the result persists performed-by, timestamp, notes, and evidence. Checklist items carry assessment values, including LSL/USL bounds where applicable.
- **Assessment & approval:** Approval is a leader action — signature capture (image + signer identity + timestamp), audit-logged. On approval the schedule is marked performed and the next due date rolls forward. The signature mechanism (image pad/photo vs typed + confirmation) is pending OQ-5 confirmation.
- **Reports:** Preventive reports print via a WYSIWYG template in a tabular view — checklist, results, configurable logo, signer identity, and signature block rendered in print output. Printing is browser print of an HTML report (not server-side PDF), shared with workorder reports.
- **Auto-workorder:** A due schedule configured to generate workorders creates an internal preventive workorder (category 02 Preventive) linked back to the schedule. Duplicate generation per schedule period is prevented (idempotent).
- **Schema discipline (NFR-P2-3):** All new schema via Flyway V41+; `ddl-auto=validate`; additive migrations only.
- **Tabular views (NFR-P2-10):** Preventive schedules and other tabular main views use TanStack Table v9, consistent with the rest of the app's tabular pattern.
- **Backend-owned computation (NFR-P2-2):** Due/overdue computation is server-side; the frontend renders, never re-aggregates or invents state.

## Technical Decisions

- **AD-12 — Preventive works without telemetry:** The preventive module must function for machines without MQTT. Scheduling is calendar/shift-based (MONTHLY/ANNUAL, floating interval from completion) using shift config V40; never fabricate data when telemetry is absent.
- **AD-10 — Evidence in Garage:** Checklist evidence images (JPEG/PNG/WebP/PDF, ≤10 MB, configurable) are stored in Garage via the existing `ObjectStorageService`; PostgreSQL stores only object keys. Replacing or deleting evidence removes the previous object; no image bytes in PostgreSQL.
- **Module structure:** Backend lives in `maintenance.preventive` (programs, schedules, checklist, results, reports) under the `maintenance` module; frontend under `features/preventive/`. Governed by AD-12; reuses the `org` module's scope service for section/group scoping.
- **WYSIWYG printing:** Reports use the existing WYSIWYG template pattern (variable picker, preview, save) and browser print; signature is an uploaded image plus signer identity.
- **Shift config source:** Schedule generation reads shift configuration (V40) for calendar/shift basis — reuse the existing config, don't duplicate it.

## UX & Interaction Patterns

- Evidence-first design: wherever an operational decision happens (checklist result, approval, signature), show data source, timestamp, actor, and result.
- Timeline/evidence rows display actor, action, target, timestamp, result, and optional source; timestamps sit near state labels, not hidden in detail-only areas.
- Confirmation dialogs for consequential actions explain the consequence and that evidence is retained.
- All operational components support loading, empty, error, stale, and read-only states (UX-DR-019); empty states explain what is missing and what to do next.
- Build from shadcn/ui primitives and Tailwind tokens; use typed variants, not one-off styling.
- Signature and approval UI should capture signer identity explicitly, matching the evidence trail pattern.

## Cross-Story Dependencies

- **11.2 depends on 11.1:** schedules exist only after programs generate them; the floating next-due rollover from 11.2's approval consumes 11.1's schedule model.
- **11.3 depends on 11.2:** reports render checklist results and signature captured in 11.2; auto-workorders consume the due schedule state produced by 11.1/11.2.
- **Workorder module:** 11.3's internal preventive workorders (category 02) enter the existing workorder flow — workorder creation, scope, and the report/WYSIWYG print surface are shared with the workorder module (FR-175).
- **Signature mechanism (OQ-5):** The approval-signature UX in 11.2 is pending an open question on the v1 mechanism — confirm before finalizing the signature capture flow.
- **Dashboards (FR-172):** The preventive dashboard showing due/overdue items is part of the dashboards area but fed by this epic's schedule data; overdue emphasis must be non-color-only.
