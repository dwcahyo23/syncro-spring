import { afterEach, describe, expect, it, vi } from "vitest";

import { expireAuthSession } from "@/lib/auth/auth-client";

import { fetchDataQuality } from "./use-data-quality";

vi.mock("@/lib/auth/auth-client", () => ({
  expireAuthSession: vi.fn(),
  getAuthToken: () => undefined,
}));

function healthyPayload(): Record<string, unknown> {
  return {
    status: "GOOD",
    statusLabel: "Good",
    statusSeverity: "SUCCESS",
    statusReason: null,
    timestamp: "2026-08-22T10:00:00Z",
    windowSeconds: 3600,
    quarantinedCount: 2,
    rejectionRatePct: 0.2,
    anomalyCount: 0,
    deadLetterCount: 0,
    receivedCount: 1002,
    quarantinedSeverity: "SUCCESS",
    rejectionRateSeverity: "SUCCESS",
    anomalySeverity: "SUCCESS",
    deadLetterSeverity: "SUCCESS",
    lastLatencyMs: 800,
    latencyState: "NORMAL",
    latencySeverity: "SUCCESS",
  };
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("telemetry data-quality fetcher", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("resolves the payload on 200", async () => {
    const payload = healthyPayload();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, payload)));
    await expect(fetchDataQuality()).resolves.toMatchObject(payload);
  });

  it("resolves the payload with a null latency sample (NO_DATA)", async () => {
    const payload = { ...healthyPayload(), lastLatencyMs: null, latencyState: "NO_DATA", latencySeverity: "NEUTRAL" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, payload)));
    await expect(fetchDataQuality()).resolves.toMatchObject(payload);
  });

  it("rejects on 500", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(500, {})));
    await expect(fetchDataQuality()).rejects.toThrow("failed: 500");
  });

  it("expires the session on 401", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(401, {})));
    await expect(fetchDataQuality()).rejects.toThrow("401");
    expect(expireAuthSession).toHaveBeenCalled();
  });

  it("rejects on 403 with no session expiry", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(403, {})));
    await expect(fetchDataQuality()).rejects.toThrow("failed: 403");
    expect(expireAuthSession).not.toHaveBeenCalled();
  });

  it("rejects on 200 with a non-JSON body", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>gateway error</html>", { status: 200 })));
    await expect(fetchDataQuality()).rejects.toThrow("not valid JSON");
  });

  it("rejects on 200 with a body missing count fields", async () => {
    const { quarantinedCount: _omitted, ...partial } = healthyPayload();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, partial)));
    await expect(fetchDataQuality()).rejects.toThrow("not a data-quality payload");
  });

  it("rejects on 200 with a status outside the data-quality union", async () => {
    const payload = { ...healthyPayload(), status: "BANANA" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, payload)));
    await expect(fetchDataQuality()).rejects.toThrow("not a data-quality payload");
  });

  it("rejects on 200 with a non-numeric rejection rate", async () => {
    const payload = { ...healthyPayload(), rejectionRatePct: "0.2%" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, payload)));
    await expect(fetchDataQuality()).rejects.toThrow("not a data-quality payload");
  });

  it("rejects on 200 with a latency state outside the union", async () => {
    const payload = { ...healthyPayload(), latencyState: "SLOW" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, payload)));
    await expect(fetchDataQuality()).rejects.toThrow("not a data-quality payload");
  });

  it("rejects on 200 with a non-null non-number latency value", async () => {
    const payload = { ...healthyPayload(), lastLatencyMs: "800ms" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, payload)));
    await expect(fetchDataQuality()).rejects.toThrow("not a data-quality payload");
  });

  it("rejects on 200 with a negative or fractional latency value", async () => {
    const negative = { ...healthyPayload(), lastLatencyMs: -300 };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, negative)));
    await expect(fetchDataQuality()).rejects.toThrow("not a data-quality payload");

    const fractional = { ...healthyPayload(), lastLatencyMs: 800.5 };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, fractional)));
    await expect(fetchDataQuality()).rejects.toThrow("not a data-quality payload");
  });

  it("rejects on 200 with a severity outside the backend severity contract", async () => {
    const payload = { ...healthyPayload(), quarantinedSeverity: "constructor" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, payload)));
    await expect(fetchDataQuality()).rejects.toThrow("not a data-quality payload");
  });
});
