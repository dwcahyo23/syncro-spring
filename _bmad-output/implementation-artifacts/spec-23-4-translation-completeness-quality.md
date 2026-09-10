---
title: 'Story 23-4: Translation Completeness & Quality'
type: 'feature'
created: '2026-09-10'
status: 'done'
baseline_revision: 'e0d05eeac2efc5efc04be81a8c68c876b6e8ee10'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-23-context.md'
  - 'syncro/apps/web/AGENTS.md'
warnings: []
deferred:
  - summary: >-
      Locale-allowlist for the identical-value warning (spec matrix row 5 says
      "not brand/technical"): brand names and code-like values legitimately stay
      identical, so the 120-leaf review list is noisier than intended. Triage now
      is manual.
    evidence: >-
      review pass: implemented the warning per spec's Always bullet (byte-identical,
      no filter); the matrix's parenthetical was not spec'd into a mechanism and
      Never clause assigns triage to product owner. Adding an allowlist is a
      policy change for whoever curates the list.
    location: >-
      scripts/check-translations.mjs (identical-value check)
    severity: low
  - summary: >-
      Static-scan blind spots documented, not eliminated: calls whose translator
      variable is bound from a non-literal/argument (props.t, helper factory) and
      runtime-built namespace arguments stay unchecked; ambiguous-binding now warns
      but multi-binding files still lose key checks.
    evidence: >-
      review pass (intent-alignment divergence 3, blind-hunter finding 2/3): repo
      has zero such call sites today (verified 2073/0 false positives); regex scan
      is the sanctioned mechanism — an AST parse would need a dependency (spec
      forbids).
    location: >-
      scripts/check-translations.mjs header note
    severity: low
  - summary: >-
      120 byte-identical en/id leaves await terminology triage (incl. 1
      en-arg-omitted: systemHealth.dataQuality.windowMinutes dropped {unit} in
      id) — the review list NFR-020 exists to surface; copy edits are product
      decisions, deferred per the Never clause.
    evidence: >-
      check:i18n report on the delivered repo.
    location: >-
      src/messages/id.json
    severity: low
---

<intent-contract>

## Intent

**Problem:** Epic 23 ships with ~2,000 keys in two catalogs, but the only guard is en-vs-id key parity; ICU placeholder drift between locales, empty values, and literal `t("key")` calls whose key exists in no catalog can each leak raw text to users, and no tooling report exists for translation review (NFR-020).

**Approach:** One shared completeness checker (`scripts/check-translations.mjs`, zero new deps) that loads the catalogs + scans `src/` for statically-referenced message keys and compares ICU arguments; runnable as `npm run check:i18n` (exit 1 on hard failures, warnings reported non-fatally) AND imported by a vitest so the existing test suite is the failing gate (the repo has no CI runner; `test:unit` is the CI surface). Export `mergeMessages` from `src/i18n/request.ts` (behavior-unchanged) and add a unit test pinning the AC "missing key in active locale falls back to English" — plus an end-to-end render test proving the merged catalog never renders a raw key for an id-missing leaf.

## Boundaries & Constraints

