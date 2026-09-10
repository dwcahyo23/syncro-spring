import { describe, expect, it } from "vitest";

import enMessages from "@/messages/en.json";
import idMessages from "@/messages/id.json";

import { collectSourceFiles, runChecks } from "../../scripts/check-translations.mjs";
import { readFileSync } from "node:fs";
import path from "node:path";

/**
 * Story 23-4: translation completeness & quality gate.
 *
 * `runChecks` (scripts/check-translations.mjs) is the single shared checker:
 * the CLI (`npm run check:i18n`) and this vitest feed it the same inputs, so
 * the test suite stays the repo's gate (there is no CI runner). The first
 * describe arms every hard-fail/warning class against synthetic fixtures; the
 * second runs the REAL catalogs + src/ scan as the gate — warnings (identical
 * en/id values, en-only ICU args) are reported but never fail.
 */

type Catalog = { [key: string]: string | Catalog };

// Concrete shape so structuredClone fixtures stay mutable & typed.
interface Fixture {
  sample: { greeting: string; items: string };
  errors: { VALIDATION_ERROR: string };
}

const EN: Fixture = {
  sample: { greeting: "Hello {name}", items: "{count, plural, one {# item} other {# items}}" },
  errors: { VALIDATION_ERROR: "The submitted data contains errors." },
};
const ID: Fixture = {
  sample: { greeting: "Halo {name}", items: "{count, plural, other {# barang}}" },
  errors: { VALIDATION_ERROR: "Data yang dikirim mengandung kesalahan." },
};

const ok = (result: ReturnType<typeof runChecks>) => ({
  failures: result.failures.length,
  warnings: result.warnings.length,
});

