import { expect, test } from "@playwright/test";

import { apiBaseUrl, webBaseUrl } from "../support/config";

// dw-web-e2e-config-hardening ATDD config-contract RED scaffolds (DW-3 + DW-7).
//
// These lock the contract of the new env-driven `tests/support/config.ts` module
// introduced by the bundle: lazy `webBaseUrl()`/`apiBaseUrl()` with fast
// descriptive errors (trim, URL validity, `/api/v1` suffix enforcement,
// trailing-slash normalization, module-resolved `.env.example` pointer).
//
// The implementation is already present in the working tree, so each scaffold is
// an ACTIVATION / REGRESSION lock: it is written for the EXPECTED behavior and
// must stay skipped until a developer activates it. Removing `test.skip(` makes
// the test active; it passes against the current contract and FAILS if the
// contract regresses (e.g. a localhost fallback is reintroduced, the `/api/v1`
// check is dropped, or an error loses the `.env.example` pointer).
//
// Env is process-global and these tests mutate `process.env`; run them serially
// (single worker) when activated so the mutations never race other workers.

const ENV_EXAMPLE_POINTER = ".env.example";

const ORIGINAL_ENV = {
  BASE_URL: process.env.BASE_URL,
  API_URL: process.env.API_URL,
  SYNCRO_API_BASE_URL: process.env.SYNCRO_API_BASE_URL,
};

test.describe("WH config contract (RED): tests/support/config.ts", () => {
  test.describe.configure({ mode: "serial" });

  test.afterEach(() => {
    if (ORIGINAL_ENV.BASE_URL === undefined) delete process.env.BASE_URL;
    else process.env.BASE_URL = ORIGINAL_ENV.BASE_URL;
    if (ORIGINAL_ENV.API_URL === undefined) delete process.env.API_URL;
    else process.env.API_URL = ORIGINAL_ENV.API_URL;
    if (ORIGINAL_ENV.SYNCRO_API_BASE_URL === undefined) delete process.env.SYNCRO_API_BASE_URL;
    else process.env.SYNCRO_API_BASE_URL = ORIGINAL_ENV.SYNCRO_API_BASE_URL;
  });

  test.skip("WH-AC1 [P0] webBaseUrl() returns the BASE_URL value with a trailing slash stripped", () => {
    process.env.BASE_URL = "http://localhost:3001/";

    const actual = webBaseUrl();

    expect(actual).toBe("http://localhost:3001");
  });

  test.skip("WH-AC2 [P0] webBaseUrl() fails fast naming BASE_URL and the .env.example pointer when unset", () => {
    delete process.env.BASE_URL;

    expect(() => webBaseUrl()).toThrow(/BASE_URL is required by Playwright tests/);
    expect(() => webBaseUrl()).toThrow(ENV_EXAMPLE_POINTER);
  });

  test.skip("WH-AC2b [P0] webBaseUrl() rejects a malformed BASE_URL with the descriptive contract error", () => {
    process.env.BASE_URL = "not a url";

    expect(() => webBaseUrl()).toThrow(/BASE_URL "not a url" is not a valid URL/);
    expect(() => webBaseUrl()).toThrow(ENV_EXAMPLE_POINTER);
  });

  test.skip("WH-AC2c [P0] whitespace-only BASE_URL is treated as unset (requireEnv trims)", () => {
    process.env.BASE_URL = "   ";

    expect(() => webBaseUrl()).toThrow(/BASE_URL is required by Playwright tests/);
  });

  test.skip("WH-AC3 [P0] apiBaseUrl() returns the API_URL base including the /api/v1 base path", () => {
    process.env.API_URL = "http://localhost:8080/api/v1";

    const actual = apiBaseUrl();

    expect(actual).toBe("http://localhost:8080/api/v1");
  });

  test.skip("WH-AC3b [P0] apiBaseUrl() rejects API_URL missing the /api/v1 suffix with a descriptive error", () => {
    process.env.API_URL = "http://localhost:8080";

    expect(() => apiBaseUrl()).toThrow(/API_URL must include the \/api\/v1 base path/);
    expect(() => apiBaseUrl()).toThrow(ENV_EXAMPLE_POINTER);
  });

  test.skip("WH-AC3c [P0] apiBaseUrl() strips a trailing slash so helpers never produce double-slash URLs", () => {
    process.env.API_URL = "http://localhost:8080/api/v1/";

    const actual = apiBaseUrl();

    expect(actual).toBe("http://localhost:8080/api/v1");
  });

  test.skip("WH-AC4 [P0] importing config with API_URL unset never throws; the descriptive error surfaces only at the call site", () => {
    delete process.env.API_URL;
    delete process.env.BASE_URL;

    // Module import happened at the top of this file without throwing (lazy).
    expect(() => apiBaseUrl()).toThrow(/API_URL is required by Playwright tests/);
    expect(() => apiBaseUrl()).toThrow(ENV_EXAMPLE_POINTER);
  });

  test.skip("WH-AC4b [P0] whitespace-only API_URL is treated as unset (requireEnv trims)", () => {
    process.env.API_URL = "   ";

    expect(() => apiBaseUrl()).toThrow(/API_URL is required by Playwright tests/);
  });

  test.skip("WH-P2-01 [P2] a portless BASE_URL (deployed origin) resolves without error", () => {
    process.env.BASE_URL = "http://app.example.com";

    const actual = webBaseUrl();

    expect(actual).toBe("http://app.example.com");
  });

  test.skip("WH-P3-01 [P3] a default-port BASE_URL resolves without error (config-level port derivation is a separate probe)", () => {
    process.env.BASE_URL = "http://app.example.com:80";

    const actual = webBaseUrl();

    expect(actual).toBe("http://app.example.com:80");
  });
});
