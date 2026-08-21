import { afterEach, describe, expect, it, vi } from "vitest";

import { expireAuthSession } from "@/lib/auth/auth-client";

import { fetchTelemetryFreshness } from "./use-telemetry-freshness";

vi.mock("@/lib/auth/auth-client", () => ({
  expireAuthSession: vi.fn(),
  getAuthToken: () => undefined,
}));

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("telemetry freshness fetcher", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("resolves the payload on 200", async () => {
    const payload = {
      status: "LIVE",
      statusLabel: "Live",
      statusSeverity: "SUCCESS",
      statusReason: null,
      timestamp: "2026-08-21T08:00:00Z",
      lastAcceptedAt: "2026-08-21T07:59:00Z",
      staleSince: null,
    };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, payload)));
    await expect(fetchTelemetryFreshness()).resolves.toMatchObject(payload);
  });

  it("rejects on 500", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(500, {})));
    await expect(fetchTelemetryFreshness()).rejects.toThrow("failed: 500");
  });

  it("rejects on 403 with no session expiry", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(403, {})));
    await expect(fetchTelemetryFreshness()).rejects.toThrow("failed: 403");
    expect(expireAuthSession).not.toHaveBeenCalled();
  });

  it("rejects on 200 with a non-JSON body", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("<html>gateway error</html>", { status: 200 })),
    );
    await expect(fetchTelemetryFreshness()).rejects.toThrow("not valid JSON");
  });

  it("rejects on 200 with a body missing the status field", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, { statusLabel: "Live" })));
    await expect(fetchTelemetryFreshness()).rejects.toThrow("not a freshness payload");
  });

  it("rejects on 200 with a status outside the freshness union", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(200, {
          status: "BANANA",
          statusLabel: "Banana",
          statusSeverity: "SUCCESS",
          timestamp: "2026-08-21T08:00:00Z",
        }),
      ),
    );
    await expect(fetchTelemetryFreshness()).rejects.toThrow("not a freshness payload");
  });

  it("expires the session on 401", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(401, {})));
    await expect(fetchTelemetryFreshness()).rejects.toThrow("401");
    expect(expireAuthSession).toHaveBeenCalled();
  });
});
