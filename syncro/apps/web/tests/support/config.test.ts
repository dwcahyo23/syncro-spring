import { afterAll, afterEach, beforeEach, describe, expect, it } from "vitest";

import { apiBaseUrl, webBaseUrl } from "./config";

const ENV_EXAMPLE_POINTER = /\.env\.example/;

const ORIGINAL_ENV = {
  BASE_URL: process.env.BASE_URL,
  API_URL: process.env.API_URL,
  SYNCRO_API_BASE_URL: process.env.SYNCRO_API_BASE_URL,
};

describe("config.ts env resolvers — automate config contract (unit)", () => {
  beforeEach(() => {
    delete process.env.BASE_URL;
    delete process.env.API_URL;
    delete process.env.SYNCRO_API_BASE_URL;
  });

  afterEach(() => {
    delete process.env.BASE_URL;
    delete process.env.API_URL;
    delete process.env.SYNCRO_API_BASE_URL;
  });

  afterAll(() => {
    if (ORIGINAL_ENV.BASE_URL === undefined) delete process.env.BASE_URL;
    else process.env.BASE_URL = ORIGINAL_ENV.BASE_URL;
    if (ORIGINAL_ENV.API_URL === undefined) delete process.env.API_URL;
    else process.env.API_URL = ORIGINAL_ENV.API_URL;
    if (ORIGINAL_ENV.SYNCRO_API_BASE_URL === undefined) delete process.env.SYNCRO_API_BASE_URL;
    else process.env.SYNCRO_API_BASE_URL = ORIGINAL_ENV.SYNCRO_API_BASE_URL;
  });

  it("[P0] webBaseUrl() returns BASE_URL with a trailing slash stripped", () => {
    process.env.BASE_URL = "http://localhost:3001/";

    const actual = webBaseUrl();

    expect(actual).toBe("http://localhost:3001");
  });

  it("[P0] webBaseUrl() fails fast naming BASE_URL and the .env.example pointer when unset", () => {
    expect(() => webBaseUrl()).toThrow(/BASE_URL is required by Playwright tests/);
    expect(() => webBaseUrl()).toThrow(ENV_EXAMPLE_POINTER);
  });

  it("[P0] webBaseUrl() rejects a malformed BASE_URL with the descriptive contract error", () => {
    process.env.BASE_URL = "not a url";

    expect(() => webBaseUrl()).toThrow(/BASE_URL "not a url" is not a valid URL/);
    expect(() => webBaseUrl()).toThrow(ENV_EXAMPLE_POINTER);
  });

  it("[P0] whitespace-only BASE_URL is treated as unset (requireEnv trims)", () => {
    process.env.BASE_URL = "   ";

    expect(() => webBaseUrl()).toThrow(/BASE_URL is required by Playwright tests/);
  });

  it("[P0] apiBaseUrl() returns API_URL including the /api/v1 base path as-is", () => {
    process.env.API_URL = "http://localhost:8080/api/v1";

    const actual = apiBaseUrl();

    expect(actual).toBe("http://localhost:8080/api/v1");
  });

  it("[P0] apiBaseUrl() rejects API_URL missing the /api/v1 suffix with a descriptive error", () => {
    process.env.API_URL = "http://localhost:8080";

    expect(() => apiBaseUrl()).toThrow(/API_URL must include the \/api\/v1 base path/);
    expect(() => apiBaseUrl()).toThrow(ENV_EXAMPLE_POINTER);
  });

  it("[P0] apiBaseUrl() strips trailing slashes so helpers never produce double-slash URLs", () => {
    process.env.API_URL = "http://localhost:8080/api/v1/";

    const actual = apiBaseUrl();

    expect(actual).toBe("http://localhost:8080/api/v1");
  });

  it("[P0] importing config with API_URL unset never throws; the descriptive error surfaces only at the call site", async () => {
    delete process.env.API_URL;
    delete process.env.BASE_URL;

    const mod = await import("./config");

    expect(mod.apiBaseUrl).toBeTypeOf("function");
    expect(() => mod.apiBaseUrl()).toThrow(/API_URL is required by Playwright tests/);
    expect(() => mod.apiBaseUrl()).toThrow(ENV_EXAMPLE_POINTER);
  });

  it("[P1] whitespace-only API_URL is treated as unset (requireEnv trims)", () => {
    process.env.API_URL = "   ";

    expect(() => apiBaseUrl()).toThrow(/API_URL is required by Playwright tests/);
  });

  it("[P1] webBaseUrl() rejects a BASE_URL carrying a query string", () => {
    process.env.BASE_URL = "http://localhost:3001?foo=bar";

    expect(() => webBaseUrl()).toThrow(
      /BASE_URL "http:\/\/localhost:3001\?foo=bar" must not contain a query string or fragment/,
    );
  });

  it("[P1] apiBaseUrl() rejects an API_URL carrying a query string or fragment", () => {
    process.env.API_URL = "http://localhost:8080/api/v1?tenant=x";

    expect(() => apiBaseUrl()).toThrow(/must not contain a query string or fragment/);
    process.env.API_URL = "http://localhost:8080/api/v1#frag";

    expect(() => apiBaseUrl()).toThrow(/must not contain a query string or fragment/);
  });

  it("[P1] apiBaseUrl() error hints at the legacy SYNCRO_API_BASE_URL when it is still exported", () => {
    delete process.env.API_URL;
    process.env.SYNCRO_API_BASE_URL = "http://localhost:8080";

    expect(() => apiBaseUrl()).toThrow(/SYNCRO_API_BASE_URL is no longer read/);
  });

  it("[P2] a portless BASE_URL (deployed origin) resolves without error", () => {
    process.env.BASE_URL = "http://app.example.com";

    const actual = webBaseUrl();

    expect(actual).toBe("http://app.example.com");
  });

  it("[P3] a default-port BASE_URL resolves without error", () => {
    process.env.BASE_URL = "http://app.example.com:80";

    const actual = webBaseUrl();

    expect(actual).toBe("http://app.example.com:80");
  });
});
