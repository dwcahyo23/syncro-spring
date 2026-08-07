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
status: open

### DW-4: Missing audit trails for machine responsibility assignments/removals

origin: migrated from legacy ledger ("Deferred from: code review of 2-7-assign-machine-responsibility-levels (2026-06-03)"), 2026-08-07
location: n/a
reason: No audit logging for assignments/removals — deferred, pre-existing (Epic 2 Task 2-9).
status: open

### DW-5: Redundant DB indexes wasting write performance

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: n/a
reason: Redundant DB indexes wasting write perf — deferred, pre-existing.
status: open

### DW-6: Expensive unindexed joins with order by

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: n/a
reason: Expensive unindexed joins with order by — deferred, pre-existing.
status: open

### DW-7: Brittle Next.js server command in Playwright

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: syncro/apps/web (Playwright server command)
reason: Brittle Next.js server command in Playwright — deferred, pre-existing.
status: open

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
