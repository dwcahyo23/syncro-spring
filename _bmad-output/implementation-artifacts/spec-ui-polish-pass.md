---
title: 'UI Polish Pass — Dialog Consistency & Native-Control Cleanup'
type: 'refactor'
created: '2026-08-27'
baseline_commit: f1b6e8e5df9677cde1409cb6e7378fcb23db261f
status: 'done'
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-master-data-tabs.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Dialogs across features render inconsistently — the sparepart create dialog is top-anchored with scroll + wide, while the category dialog and several master-data dialogs use the default centered small panel; and two files still use native `<select>`/`<input type="checkbox">` that clash with the shadcn theme. The user wants a consistent dialog presentation and native controls eliminated.

**Approach:** Standardize every `DialogContent` to the same top-anchored, scrollable, wide presentation used by the sparepart dialog (`top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl`), replace the remaining native `<select>` and raw `<input type="checkbox">` with shadcn `Select`/`Checkbox`, and align dialog headers/footers. Pure presentation; no data/API changes.

## Boundaries & Constraints

**Always:**
- **Dialog standard:** every feature `DialogContent` gets the same class string: `className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl"`. Files with a wide/stepper dialog (sparepart-management) keep their current class (already matches); files using plain `DialogContent` get the standard class added.
  - For small confirmation-style dialogs (if any are intentionally compact) keep them compact but ensure they still match the standard header/footer structure — assess per file; do not force width on genuinely tiny dialogs if it hurts usability (e.g. a 1-field prompt). The standard applies to CRUD create/edit dialogs.
- **Native control cleanup:**
  - `src/features/master-data/sections/section-management.tsx` — replace any raw `<input type="checkbox">` with shadcn `Checkbox`.
  - `src/features/organization/components/department-management.tsx` — replace any raw `<select>` with shadcn `Select`.
  - Re-scan `src/features/**` for any remaining native `<select>`/raw checkbox and fix.
- **Dialog header/footer alignment:** each CRUD dialog uses `DialogHeader` (with `DialogTitle` + `DialogDescription`) and `DialogFooter` (with a Cancel ghost/outline button + primary action). If a dialog omits `DialogDescription` or wraps buttons in a bare div instead of `DialogFooter`, align it.
- **Scope:** ONLY presentation changes in feature components. No state/logic/API/data changes. Do not touch already-consistent dialogs beyond the class string. Do not touch backend, rego, or tests that assert on rendered text (keep all visible strings identical — only class/structure changes).
- **Verification:** `tsc --noEmit` green; `biome check` clean; existing feature tests still pass (they assert visible text/structure, not class names).

**Ask First:** none.

**Never:**
- Never change visible text, labels, button text, or any string a test/user sees.
- Never alter form state, submission logic, or validation.
- Never change dialog open/close behavior or keyboard/a11y handling beyond the shadcn primitives.
- Never introduce a new component library or custom Dialog wrapper.
- Never touch backend, rego, or `.env.example`.
- Never restructure a dialog so its tests break (keep testids/roles/visible structure).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| DIALOG_STANDARD | any CRUD create/edit dialog | DialogContent uses standard top-anchored scrollable class | — |
| NATIVE_SELECT | section/department management | native `<select>`/checkbox replaced with shadcn primitives | — |
| DIALOG_HEADER | any dialog missing description/footer | DialogHeader + DialogFooter structure aligned | — |
| TEXT_PRESERVED | all dialogs | visible strings unchanged | — |

</frozen-after-approval>

## Code Map

**Files to update (presentation only):**
- `src/features/master-data/spareparts/sparepart-management.tsx` -- already standard (`top-4 max-h... sm:max-w-2xl`) — leave; verify.
- `src/features/master-data/spareparts/sparepart-taxonomy-management.tsx` -- MODIFY -- DialogContent gets standard class; header/footer align.
- `src/features/master-data/sections/section-management.tsx` -- MODIFY -- native checkbox → shadcn Checkbox; DialogContent class.
- `src/features/master-data/machines/machine-management.tsx` -- MODIFY -- DialogContent class.
- `src/features/master-data/machine-groups/machine-group-management.tsx` -- MODIFY -- DialogContent class.
- `src/features/master-data/plants/plant-management.tsx` -- MODIFY -- DialogContent class.
- `src/features/master-data/teams/team-management.tsx` -- MODIFY -- DialogContent class.
- `src/features/master-data/installations/installation-management.tsx` -- MODIFY -- DialogContent class.
- `src/features/master-data/responsibilities/responsibility-management.tsx` -- MODIFY -- DialogContent class.
- `src/features/organization/components/department-management.tsx` -- MODIFY -- native select → shadcn Select; DialogContent class.
- `src/features/organization/components/user-management.tsx` -- MODIFY -- DialogContent class.
- `src/features/sparepart-requests/components/request-part-dialog.tsx` -- MODIFY -- DialogContent class (already uses shadcn Select).
- `src/features/preventive/components/preventive-schedule-detail.tsx` -- MODIFY -- evidence upload DialogContent class.
- Re-scan `src/features/**` for any other native `<select>`/raw checkbox → fix.

**Tests:**
- Existing feature tests (`*.test.tsx`) must still pass (they assert visible text/roles, not class names). No new tests required for pure presentation; verification = tsc + biome + existing tests.

## Tasks & Acceptance

**Execution:**
- [x] Apply standard DialogContent class to all CRUD dialogs (taxonomy, machines, machine-groups, plants, teams, installations, responsibilities, department, user, request-part, preventive evidence).
- [x] Replace native `<select>`/raw checkbox in section-management + department-management (+ any others found).
- [x] Align DialogHeader/DialogFooter structure where missing.
- [x] Verify tsc + biome + existing feature tests.

**Acceptance Criteria:**
- Given any CRUD create/edit dialog across features, when opened, then it uses the same top-anchored scrollable wide presentation. [standard]
- Given section and department management, when rendered, then no native `<select>`/checkbox appears — shadcn primitives are used. [native]
- Given a dialog, when inspected, then it has a DialogHeader (title+description) and DialogFooter (cancel + primary). [structure]
- Given the polish pass, when all features are tested, then existing feature tests still pass and tsc/biome are clean. [no-regression]

## Design Notes

- **One class, everywhere.** The sparepart dialog's `top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl` is the de-facto standard (top-anchored so it doesn't obscure the form header, scrollable for tall forms, wide enough for two-column grids). Reusing the exact string keeps consistency without a new abstraction.
- **Deliberate exception:** genuinely tiny confirmation dialogs (a single prompt) may stay compact — forcing `sm:max-w-2xl` on a one-field dialog looks worse. Assess per file; the standard targets create/edit forms.
- **Presentation-only, test-safe.** All visible text/roles/testids unchanged; only className and primitive swaps. Existing tests (which assert on `getByRole`/text) remain green.

## Verification

**Commands:**
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green.
- `cd syncro/apps/web && npx biome check src/features` -- expected clean.
- `cd syncro/apps/web && npx vitest run src/features` -- expected pass (no regressions).

**Manual checks (if no CLI):**
- Open sparepart create vs category create in browser — both dialogs render top-anchored, scrollable, same width.
- Open section + department management — no native dropdown/checkbox styling.

## Spec Change Log

<!-- Empty until review loop. -->
