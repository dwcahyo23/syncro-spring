import type { APIResponse } from "@playwright/test";
import { expect, test } from "@playwright/test";

import type {
  InstallationRequest,
  InstallationUpdateRequest,
  InstallationView,
} from "../../src/lib/api/generated/model";

import { apiBaseUrl } from "../support/config";

// Resolved lazily so this module still loads when API_URL is unset and tests skip.
function installationsUrl() {
  return `${apiBaseUrl()}/machine-sparepart-installations`;
}

function machinesUrl() {
  return `${apiBaseUrl()}/machines`;
}

function sparepartsUrl() {
  return `${apiBaseUrl()}/spareparts`;
}

type RoleName = "SUPER_ADMIN" | "MANAGE" | "VIEWER";

function authHeaders(role: RoleName) {
  return {
    Authorization: `Bearer ${process.env[`SYNCRO_ATDD_${role}_TOKEN`] ?? `${role.toLowerCase()}-token-red-phase`}`,
    "Content-Type": "application/json",
  };
}

function installationPayload(overrides: Partial<InstallationRequest> = {}): InstallationRequest {
  return {
    machineId: overrides.machineId ?? "11111111-1111-4111-8111-111111111126",
    sparepartId: overrides.sparepartId ?? "22222222-2222-4222-8222-222222222226",
    functionName: overrides.functionName ?? "Primary",
    expectedProductionCount: overrides.expectedProductionCount ?? 1_000_000,
    baselineCounter: overrides.baselineCounter ?? 42_000,
    ...(Object.hasOwn(overrides, "thresholdPercentage") ? { thresholdPercentage: overrides.thresholdPercentage } : {}),
  };
}

function installationUpdatePayload(overrides: Partial<InstallationUpdateRequest> = {}): InstallationUpdateRequest {
  return {
    functionName: overrides.functionName ?? "Primary",
    expectedProductionCount: overrides.expectedProductionCount ?? 1_500_000,
    baselineCounter: overrides.baselineCounter ?? 12_000,
    thresholdPercentage: overrides.thresholdPercentage ?? 80,
  };
}

