# Epic 22 Context: Integration, Webhook, Audit & Signature

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Complete the integration and evidence layer so external systems receive observable outbound webhooks, auth activity and phone-verification flows are traceable, approvals carry a reusable tracked signature, and every WhatsApp send has a complete diagnosable message log.

## Stories

- Story 22.1: Webhook Config & Delivery Log
- Story 22.2: Auth Login Audit & Phone Verification
- Story 22.3: User Signatures & Signature Use
- Story 22.4: WhatsApp Message Log

## Requirements & Constraints

- Outbound webhooks fire only for matching subscribed event types; each delivery attempt is logged with status, response, latency, attempt count, and traceId, with retry/backoff and a dead-letter state; secrets are never logged and delivery failures are visible to SUPER_ADMIN.
- Every login attempt is audited with actor, timestamp, source, result, and traceId without storing credentials; phone-verification challenges track expiry, attempt limits, and consumption for phone-based flows such as the 4-hour ack auto-login.
- One reusable signature per user; every application of a signature (workorder close, preventive approval, reports) writes a per-use row with target, actor, and timestamp that also renders in print output.
- Every WAHA send writes a message log with recipient, template, status, timestamps, and traceId that complements (not replaces) the existing notification job/attempt records; dedupe by target plus template plus recipient; mask full phone numbers and WAHA secrets.
- Mutations are OPA-authorized server-side and written to the immutable audit log with actor, action, target, result, and traceId; all endpoints live under `/api/v1` with the standard error shape, UTC ISO-8601 timestamps, and uppercase-string enums.
- Frontend renders backend decisions only and never calls databases or OPA directly.

## Technical Decisions

- New `integration` bounded context holds `webhook_configs` (name, direction INBOUND/OUTBOUND, event types, endpoint, HMAC secret, active flag) and `webhook_delivery_logs` (status PENDING/DELIVERED/FAILED/RETRYING/DLQ, response code/body, latency, attempt count, next-retry); dispatch uses the Resilience4j retry/timeout/circuit-breaker pattern and never inline with the request path.
- Auth evidence lives in the `auth` package: `auth_login_audits` (identifier, IP, user agent, success flag, failure reason) and `phone_verification_challenges` (OTP hash, expiry, attempt count, max 5 attempts, resend window, consumed-at); reads are SUPER_ADMIN/AUDITOR-only with filters and never expose secrets.
- `user_signatures` (one row per user: bucket, object key, content type, failed-attempt throttle) plus `signature_uses` (module, subject type/id, action, reason, signature reference and hash, signed-artifact reference, IP, user agent), indexed on module plus subject; replaces `workorder_signatures`, with workorder signatures becoming uses with subject type WORK_ORDER.
- `whatsapp_message_logs` lives in the `notification` package and records the WAHA message id, session, chat, template reference, status, timestamps, and traceId; notification sending keeps the outbox pattern with idempotency keys, decoupled from ingest.
- OPA remains the enforcement point with default-deny; decision logs are masked with 30-day configurable retention.

## UX & Interaction Patterns

- SUPER_ADMIN-only evidence views for webhook deliveries, login audits, and WhatsApp logs: dense tables with status, reason, timestamp, and filters; failures link to the related record rather than showing secrets.
- Signature capture and approval flows explain what signing evidences before confirming; WYSIWYG print reports include the signature block, logo, and tabular layout.
- All views support loading, empty, error, stale, read-only, and forbidden states with non-color-only status communication.

## Cross-Story Dependencies

- Story 22.4 extends Epic 14 notification evidence (notification jobs/attempts) and covers the lifecycle-event and 4-hour ack sends; Story 22.2 supports the 4-hour ack auto-login link flow.
- Story 22.3 is consumed by workorder close, preventive checklist approval, and WYSIWYG print reports in Epics 10, 11, and 14.
- All stories build on the Epic 9 OPA foundation, the Phase 1 immutable audit log, and the fresh V1 baseline schema from the ORM redesign; implementation follows phasing with KPI/IATF first only if sequencing requires it.
