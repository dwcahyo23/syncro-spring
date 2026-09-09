import { describe, expect, it } from "vitest";

import enMessages from "@/messages/en.json";
import idMessages from "@/messages/id.json";

import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

/**
 * Story 23-2 guard: the two catalogs must end with identical key sets, and
 * every UPPER_SNAKE error code referenced by app code must exist in `errors`
 * (cheap drift check — the real completeness tooling is story 23.4).
 */

type Catalog = { [key: string]: string | Catalog };

function collectKeys(catalog: Catalog, prefix = ""): string[] {
  const keys: string[] = [];
  for (const [key, value] of Object.entries(catalog)) {
    const full = prefix ? `${prefix}.${key}` : key;
    if (typeof value === "string") keys.push(full);
    else keys.push(...collectKeys(value, full));
  }
  return keys.sort();
}

function walkFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    if (entry === "node_modules" || entry === ".next" || entry === "generated") continue;
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...walkFiles(full));
    else if (/\.(ts|tsx)$/.test(entry) && !/\.test\./.test(entry)) out.push(full);
  }
  return out;
}

describe("message catalog parity (en vs id)", () => {
  const enKeys = collectKeys(enMessages as Catalog);
  const idKeys = collectKeys(idMessages as Catalog);

  it("en and id expose identical key sets", () => {
    const missingFromId = enKeys.filter((key) => !idKeys.includes(key));
    const missingFromEn = idKeys.filter((key) => !enKeys.includes(key));
    expect(missingFromId, `keys missing from id.json:\n${missingFromId.join("\n")}`).toEqual([]);
    expect(missingFromEn, `keys missing from en.json:\n${missingFromEn.join("\n")}`).toEqual([]);
  });

  it("every catalog value is a non-empty string", () => {
    const empty = [...enKeys, ...idKeys].filter((key) => {
      const lookup = (catalog: unknown) =>
        key
          .split(".")
          .reduce<unknown>(
            (acc, seg) => (acc && typeof acc === "object" ? (acc as Record<string, unknown>)[seg] : undefined),
            catalog,
          );
      return typeof lookup(enMessages) !== "string" || lookup(enMessages) === "";
    });
    expect(empty).toEqual([]);
  });
});

describe("errors catalog drift guard", () => {
  it("every UPPER_SNAKE code compared in app code has an errors.<CODE> entry", () => {
    const srcDir = path.resolve(__dirname, "..");
    const codes = new Set<string>();
    // Matches: foo.code === "SOME_CODE", and direct errors-namespace lookups
    // te("SOME_CODE") / t("SOME_CODE") (Story 23-2 sweep style).
    const patterns = [/\.code\s*===\s*"([A-Z][A-Z0-9_]+)"/g, /\b(?:te|t)\(\s*"([A-Z][A-Z0-9_]+)"\s*[,)]/g];
    for (const file of walkFiles(srcDir)) {
      const text = readFileSync(file, "utf8");
      for (const pattern of patterns) for (const match of text.matchAll(pattern)) codes.add(match[1]);
    }
    const errorsSection = (idMessages as Catalog).errors;
    const errors: Catalog = typeof errorsSection === "string" ? {} : (errorsSection ?? {});
    // Skip control-flow codes that never surface as user-facing messages.
    const NON_MESSAGE_CODES = new Set([
      "MACHINERY",
      "UTILITY",
      "WORKSHOP",
      "SPAREPART",
      "CONSUMABLE",
      "SERVICE_EXTERNAL",
      "UNRESTRICTED",
      "ASSIGNED",
      "EMPTY",
    ]);
    const missing = [...codes]
      .filter((code) => !NON_MESSAGE_CODES.has(code) && typeof errors[code] !== "string")
      .sort();
    expect(missing, `codes used in app but absent from errors catalog:\n${missing.join("\n")}`).toEqual([]);
  });
});
