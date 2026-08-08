import { resolve } from "node:path";

const ENV_EXAMPLE_PATH = resolve(__dirname, "../../.env.example");

// Resolved at call time (not module load) so the hint reflects the operator's
// environment when the error actually fires, not when this module was imported.
function legacyApiUrlHint() {
  return process.env.SYNCRO_API_BASE_URL
    ? " The legacy SYNCRO_API_BASE_URL is no longer read; export API_URL including the /api/v1 base path instead."
    : "";
}

// Resolvers stay lazy so importing this module never throws when env is unset;
// the descriptive error surfaces only when a test actually needs the value.
function requireEnv(name: string, extra = ""): string {
  const value = (process.env[name] ?? "").trim();
  if (!value) {
    throw new Error(
      `${name} is required by Playwright tests. See ${ENV_EXAMPLE_PATH} for the expected format, then export ${name} in your shell or CI.${extra}`,
    );
  }
  return value;
}

function requireUrl(name: string, extra = ""): string {
  const value = requireEnv(name, extra);
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(
      `${name} "${value}" is not a valid URL. See ${ENV_EXAMPLE_PATH} for the expected format, then export ${name} in your shell or CI.${extra}`,
    );
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error(
      `${name} "${value}" must use the http or https protocol. See ${ENV_EXAMPLE_PATH} for the expected format, then export ${name} in your shell or CI.${extra}`,
    );
  }
  if (parsed.search || parsed.hash) {
    throw new Error(
      `${name} "${value}" must not contain a query string or fragment. See ${ENV_EXAMPLE_PATH} for the expected format, then export ${name} in your shell or CI.${extra}`,
    );
  }
  return value;
}

export function webBaseUrl() {
  return requireUrl("BASE_URL").replace(/\/+$/, "");
}

export function apiBaseUrl() {
  const extra = legacyApiUrlHint();
  const value = requireUrl("API_URL", extra).replace(/\/+$/, "");
  if (!new URL(value).pathname.endsWith("/api/v1")) {
    throw new Error(
      `API_URL must include the /api/v1 base path (e.g. https://<host>/api/v1). See ${ENV_EXAMPLE_PATH} for the expected format, then export API_URL in your shell or CI.${extra}`,
    );
  }
  return value;
}
