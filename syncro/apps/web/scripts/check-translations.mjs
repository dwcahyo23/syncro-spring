// Story 23-4: translation completeness & quality checker.
//
// Loads the en/id message catalogs, scans src/ for statically-referenced
// next-intl message keys (`useTranslations("ns")` / `getTranslations("ns")`
// bindings resolved against literal `t(` / `t.has(` / `t.rich(` / `t.raw(` /
// `te(` calls), and compares ICU arguments between locales.
//
// Hard failures (exit 1):
//   key-asymmetry   — a leaf key present in one catalog but not the other
//   unresolved-key  — a static literal call whose key is absent from either catalog
//   icu-arg-leak    — an id string references `{arg}` absent from the en reference
//                     (en is the call-site contract; an id-only token can never be
//                     supplied by callers and would render literally)
//   empty-value     — an empty/whitespace-only leaf in either catalog
//   non-string-leaf — an array/number/boolean/null leaf in either catalog
//                     (next-intl can only render strings)
// Warnings (printed, non-fatal): id leaf byte-identical to en (terminology
// review list); an en argument omitted in id (possibly intentional); a
// translation variable bound to two different namespaces in one file (its
// calls cannot be resolved, so they are skipped and surfaced here).
//
// The catalog file keys/values are never rewritten — catalogs are data.
// Dynamic keys (`t(`x.${y}`)`) are un-checkable statically and are skipped.
//
// Core logic is the pure `runChecks({en, id, files})`; the CLI below wraps
// fs + exit codes so a human can run `npm run check:i18n` without vitest.
// `src/messages/translation-completeness.test.ts` imports runChecks and uses
// the real-catalog scan as the `test:unit` gate.
//
// Usage: node scripts/check-translations.mjs [--json]

import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

/**
 * @typedef {{ kind: string, message: string, file?: string, key?: string }} Issue
 * @typedef {{ failures: Issue[], warnings: Issue[], stats: Record<string, number> }} CheckResult
 */

/**
 * Flatten a catalog into dotted leaf-key → string-value pairs, collecting
 * array/number/boolean/null leaves as nonStrings (next-intl renders only
 * strings — an array leaf like `list.0` is not a supported shape).
 */
function leafEntries(catalog, prefix = "", out = {}, nonStrings = []) {
  if (!catalog || typeof catalog !== "object") return { out, nonStrings };
  for (const [key, value] of Object.entries(catalog)) {
    const full = prefix ? `${prefix}.${key}` : key;
    if (typeof value === "string") out[full] = value;
    else if (value && typeof value === "object" && !Array.isArray(value)) leafEntries(value, full, out, nonStrings);
    else nonStrings.push({ key: full, type: leafTypeName(value) });
  }
  return { out, nonStrings };
}

function leafTypeName(value) {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  return typeof value;
}

/**
 * Whether `ns` + dotted `key` resolves to any leaf in the catalog. Non-string
 * leaves count as present — they are reported as non-string-leaf failures by
 * their own rule, and a code reference to them must not double-report as an
 * "absent" unresolved-key.
 */
function hasKey(catalog, ns, key) {
  let node = catalog;
  for (const seg of [...ns.split("."), ...key.split(".")].filter(Boolean)) {
    if (!node || typeof node !== "object" || !(seg in node)) return false;
    node = node[seg];
  }
  return true;
}

/**
 * Top-level ICU MessageFormat argument names of a message string: identifiers
 * opening a `{name` that is followed by `,` or `}`. Plural/select branch keys
 * (`one {`, `other {`) are not arguments; `#` needs no handling; `<markup>` tags
 * and `@format` never produce a `{ident[,}]` so they cannot false-positive.
 * Apostrophes follow the MessageFormat 2.0 / intl-messageformat rules next-intl
 * implements: `''` is an escaped apostrophe, `'` escapes a following special
 * char (`{ } [ ] #`) making it literal, and any other `'` (English
 * contractions, "Don't {name}") is a plain literal — it does NOT swallow the
 * rest of the message as a quoted run.
 */
function icuArgs(text) {
  const args = new Set();
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === "'") {
      const next = text[i + 1];
      if (next === "'" || next === "{" || next === "}" || next === "[" || next === "]" || next === "#") i++;
      continue;
    }
    if (ch === "{") {
      const m = /^\{\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*[,}]/.exec(text.slice(i));
      if (m) args.add(m[1]);
    }
  }
  return args;
}

