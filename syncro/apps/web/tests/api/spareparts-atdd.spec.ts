import type { APIResponse } from "@playwright/test";
import { expect, test } from "@playwright/test";

import { apiBaseUrl } from "../support/config";

// Resolved lazily so this module still loads when API_URL is unset and tests skip.
function sparepartsUrl() {
  return `${apiBaseUrl()}/spareparts`;
}

type RoleName = "SUPER_ADMIN" | "MANAGE" | "VIEWER";

type TaxonomyRefs = {
  categoryId: string;
  brandId: string;
  kindId: string;
  typeId: string;
};

function authHeaders(role: RoleName) {
  return {
    Authorization: `Bearer ${process.env[`SYNCRO_ATDD_${role}_TOKEN`] ?? `${role.toLowerCase()}-token-red-phase`}`,
    "Content-Type": "application/json",
  };
}

function sparepartPayload(overrides: Partial<TaxonomyRefs & { code: string; name: string }> = {}) {
  return {
    code: overrides.code ?? `BRG-${Date.now()}`,
    name: overrides.name ?? `Bearing ${Date.now()}`,
    categoryId: overrides.categoryId ?? "11111111-1111-4111-8111-111111111111",
    brandId: overrides.brandId ?? "22222222-2222-4222-8222-222222222222",
    kindId: overrides.kindId ?? "33333333-3333-4333-8333-333333333333",
    typeId: overrides.typeId ?? "44444444-4444-4444-8444-444444444444",
  };
}

