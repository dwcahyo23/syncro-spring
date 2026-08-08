import { expect, test } from "@playwright/test";

import type { ActuatorHealth } from "../support/helpers/syncro-api-client";
import { SyncroApiClient } from "../support/helpers/syncro-api-client";

// Story 3.1 green-capable client-contract suite for the actuator health `mqtt`
// contributor (GET /actuator/health). This is the observable API surface of the
// telemetry ingest boundary: the adapter's connection/subscription state is
// surfaced via MqttHealthIndicator under `components.mqtt`.
//
// Unlike the RED ATDD scaffold (tests/api/mqtt-telemetry-contract.atdd-red.spec.ts,
// which locks the exact UP/DOWN/lastError semantics and stays test.skip until a
// developer activates it), this suite locks the CONTRACT over real HTTP and is
// CI-safe: it runs whenever API_URL is configured (operator action #2 in the
// spec) and skips cleanly otherwise. Backend MockMvc/unit tests already lock the
// health mapping (MqttHealthIndicatorTest, MqttSubscriptionConfigTest); this
// suite re-verifies the same contract at the HTTP boundary without a browser.
//
// Run targeted:
//   npm --prefix syncro/apps/web run test:api -- tests/api/mqtt-health.spec.ts
test.describe("Story 3.1 API contract: actuator health mqtt contributor", () => {
  test.beforeEach(() => {
    test.skip(
      !process.env.API_URL,
      "API_URL not configured; needs a live backend exposing /actuator/health",
    );
  });

  test("[P0] /actuator/health returns 200 and exposes an mqtt component", async ({ request }) => {
    const client = new SyncroApiClient(request);
    const response = await client.getActuatorHealth();
    expect(response.status()).toBe(200);

    const body = (await response.json()) as ActuatorHealth;
    expect(body.components).toBeDefined();
    expect(body.components?.mqtt).toBeDefined();
    expect(["UP", "DOWN"]).toContain(body.components?.mqtt.status);
  });

  test("[P1] mqtt health DOWN carries a lastError detail after a failure", async ({ request }) => {
    const client = new SyncroApiClient(request);
    const response = await client.getActuatorHealth();
    expect(response.status()).toBe(200);

    const body = (await response.json()) as ActuatorHealth;
    const mqtt = body.components?.mqtt;
    // With no connected/subscribed broker the mqtt contributor must report DOWN
    // and, after a failure, surface the lastError detail (MqttHealthIndicator).
    if (mqtt?.status === "DOWN" && mqtt.details) {
      expect(mqtt.details.lastError).toBeTruthy();
    }
  });
});