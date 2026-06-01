---
name: syncro-correct-story
description: Correct broken Syncro stories with BMad and TEA remediation gates. Use when user says /syncro-correct-story, correct story, fix completed story, story done but buggy, blind inspection, proxy user, or syncro correct story.
---

# syncro-correct-story

## Purpose

Correct Syncro story already started, in review, or marked done but failing frontend, backend, UI/UX, workflow, domain-model, or acceptance validation.

Act as remediation conductor and proxy user. Inspect built app independently, formalize correction, route fixes through BMad Phase 4 and TEA evidence, block next-story progress until remediation passes.

## Inputs

- Required: story id/key, `next`, or `resume`; examples: `2-6`, `2-6-install-spareparts`, `resume 2-6`, `next broken story`.
- Optional flags: `auto`, `phase 4`, `tea`, `investigate only`, `review only`, `blind inspection`, `proxy user`, `ui hardening`, `ux review`, `wds`, `party mode`, `backend contract`, `domain model`, `workflow gap scan`, `no new story`.
- Free-form correction signal after story id. Preserve domain intent, examples, UX expectations, data relationships, workflow rules, acceptance gaps.

Recommended commands:

```text
/syncro-correct-story 2-6 auto phase 4 tea "<correction signal>"
/syncro-correct-story resume 2-6 auto tea "<remaining correction signal>"
/syncro-correct-story 2-6 investigate only "<what feels wrong>"
/syncro-correct-story 2-6 review only
/syncro-correct-story 2-6 auto phase 4 tea wds party mode "<broad UX/backend/domain correction signal>"
```

## Hard Rules

- Remediation, not normal forward delivery. Do not advance next story until corrected story passes gates.
- Start with sprint status, story file, test artifacts, git diff, failing behavior, user-reported bugs, independent app/code inspection.
- Treat planning artifacts as claims to verify, not truth.
- Use `bmad-correct-course` when story is `done`, ACs changed, UX materially wrong, blind inspection finds systemic gaps, or multiple layers broken.
- Use `bmad-investigate` before implementation when root cause spans frontend, backend, UI/UX, tests, or unclear area.
- Create remediation story only when fixes exceed small cleanup, affect multiple stories, or need separate ACs/evidence.
- Preserve TEA: ATDD before major fixes, automation after fixes, test review and traceability before done.
- UI claims require MCP Playwright browser verification plus console/network evidence when relevant.
- Syncro UI should prefer shadcn/Radix non-native selects, dropdowns, popovers, comboboxes, dialogs, pickers. Native controls need explicit justification.
- Use PowerShell and stable Syncro commands from `CLAUDE.md`; do not invent equivalent command shapes.
- Never run native Playwright CLI, package installs, dependency upgrades, Docker resets, database resets, commits, pushes, branch changes, or destructive git commands unless user explicitly asks.
- Never mark complete while tests fail, UI unverified, code review unresolved, or traceability has gaps.

## Stage Order

### 1. Orient

Use `bmad-sprint-status` or inspect sprint status. Identify story status, whether marked `done`, related artifacts, current git changes, likely blast radius.

For `resume`, infer next stage from correction proposal, investigation notes, ATDD artifacts, modified files, review findings, traceability artifacts.

### 2. Blind Inspection

Run when flags/signals include `blind inspection`, `proxy user`, `ui hardening`, `wds`, `ux review`, broad correction language, or obvious UI risk.

Use MCP Playwright as user. Check desktop and narrow viewport behavior, alignment, overflow/clipping, scroll traps, spacing density, table/list usability, form composition, disabled/loading/empty/error states, keyboard path, visual hierarchy, coherent shadcn/Radix usage.

Inspect code for cause and blast radius. If UI cannot launch, record gap, continue static inspection, require later browser-verification gate.

### 3. Intake and Gap Scan

Convert correction signal plus blind-inspection findings into remediation brief. Preserve examples as evidence, not full scope.

Scan product intent, domain model, backend contract, frontend state, UI components, UX flow, data tables/lists, permissions, validation, error/loading/empty states, tests, traceability.

When `wds`, `ux review`, or UI/UX risk exists, use `wds-4-ux-design`, `wds-5-agentic-development`, or `bmad-ux` if available. When broad/cross-functional, use `bmad-party-mode` before freezing scope.

### 4. Correct Course

Use `bmad-correct-course` when problem changes scope, quality bar, UX direction, API contract, ACs, or story status.

Change signal must include observed failures, affected layers, whether story is `done`, correction standard, gap-scan findings.