test.describe("Story 2.5 ATDD API RED-PHASE scaffold: manage spareparts", () => {
  test.skip("2.5-ATDD-API-001 P0 SUPER_ADMIN creates sparepart with CATEGORY, BRAND, KIND, TYPE taxonomy refs and sees it in list", async ({
    request,
  }) => {
    const payload = sparepartPayload({ code: "BRG-6205-ZZ", name: "Bearing 6205 ZZ" });
    const createResponse = await request.post(sparepartsUrl(), { headers: authHeaders("SUPER_ADMIN"), data: payload });
    expect(createResponse.status()).toBe(201);
    const created = await createResponse.json();
    expect(created).toMatchObject({ code: payload.code, name: payload.name });
    expect(created.category.id).toBe(payload.categoryId);
    expect(created.brand.id).toBe(payload.brandId);
    expect(created.kind.id).toBe(payload.kindId);
    expect(created.type.id).toBe(payload.typeId);

    const listResponse = await request.get(sparepartsUrl(), { headers: authHeaders("SUPER_ADMIN") });
    expect(listResponse.status()).toBe(200);
    await expectListContains(listResponse, payload.code);
  });

  test.skip("2.5-ATDD-API-002 P0 MANAGE creates sparepart and VIEWER can read list/detail as global master data", async ({
    request,
  }) => {
    const payload = sparepartPayload({ code: "BLT-M8-30", name: "Bolt M8 x 30" });
    const createResponse = await request.post(sparepartsUrl(), { headers: authHeaders("MANAGE"), data: payload });
    expect(createResponse.status()).toBe(201);
    const created = await createResponse.json();

    const listResponse = await request.get(sparepartsUrl(), { headers: authHeaders("VIEWER") });
    expect(listResponse.status()).toBe(200);
    await expectListContains(listResponse, payload.code);

    const detailResponse = await request.get(`${sparepartsUrl()}/${created.id}`, { headers: authHeaders("VIEWER") });
    expect(detailResponse.status()).toBe(200);
    await expectJsonMatches(detailResponse, { id: created.id, code: payload.code });
  });

  test.skip("2.5-ATDD-API-003 P0 rejects create when required taxonomy references are missing, null, unknown, or wrong dimension", async ({
    request,
  }) => {
    await expectStatus(
      request.post(sparepartsUrl(), {
        headers: authHeaders("MANAGE"),
        data: sparepartPayload({ categoryId: undefined as unknown as string }),
      }),
      400,
    );
    await expectStatus(
      request.post(sparepartsUrl(), { headers: authHeaders("MANAGE"), data: { ...sparepartPayload(), brandId: null } }),
      400,
    );
    await expectStatus(
      request.post(sparepartsUrl(), {
        headers: authHeaders("MANAGE"),
        data: sparepartPayload({ kindId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" }),
      }),
      404,
    );
    await expectStatus(
      request.post(sparepartsUrl(), {
        headers: authHeaders("MANAGE"),
        data: sparepartPayload({ typeId: "11111111-1111-4111-8111-111111111111" }),
      }),
      400,
    );
  });

  test.skip("2.5-ATDD-API-004 P0 rejects update when taxonomy refs are unknown or wrong dimension using safe not-found or validation shape", async ({
    request,
  }) => {
    const sparepartId = "00000000-0000-4000-8000-000000000026";
    await expectStatus(
      request.put(`${sparepartsUrl()}/${sparepartId}`, {
        headers: authHeaders("SUPER_ADMIN"),
        data: sparepartPayload({ categoryId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" }),
      }),
      404,
    );
    await expectStatus(
      request.put(`${sparepartsUrl()}/${sparepartId}`, {
        headers: authHeaders("SUPER_ADMIN"),
        data: sparepartPayload({ brandId: "33333333-3333-4333-8333-333333333333" }),
      }),
      400,
    );
  });

  test.skip("2.5-ATDD-API-005 P0 rejects blank code/name, over-length values, malformed JSON, and invalid UUID values safely", async ({
    request,
  }) => {
    await expectStatus(
      request.post(sparepartsUrl(), {
        headers: authHeaders("MANAGE"),
        data: sparepartPayload({ code: "   ", name: "Valid Pump Seal" }),
      }),
      400,
    );
    await expectStatus(
      request.post(sparepartsUrl(), {
        headers: authHeaders("MANAGE"),
        data: sparepartPayload({ code: "X".repeat(65), name: "Valid Pump Seal" }),
      }),
      400,
    );
    await expectStatus(
      request.post(sparepartsUrl(), {
        headers: authHeaders("MANAGE"),
        data: sparepartPayload({ code: "SEAL-001", name: "   " }),
      }),
      400,
    );
    await expectStatus(
      request.post(sparepartsUrl(), {
        headers: { ...authHeaders("MANAGE"), "Content-Type": "application/json" },
        data: "{ malformed-json",
      }),
      400,
    );
    await expectStatus(request.get(`${sparepartsUrl()}/not-a-uuid`, { headers: authHeaders("MANAGE") }), 400);
  });

  test.skip("2.5-ATDD-API-006 P0 rejects duplicate code or name regardless casing and surrounding whitespace on create and update", async ({
    request,
  }) => {
    await expectStatus(
      request.post(sparepartsUrl(), {
        headers: authHeaders("SUPER_ADMIN"),
        data: sparepartPayload({ code: "FLT-OIL-01", name: "Oil Filter Primary" }),
      }),
      201,
    );
    await expectConflict(
      request.post(sparepartsUrl(), {
        headers: authHeaders("SUPER_ADMIN"),
        data: sparepartPayload({ code: "  flt-oil-01  ", name: "Oil Filter Secondary" }),
      }),
    );
    await expectConflict(
      request.post(sparepartsUrl(), {
        headers: authHeaders("SUPER_ADMIN"),
        data: sparepartPayload({ code: "FLT-OIL-02", name: "  oil filter primary  " }),
      }),
    );
    await expectConflict(
      request.put(`${sparepartsUrl()}/00000000-0000-4000-8000-000000000027`, {
        headers: authHeaders("SUPER_ADMIN"),
        data: sparepartPayload({ code: "flt-oil-01", name: "Unique Filter Name" }),
      }),
    );
  });

  test.skip("2.5-ATDD-API-007 P1 allows same taxonomy combination for distinct spareparts when code and name are unique", async ({
    request,
  }) => {
    const refs = {
      categoryId: "11111111-1111-4111-8111-111111111111",
      brandId: "22222222-2222-4222-8222-222222222222",
      kindId: "33333333-3333-4333-8333-333333333333",
      typeId: "44444444-4444-4444-8444-444444444444",
    };
    await expectStatus(
      request.post(sparepartsUrl(), {
        headers: authHeaders("MANAGE"),
        data: sparepartPayload({ ...refs, code: "CHAIN-40-1", name: "Roller Chain 40 A" }),
      }),
      201,
    );
    await expectStatus(
      request.post(sparepartsUrl(), {
        headers: authHeaders("MANAGE"),
        data: sparepartPayload({ ...refs, code: "CHAIN-40-2", name: "Roller Chain 40 B" }),
      }),
      201,
    );
  });

  test.skip("2.5-ATDD-API-008 P0 lists spareparts with predictable ordering and filters by category, brand, kind, type, and code/name search", async ({
    request,
  }) => {
    await expectStatus(
      request.get(
        `${sparepartsUrl()}?categoryId=11111111-1111-4111-8111-111111111111&brandId=22222222-2222-4222-8222-222222222222`,
        { headers: authHeaders("VIEWER") },
      ),
      200,
    );
    await expectStatus(
      request.get(
        `${sparepartsUrl()}?kindId=33333333-3333-4333-8333-333333333333&typeId=44444444-4444-4444-8444-444444444444`,
        { headers: authHeaders("VIEWER") },
      ),
      200,
    );
    await expectStatus(request.get(`${sparepartsUrl()}?search=bearing`, { headers: authHeaders("VIEWER") }), 200);
    await expectStatus(request.get(`${sparepartsUrl()}?search=BRG-6205`, { headers: authHeaders("VIEWER") }), 200);
  });

  test.skip("2.5-ATDD-API-009 P0 updates bounded fields and taxonomy references then returns updated taxonomy labels", async ({
    request,
  }) => {
    const sparepartId = "00000000-0000-4000-8000-000000000028";
    const payload = sparepartPayload({
      code: "SEAL-PUMP-02",
      name: "Pump Mechanical Seal Updated",
      categoryId: "55555555-5555-4555-8555-555555555555",
    });
    const updateResponse = await request.put(`${sparepartsUrl()}/${sparepartId}`, {
      headers: authHeaders("MANAGE"),
      data: payload,
    });
    expect(updateResponse.status()).toBe(200);
    await expectJsonMatches(updateResponse, { id: sparepartId, code: payload.code, name: payload.name });

    const detailResponse = await request.get(`${sparepartsUrl()}/${sparepartId}`, { headers: authHeaders("MANAGE") });
    expect(detailResponse.status()).toBe(200);
    await expectJsonMatches(detailResponse, { id: sparepartId, code: payload.code, name: payload.name });
  });

  test.skip("2.5-ATDD-API-010 P0 deletes sparepart without dependent installations and subsequent get returns safe not found", async ({
    request,
  }) => {
    const sparepartId = "00000000-0000-4000-8000-000000000029";
    await expectStatus(
      request.delete(`${sparepartsUrl()}/${sparepartId}`, { headers: authHeaders("SUPER_ADMIN") }),
      204,
    );
    await expectStatus(request.get(`${sparepartsUrl()}/${sparepartId}`, { headers: authHeaders("SUPER_ADMIN") }), 404);
  });

  test.skip("2.5-ATDD-API-011 P0 returns safe 409 conflict when deleting sparepart referenced by installed spareparts", async ({
    request,
  }) => {
    const installedSparepartDependencyTargetId = "00000000-0000-4000-8000-000000000030";
    await expectConflict(
      request.delete(`${sparepartsUrl()}/${installedSparepartDependencyTargetId}`, { headers: authHeaders("MANAGE") }),
    );
  });

  test.skip("2.5-ATDD-API-012 P0 denies VIEWER create, update, and delete with safe forbidden response", async ({
    request,
  }) => {
    await expectStatus(
      request.post(sparepartsUrl(), {
        headers: authHeaders("VIEWER"),
        data: sparepartPayload({ code: "VIEWER-DENIED-01", name: "Viewer Denied Create" }),
      }),
      403,
    );
    await expectStatus(
      request.put(`${sparepartsUrl()}/00000000-0000-4000-8000-000000000031`, {
        headers: authHeaders("VIEWER"),
        data: sparepartPayload({ code: "VIEWER-DENIED-02", name: "Viewer Denied Update" }),
      }),
      403,
    );
    await expectStatus(
      request.delete(`${sparepartsUrl()}/00000000-0000-4000-8000-000000000031`, { headers: authHeaders("VIEWER") }),
      403,
    );
  });

  test.skip("2.5-ATDD-API-013 P0 unauthenticated requests to every sparepart endpoint return existing safe authentication error shape", async ({
    request,
  }) => {
    await expectStatus(request.get(sparepartsUrl()), 401);
    await expectStatus(request.get(`${sparepartsUrl()}/00000000-0000-4000-8000-000000000032`), 401);
    await expectStatus(
      request.post(sparepartsUrl(), { data: sparepartPayload({ code: "NOAUTH-01", name: "No Auth Create" }) }),
      401,
    );
    await expectStatus(
      request.put(`${sparepartsUrl()}/00000000-0000-4000-8000-000000000032`, {
        data: sparepartPayload({ code: "NOAUTH-02", name: "No Auth Update" }),
      }),
      401,
    );
    await expectStatus(request.delete(`${sparepartsUrl()}/00000000-0000-4000-8000-000000000032`), 401);
  });

  test.skip("2.5-ATDD-API-014 P1 returns global non-plant-scoped sparepart master data for MANAGE and VIEWER despite plant assignment limits", async ({
    request,
  }) => {
    await expectStatus(request.get(sparepartsUrl(), { headers: authHeaders("MANAGE") }), 200);
    await expectStatus(request.get(sparepartsUrl(), { headers: authHeaders("VIEWER") }), 200);
  });

  test.skip("2.5-ATDD-API-015 P1 rejects invalid UUID query filters and unknown sparepart detail id safely", async ({
    request,
  }) => {
    await expectStatus(request.get(`${sparepartsUrl()}?categoryId=not-a-uuid`, { headers: authHeaders("VIEWER") }), 400);
    await expectStatus(
      request.get(`${sparepartsUrl()}/99999999-9999-4999-8999-999999999999`, { headers: authHeaders("VIEWER") }),
      404,
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

async function expectListContains(response: APIResponse, code: string) {
  const body = (await response.json()) as { items: Array<{ code: string }> };
  expect(body.items).toEqual(expect.arrayContaining([expect.objectContaining({ code })]));
}
