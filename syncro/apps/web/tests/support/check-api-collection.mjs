import { spawn } from "node:child_process";
import { readdirSync } from "node:fs";
import { join, sep } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = fileURLToPath(new URL(".", import.meta.url));
// Resolve everything from this module so the script works from any cwd
// (repo root, CI wrapper) as long as the web app checkout is intact.
const webDir = join(scriptDir, "..", "..");
const apiDir = join(webDir, "tests", "api");
const apiConfig = join(webDir, "playwright.api.config.ts");
const listTimeoutMs = Number(process.env.PLAYWRIGHT_LIST_TIMEOUT_MS ?? 30_000);
// Spawn the project-pinned Playwright CLI via `node` (no shell, no npx): a fresh
// CI checkout never fetches an unrelated published release, and paths containing
// spaces never get mangled by a shell concatenation.
const playwrightCli = join(webDir, "node_modules", "@playwright", "test", "cli.js");

function collectApiSpecs() {
  const specs = [];
  let entries;
  try {
    entries = readdirSync(apiDir, { recursive: true });
  } catch {
    // tests/api missing/renamed -> treat as no specs so the gate fails cleanly.
    return specs;
  }
  for (const entry of entries) {
    if (typeof entry === "string" && entry.endsWith(".spec.ts")) {
      // Playwright --list prints forward slashes on all platforms; normalize the
      // on-disk separator so nested specs match on Windows too.
      specs.push(entry.split(sep).join("/"));
    }
  }
  return specs;
}

function runPlaywrightList(args) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [playwrightCli, "test", "--list", ...args], {
      cwd: webDir,
      windowsHide: true,
    });
    let stdout = "";
    let stderr = "";
    let timedOut = false;
    // Guard against a stalled playwright/install prompt hanging CI forever.
    const killer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, listTimeoutMs);
    child.stdout.on("data", (chunk) => (stdout += chunk.toString()));
    child.stderr.on("data", (chunk) => (stderr += chunk.toString()));
    child.on("error", (error) => {
      clearTimeout(killer);
      resolve({ code: -1, timedOut: false, stdout, stderr: String(error) });
    });
    child.on("close", (code) => {
      clearTimeout(killer);
      resolve({ code, timedOut, stdout, stderr });
    });
  });
}

// Match a --list line to an on-disk api spec exactly. --list prints each spec as
// `<path>:<line>:<col> › <title>`, where `<path>` is relative to the config's
// testDir; compare that leading path segment so `a.spec.ts` never matches
// `aa.spec.ts` (substring guard) while nested specs still match.
function lineMatchesApiSpec(line, specs) {
  const pathSegment = line.trim().split(":")[0].split(sep).join("/");
  return specs.includes(pathSegment);
}

async function main() {
  const apiSpecsOnDisk = collectApiSpecs();

  // The default-collection probe needs BASE_URL (the base config calls
  // webBaseUrl() at load). An API-only environment exports only API_URL, so skip
  // that stage and verify the test:api config directly — the coverage-gap signal
  // must not go red for an unrelated missing web URL.
  let defaultApiSpecs = [];
  if (process.env.BASE_URL) {
    // Default collection (uses testDir: ./tests/e2e). The API suites live outside
    // it, so a green default run can silently carry zero API coverage (WH-01/DW-10).
    const defaultList = await runPlaywrightList([]);

    if (defaultList.code !== 0) {
      // A broken/missing web config (e.g. stale or invalid BASE_URL) is unrelated
      // to API enumeration; warn and fall through to the explicit test:api probe
      // instead of failing the coverage-gap signal.
      const detail = defaultList.timedOut
        ? `playwright test --list timed out after ${listTimeoutMs}ms and was killed`
        : defaultList.stderr || `playwright test --list exited with code ${defaultList.code}`;
      process.stderr.write(`check-api-collection: WARNING - default probe failed: ${detail}\n`);
    } else {
      defaultApiSpecs = defaultList.stdout.split(/\r?\n/).filter((line) => lineMatchesApiSpec(line, apiSpecsOnDisk));

      if (defaultApiSpecs.length > 0) {
        process.stdout.write(
          `check-api-collection: OK - default run collects ${defaultApiSpecs.length} tests/api spec(s).\n`,
        );
        for (const spec of defaultApiSpecs) {
          process.stdout.write(`  ${spec}\n`);
        }
        return;
      }
    }
  }

  // Default testDir omits tests/api. Verify the suites are at least enumerable via
  // the explicit test:api config (playwright.api.config.ts) so a regression
  // (missing/broken API spec) still fails loudly, and surface the collection gap
  // for the operator.
  const apiList = await runPlaywrightList([`--config=${apiConfig}`]);

  if (apiList.code !== 0) {
    const detail = apiList.timedOut
      ? `playwright test --list timed out after ${listTimeoutMs}ms and was killed`
      : apiList.stderr || `playwright test --config=playwright.api.config.ts --list exited with code ${apiList.code}`;
    process.stderr.write(`check-api-collection: FAIL - tests/api cannot be enumerated: ${detail}\n`);
    process.exitCode = 1;
    return;
  }

  const apiSpecs = apiList.stdout.split(/\r?\n/).filter((line) => lineMatchesApiSpec(line, apiSpecsOnDisk));

  if (apiSpecs.length === 0) {
    process.stderr.write("check-api-collection: FAIL - no tests/api spec enumerated by the test:api config.\n");
    process.exitCode = 1;
    return;
  }

  if (process.env.BASE_URL) {
    process.stderr.write(
      "check-api-collection: WARNING - default `playwright test --list` collects no tests/api spec\n" +
        "(testDir is ./tests/e2e, WH-01/DW-10). API suites are enumerable via `test:api`, but run them\n" +
        "explicitly in CI (`npm run test:api`) or a default run is green with zero API coverage.\n",
    );
  } else {
    process.stderr.write(
      "check-api-collection: WARNING - BASE_URL unset, default-collection probe skipped; API suites\n" +
        "verified via `test:api` only. Run them explicitly in CI (`npm run test:api`).\n",
    );
  }
  process.stdout.write(`check-api-collection: OK (via test:api) - ${apiSpecs.length} tests/api spec(s) enumerable.\n`);
  for (const spec of apiSpecs) {
    process.stdout.write(`  ${spec}\n`);
  }
}

await main();
