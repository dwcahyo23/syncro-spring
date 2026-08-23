// DW-117: fetches the live OpenAPI document from the running backend and writes
// a committed snapshot that orval consumes (see orval.config.ts). Refresh this
// snapshot whenever the backend contract changes INTENTIONALLY, then review the
// generated diff from `npm run generate:api`.
//
// Usage: node scripts/fetch-openapi.mjs [baseUrl]
//   baseUrl defaults to http://localhost:8080

import { writeFile } from "node:fs/promises";

const baseUrl = process.argv[2] ?? "http://localhost:8080";
const response = await fetch(`${baseUrl}/v3/api-docs`);

if (!response.ok) {
  console.error(`Failed to fetch OpenAPI spec: ${response.status} ${response.statusText}`);
  process.exit(1);
}

const spec = await response.json();

// Sanity guard: refuse to overwrite the snapshot when the ALERT enum value is
// missing (regression guard originally hand-patched before bundle-10).
const entityTypeValues = spec.components?.schemas?.AuditLogEntryView?.properties?.entityType?.enum ?? [];
if (!entityTypeValues.includes("ALERT")) {
  console.error("Refusing to write snapshot: AuditLogEntryView.entityType enum lacks ALERT.");
  process.exit(1);
}

await writeFile(
  new URL("../openapi.json", import.meta.url),
  JSON.stringify(spec, null, 2) + "\n",
  "utf8",
);

console.log(`OpenAPI snapshot written (${Object.keys(spec.paths ?? {}).length} paths).`);
