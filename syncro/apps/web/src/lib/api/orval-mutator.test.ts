import { describe, expect, it, vi } from "vitest";

import { syncroFetch } from "./orval-mutator";

vi.mock("@/lib/auth/auth-client", () => ({
  getAuthToken: vi.fn(() => null),
  expireAuthSession: vi.fn(),
}));

describe("syncroFetch FormData guard", () => {
  it("does not set Content-Type when body is FormData", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({}), { status: 200, headers: { "Content-Type": "application/json" } }),
        ),
      ),
    );

    const formData = new FormData();
    formData.append("data", new Blob(["test"]));
    await syncroFetch("/upload", { method: "POST", body: formData });

    const fetchCall = vi.mocked(fetch).mock.calls[0];
    const headers = fetchCall[1]?.headers as Headers;
    expect(headers.has("Content-Type")).toBe(false);
  });

  it("sets Content-Type application/json when body is not FormData", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({}), { status: 200, headers: { "Content-Type": "application/json" } }),
        ),
      ),
    );

    await syncroFetch("/data", { method: "POST", body: JSON.stringify({ key: "value" }) });

    const fetchCall = vi.mocked(fetch).mock.calls[0];
    const headers = fetchCall[1]?.headers as Headers;
    expect(headers.get("Content-Type")).toBe("application/json");
  });
});
