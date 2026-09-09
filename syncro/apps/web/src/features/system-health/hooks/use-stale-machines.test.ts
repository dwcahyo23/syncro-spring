import { afterEach, describe, expect, it, vi } from "vitest";

import { expireAuthSession } from "@/lib/auth/auth-client";

import { fetchStaleMachines } from "./use-stale-machines";

vi.mock("@/lib/auth/auth-client", () => ({
  expireAuthSession: vi.fn(),
  getAuthToken: () => undefined,
}));

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const fullPayload = {
  timestamp: "2026-08-21T08:00:00Z",
  staleMachineCount: 2,
  items: [
    {
      machineId: "00000000-0000-0000-0000-000000000001",
      machineCode: "AA-01",
      plantCode: "GM1",
      freshnessState: "OFFLINE",
      statusLabel: "offline",
      lastReceivedAt: null,
    },
    {
      machineId: "00000000-0000-0000-0000-000000000002",
      machineCode: "ZZ-01",
      plantCode: "GM1",
      freshnessState: "STALE",
      statusLabel: "stale",
      lastReceivedAt: "2026-08-21T07:40:00Z",
    },
  ],
};

describe("stale machines fetcher", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("resolves the payload on 200", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, fullPayload)));
    await expect(fetchStaleMachines()).resolves.toMatchObject(fullPayload);
  });

  it("rejects on 500", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(500, {})));
    await expect(fetchStaleMachines()).rejects.toThrow("failed: 500");
  });

  it("rejects on 403 with no session expiry", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(403, {})));
    await expect(fetchStaleMachines()).rejects.toThrow("failed: 403");
    expect(expireAuthSession).not.toHaveBeenCalled();
  });

  it("rejects on 200 with a non-JSON body", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>gateway error</html>", { status: 200 })));
    await expect(fetchStaleMachines()).rejects.toThrow("not valid JSON");
  });

  it("rejects on 200 with a body missing the items array", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, { timestamp: "2026-08-21T08:00:00Z" })));
    await expect(fetchStaleMachines()).rejects.toThrow("not a stale-machine payload");
  });

  it("rejects on 200 with a malformed item shape", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(200, {
          timestamp: "2026-08-21T08:00:00Z",
          staleMachineCount: 1,
          items: [{ machineCode: "AA-01", plantCode: "GM1" }],
        }),
      ),
    );
    await expect(fetchStaleMachines()).rejects.toThrow("not a stale-machine payload");
  });

  it("rejects on 200 with an empty machineCode item", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(200, {
          timestamp: "2026-08-21T08:00:00Z",
          staleMachineCount: 1,
          items: [
            {
              machineId: "00000000-0000-0000-0000-000000000001",
              machineCode: "",
              plantCode: "GM1",
              freshnessState: "OFFLINE",
              statusLabel: "offline",
              lastReceivedAt: null,
            },
          ],
        }),
      ),
    );
    await expect(fetchStaleMachines()).rejects.toThrow("not a stale-machine payload");
  });

  it("rejects on 200 when the count contradicts the items length", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(200, {
          timestamp: "2026-08-21T08:00:00Z",
          staleMachineCount: 5,
          items: [fullPayload.items[0]],
        }),
      ),
    );
    await expect(fetchStaleMachines()).rejects.toThrow("not a stale-machine payload");
  });

  it("expires the session on 401", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(401, {})));
    await expect(fetchStaleMachines()).rejects.toThrow("401");
    expect(expireAuthSession).toHaveBeenCalled();
  });
});
