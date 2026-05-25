## Deferred from: code review of 1-3-initialize-spring-boot-backend-skeleton (2026-05-25)

- MQTT credentials are configured in backend but local EMQX MQTT auth is not enforced. Deferred because local broker MQTT authentication belongs to later EMQX security/auth configuration scope, not Story 1.3 backend skeleton.
