import { afterEach, describe, expect, it, vi } from "vitest";

import { expireAuthSession } from "@/lib/auth/auth-client";

import { fetchActuatorHealth } from "./use-actuator-health-query";

vi.mock("@/lib/auth/auth-client", () => ({
  expireAuthSession: vi.fn(),
  getAuthToken: () => undefined,
}));

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("fetchActuatorHealth", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("resolves the JSON body on 200", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, { status: "UP", components: {} })));
    await expect(fetchActuatorHealth()).resolves.toMatchObject({ status: "UP" });
  });

  it("resolves the JSON body on 503 when aggregate status is DOWN", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(503, { status: "DOWN", components: { db: { status: "DOWN" } } })),
    );
    await expect(fetchActuatorHealth()).resolves.toMatchObject({ status: "DOWN" });
  });

  it("rejects on 500", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(500, {})));
    await expect(fetchActuatorHealth()).rejects.toThrow("Actuator health check failed: 500");
  });

  it("rejects with a meaningful error when the 503 body is not valid JSON", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>oops</html>", { status: 503 })));
    await expect(fetchActuatorHealth()).rejects.toThrow("not valid JSON");
  });

  it("rejects when the 503 body is JSON but not a health payload", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(503, { detail: "gateway error" })));
    await expect(fetchActuatorHealth()).rejects.toThrow("not a health payload");
  });

  it("expires the session on 401", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(401, {})));
    await expect(fetchActuatorHealth()).rejects.toThrow("401");
    expect(expireAuthSession).toHaveBeenCalled();
  });
});
