import { afterEach, describe, expect, it, vi } from "vitest";

import { expireAuthSession } from "@/lib/auth/auth-client";

import { fetchIngestWorkerStatus } from "./use-ingest-worker-status";
import { fetchNotificationWorkerStatus } from "./use-notification-worker-status";

vi.mock("@/lib/auth/auth-client", () => ({
  expireAuthSession: vi.fn(),
  getAuthToken: () => undefined,
}));

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("worker status fetchers", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("ingest resolves the payload on 200", async () => {
    const payload = { status: "RUNNING" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, payload)));
    await expect(fetchIngestWorkerStatus()).resolves.toMatchObject(payload);
  });

  it("ingest rejects on 500", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(500, {})));
    await expect(fetchIngestWorkerStatus()).rejects.toThrow("failed: 500");
  });

  it("ingest expires the session on 401", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(401, {})));
    await expect(fetchIngestWorkerStatus()).rejects.toThrow("401");
    expect(expireAuthSession).toHaveBeenCalled();
  });

  it("notification resolves the payload on 200", async () => {
    const payload = { status: "DEGRADED" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, payload)));
    await expect(fetchNotificationWorkerStatus()).resolves.toMatchObject(payload);
  });

  it("notification rejects on 500", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(500, {})));
    await expect(fetchNotificationWorkerStatus()).rejects.toThrow("failed: 500");
  });

  it("notification expires the session on 401", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(401, {})));
    await expect(fetchNotificationWorkerStatus()).rejects.toThrow("401");
    expect(expireAuthSession).toHaveBeenCalled();
  });
});
