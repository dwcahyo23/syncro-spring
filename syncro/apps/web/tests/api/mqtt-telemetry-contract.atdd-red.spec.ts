import { expect, test } from "@playwright/test";

import { apiBaseUrl } from "../support/config";

// Story 3.1 ATDD RED-phase API scaffolds (R-001 / R-010).
//
// The observable API surface of Story 3.1 is the Spring Boot actuator health
// endpoint: a new `mqtt` component under GET /actuator/health that reports UP
// once the adapter subscribes (MqttConnectionStatus.SUBSCRIBED) and DOWN
// otherwise (UNKNOWN/FAILED), with a `lastError` detail after a failure.
//
// These scaffolds lock that contract. They stay `test.skip` until a developer
// activates the current task. Removing `test.skip(` makes the test active; it
// needs a live backend. The spec is written for the EXPECTED behavior and FAILS
// if the contract regresses (e.g. the mqtt component is dropped, or health
// reports UP while the broker is disconnected).
//
// Requires a running backend that exposes /actuator/health (the spec's
// management.endpoints.web.exposure.include: health,info). `API_URL` must point
// at the backend (e.g. http://localhost:8080/api/v1); the actuator base is
// derived by stripping the /api/v1 base path.

// Derived lazily so this module still loads when API_URL is unset and tests skip.
function actuatorHealthUrl() {
  const api = apiBaseUrl().replace(/\/api\/v1\/?$/, "");
  return `${api}/actuator/health`;
}

test.describe("Story 3.1 API contract (RED): actuator health mqtt component", () => {
  test.skip("[P1] /actuator/health exposes an mqtt component", async ({ request }) => {
    const response = await request.get(actuatorHealthUrl());
    expect(response.status()).toBe(200);
    const body = (await response.json()) as {
      components?: Record<string, { status: string }>;
    };
    expect(body.components).toBeDefined();
    expect(body.components?.mqtt).toBeDefined();
    expect(["UP", "DOWN"]).toContain(body.components?.mqtt.status);
  });

  test.skip("[P1] mqtt health is DOWN when the broker is not connected", async ({ request }) => {
    // With no connected/subscribed broker the mqtt component must report DOWN.
    const response = await request.get(actuatorHealthUrl());
    expect(response.status()).toBe(200);
    const body = (await response.json()) as {
      components?: Record<string, { status: string }>;
    };
    expect(body.components?.mqtt.status).toBe("DOWN");
  });

  test.skip("[P2] mqtt health DOWN carries a lastError detail after a failure", async ({ request }) => {
    // After a connection failure the DOWN detail must surface the lastError.
    const response = await request.get(actuatorHealthUrl());
    expect(response.status()).toBe(200);
    const body = (await response.json()) as {
      components?: Record<string, { status: string; details?: Record<string, string> }>;
    };
    const mqtt = body.components?.mqtt;
    if (mqtt?.status === "DOWN" && mqtt.details) {
      expect(mqtt.details.lastError).toBeTruthy();
    }
  });

  test.skip("[P1] mqtt health is UP once the adapter is subscribed", async ({ request }) => {
    // Activation lock: when the broker is up and the adapter has subscribed,
    // the mqtt component must report UP. Red until the operator verifies a live
    // subscription (manual broker E2E).
    const response = await request.get(actuatorHealthUrl());
    expect(response.status()).toBe(200);
    const body = (await response.json()) as {
      components?: Record<string, { status: string }>;
    };
    expect(body.components?.mqtt.status).toBe("UP");
  });
});