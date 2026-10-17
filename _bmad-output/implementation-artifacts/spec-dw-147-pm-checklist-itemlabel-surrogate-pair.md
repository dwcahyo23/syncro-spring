---
title: 'Fix surrogate-pair crash in PmChecklistService.itemLabel (DW-147)'
type: 'bugfix'
created: '2026-10-17'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `PmChecklistService.itemLabel` (`syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmChecklistService.java:482-488`) guards with `text.length() <= 255` but truncates via `text.offsetByCodePoints(0, 255)`. When a checklist item's `parameter_text` is surrogate-heavy — UTF-16 `length()` above 255 while its code-point count stays at or below 255 — the guard passes, the truncation call overruns the string, and the mutation dies with `IndexOutOfBoundsException`. The identical bug was already fixed at the sibling site `PmExecutionService.itemLabel` (`PmExecutionService.java:629-638`, story 19-5, commit `fe13faad`), and that story's fixture comment (`PmExecutionServiceIntegrationTest.java:258-262`) names this remaining site explicitly. Because `itemLabel` feeds the `entity_label` of the audit row, the crash aborts the whole mutation — creating, updating, or deleting such an item fails, and the audit trail for it is never written.

**Approach:** Mirror the already-shipped fix at the sibling site: change the guard in `PmChecklistService.itemLabel` to compare `text.codePointCount(0, text.length())` against 255 instead of `text.length()`, and carry over the sibling's javadoc rationale. Add a regression test in `PmChecklistServiceIntegrationTest` covering the surrogate-heavy case that the existing `19.2-INT-017` (ASCII-only) does not reach.

</frozen-after-approval>

## Implementation Notes

- `PmChecklistService.itemLabel` (`:483-503`): guard swapped to `text.codePointCount(0, text.length()) <= MAX_ENTITY_LABEL_CODE_POINTS`; javadoc rewritten to name the exact exception/condition, the `requireText` ≤500 fact, the non-null invariant, and the grapheme limitation. The `255` literal became `MAX_ENTITY_LABEL_CODE_POINTS` (class constant). No null fallback added — `pm_checklist_items.parameter_text` is `VARCHAR(500) NOT NULL` (V1 schema) and `requireText` rejects null, so the sibling's `null → id` branch has no reachable state here.
- `PmChecklistServiceIntegrationTest`: two new tests mirroring `19.5-INT-017`. `19.2-INT-018` uses `"🔧".repeat(130)` (130 code points / 260 units) — the exact shape the old guard let through. `19.2-INT-019` covers the boundary with mixed fixtures: 244 emoji + 11 ASCII = 255 code points / 499 units (kept whole) and 244 emoji + 12 ASCII = 256 code points / 500 units (truncated to 255 code points / 499 units). A pure-emoji fixture cannot reach the truncation branch through `createItem` because `requireText` caps at 500 UTF-16 units.
- Red/green proven: with the old guard restored, `19.2-INT-018` fails with `IndexOutOfBoundsException` at `PmChecklistService.itemLabel` → `createItem`; with the fix the full class is 19/19 green.
- `PmExecutionServiceIntegrationTest` fixture comment updated — it still claimed the checklist site was unfixed.
- Scope note: the ledger's duplicate `DW-147` id (also used at line 1185 for the OPA CI entry) is left untouched — renaming ids is ledger hygiene, not part of this fix.

## Review Triage Log

Reviewer: blind-hunter (context-free), finding floor N=2 from ~1.4 kB changed content.

| # | Finding | Verdict | Evidence / action |
|---|---|---|---|
| 1 | New test only exercises the non-truncating branch; a regression in the truncation line would pass | medium | **patched** — added `19.2-INT-019` with a 256-code-point fixture that reaches `offsetByCodePoints` |
| 2 | A pure-emoji fixture cannot reach truncation through `createItem` (500-unit cap) | medium | same root cause as #1; **patched** with a mixed fixture (244 emoji + 12 ASCII = 256 code points / 500 units) |
| 3 | No boundary coverage at 255 vs 256 code points | medium | same root cause as #1; **patched** — `19.2-INT-019` pins exactly-255 kept whole and 256 truncated to 255 |
| 4 | `requireText` counts UTF-16 units while the label path counts code points | medium | **deferred** — pre-existing since 19-2, not caused by this change; aligning it loosens user-facing input validation, so it needs a product decision |
| 5 | `null` fallback from the sibling silently dropped | low | **patched** — javadoc now states the non-null invariant instead of advertising the sibling's fallback |
| 6 | Duplicated helper and javadoc left in place | medium | **deferred** — extracting a shared helper is a refactor beyond this bugfix; recorded so the next editor of either site sees it |
| 7 | Sibling test comment now false | low | **patched** — `PmExecutionServiceIntegrationTest.java:258-263` updated |
| 8 | New test comment said the old guard "passed" (inverted meaning) | low | **patched** — reworded to "did not fire" |
| 9 | Missing `19.2-INT-0NN` traceability id in `@DisplayName` | low | **patched** — now `19.2-INT-018` |
| 10 | Fixture assertion checked only one axis | low | **patched** — asserts both `length()` and `codePointCount` |
| 11 | `anySatisfy` weaker than the sibling's `singleElement` | low | **patched** — both new tests use `singleElement()` |
| 12 | Code-point-safe is not grapheme-safe; javadoc overstated | low | **patched** — javadoc now names the ZWJ/flag/combining-mark limitation |
| 13 | Javadoc imprecise and dropped the "≤500" fact | low | **patched** — names `IndexOutOfBoundsException`, the exact condition, and restores the `requireText` reference |
| 14 | Test covers only CREATE, not update/delete | low | **rejected** — `itemLabel` is a private static helper called identically from all three audit sites; the update path differs only in which command value it passes, which #1–#3 already cover through the same code path |

## Verification

**Commands:**
- `mvn -o test -Dtest='PmChecklistServiceIntegrationTest'` -- expected: 19 tests, 0 failures (was 18 before the two new tests)
- `mvn -o test -Dtest='PmExecutionServiceIntegrationTest#itemLabelTruncationIsCodePointSafe'` -- expected: 1 test, 0 failures (sibling unaffected)

**Red proof:** restoring the old `text.length() <= 255` guard makes `19.2-INT-018` fail with `IndexOutOfBoundsException` at `PmChecklistService.itemLabel` → `createItem`; the fixed guard is green.