test.describe("Story 2.6 ATDD API RED-PHASE scaffold: machine sparepart installations", () => {
  test.skip("2.6-ATDD-API-001 P0 SUPER_ADMIN creates installation and response includes persisted baseline lifetime fields", async ({
    request,
  }) => {
    const payload = installationPayload({ expectedProductionCount: 1_000_000, baselineCounter: 42_000 });

    const createResponse = await request.post(installationsUrl(), {
      headers: authHeaders("SUPER_ADMIN"),
      data: payload,
    });

    expect(createResponse.status()).toBe(201);
    const created: InstallationView = await createResponse.json();
    expect(created).toMatchObject({
      expectedProductionCount: payload.expectedProductionCount,
      baselineCounter: payload.baselineCounter,
      thresholdPercentage: 90,
      calculationBasis: "COUNTER_BASED",
      currentCount: null,
      consumedProductionCount: null,
      consumedPercentage: null,
    });
    expect(created.machineId).toBe(payload.machineId);
    expect(created.sparepartId).toBe(payload.sparepartId);
    expect(created.createdAt).toEqual(expect.any(String));
    expect(created.updatedAt).toEqual(expect.any(String));
  });

  test.skip("2.6-ATDD-API-002 P0 MANAGE creates installation with threshold override and VIEWER can read within plant scope", async ({
    request,
  }) => {
    const payload = installationPayload({ thresholdPercentage: 75 });
    const createResponse = await request.post(installationsUrl(), { headers: authHeaders("MANAGE"), data: payload });
    expect(createResponse.status()).toBe(201);
    const created: InstallationView = await createResponse.json();
    expect(created.thresholdPercentage).toBe(75);

    const detailResponse = await request.get(`${installationsUrl()}/${created.id}`, { headers: authHeaders("VIEWER") });
    expect(detailResponse.status()).toBe(200);
    await expectJsonMatches(detailResponse, { id: created.id, thresholdPercentage: 75 });
  });

  test.skip("2.6-ATDD-API-003 P0 defaults threshold to 90 when omitted or null", async ({ request }) => {
    const omittedResponse = await request.post(installationsUrl(), {
      headers: authHeaders("MANAGE"),
      data: installationPayload(),
    });
    expect(omittedResponse.status()).toBe(201);
    await expectJsonMatches(omittedResponse, { thresholdPercentage: 90 });

    const nullResponse = await request.post(installationsUrl(), {
      headers: authHeaders("MANAGE"),
      data: installationPayload({ thresholdPercentage: null as unknown as number }),
    });
    expect(nullResponse.status()).toBe(201);
    await expectJsonMatches(nullResponse, { thresholdPercentage: 90 });
  });

  test.skip("2.6-ATDD-API-004 P0 rejects invalid lifetime numeric ranges with safe validation shape", async ({
    request,
  }) => {
    await expectStatus(
      request.post(installationsUrl(), {
        headers: authHeaders("MANAGE"),
        data: installationPayload({ expectedProductionCount: 0 }),
      }),
      400,
    );
    await expectStatus(
      request.post(installationsUrl(), {
        headers: authHeaders("MANAGE"),
        data: installationPayload({ baselineCounter: -1 }),
      }),
      400,
    );
    await expectStatus(
      request.post(installationsUrl(), {
        headers: authHeaders("MANAGE"),
        data: installationPayload({ thresholdPercentage: 0 }),
      }),
      400,
    );
    await expectStatus(
      request.post(installationsUrl(), {
        headers: authHeaders("MANAGE"),
        data: installationPayload({ thresholdPercentage: 101 }),
      }),
      400,
    );
  });

  test.skip("2.6-ATDD-API-005 P0 rejects missing ids malformed JSON invalid UUID path and unknown references safely", async ({
    request,
  }) => {
    await expectStatus(
      request.post(installationsUrl(), {
        headers: authHeaders("MANAGE"),
        data: { ...installationPayload(), machineId: null },
      }),
      400,
    );
    await expectStatus(
      request.post(installationsUrl(), {
        headers: { ...authHeaders("MANAGE"), "Content-Type": "application/json" },
        data: "{ malformed-json",
      }),
      400,
    );
    await expectStatus(request.get(`${installationsUrl()}/not-a-uuid`, { headers: authHeaders("MANAGE") }), 400);
    await expectStatus(
      request.post(installationsUrl(), {
        headers: authHeaders("MANAGE"),
        data: installationPayload({ machineId: "99999999-9999-4999-8999-999999999999" }),
      }),
      404,
    );
    await expectStatus(
      request.post(installationsUrl(), {
        headers: authHeaders("MANAGE"),
        data: installationPayload({ sparepartId: "88888888-8888-4888-8888-888888888888" }),
      }),
      404,
    );
  });

  test.skip("2.6-ATDD-API-006 P0 lists installation evidence with machine plant group sparepart taxonomy and nullable current-count fields", async ({
    request,
  }) => {
    const response = await request.get(installationsUrl(), { headers: authHeaders("VIEWER") });

    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.items).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          machineId: expect.any(String),
          machineCode: expect.any(String),
          plantId: expect.any(String),
          sparepartId: expect.any(String),
          sparepartCode: expect.any(String),
          category: expect.any(Object),
          expectedProductionCount: expect.any(Number),
          baselineCounter: expect.any(Number),
          thresholdPercentage: expect.any(Number),
          calculationBasis: "COUNTER_BASED",
          currentCount: null,
          consumedProductionCount: null,
          consumedPercentage: null,
        }),
      ]),
    );
  });

  test.skip("2.6-ATDD-API-007 P0 updates lifetime fields without relinking machine or sparepart", async ({
    request,
  }) => {
    const installationId = "00000000-0000-4000-8000-000000000226";
    const updateResponse = await request.put(`${installationsUrl()}/${installationId}`, {
      headers: authHeaders("MANAGE"),
      data: {
        expectedProductionCount: 2_500_000,
        baselineCounter: 80_000,
        thresholdPercentage: 85,
      },
    });

    expect(updateResponse.status()).toBe(200);
    const updated: InstallationView = await updateResponse.json();
    expect(updated).toMatchObject({
      id: installationId,
      expectedProductionCount: 2_500_000,
      baselineCounter: 80_000,
      thresholdPercentage: 85,
    });
    expect(updated.machineId).toEqual(expect.any(String));
    expect(updated.sparepartId).toEqual(expect.any(String));
  });

  test.skip("2.6-ATDD-API-008 P0 deletes installation without dependents and subsequent get returns safe not found", async ({
    request,
  }) => {
    const installationId = "00000000-0000-4000-8000-000000000227";
    await expectStatus(
      request.delete(`${installationsUrl()}/${installationId}`, { headers: authHeaders("SUPER_ADMIN") }),
      204,
    );
    await expectStatus(
      request.get(`${installationsUrl()}/${installationId}`, { headers: authHeaders("SUPER_ADMIN") }),
      404,
    );
  });

  test.skip("2.6-ATDD-API-009 P0 denies VIEWER create update and delete with safe forbidden response", async ({
    request,
  }) => {
    await expectStatus(
      request.post(installationsUrl(), { headers: authHeaders("VIEWER"), data: installationPayload() }),
      403,
    );
    await expectStatus(
      request.put(`${installationsUrl()}/00000000-0000-4000-8000-000000000228`, {
        headers: authHeaders("VIEWER"),
        data: installationUpdatePayload(),
      }),
      403,
    );
    await expectStatus(
      request.delete(`${installationsUrl()}/00000000-0000-4000-8000-000000000228`, { headers: authHeaders("VIEWER") }),
      403,
    );
  });

  test.skip("2.6-ATDD-API-010 P0 unauthenticated requests to every installation endpoint return safe auth error", async ({
    request,
  }) => {
    await expectStatus(request.get(installationsUrl()), 401);
    await expectStatus(request.get(`${installationsUrl()}/00000000-0000-4000-8000-000000000229`), 401);
    await expectStatus(request.post(installationsUrl(), { data: installationPayload() }), 401);
    await expectStatus(
      request.put(`${installationsUrl()}/00000000-0000-4000-8000-000000000229`, {
        data: installationUpdatePayload(),
      }),
      401,
    );
    await expectStatus(request.delete(`${installationsUrl()}/00000000-0000-4000-8000-000000000229`), 401);
  });

  test.skip("2.6-ATDD-API-011 P0 enforces machine plant scope for MANAGE and VIEWER list detail update delete", async ({
    request,
  }) => {
    const outOfScopeInstallationId = "00000000-0000-4000-8000-000000000230";
    await expectStatus(
      request.get(`${installationsUrl()}?plantId=77777777-7777-4777-8777-777777777777`, {
        headers: authHeaders("VIEWER"),
      }),
      403,
    );
    await expectStatus(
      request.get(`${installationsUrl()}/${outOfScopeInstallationId}`, { headers: authHeaders("VIEWER") }),
      404,
    );
    await expectStatus(
      request.put(`${installationsUrl()}/${outOfScopeInstallationId}`, {
        headers: authHeaders("MANAGE"),
        data: installationUpdatePayload(),
      }),
      404,
    );
    await expectStatus(
      request.delete(`${installationsUrl()}/${outOfScopeInstallationId}`, { headers: authHeaders("MANAGE") }),
      404,
    );
  });

  test.skip("2.6-ATDD-API-012 P1 filters installations by machine sparepart plant and machine group with predictable ordering", async ({
    request,
  }) => {
    await expectStatus(
      request.get(`${installationsUrl()}?machineId=11111111-1111-4111-8111-111111111126`, {
        headers: authHeaders("VIEWER"),
      }),
      200,
    );
    await expectStatus(
      request.get(`${installationsUrl()}?sparepartId=22222222-2222-4222-8222-222222222226`, {
        headers: authHeaders("VIEWER"),
      }),
      200,
    );
    await expectStatus(
      request.get(
        `${installationsUrl()}?plantId=33333333-3333-4333-8333-333333333326&machineGroupId=44444444-4444-4444-8444-444444444426`,
        {
          headers: authHeaders("MANAGE"),
        },
      ),
      200,
    );
  });

  test.skip("2.6-ATDD-API-013 P0 machine and sparepart delete attempts prove real FK conflicts from installation rows", async ({
    request,
  }) => {
    const machineId = "11111111-1111-4111-8111-111111111126";
    const sparepartId = "22222222-2222-4222-8222-222222222226";

    await expectConflict(request.delete(`${machinesUrl()}/${machineId}`, { headers: authHeaders("MANAGE") }));
    await expectConflict(request.delete(`${sparepartsUrl()}/${sparepartId}`, { headers: authHeaders("MANAGE") }));
  });

  test.skip("2.6-ATDD-API-014 P1 maps future installation delete integrity failures to safe 409 response", async ({
    request,
  }) => {
    const installationWithFutureDependentsId = "00000000-0000-4000-8000-000000000231";
    await expectConflict(
      request.delete(`${installationsUrl()}/${installationWithFutureDependentsId}`, {
        headers: authHeaders("SUPER_ADMIN"),
      }),
    );
  });
});

async function expectStatus(responsePromise: Promise<APIResponse>, status: number) {
  const response = await responsePromise;
  expect(response.status()).toBe(status);
}

async function expectConflict(responsePromise: Promise<APIResponse>) {
  const response = await responsePromise;
  expect(response.status()).toBe(409);
  expect(await response.json()).toMatchObject({ message: expect.any(String) });
}

async function expectJsonMatches(response: APIResponse, shape: Record<string, unknown>) {
  expect(await response.json()).toMatchObject(shape);
}
