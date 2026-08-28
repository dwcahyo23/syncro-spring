# Epic 14 Context: Dashboards, Reports & Notifications

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Give maintenance leaders scope-aware dashboards (machine, workorder, preventive, MTBF/MTTR, technician KPI), WYSIWYG printable reports with logo and signature, and WAHA WhatsApp notifications for lifecycle events plus a 4-hour escalation with a phone-bound auto-login link. Analytics are backend-computed; the frontend renders only.

## Stories

- Story 14.1: Machine, Workorder & Preventive Dashboards
- Story 14.2: MTBF/MTTR & Technician KPI Dashboards
- Story 14.3: WYSIWYG Print Reports with Signature
- Story 14.4: WAHA Notifications & 4-Hour Acknowledgment

## Requirements & Constraints

- Dashboards respect derived scope (plant/machineGroup/team); every component renders loading, empty, error, stale, read-only, and forbidden states. Overdue signals never rely on color alone.
- Workorder dashboard counts are backend-computed with filtering by plant/section/status/category; frontend does not re-aggregate. Preventive due/overdue derives from the server clock.
- MTBF/MTTR are backend-computed: MTBF orders consecutive breakdown workorders by `woStopAt` (not id), MTTR sums repair-session durations; units hours, window monthly rolling, 30-minute Redis TTL with stale indicator, explicit insufficient-data state below 2 breakdown workorders. Sync mutations invalidate analytics cache.
- Technician KPI dashboard combines configurable rating dimensions (1–5 stars, SUPER_ADMIN-configured) with objective KPIs (completed count, average MTTR, on-time %); visibility by role: leader own-group, manager plant, manager-global all. Guard against rating inflation vs stagnant objective KPIs.
- Reports are browser-printed WYSIWYG HTML (no server-side PDF): tabular view, configurable logo, WO fields, sessions, parts, narrative, CP/CPK when present, evidence references, and a signature block (uploaded image + signer identity + timestamp) capturing leader/SPV approval at DONE/CLOSED.
- Notifications are non-blocking: enqueue via the notification module's outbox (never write `notification_jobs` directly), reuse Epic 5 worker/rate-limit (Redis)/circuit-breaker (Resilience4j) machinery, never log WAHA credentials or phone numbers. Each lifecycle event (new breakdown, ON_PROCUREMENT, part READY, DONE/CLOSED) is idempotent; recipients are section leaders and above plus inventory roles with phone numbers.
- The 4-hour escalation threshold is configurable; elapsed IN_PROGRESS time is computed from `_status_history` rows excluding ON_PROCUREMENT segments, never from live status. Acknowledgment stops further escalation and records to timeline + audit; the landing task list separates acknowledged vs pending acks and rated vs unrated closed workorders.
- Auto-login link carries a short-lived token bound to the recipient's registered WA number: expires on first use or configurable TTL (default 15 min), revoked on expiry/mismatch/login reuse; any mismatch falls back to normal login, no residual access via a stale link.
- Out of scope: production OEE/stop-time dashboards, cost/profitability reporting, preventive types beyond MONTHLY/ANNUAL, technician self-rating.

## Technical Decisions

- Dashboards and reports live in `maintenance.*` (AD-6, AD-10, AD-12); WAHA notifications extend the `notification` module with a polymorphic outbox target (AD-9): `notification_jobs` gains nullable `target_type`/`target_id`, `alert_id` becomes nullable (additive migration), idempotency key `target_type + target_id + template_name + recipient_id`.
- MTBF/MTTR recompute from the current dataset with a `last_analytics_at` watermark for incremental computation; analytics work without telemetry (AD-12): MTTR uses repair-session timestamps; machines without MQTT show "insufficient data", never fabricated values.
- ON_PROCUREMENT is derived from live sparepart-request state, recomputed on transition events (not a poller); derived transitions write a `_status_history` row (`source=DERIVED`, `actor=SYSTEM`) — what the 4-hour ack clock reads (AD-5).
- Evidence images and technical drawings in reports are stored in Garage via `ObjectStorageService` (JPEG/PNG/WebP/PDF, ≤10 MB configurable); PostgreSQL keeps only object keys (AD-10).
- Template-driven WAHA messages reuse the existing WYSIWYG template editor pattern with workorder-specific variables. Notifications are never inline with request/ingest paths; secrets never logged.
- Tabular views on these dashboards use TanStack Table v9 (the reference system's dense tabular pattern).

## UX & Interaction Patterns

- UX-DR-019 governs all dashboard components: loading, empty, error, stale, read-only, and forbidden states must each be representable.
- Interface language follows the reference admin system: dense tabular views, card-based dashboard layout from the shadcn/Next.js admin boilerplate, no decorative charts without operational action.
- Overdue items must be visually distinct without color-only encoding; analytics freshness conveyed via stale indicator.
- Reports must print pixel-accurate from the browser (WYSIWYG), including logo and signature placement; signature capture is an uploaded image plus signer identity and timestamp (capture mechanism pending OQ-5).
- The 4-hour-ack landing page presents the leader's task list (acknowledged vs pending; rated vs unrated closed) for action at a glance. Recipients follow the link from a phone, so acknowledging and rating must work on mobile.

## Cross-Story Dependencies

- 14.1/14.2 depend on org scope derivation (plant/machineGroup/team), workorder lifecycle states, and preventive schedule data from earlier epics; dashboards are read surfaces, no new write paths.
- 14.2 (MTBF/MTTR) depends on breakdown category identification and the OQ-4 external-priority-to-local-category mapping resolved in sync work; analytics invalidation hooks into sync mutations.
- 14.3 depends on the OQ-5 capture-mechanism decision and AD-10 object storage for evidence references; signature feeds workorder DONE/CLOSED approval and close rules from the workorder epic.
- 14.4 reuses Epic 5 outbox, worker, rate-limit, circuit-breaker, escalation machinery, extended polymorphically; the 4-hour clock depends on AD-5 derived ON_PROCUREMENT and `_status_history` rows from the workorder state machine; auto-login touches auth (token issuance/revocation) and must interoperate with OPA scoping of the landing task list.
