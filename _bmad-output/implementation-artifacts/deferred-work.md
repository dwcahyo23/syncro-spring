## Deferred from: code review of 1-3-initialize-spring-boot-backend-skeleton (2026-05-25)

- MQTT credentials are configured in backend but local EMQX MQTT auth is not enforced. Deferred because local broker MQTT authentication belongs to later EMQX security/auth configuration scope, not Story 1.3 backend skeleton.

## Deferred from: code review of 2-2-manage-plant-scoped-machine-groups (2026-05-27)

- Add durable UI state evidence for AC11 (`syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx:176`). Current code/browser evidence is acceptable for this review, but component tests or captured trace for empty/loading/error/read-only/forbidden/validation states would make regression proof stronger.