**Always:**
- Frontend-only; catalogs are data — the checker reads them, never rewrites them
- Hard failures (exit 1): (a) en/id key-set asymmetry (duplicates the parity test's rule inside the tool so the tool alone is authoritative), (b) any `t("a.b.c")` / `t.has("...")` / errors-`te("...")` static literal whose key is absent from BOTH or either catalog namespace, (c) a locale string referencing an ICU argument (`{name}`) not present in the en reference (risk of literal-token leak — en is the call-site contract), (d) empty/whitespace-only leaf values
- Warnings (non-fatal, printed): id leaf byte-identical to en (terminology review list), en argument omitted in id (possibly intentional)
- Dynamic keys (`t(\`x.${y}\`)`) stay un-checkable statically — out of scope, as today
- Checker core is a pure function (`runChecks({en, id, files})`) so the vitest can feed synthetic fixtures; CLI wraps fs/exit
- Existing `catalog-parity.test.ts` and `display-edge.test.tsx` keep passing; test:unit suite remains the gate
- Node built-ins only (fs, path, process); mjs like the sibling `fetch-openapi.mjs`

**Block If:**
- The static key scan proves unable to resolve next-intl namespace bindings reliably across the 90-file tree without a parser dependency → HALT (regex scan is the chosen mechanism; if it can't be made sound, surface the decision)
- Fallback behavior in `request.ts` turns out to NOT actually prevent raw-key render (AC1 broken today) with no in-scope fix → HALT and report

**Never:**
- No CI workflow/husky-hook creation — the repo has no runner and fabricating deployment infra is out of a frontend story's scope; the gate is the npm script + test suite
- No changes to `mergeMessages` semantics, routing, proxy, or any component
- No new runtime/dev dependencies
- No bulk catalog cleanup based on warnings (identical-value triage is product-owner copy work)

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| id missing a key en has | synthetic catalogs | runChecks reports it under key-asymmetry failures | exit 1 / test fail |
| `t("nope.missing")` literal in src | synthetic file | unresolved-key failure listing file | exit 1 |
| id string uses `{extra}` absent in en | synthetic | argument-leak failure | exit 1 |
| en uses `{unit}`, id omits | synthetic | warning only | exit 0 |
| id leaf === en leaf (not brand/technical) | real catalogs | warning list (count + keys) | exit 0 |
| empty/whitespace value | synthetic | failure | exit 1 |
| `te("VALIDATION_ERROR")` errors ns | real src | resolves (no false positive) | — |
| key missing in id at runtime (real app) | merged catalog render | English fallback string renders | pinned by test |
| `npm run check:i18n` on repo as-is | current state | exit 0 with warnings summary | — |

</intent-contract>

## Code Map

- `syncro/apps/web/scripts/fetch-openapi.mjs` -- mjs script pattern + node built-ins style; new `check-translations.mjs` sits beside it
- `syncro/apps/web/src/messages/catalog-parity.test.ts` -- existing key-symmetry + errors-drift test; keep (redundancy is deliberate), new completeness test lives beside it
- `src/i18n/request.ts:19-31` -- `mergeMessages` (English-fallback merge) -- export unchanged; test it directly + an `enMessages`-missing-key render assertion via `@/test/i18n-wrapper` style provider fed with `mergeMessages(en, partialId)`
- Scan targets: `useTranslations("ns")` / `getTranslations("ns")` bindings → `t(`/`te(`/`.has(` call literals; known-good real-world cases to survive: `errors` UPPER_SNAKE via te (login-form.tsx:16), `navigation.languageSwitcher.locales.${id}` template (skipped), `t.rich` keys (alert-detail), default-namespace calls? (none — every call binds a namespace; verify)
- ICU extraction: brace-depth tokenizer; handle `{count, plural, one {…} other {…}}` incl. `#`; arguments = top-level names before first comma/brace; `@format` and markup tags (`<sparepart>{x}</sparepart>`) must not false-positive
- `package.json` scripts block -- add `check:i18n`

## Tasks & Acceptance

**Execution:**
- `scripts/check-translations.mjs` -- create -- pure `runChecks({en, id, files: [{path, text}]})` → `{failures, warnings}` + CLI (`--json`, human report, exit codes); namespace-binding + `.has`/`t.rich`/`te` handling; dynamic-template skip
- `src/messages/translation-completeness.test.ts` -- create -- imports runChecks: synthetic-fixture unit arms for every hard-fail class + warning class (matrix rows 1–6), and the REAL catalogs+src scan as the gate test (`expect(failures).toEqual([])`), warnings printed via console
- `src/i18n/request.ts` -- extend -- `export function mergeMessages` (additive keyword only); comment update if needed
- `src/i18n/request.test.ts` -- create -- mergeMessages: deep id wins over en, en fills id-missing branches, primitives replace, array passthrough; render arm: `<NextIntlClientProvider locale="id" messages={mergeMessages(en, idMinusOneKey)}>` asserting the English string shows and no raw key text appears anywhere in the DOM
- `package.json` -- extend -- `"check:i18n": "node scripts/check-translations.mjs"`

**Acceptance Criteria:**
- Given a missing key either side, an unresolved static `t()` literal, an ICU argument leak, or an empty value, when the checker runs (CLI or `test:unit`), then it fails listing file/key — and the current repo passes (AC1)
- Given warnings (identical-value, en-only-args), then they are reported without failing (AC2)
- Given a key missing from id at runtime, then the merged catalog renders the English fallback, never a raw key — pinned by test (AC3)
- Given `npm run check:i18n`, exit 0; `test:unit` green incl. new files; build/lint unchanged (AC4)

## Spec Change Log

## Review Triage Log

### 2026-09-10 — Review pass 1
- intent_gap: 0
- bad_spec: 0
- patch: 9 (high 1, medium 3, low 5)
- defer: 3 (low 3)
- reject: 6
- addressed_findings:
  - `[high]` `[patch]` P1 checker soundness: `icuArgs` treated every `'` as a quoted-run start, so `Don't {name}` swallowed the `{name}` argument — a future id `Halo {name}` would then be a FALSE `icu-arg-leak` failure (gate breaks on ordinary English copy). Rewrote to intl-messageformat 2.0 rules (`''` and `'`+`{ } [ ] #` are escapes; other `'` is literal). Same patch: `t.raw` added to CALL_RE methods; `(?<![\w$.])` lookbehind so `i18n.t("x")`/`obj.t("x")` can't be attributed to a bound `t`; `useTranslations<"ns">("ns")` generics bind; negative fixtures pin unresolved-key for `te("NOPE_CODE")`/`t.rich`/`t.has` (rule b was only proven for plain `t()` before).
  - `[medium]` `[patch]` P2 CLI exit surface untested (AC4's exit-code contract rested on manual runs only): added a describe spawning `node scripts/check-translations.mjs` via `promisify(execFile)` asserting stdout "OK: no hard failures." and a `--json` run parsed with `ok === true`, `staticKeysChecked > 1000`.
  - `[medium]` `[patch]` P3 the `namespaces.size > 1` skip silently dropped every call of an ambiguously-bound variable (spec Block-If requires surfacing the decision): now pushes an `ambiguous-binding` warning per (file, variable); fixture pins warning-present + `staticKeysChecked === 0`.
  - `[medium]` `[patch]` P4 non-string leaves handled inconsistently (arrays flattened to unsupported `list.0` keys; numbers/booleans/null invisible to all checks while `lookupLeaf` then reported false unresolved-key): `leafEntries` returns `{out, nonStrings}`, array/number/boolean/null leaves become `non-string-leaf` hard failures, `hasKey` counts them present so they fail once under their own rule; `main()` rejects non-object parsed catalogs (exit 1).
  - `[low]` `[patch]` P5 request-config wiring unpinned (if the default export stopped calling mergeMessages all 23-4 tests stayed green): identity mock of `next-intl/server`'s `getRequestConfig` makes the default export the callback; test invokes it with locale stubbed "id" and asserts `config.messages` deep-equals `mergeMessages(en, id)` on the real catalogs.
  - `[low]` `[patch]` P6 `request.ts` ponytail comment claimed "~35 keys"; this story measured 2,006 leaves/catalog — comment updated to the measured number with the accepted trade-off and revisit condition.
  - `[low]` `[patch]` P7 render test hardcoded `toBe("Cancel")` against live en.json copy (translator edits would break the gate): fixture now built from the real `idMessages` import with the expected fallback captured from the real `enMessages` import, plus a non-vacuity guard.
  - `[low]` `[patch]` P8 nondeterministic output: `runChecks` sorts failures and warnings by `(kind, file, key)` before returning — CLI report, `--json`, and test prints stable across runs/platforms.
  - `[low]` `[patch]` P9 capability undocumented: README.md gained a "Translation completeness (Story 23-4)" section — `npm run check:i18n` (+`--json`), the failure classes, and all three warning kinds with their review meaning.
- rejected (noted, not actioned):
  - "brand/technical filter for identical-value warnings" — spec's Always bullet defines the warning as byte-identical with no filter and the Never clause assigns triage to product owner; the matrix parenthetical has no mechanism in the intent. Deferred as a policy note instead.
  - "AST parser for the scan" — spec sanctions regex and forbids new dependencies; the soundness obligation was met by narrowing (P1) + surfacing ambiguity (P3).
  - "catalog orphan detection" — spec explicitly scoped key checks code→catalog only (dynamic keys would false-positive orphans).
  - "persist a report artifact / CI workflow" — Never clause; `test:unit` is the gate, `--json` is the machine surface.
  - "comment/string-literal stripping in scanned files" — no false positive exists today (2073 refs, 0 failures); commented-out stale keys failing the gate is arguably correct behavior.
  - "symlink-loop guard in collectSourceFiles" — scans a fixed repo-relative `src/`; no symlinked dirs exist and the CLI is not a general-purpose crawler.

## Design Notes

- The gate is `test:unit` (vitest) — this repo has no CI runner and adding deployment infra inside a frontend story is scope creep; the npm script gives humans/any future CI the same signal with a readable report.
- en is treated as the call-site contract: components were written English-first (23-2), so an ICU argument absent from en cannot be supplied by callers → id-only `{token}` = guaranteed literal leak = hard failure; the reverse direction is merely stylistic → warning.
- Key-existence check is one-directional (code→catalog): catalog orphans stay invisible by design (dynamic keys would false-positive them; 23-2 already shipped a full catalog with deliberate near-orphan tolerance).
- Checker duplicates parity-test rules on purpose: the standalone tool must be usable by a human without running vitest.

## Verification

**Commands:**
- `npm --prefix syncro/apps/web run check:i18n` -- expected: exit 0, warnings summary printed
- `npm --prefix syncro/apps/web run test:unit` -- expected: green except deferred baseline `system-health-page 6-6-AC3`
- `npm --prefix syncro/apps/web run build` -- expected: webpack green incl. type-check
- `npm --prefix syncro/apps/web run lint` -- expected: 12 pre-existing errors, none in touched files

## Auto Run Result

Status: done

**Summary of implemented change:** Translation completeness tooling (NFR-020) — `scripts/check-translations.mjs`: a pure `runChecks({en, id, files})` core (zero deps, node built-ins) + CLI (`npm run check:i18n`, `--json`, exit 1 on hard failures) enforcing key asymmetry, unresolved static `t()`/`t.has()`/`t.rich()`/`t.raw()`/`te()` literals via namespace-binding resolution, id-only ICU argument leaks, empty and non-string leaves; warnings (non-fatal): identical en/id values, en-arg-omitted, ambiguous-binding. `src/messages/translation-completeness.test.ts` feeds it synthetic fixtures for every matrix row plus the REAL catalogs+src scan as the `test:unit` gate (the repo's CI surface — no runner exists). `mergeMessages` exported from `src/i18n/request.ts` (additive) with unit + wiring + render tests proving the English-fallback contract (AC3: missing id leaf renders en value, never a raw key). README documents the command and warning semantics.

**Files changed (7):** `scripts/check-translations.mjs` (new), `src/messages/translation-completeness.test.ts` (new, 23 tests), `src/i18n/request.test.ts` (new, 6 tests), `src/i18n/request.ts` (export + comment), `package.json` (`check:i18n`), `README.md` (docs section), spec artifact.

**Review findings breakdown (pass 1):** 9 patches applied (1 high — `icuArgs` apostrophe rule that would false-fail the gate on `Don't {name}` copy + missing `t.raw`/dotted-call/generic-binding soundness; 3 medium — CLI exit-code surface untested, silent ambiguous-binding skip, non-string-leaf inconsistency; 5 low — request-config wiring pin, stale "~35 keys" comment, hardcoded copy in render test, output sorting, README docs), 3 deferred (frontmatter `deferred:`), 6 rejected.

**Follow-up review recommendation:** true — 1 patched finding was `high`; score `3×3 + 1×5 = 14` (≥5 threshold), plus the high-severity rule.

**Verification performed:** `npm run check:i18n` exit 0 — 2006 en / 2006 id leaves, 293 files, 2073 static key references, 0 failures, 121 warnings (120 identical-value review list, 1 en-arg-omitted). `npm run test:unit` 419/420 (sole failure = deferred baseline `system-health-page 6-6-AC3`). `npx tsc --noEmit` clean. `npm run build` ✓. `npm run lint`: 12 pre-existing errors in repo files + 3 extra from five untracked `__probe*_23_4.mjs` review-pass scratch files in `syncro/apps/web/` — deletion was denied by user policy, so they remain on disk untracked and were NOT committed; user should remove them.

**Residual risks:** regex scan is sound-by-narrowing — non-literal bindings and runtime-built namespaces stay unchecked (documented, zero occurrences today); the `getRequestConfig` wiring pin tests the callback seam under an identity mock, not next-intl's real RSC wrapper (unreachable outside a Next build); the 120-leaf identical-value list awaits product-owner terminology triage.