describe("runChecks — synthetic fixtures (I/O matrix rows 1–7)", () => {
  it("clean fixtures produce no failures and no warnings", () => {
    expect(ok(runChecks({ en: EN, id: ID, files: [] }))).toEqual({ failures: 0, warnings: 0 });
  });

  it("row 1: id missing a key en has fails under key-asymmetry (both directions)", () => {
    const enMinus = structuredClone(EN);
    delete (enMinus.sample as Catalog).items;
    const idMinus = structuredClone(ID);
    delete (idMinus.sample as Catalog).greeting;

    const missingFromId = runChecks({ en: EN, id: idMinus, files: [] });
    expect(missingFromId.failures.map((f) => f.kind)).toEqual(["key-asymmetry"]);
    expect(missingFromId.failures[0].key).toBe("sample.greeting");
    expect(missingFromId.failures[0].message).toContain("missing from id.json");

    const missingFromEn = runChecks({ en: enMinus, id: ID, files: [] });
    expect(missingFromEn.failures.map((f) => f.kind)).toEqual(["key-asymmetry"]);
    expect(missingFromEn.failures[0].key).toBe("sample.items");
    expect(missingFromEn.failures[0].message).toContain("missing from en.json");
  });

  it("row 2: an unresolved static t() literal fails and names the file", () => {
    const result = runChecks({
      en: EN,
      id: ID,
      files: [{ path: "src/widget.tsx", text: 'const t = useTranslations("sample"); t("nope.missing");' }],
    });
    expect(result.failures.map((f) => f.kind)).toEqual(["unresolved-key"]);
    expect(result.failures[0].file).toBe("src/widget.tsx");
    expect(result.failures[0].key).toBe("sample.nope.missing");
  });

  it("row 3: an id string referencing an ICU argument absent from en fails (literal-token leak)", () => {
    const idLeak = structuredClone(ID);
    idLeak.sample.greeting = "Halo {name}, sampai {extra}!";
    const result = runChecks({ en: EN, id: idLeak, files: [] });
    expect(result.failures.map((f) => f.kind)).toEqual(["icu-arg-leak"]);
    expect(result.failures[0].key).toBe("sample.greeting");
    expect(result.failures[0].message).toContain("{extra}");
  });

  it("row 4: an en argument omitted in id is a warning, not a failure", () => {
    const idNoArg = structuredClone(ID);
    idNoArg.sample.greeting = "Halo!";
    const result = runChecks({ en: EN, id: idNoArg, files: [] });
    expect(result.failures).toEqual([]);
    expect(result.warnings.map((w) => w.kind)).toContain("en-arg-omitted");
    expect(result.warnings.find((w) => w.kind === "en-arg-omitted")?.key).toBe("sample.greeting");
  });

  it("row 5: a byte-identical en/id leaf warns with count + key, never fails", () => {
    const idDup = structuredClone(ID);
    idDup.sample.greeting = "Hello {name}"; // copy-paste of the en value
    const result = runChecks({ en: EN, id: idDup, files: [] });
    expect(result.failures).toEqual([]);
    const identical = result.warnings.filter((w) => w.kind === "identical-value");
    expect(identical.map((w) => w.key)).toEqual(["sample.greeting"]);
  });

  it("row 6: an empty/whitespace leaf value fails in either locale", () => {
    // errors.VALIDATION_ERROR has no ICU args on either side, so the only
    // failure can be empty-value itself.
    const enEmpty = structuredClone(EN);
    enEmpty.errors.VALIDATION_ERROR = "   ";
    const result = runChecks({ en: enEmpty, id: ID, files: [] });
    expect(result.failures.map((f) => f.kind)).toEqual(["empty-value"]);
    expect(result.failures[0].key).toBe("errors.VALIDATION_ERROR");

    const idEmpty = structuredClone(ID);
    idEmpty.errors.VALIDATION_ERROR = "";
    const emptyId = runChecks({ en: EN, id: idEmpty, files: [] });
    expect(emptyId.failures.map((f) => f.kind)).toEqual(["empty-value"]);
    expect(emptyId.failures[0].key).toBe("errors.VALIDATION_ERROR");
  });

  it('row 7: te("VALIDATION_ERROR") resolves against the bound errors namespace (no false positive)', () => {
    const result = runChecks({
      en: EN,
      id: ID,
      files: [{ path: "src/form.tsx", text: 'const te = useTranslations("errors"); te("VALIDATION_ERROR");' }],
    });
    expect(result.failures).toEqual([]);
    expect(result.stats.staticKeysChecked).toBe(1);
  });

  it("t.has(), t.rich() and getTranslations bindings resolve against their namespace", () => {
    const result = runChecks({
      en: EN,
      id: ID,
      files: [
        {
          path: "src/rich.tsx",
          text:
            'const t = useTranslations("sample");\n' +
            't.rich("greeting", { name });\n' +
            'if (t.has("items")) t("items", { count });\n' +
            'const t2 = await getTranslations("sample"); t2("greeting", { name });',
        },
      ],
    });
    expect(result.failures).toEqual([]);
    expect(result.stats.staticKeysChecked).toBe(4);
  });

  it("rule (b) is pinned negatively for te(), t.rich() and t.has() too", () => {
    const result = runChecks({
      en: EN,
      id: ID,
      files: [
        {
          path: "src/missing.tsx",
          text:
            'const te = useTranslations("errors"); te("NOPE_CODE");\n' +
            'const t = useTranslations("sample"); t.rich("gone.deeper", { name });\n' +
            'if (t.has("nothere")) t("greeting");',
        },
      ],
    });
    const unresolved = result.failures.filter((f) => f.kind === "unresolved-key");
    expect(unresolved.map((f) => f.key)).toEqual(["errors.NOPE_CODE", "sample.gone.deeper", "sample.nothere"]);
    expect(unresolved[0].message).toContain('te("NOPE_CODE")');
    expect(unresolved[1].message).toContain('t.rich("gone.deeper")');
    expect(unresolved[2].message).toContain('t.has("nothere")');
  });

  it("member calls on other objects are not attributed to a bound translator", () => {
    const result = runChecks({
      en: EN,
      id: ID,
      files: [
        {
          path: "src/member.tsx",
          text: 'const t = useTranslations("sample"); foo.t("not-a-key"); helper.has("also-not-a-key"); alert("t");',
        },
      ],
    });
    expect(result.failures).toEqual([]);
    expect(result.stats.staticKeysChecked).toBe(0);
  });

  it("useTranslations generic + t.raw() resolve against the bound namespace", () => {
    const result = runChecks({
      en: EN,
      id: ID,
      files: [
        {
          path: "src/generic.tsx",
          text:
            'const t = useTranslations<"sample">("sample");\n' +
            'const title = t.raw("items");\n' +
            'if (t.has("greeting")) t("greeting", { name });',
        },
      ],
    });
    expect(result.failures).toEqual([]);
    expect(result.stats.staticKeysChecked).toBe(3);
  });

  it("non-translation variables (Map/Set .has, unbound helpers) are not scanned", () => {
    const result = runChecks({
      en: EN,
      id: ID,
      files: [
        {
          path: "src/sets.ts",
          text: 'const seen = new Set<string>(); if (seen.has("definitely-not-a-key")) {} const other = makeT(); other("also-not-a-key");',
        },
      ],
    });
    expect(result.failures).toEqual([]);
    expect(result.stats.staticKeysChecked).toBe(0);
  });

  it("dynamic template-literal keys stay un-checkable and are skipped", () => {
    const result = runChecks({
      en: EN,
      id: ID,
      files: [
        {
          path: "src/dyn.tsx",
          // biome-ignore lint/suspicious/noTemplateCurlyInString: fixture simulates dynamic-key call-site source verbatim
          text: 'const t = useTranslations("sample"); t(`greeting${suffix}`); t(`locales.${id}`);',
        },
      ],
    });
    expect(result.failures).toEqual([]);
    expect(result.stats.staticKeysChecked).toBe(0);
  });

  it("an ambiguous binding is surfaced as a warning, not silently unchecked (Block-If)", () => {
    const result = runChecks({
      en: EN,
      id: ID,
      files: [
        {
          path: "src/ambiguous.tsx",
          text: 'const t = useTranslations("sample"); t("greeting", { name }); const t = useTranslations("errors"); t("NOPE_CODE");',
        },
      ],
    });
    expect(result.failures).toEqual([]);
    expect(result.stats.staticKeysChecked).toBe(0);
    expect(result.warnings.map((w) => w.kind)).toContain("ambiguous-binding");
  });

  it("a lone apostrophe is a literal: Don't {name} still sees {name} as an argument (no icu-arg-leak)", () => {
    const enApostrophe = structuredClone(EN);
    enApostrophe.sample.greeting = "Don't {name}"; // contraction must NOT swallow the arg
    const result = runChecks({ en: enApostrophe, id: ID, files: [] });
    expect(result.failures).toEqual([]);
    // Same leaf with a real id-side leak must still fail — apostrophe handling
    // did not desaturate the checker.
    const idLeak = structuredClone(ID);
    idLeak.sample.greeting = "Jangan {nama}, halo {name}";
    const leaked = runChecks({ en: enApostrophe, id: idLeak, files: [] });
    expect(leaked.failures.map((f) => [f.kind, f.key])).toEqual([["icu-arg-leak", "sample.greeting"]]);
  });

  it("an escaped '{name}' is literal text; '' is an escaped apostrophe", () => {
    // en literally contains the TEXT "{name}" (escaped) — no argument expected,
    // so an id leaf with a real {name} arg leaks (arg absent from en).
    const enEscaped = structuredClone(EN);
    enEscaped.sample.greeting = "Pass '{name}' verbatim!";
    const result = runChecks({ en: enEscaped, id: ID, files: [] });
    expect(result.failures.map((f) => [f.kind, f.key])).toEqual([["icu-arg-leak", "sample.greeting"]]);

    // '' doubled apostrophe: en "Don''t {name}" keeps {name} as an arg.
    const enDoubled = structuredClone(EN);
    enDoubled.sample.greeting = "Don''t {name}";
    expect(runChecks({ en: enDoubled, id: ID, files: [] }).failures).toEqual([]);
  });

  it("non-string leaves (number, array, null) fail in either catalog", () => {
    const enNumber = structuredClone(EN);
    (enNumber.sample as Record<string, unknown>).items = 7;
    const numResult = runChecks({ en: enNumber, id: ID, files: [] });
    expect(numResult.failures.some((f) => f.kind === "non-string-leaf" && f.key === "sample.items")).toBe(true);

    const idArray = structuredClone(ID);
    (idArray.sample as Record<string, unknown>).greeting = ["a", "b"];
    const arrResult = runChecks({ en: EN, id: idArray, files: [] });
    expect(arrResult.failures.some((f) => f.kind === "non-string-leaf" && f.key === "sample.greeting")).toBe(true);

    const enNull = structuredClone(EN);
    (enNull.sample as Record<string, unknown>).items = null;
    expect(runChecks({ en: enNull, id: ID, files: [] }).failures.some((f) => f.kind === "non-string-leaf")).toBe(true);
  });

  it("an array catalog root does not crash (key-asymmetry reports the whole shape)", () => {
    expect(() => runChecks({ en: [] as unknown as Fixture, id: ID, files: [] })).not.toThrow();
    expect(() => runChecks({ en: 5 as unknown as Fixture, id: ID, files: [] })).not.toThrow();
  });
});

