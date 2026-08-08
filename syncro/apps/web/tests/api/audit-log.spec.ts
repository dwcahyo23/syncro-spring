import type { APIResponse } from "@playwright/test";
import { expect, test } from "@playwright/test";

import type { AuditLogListResponse } from "../../src/lib/api/generated/model";
import { apiBaseUrl } from "../support/config";

// Resolved lazily so this module still loads when API_URL is unset and tests skip.
function auditLogUrl() {
  return `${apiBaseUrl()}/audit-log`;
}

const SUPER_ADMIN_TOKEN = process.env.SYNCRO_ATDD_SUPER_ADMIN_TOKEN;
const MANAGE_TOKEN = process.env.SYNCRO_ATDD_MANAGE_TOKEN;
const VIEWER_TOKEN = process.env.SYNCRO_ATDD_VIEWER_TOKEN;

const hasBackendTokens = Boolean(SUPER_ADMIN_TOKEN && MANAGE_TOKEN && VIEWER_TOKEN);

function authHeaders(role: "SUPER_ADMIN" | "MANAGE" | "VIEWER") {
  const token = { SUPER_ADMIN: SUPER_ADMIN_TOKEN, MANAGE: MANAGE_TOKEN, VIEWER: VIEWER_TOKEN }[role];
  return { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
}

// Green-capable client-contract suite for GET /api/v1/audit-log (Story 2.9).
// Runs against a live backend when the three SYNCRO_ATDD_*_TOKEN env vars are
// configured (operator action #2 in the spec); skips cleanly otherwise so the
// suite is CI-safe. Backend MockMvc tests (AuditLogControllerTest) already lock
// the controller slice; this suite re-verifies the same contract over real HTTP.
test.describe("Story 2.9 API client contract: audit log", () => {
  test.beforeEach(() => {
    test.skip(!hasBackendTokens, "SYNCRO_ATDD_{SUPER_ADMIN,MANAGE,VIEWER}_TOKEN not configured; needs a live backend");
  });

  test("[P0] unauthenticated request returns 401", async ({ request }) => {
    const response = await request.get(auditLogUrl());
    expect(response.status()).toBe(401);
    await expectErrorCode(response, "AUTHENTICATION_REQUIRED");
  });

  test("[P0] SUPER_ADMIN lists entries with the full response shape", async ({ request }) => {
    const response = await request.get(auditLogUrl(), { headers: authHeaders("SUPER_ADMIN") });
    expect(response.status()).toBe(200);
    const body = (await response.json()) as AuditLogListResponse;
    expect(Array.isArray(body.items)).toBe(true);
    expect(typeof body.totalElements).toBe("number");
    expect(body.page).toBe(0);
    expect(body.size).toBeGreaterThan(0);
    expect(body.sort).toBe("createdAt,desc");
    for (const entry of body.items ?? []) {
      expect(entry.actorName).toBeTruthy();
      expect(["CREATE", "UPDATE", "DELETE"]).toContain(entry.action);
      expect(entry.entityType).toBeTruthy();
      expect(entry.createdAt).toBeTruthy();
    }
  });

  test("[P1] filter parameters bind without error", async ({ request }) => {
    const response = await request.get(
      `${auditLogUrl()}?entityType=MACHINE&actor=admin&page=0&size=25&sort=createdAt,desc`,
      { headers: authHeaders("SUPER_ADMIN") },
    );
    expect(response.status()).toBe(200);
    const body = (await response.json()) as AuditLogListResponse;
    expect(body.page).toBe(0);
    expect(body.size).toBe(25);
    expect(body.sort).toBe("createdAt,desc");
  });

  test("[P1] MANAGE role can read global master data audit entries", async ({ request }) => {
    const response = await request.get(auditLogUrl(), { headers: authHeaders("MANAGE") });
    expect(response.status()).toBe(200);
  });

  test("[P1] VIEWER role can read the audit log", async ({ request }) => {
    const response = await request.get(auditLogUrl(), { headers: authHeaders("VIEWER") });
    expect(response.status()).toBe(200);
  });

  test("[P1] unknown entityType returns 400 INVALID_QUERY_VALUE", async ({ request }) => {
    const response = await request.get(`${auditLogUrl()}?entityType=FOO`, { headers: authHeaders("SUPER_ADMIN") });
    expect(response.status()).toBe(400);
    await expectErrorCode(response, "INVALID_QUERY_VALUE");
  });

  test("[P1] invalid sort property returns 400 INVALID_QUERY_VALUE", async ({ request }) => {
    const response = await request.get(`${auditLogUrl()}?sort=plantId,asc`, { headers: authHeaders("SUPER_ADMIN") });
    expect(response.status()).toBe(400);
    await expectErrorCode(response, "INVALID_QUERY_VALUE");
  });

  test("[P1] malformed from date returns 400 INVALID_QUERY_VALUE", async ({ request }) => {
    const response = await request.get(`${auditLogUrl()}?from=not-a-date`, { headers: authHeaders("SUPER_ADMIN") });
    expect(response.status()).toBe(400);
    await expectErrorCode(response, "INVALID_QUERY_VALUE");
  });

  test("[P1] malformed to date returns 400 INVALID_QUERY_VALUE", async ({ request }) => {
    const response = await request.get(`${auditLogUrl()}?to=not-a-date`, { headers: authHeaders("SUPER_ADMIN") });
    expect(response.status()).toBe(400);
    await expectErrorCode(response, "INVALID_QUERY_VALUE");
  });

  test("[P1] no write endpoints exist for the audit log", async ({ request }) => {
    await expectStatus(request.put(auditLogUrl(), { headers: authHeaders("SUPER_ADMIN"), data: {} }), 405);
    await expectStatus(request.delete(auditLogUrl(), { headers: authHeaders("SUPER_ADMIN") }), 405);
  });
});

async function expectStatus(responsePromise: Promise<APIResponse>, status: number) {
  const response = await responsePromise;
  expect(response.status()).toBe(status);
}

async function expectErrorCode(response: APIResponse, code: string) {
  expect(await response.json()).toMatchObject({ code });
}