// `const t = useTranslations("ns")` / `const t = await getTranslations("ns")`,
// with an optional TypeScript generic (`useTranslations<"ns">("ns")`) between
// the name and the paren. Only double/single-quoted literals bind a namespace —
// a non-literal argument would make the calls ambiguous, and the repo has none.
const BINDING_RE =
  /\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:await\s+)?(?:use|get)Translations\s*(?:<[^<>()]*>)?\s*\(\s*(["'])((?:(?!\2)[^\\]|\\.)*?)\2\s*\)/g;

// `t("key")`, `t.rich("key", …)`, `t.raw("key", …)`, `t.has("key")`,
// `te("CODE", …)` — static string literals only; template literals and
// concatenations never match (dynamic keys are out of scope by design).
// The negative lookbehind keeps `i18n.t("x")` / `obj.has("x")` from resolving
// against a variable bound to a namespace — those are member calls on some
// other object — and keeps tail matches like `alert(` (name "t") out.
const CALL_RE =
  /(?<![\w$.])([A-Za-z_$][\w$]*)(?:\.(rich|raw|markup|html|parts|has))?\s*\(\s*(["'])((?:(?!\3)[^\\]|\\.)*?)\3\s*[,)]/g;

/**
 * Pure checker core: catalogs + scanned files in, `{failures, warnings, stats}`
 * out. Fed by the CLI (real fs) and by vitest (real scan + synthetic fixtures).
 *
 * @param {{ en: object, id: object, files: {path: string, text: string}[] }} input
 * @returns {CheckResult}
 */
export function runChecks({ en, id, files }) {
  /** @type {Issue[]} */
  const failures = [];
  /** @type {Issue[]} */
  const warnings = [];

  const { out: enLeaves, nonStrings: enNonStrings } = leafEntries(en);
  const { out: idLeaves, nonStrings: idNonStrings } = leafEntries(id);
  const enKeys = Object.keys(enLeaves);
  const idKeys = Object.keys(idLeaves);

  // (a) key-set asymmetry — the rule catalog-parity.test.ts checks, duplicated
  // here deliberately so the standalone tool is authoritative on its own.
  const idKeySet = new Set(idKeys);
  for (const key of enKeys) {
    if (!idKeySet.has(key))
      failures.push({ kind: "key-asymmetry", key, message: `"${key}" present in en.json, missing from id.json` });
  }
  const enKeySet = new Set(enKeys);
  for (const key of idKeys) {
    if (!enKeySet.has(key))
      failures.push({ kind: "key-asymmetry", key, message: `"${key}" present in id.json, missing from en.json` });
  }

  // Non-string leaves: next-intl renders only strings — an array/number/
  // boolean/null leaf would throw at render time, so it fails hard.
  for (const [locale, leaves] of [
    ["en", enNonStrings],
    ["id", idNonStrings],
  ]) {
    for (const { key, type } of leaves) {
      failures.push({ kind: "non-string-leaf", key, message: `${locale}.json "${key}" is a ${type}, not a string` });
    }
  }

  // (d) empty/whitespace-only leaf values.
  for (const [locale, leaves] of [
    ["en", enLeaves],
    ["id", idLeaves],
  ]) {
    for (const key of Object.keys(leaves)) {
      if (leaves[key].trim() === "") {
        failures.push({ kind: "empty-value", key, message: `${locale}.json "${key}" is empty or whitespace-only` });
      }
    }
  }

  // (c) ICU argument comparison + identical-value warning, per shared leaf.
  for (const key of enKeys) {
    if (!(key in idLeaves)) continue; // asymmetry already failed above
    const enArgs = icuArgs(enLeaves[key]);
    const idArgs = icuArgs(idLeaves[key]);
    for (const arg of idArgs) {
      if (!enArgs.has(arg)) {
        failures.push({
          kind: "icu-arg-leak",
          key,
          message: `id.json "${key}" references {${arg}} absent from the en reference (callers can never supply it)`,
        });
      }
    }
    for (const arg of enArgs) {
      if (!idArgs.has(arg)) {
        warnings.push({
          kind: "en-arg-omitted",
          key,
          message: `en.json "${key}" uses {${arg}} which id.json omits (possibly intentional)`,
        });
      }
    }
    if (idLeaves[key] === enLeaves[key]) {
      warnings.push({ kind: "identical-value", key, message: `"${key}" identical in en/id — "${enLeaves[key]}"` });
    }
  }

  // (b) static literal message keys must exist in BOTH catalogs under the
  // namespace their function is bound to.
  let staticKeysChecked = 0;
  const ambiguousReported = new Set();
  for (const file of files) {
    const namespacesByVar = new Map();
    for (const match of file.text.matchAll(BINDING_RE)) {
      const [, name, , ns] = match;
      if (!namespacesByVar.has(name)) namespacesByVar.set(name, new Set());
      namespacesByVar.get(name).add(ns);
    }
    for (const match of file.text.matchAll(CALL_RE)) {
      const [, name, method, , key] = match;
      const namespaces = namespacesByVar.get(name);
      if (!namespaces) continue; // not a translation function (Map/Set `.has` etc.)
      if (namespaces.size > 1) {
        // Same var bound to different namespaces in one file — its calls cannot
        // be resolved; skip them but SURFACE the decision (spec Block-If: an
        // unreliable binding must not fail silently).
        const id = `${file.path}#${name}`;
        if (!ambiguousReported.has(id)) {
          ambiguousReported.add(id);
          warnings.push({
            kind: "ambiguous-binding",
            file: file.path,
            message: `${file.path}: ${name} bound to multiple namespaces (${[...namespaces].join(", ")}) — its key references are unchecked`,
          });
        }
        continue;
      }
      const [ns] = namespaces;
      staticKeysChecked++;
      const display = `${name}${method ? `.${method}` : ""}("${key}")`;
      const inEn = hasKey(en, ns, key);
      const inId = hasKey(id, ns, key);
      if (!inEn || !inId) {
        const where = [!inEn && "en.json", !inId && "id.json"].filter(Boolean).join(", ");
        failures.push({
          kind: "unresolved-key",
          file: file.path,
          key: ns ? `${ns}.${key}` : key,
          message: `${display} in ${file.path} resolves to "${ns ? `${ns}.${key}` : key}" which is absent from ${where}`,
        });
      }
    }
  }

  // Stable output (kind → file → key) so CLI report, --json, and test prints
  // are identical across runs and platforms.
  const byKindFileKey = (a, b) =>
    a.kind.localeCompare(b.kind) ||
    (a.file ?? "").localeCompare(b.file ?? "") ||
    (a.key ?? "").localeCompare(b.key ?? "");
  failures.sort(byKindFileKey);
  warnings.sort(byKindFileKey);

  return {
    failures,
    warnings,
    stats: {
      enLeaves: enKeys.length,
      idLeaves: idKeys.length,
      filesScanned: files.length,
      staticKeysChecked,
    },
  };
}

// ---------------------------------------------------------------- CLI wrapper
// Guarded so translation-completeness.test.ts can import runChecks without
// executing the CLI (exit codes, fs reads) inside vitest.

/**
 * Mirror of the vitest scan filter: app code only, no tests or declarations.
 * @param {string} dir
 * @returns {string[]}
 */
export function collectSourceFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    if (entry === "node_modules" || entry === ".next" || entry === "generated" || entry === "dist" || entry === "out")
      continue;
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...collectSourceFiles(full));
    else if (/\.(ts|tsx)$/.test(entry) && !/\.test\./.test(entry) && !entry.endsWith(".d.ts")) out.push(full);
  }
  return out;
}

/** True for a JSON object (not null, not an array). */
function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function main() {
  const cli = process.argv.includes("--json");
  if (process.argv.includes("--help")) {
    console.log("Usage: node scripts/check-translations.mjs [--json]");
    return 0;
  }

  const root = path.resolve(import.meta.dirname, "..");
  const srcDir = path.join(root, "src");

  let en;
  let id;
  try {
    en = JSON.parse(readFileSync(path.join(srcDir, "messages", "en.json"), "utf8"));
    id = JSON.parse(readFileSync(path.join(srcDir, "messages", "id.json"), "utf8"));
  } catch (error) {
    console.error(`check-translations: cannot load catalogs: ${error.message}`);
    return 1;
  }
  if (!isPlainObject(en) || !isPlainObject(id)) {
    console.error("check-translations: catalogs must be JSON objects");
    return 1;
  }

  const files = collectSourceFiles(srcDir).map((full) => ({
    path: path.relative(root, full).replaceAll("\\", "/"),
    text: readFileSync(full, "utf8"),
  }));

  const { failures, warnings, stats } = runChecks({ en, id, files });

  if (cli) {
    console.log(JSON.stringify({ ok: failures.length === 0, failures, warnings, stats }, null, 2));
    return failures.length === 0 ? 0 : 1;
  }

  const MAX_SHOWN = 20;
  const byKind = (issues) => {
    const groups = new Map();
    for (const issue of issues) {
      if (!groups.has(issue.kind)) groups.set(issue.kind, []);
      groups.get(issue.kind).push(issue);
    }
    return groups;
  };

  console.log(
    `check-translations: ${stats.enLeaves} en / ${stats.idLeaves} id leaves · ` +
      `${stats.filesScanned} files scanned · ${stats.staticKeysChecked} static key references`,
  );

  if (failures.length > 0) {
    console.log(`\nFAILURES (${failures.length}):`);
    for (const [kind, group] of byKind(failures)) {
      for (const issue of group.slice(0, MAX_SHOWN)) console.log(`  [${kind}] ${issue.message}`);
      if (group.length > MAX_SHOWN) console.log(`  … +${group.length - MAX_SHOWN} more [${kind}] (see --json)`);
    }
    console.log(`\nTranslation completeness check failed: ${failures.length} hard failure(s).`);
    return 1;
  }

  if (warnings.length > 0) {
    console.log(`\nWarnings (${warnings.length}) — reported, not failing:`);
    for (const [kind, group] of byKind(warnings)) {
      const label = kind === "identical-value" ? "identical en/id values (terminology review list)" : kind;
      console.log(`  ${label}: ${group.length}`);
      for (const issue of group.slice(0, MAX_SHOWN)) console.log(`    - ${issue.key ?? issue.message}`);
      if (group.length > MAX_SHOWN)
        console.log(`    … +${group.length - MAX_SHOWN} more (run with --json for the full list)`);
    }
  }

  console.log("\nOK: no hard failures.");
  return 0;
}

// Only run the CLI when executed directly (`npm run check:i18n`); importing
// runChecks from vitest must not read fs or call process.exit.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exit(main());
}