describe("check-translations CLI (AC4 exit-code contract)", () => {
  const repoRoot = path.resolve(__dirname, "..", "..");

  it("node scripts/check-translations.mjs exits 0 on the real repo", async () => {
    const { execFile } = await import("node:child_process");
    const { promisify } = await import("node:util");
    const execFileAsync = promisify(execFile);
    const { stdout } = await execFileAsync("node", ["scripts/check-translations.mjs"], { cwd: repoRoot });
    expect(stdout).toContain("OK: no hard failures.");
  });

  it("--json emits a machine report that parses and matches the test gate", async () => {
    const { execFile } = await import("node:child_process");
    const { promisify } = await import("node:util");
    const execFileAsync = promisify(execFile);
    const { stdout } = await execFileAsync("node", ["scripts/check-translations.mjs", "--json"], { cwd: repoRoot });
    const report = JSON.parse(stdout);
    expect(report.ok).toBe(true);
    expect(report.failures).toEqual([]);
    expect(report.stats.staticKeysChecked).toBeGreaterThan(1000);
  });
});

describe("runChecks — real catalogs + src/ scan (gate)", () => {
  // Same file filter the CLI applies: app code only, no tests or d.ts.
  const srcDir = path.resolve(__dirname, "..");
  const files = collectSourceFiles(srcDir).map((full) => ({
    path: path.relative(path.resolve(srcDir, ".."), full).replaceAll("\\", "/"),
    text: readFileSync(full, "utf8"),
  }));

  it("scans the expected surface", () => {
    expect(files.length).toBeGreaterThan(100);
    expect(files.some((f) => f.text.includes("useTranslations("))).toBe(true);
  });

  it("the repo has no hard failures (AC1)", () => {
    const { failures, warnings } = runChecks({
      en: enMessages as Catalog,
      id: idMessages as Catalog,
      files,
    });
    // Print the review lists so a test run doubles as the tooling report (NFR-020).
    const identical = warnings.filter((w) => w.kind === "identical-value");
    console.log(`[23-4] warnings — identical en/id values: ${identical.length} (terminology review list)
${identical.map((w) => `  - ${w.key}`).join("\n")}`);
    const argOmitted = warnings.filter((w) => w.kind === "en-arg-omitted");
    console.log(`[23-4] warnings — en ICU args omitted in id: ${argOmitted.length}
${argOmitted.map((w) => `  - ${w.key}`).join("\n")}`);
    expect(
      failures,
      `translation completeness failures:\n${failures.map((f) => `  [${f.kind}] ${f.message}`).join("\n")}`,
    ).toEqual([]);
  });
});
