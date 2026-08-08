# Deferred Work

### DW-1: MQTT credentials configured but local EMQX MQTT auth not enforced

origin: migrated from legacy ledger ("Deferred from: code review of 1-3-initialize-spring-boot-backend-skeleton (2026-05-25)"), 2026-08-07
location: syncro backend MQTT/EMQX broker configuration
reason: Local broker MQTT authentication belongs to later EMQX security/auth configuration scope, not Story 1.3 backend skeleton.
status: open

### DW-2: Add durable UI state evidence for AC11

origin: migrated from legacy ledger ("Deferred from: code review of 2-2-manage-plant-scoped-machine-groups (2026-05-27)"), 2026-08-07
location: syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx:176
reason: Current code/browser evidence is acceptable for this review, but component tests or captured trace for empty/loading/error/read-only/forbidden/validation states would make regression proof stronger.
status: open

### DW-3: Hardcoded local ports in web test config

origin: migrated from legacy ledger ("Deferred from: code review of 2-5-manage-spareparts (2026-05-28)"), 2026-08-07
location: syncro/apps/web/playwright.config.ts:2
reason: Playwright baseline and local test env defaults predate this story; not caused by Story 2.5 functional change.
status: done 2026-08-08
resolution: resolved by sweep bundle dw-web-e2e-config-hardening
resolution-undo: cc5d75d761fd6374ffb8e047e9ec1dfeb40b9c2acb6e41087d6dcce158aaad73 2026-08-08 7374617475733a206f70656e

### DW-4: Missing audit trails for machine responsibility assignments/removals

origin: migrated from legacy ledger ("Deferred from: code review of 2-7-assign-machine-responsibility-levels (2026-06-03)"), 2026-08-07
location: n/a
reason: No audit logging for assignments/removals — deferred, pre-existing (Epic 2 Task 2-9).
status: done 2026-08-08
resolution: already resolved: MachineResponsibilityService.java:85,103,120 now records CREATE/UPDATE/DELETE audit entries for RESPONSIBILITY via AuditLogWriter (AuditEntityType.RESPONSIBILITY), landed by story 2-9 (commit c386dba, merged ad00966).

### DW-5: Redundant DB indexes wasting write performance

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: n/a
reason: Redundant DB indexes wasting write perf — deferred, pre-existing.
status: done 2026-08-08
resolution: resolved by sweep bundle dw-db-index-hygiene
resolution-undo: 116e09ed8ca373373f2953aff0446ca01aca198accd121c508cb4c4e3d05415a 2026-08-08 7374617475733a206f70656e

### DW-6: Expensive unindexed joins with order by

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: n/a
reason: Expensive unindexed joins with order by — deferred, pre-existing.
status: done 2026-08-08
resolution: resolved by sweep bundle dw-db-index-hygiene
resolution-undo: 116e09ed8ca373373f2953aff0446ca01aca198accd121c508cb4c4e3d05415a 2026-08-08 7374617475733a206f70656e

### DW-7: Brittle Next.js server command in Playwright

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: syncro/apps/web (Playwright server command)
reason: Brittle Next.js server command in Playwright — deferred, pre-existing.
status: done 2026-08-08
resolution: resolved by sweep bundle dw-web-e2e-config-hardening
resolution-undo: cc5d75d761fd6374ffb8e047e9ec1dfeb40b9c2acb6e41087d6dcce158aaad73 2026-08-08 7374617475733a206f70656e

### DW-8: Worthless skipped test suites

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: n/a
reason: Worthless skipped test suites — deferred, pre-existing.
status: open

### DW-9: Missing optimistic locking on Installation entity

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: Installation entity (backend)
reason: Missing optimistic locking on Installation entity — deferred, pre-existing.
status: open

### DW-10: tests/api suites not collected by the default Playwright testDir

origin: flagged in spec-web-e2e-config-hardening review log (WH-01), 2026-08-08
location: syncro/apps/web/playwright.config.ts (`testDir: ./tests/e2e`)
reason: The API suites (audit-log contract, spareparts, installations — the primary `apiBaseUrl()` consumers of the config-hardening change) live under `tests/api/`, outside the default testDir, so `npm run test:e2e` and `playwright test --list` silently omit them. CI can be green with zero API-suite execution and no signal.
status: done 2026-08-08
resolution: resolved by bundle dw-web-e2e-config-hardening automate run — added `playwright.api.config.ts` (testDir `./tests/api`), `npm run test:api` (`playwright test --config=playwright.api.config.ts`), and `npm run test:api:check` (tests/support/check-api-collection.mjs) which exits non-zero if no tests/api spec is collected. Run `test:api:check` after `test:api` in CI.

### DW-11: Portless BASE_URL hangs web server readiness

origin: flagged in spec-web-e2e-config-hardening review log (WH-03), 2026-08-08
location: syncro/apps/web/playwright.config.ts (webServer derivation)
reason: With a portless `BASE_URL` (e.g. a deployed origin like `https://app.example.com`), the derived default web server command degrades to plain `npm run dev` (Next default port 3000) while `webServer.url` is the portless origin — the server never reaches readiness and the run hangs until the 120s timeout. Pre-existing mismatch; the config-hardening derivation keeps it.
status: open
mitigation: documented in syncro/apps/web/tests/README.md — pair a portless `BASE_URL` with `PLAYWRIGHT_SKIP_WEB_SERVER=1` so Playwright targets the already-running origin without booting a local server.

### DW-12: Follow-up review still recommended for dw-web-e2e-config-hardening after the damping cap was spent
origin: review-budget-followup
location: n/a
source_spec: `spec-web-e2e-config-hardening.md`
severity: low
reason: The follow-up-review damping cap (limits.max_followup_reviews = 1) was spent with the story finalized (status: done, verify green) while the review pass still recommended an independent follow-up. The work was committed by bmad-loop run 20260808-012337-779e; this entry preserves the lingering recommendation for a deliberate later review.
status: open

### DW-13: Validate MQTT connection properties in typed config
- source_spec: `_bmad-output/implementation-artifacts/spec-3-1-configure-mqtt-subscription-and-telemetry-contract.md`
  summary: Add null/blank validation to `MqttProperties` (host, port, clientId, topicFilter) so a missing env value fails fast instead of producing `tcp://null:1883` or a runtime adapter NPE.
  evidence: Real, surfaced by review of Story 3.1 — `MqttSubscriptionConfig.mqttConnectOptions` builds `tcp://" + host + ":" + port` with no guard, and `MqttProperties` has no validation constraints. Shared config hardening touching a class used by other stories; defer beyond Story 3.1 scope.

### DW-14: Mid-session MQTT connectivity loss invisible to health indicator
- source_spec: `_bmad-output/implementation-artifacts/spec-3-1-configure-mqtt-subscription-and-telemetry-contract.md`
  summary: `MqttConnectionStatus` stays `SUBSCRIBED`/UP throughout a broker outage that begins after the initial subscribe, because the Paho reconnect path handles the drop in its background thread and does not publish an `MqttConnectionFailedEvent` the way `doStart()`/`subscribe()` catch paths do.
  evidence: Real, surfaced by review of Story 3.1 — with `setAutomaticReconnect(true)`, a mid-session drop does not emit the adapter's connection-failed event, so the sole observability signal (health) reports UP for the entire offline window. The Spring Integration adapter's event set exposes no connection-lost event observable by this listener; needs a later adapter-level or event-source investigation, out of Story 3.1 scope.