If small contained defect with unchanged ACs, record why Correct Course not needed and continue.

### 5. Investigate

Use `bmad-investigate` for multi-layer failures. Separate evidence by product/AC mismatch, domain model, frontend state, backend/API contract, native-control gaps, UX workflow mismatch, table/list/search/filter/pagination gaps, loading/empty/error/permission/validation gaps, tests/traceability gaps, regressions.

`investigate only` stops after ranked remediation list and next-stage recommendation.

### 6. Scope Remediation

Correct original story unless multiple completed stories affected, UX/AC wording changes, fix too large for original story record, or separate review/evidence needed. Then create remediation story via `bmad-create-story`.

Continue only when target has ACs clear enough for tests and review.

### 7. TEA ATDD

Use `bmad-testarch-atdd` before major remediation unless `review only` or investigation-only. Cover reported failures and corrected user path, including backend validation and UI behavior when relevant.

### 8. Dev Remediation

Use `bmad-dev-story`. Dev owns code changes, targeted backend/web checks, story evidence updates, browser verification when UI touched.

Required command shapes when applicable:

```powershell
./syncro/apps/backend/mvnw.cmd -f ./syncro/apps/backend/pom.xml -Dtest=ClassName test
./syncro/apps/backend/mvnw.cmd -f ./syncro/apps/backend/pom.xml test
npm --prefix ./syncro/apps/web run typecheck
npm --prefix ./syncro/apps/web run lint
npm --prefix ./syncro/apps/web run test
npm --prefix ./syncro/apps/web run build
./syncro/scripts/start-backend.ps1
./syncro/scripts/start-web.ps1
```

### 9. TEA Automation

Use `bmad-testarch-automate` after implementation when correction touches UI, API behavior, validation, data integrity, or missing coverage. Route failures back to Dev Remediation or ATDD as needed.

### 10. TEA Test Review

Use `bmad-testarch-test-review` when tests changed or high-risk fix reuses existing coverage. Unresolved findings block Code Review and Traceability unless explicitly out of scope.

### 11. Code Review

Use `bmad-code-review` with corrected story/remediation story and test artifacts. Implementation bugs → Dev. Coverage gaps → Automation. Ambiguous ACs → Scope Remediation.

`review only` starts here after Orient.

### 12. TEA Traceability Gate

Use `bmad-testarch-trace` after review findings resolved. Map corrected ACs to tests, browser evidence, backend evidence, review outcome. Trace gaps block completion.

### 13. Closeout

Close only when evidence coherent: corrected story status, skills run, checks passed, browser verification notes, console/network findings, code-review result, traceability result, remaining risks.

Do not commit automatically. If commit requested, follow repo rules and stage only scoped intended files.

## Resume Paths

- `resume`: infer next stage from correction proposal, investigation, ATDD, code changes, review findings, traceability evidence.
- `investigate only`: Orient → Blind Inspection when needed → Intake/Gap Scan → Correct Course if needed → Investigate → stop.
- `review only`: Orient → Code Review → route fixes or closeout.
- `blind inspection`: inspect implementation and code independently before narrowing scope.
- `proxy user`: judge obvious user-facing quality failures.
- `no new story`: correct original story unless Correct Course requires remediation story.
- `ui hardening`: prioritize shadcn/Radix, layout consistency, browser verification, UX evidence.
- `workflow gap scan`: check create/edit/delete/list/table/filter/search/detail/error/loading/empty flows.
- `domain model`: prioritize entity relationships, ownership, identifiers, display names, backend/frontend contract.
- `backend contract`: prioritize API validation, integration tests, generated client/query usage, frontend error-state behavior.

## Failure Routing

- Story target unclear → Sprint Status and ask.
- Story `done` but materially wrong → Blind Inspection, Intake/Gap Scan, Correct Course.
- Broad/example-driven signal → Blind Inspection and Gap Scan before implementation.
- UI messy/overflowing/non-responsive/unfinished → Proxy User Blind Inspection.
- UX intent unclear → WDS/UX review before Correct Course.
- Cross-functional risk high → Party Mode before Correct Course.
- Root cause unclear → Investigate.
- Native UI controls without justification → Dev Remediation.
- Browser behavior unverified → Dev Remediation verification.
- Backend/API mismatch → Dev Remediation plus backend tests.
- ATDD missing corrected path → TEA ATDD.
- Automation coverage gap → TEA Automation.
- Review findings unresolved → owning review/dev stage.
- Traceability gaps → owning stage by gap type.
