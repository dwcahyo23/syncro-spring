---
status: done
---

# TEA ATDD: Story 3.1 - Configure MQTT Subscription and Telemetry Contract

## Outcome
Completed the TEA ATDD workflow (`bmad-testarch-atdd`) for the Story 3.1 working-tree change (commit `ec973873f8ae0cb7aa020f2dd30eb61e8be8ef1e`). Generated RED-phase acceptance test scaffolds plus an implementation checklist covering the changes currently in the working tree.

## Deliverables
- **Red-phase scaffolds (RED, all skipped):**
  - Backend unit: `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestAtddScaffoldTest.java` (3 `@Disabled` - R-009 handler edge payloads + defensive catch).
  - Backend config-slice: `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttSubscriptionResilienceAtddScaffoldTest.java` (3 `@Disabled` - R-001 broker-down context load + health DOWN, R-004 credentials carry, R-010 health bean).
  - API: `syncro/apps/web/tests/api/mqtt-telemetry-contract.atdd-red.spec.ts` (4 `test.skip` - `/actuator/health#mqtt` contract: exposure, DOWN when disconnected, lastError detail, UP when subscribed).
- **Implementation checklist:** `_bmad-output/test-artifacts/atdd-checklist-3-1-configure-mqtt-subscription-and-telemetry-contract.md` (AC mapping, task-by-task red-green activation, running commands, risk de-risking for R-001/R-004/R-009/R-010).

## Verification
- Backend scaffolds compile clean (`mvn -o test-compile`).
- Web spec passes TypeScript typecheck (`tsc --noEmit` no errors in the new file).

No production code modified.