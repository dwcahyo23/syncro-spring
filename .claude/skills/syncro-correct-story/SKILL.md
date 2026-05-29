---
name: syncro-correct-story
description: Corrects broken Syncro stories with BMad and TEA. Use when user says 'correct story', 'fix completed story', 'syncro correct story', or 'story done but buggy'.
---

# syncro-correct-story

## Overview

This skill orchestrates correction of a Syncro story that is already started, in review, or marked done but fails frontend, backend, UI/UX, or acceptance validation. Act as a remediation-flow conductor and proxy user: independently inspect what was built, formalize the course correction, route fixes through BMad Phase 4 and TEA evidence, and block next-story progress until remediation is verified. Use specialized review agents/lenses when useful, but keep this skill as the coordinator rather than replacing BMad/TEA workflows.

## Inputs

- Required: story id/key, `next`, or `resume`; examples: `2-6`, `2-6-install-spareparts`, `resume 2-6`, `next broken story`.
- Optional flags: `auto`, `phase 4`, `tea`, `investigate only`, `review only`, `blind inspection`, `proxy user`, `ui hardening`, `ux review`, `wds`, `party mode`, `backend contract`, `domain model`, `workflow gap scan`, `no new story`.
- Free-form correction signal is accepted after the story id. Preserve all domain intent, examples, UX expectations, data relationships, workflow rules, and acceptance gaps as input to Correct Course instead of narrowing them into a single bug.
- `blind inspection` means inspect the implemented app and code independently from planning artifacts, treating PRD/story text as claims to verify rather than truth.
- `proxy user` means act as the user's substitute reviewer for obvious UI/UX quality failures: messy layout, overflow, poor responsiveness, awkward interaction flow, broken states, and components that look unfinished.
- Recommended commands:
  - `/syncro-correct-story 2-6 auto phase 4 tea "<correction signal>"`
  - `/syncro-correct-story resume 2-6 auto tea "<remaining correction signal>"`
  - `/syncro-correct-story 2-6 investigate only "<what feels wrong>"`
  - `/syncro-correct-story 2-6 review only`
  - `/syncro-correct-story 2-6 auto phase 4 tea wds party mode "<broad UX/backend/domain correction signal>"`

If no story is supplied, run sprint orientation first and ask for the target story before changing files.

## Core Rules

- Treat this as remediation, not normal forward story delivery; do not advance to the next story until the corrected story passes required gates.
- Start with current evidence: sprint status, story file, test artifacts, git diff, failing behavior, user-reported bug classes, and independent inspection of the implemented app/code when UI or workflow quality is in question.
- Do not let planning artifacts narrow the audit; they define intended scope, while observed app behavior and code structure reveal missed gaps.
- Use `bmad-correct-course` when the story is `done`, acceptance criteria changed, UX is materially wrong, blind inspection finds systemic quality gaps, or multiple layers are broken.
- Use `bmad-investigate` before implementation when the failure surface spans frontend, backend, UI/UX, tests, or unclear root cause.
- Create a remediation story only when fixes exceed small cleanup, affect multiple existing stories, or need separate acceptance criteria; otherwise correct the original story and record evidence there.
- Preserve TEA discipline: ATDD before major fixes, automation after fixes, test review and traceability before declaring done.
- UI claims require MCP Playwright browser verification, console review, and network/API evidence when relevant.
- For Syncro UI, prefer shadcn/Radix non-native selects, dropdowns, popovers, comboboxes, dialogs, and pickers; native controls are allowed only when explicitly justified by platform behavior.
- Use PowerShell and stable Syncro commands from `CLAUDE.md`; do not invent equivalent command shapes during automation.
- Never run native Playwright CLI, package installs, dependency upgrades, Docker resets, database resets, commits, pushes, branch changes, or destructive git commands unless the user explicitly asks.
- Never mark remediation complete while required tests fail, UI verification is unobserved, code review has unresolved findings, or traceability has implementation/evidence gaps.

## Stage Order

### 1. Orient

Invoke or inspect `bmad-sprint-status`. Identify story status, whether it was marked `done`, related BMad artifacts, current git changes, and likely blast radius. If `resume` is supplied, infer the next stage from existing correction proposal, investigation notes, ATDD artifacts, modified files, review findings, and traceability artifacts.

Proceed automatically when the target story and next stage are clear. Stop only when multiple stories could be the target or continuing would modify the wrong story.

### 2. Blind Inspection

Run this stage when `blind inspection`, `proxy user`, `ui hardening`, `wds`, `ux review`, broad correction language, or obvious UI risk is present. Inspect the working implementation without trusting planning artifacts as the only checklist.

Use MCP Playwright to exercise the relevant screens as a user would. Check desktop and narrow viewport behavior, layout alignment, overflow/clipping, scroll traps, spacing density, table/list usability, form composition, disabled/loading/empty/error states, keyboard path, visual hierarchy, and whether shadcn/Radix components are used coherently instead of raw native controls.

Inspect code only to identify implementation causes and blast radius. If the UI cannot be launched, record the gap and continue with static code inspection plus a required later browser-verification gate; do not claim visual quality.

For robust coverage, spawn independent review lenses when available: UX/layout reviewer, frontend implementation reviewer, backend/domain-contract reviewer, and TEA evidence reviewer. Each lens should return only actionable gaps with observed evidence, likely owner stage, and whether it blocks Correct Course.

### 3. Intake and Gap Scan

Convert the user's free-form correction signal plus blind-inspection findings into a broad remediation brief before invoking Correct Course. Preserve examples as evidence, not as the full scope. Scan for gaps across product intent, domain model, backend contract, frontend state, UI components, UX flow, data tables/lists, permissions, validation, error states, loading/empty states, tests, and traceability.

When `wds`, `ux review`, or UI/UX risk is present, invoke the relevant WDS/UX skill if available (`wds-4-ux-design`, `wds-5-agentic-development`, `frontend-design:frontend-design`, or `bmad-ux`) to identify design/interaction gaps before implementation. When the correction is broad or cross-functional, use `bmad-party-mode` for multi-agent perspectives before freezing the remediation scope.

Proceed to Correct Course with a single consolidated change signal that includes known bugs, blind-inspection gaps, suspected gaps, acceptance criteria deltas, UX expectations, and test evidence needs.

### 4. Correct Course

Invoke `bmad-correct-course` when the problem changes scope, quality bar, UX direction, API contract, acceptance criteria, or story status. The change signal must include observed failures, affected layer(s), whether the story is already `done`, intended correction standard, and gap-scan findings.

If the issue is a small contained defect with unchanged ACs, record why Correct Course is not needed and continue to Investigation or Dev Story.

### 5. Investigate

Invoke `bmad-investigate` for multi-layer failures. Evidence should separate:

- product/acceptance criteria mismatch
- domain model and entity relationship defects
- frontend behavior and state management defects
- backend/API contract or validation defects
- shadcn/Radix vs native-control gaps
- UX mismatch against desired workflow
- table/list/search/filter/pagination gaps
- loading, empty, error, permission, and validation-state gaps
- missing tests, stale ATDD, or traceability gaps
- regressions across earlier Epic 2 stories

`investigate only` stops after a ranked remediation list and recommended next stage.

### 6. Scope Remediation

Decide whether to correct the original story or create a remediation story through `bmad-create-story`.

Create a remediation story when any condition is true: multiple completed stories are affected, UX/AC wording must change, the fix is too large for the original story record, or separate review/evidence is needed. Name it with the affected epic/story scope and keep the original broken story referenced.

Continue only when the work target has acceptance criteria clear enough for tests and review.

### 7. TEA ATDD

Invoke `bmad-testarch-atdd` before major remediation unless the user chose `review only` or the change is investigation-only. ATDD must cover the reported failures and the corrected user path, including backend validation and UI behavior where relevant.

If ATDD is skipped, record the explicit reason and continue only if existing acceptance evidence covers the corrected behavior.

### 8. Dev Remediation

Invoke `bmad-dev-story` for implementation. The dev stage owns code changes, targeted backend/web checks, story evidence updates, and local browser verification when UI is touched.

Required Syncro checks should use these shapes when applicable:

```powershell
./syncro/apps/backend/mvnw.cmd -f ./syncro/apps/backend/pom.xml -Dtest=ClassName test
./syncro/apps/backend/mvnw.cmd -f ./syncro/apps/backend/pom.xml test
npm --prefix ./syncro/apps/web run typecheck
npm --prefix ./syncro/apps/web run lint
npm --prefix ./syncro/apps/web run test
npm --prefix ./syncro/apps/web run build
```

For UI work, start backend/web only if the standard ports are not already reachable, using:

```powershell
./syncro/scripts/start-backend.ps1
./syncro/scripts/start-web.ps1
```

Then verify through MCP Playwright browser tools. Do not claim UI success from tests alone.

### 9. TEA Automation

Invoke `bmad-testarch-automate` after implementation when the correction touches UI, API behavior, validation, data integrity, or previously missing coverage. If no tests are added, require a clear evidence-based reason that existing tests already fail/pass on the corrected behavior.

Route back to Dev Remediation if automation exposes implementation failures. Route to TEA ATDD if acceptance scenarios were wrong or incomplete.

### 10. TEA Test Review

Invoke `bmad-testarch-test-review` when remediation has new/changed tests or when existing coverage is being reused for a high-risk fix. Use its findings to decide whether evidence quality is enough for traceability.

Unresolved test-review findings block Code Review and Traceability unless the finding is explicitly out of scope and documented.

### 11. Code Review

Invoke `bmad-code-review` with the corrected story/remediation story and available test artifacts. If findings are implementation bugs, route back to Dev Remediation. If findings are coverage gaps, route back to TEA Automation. If findings are ambiguous ACs, route back to Scope Remediation.

`review only` starts here after Orient, but still routes backward when findings require fixes.

### 12. TEA Traceability Gate

Invoke `bmad-testarch-trace` after code review findings are resolved. Map corrected acceptance criteria to tests, browser evidence, backend evidence, and review outcome.

Trace gaps block completion. Implementation gaps route to Dev Remediation; evidence gaps route to TEA Automation; unclear criteria route to Scope Remediation or Correct Course.

### 13. Closeout

Close only when remediation evidence is coherent: corrected story status, skills run, checks passed, browser verification notes, console/network findings, code-review result, traceability result, and remaining risks.

Do not commit automatically. If the user asks for a commit, follow the repository commit rules in `CLAUDE.md`, stage only scoped intended files, and never push unless separately requested.

Offer next actions: rerun verification, commit, resume normal `/syncro-story-flow` for the next story, or stop.

## Resume Paths

- `resume`: infer next stage from correction proposal, investigation, ATDD, code changes, review findings, and traceability evidence.
- `investigate only`: Orient → Blind Inspection when UI/workflow risk exists → Intake and Gap Scan → Correct Course if needed → Investigate → stop with remediation list.
- `review only`: Orient → Code Review → route fixes or closeout if clean.
- `blind inspection`: inspect the running implementation and code independently before using planning artifacts to narrow scope.
- `proxy user`: judge obvious user-facing quality failures without waiting for the user to enumerate every issue.
- `no new story`: correct the original story unless Correct Course requires a remediation story.
- `ui hardening`: prioritize shadcn/Radix replacement, layout consistency, browser verification, and UX evidence.
- `ux review` or `wds`: run UX/WDS gap discovery before Correct Course when interaction quality or layout intent is part of the correction signal.
- `party mode`: use multi-agent perspectives before Correct Course for broad, ambiguous, or cross-functional correction signals.
- `workflow gap scan`: search for missed behavior across create/edit/delete/list/table/filter/search/detail/error/loading/empty flows before implementation.
- `domain model`: prioritize entity relationships, data ownership, identifiers, display names, and backend/frontend contract alignment.
- `backend contract`: prioritize API validation, integration tests, generated client/query usage, and frontend error-state behavior.

## Failure Routing

- Story target unclear → Sprint Status and ask for target.
- Story marked `done` but materially wrong → Blind Inspection, Intake and Gap Scan, then Correct Course before fixes.
- Correction signal is broad or example-driven → consolidate scope through Blind Inspection and Intake and Gap Scan before freezing implementation work.
- UI looks messy, overflowing, non-responsive, unfinished, or interaction quality is suspect → Proxy User Blind Inspection before Correct Course.
- UX/UI intent unclear or likely under-specified → WDS/UX review before Correct Course.
- Cross-functional risk is high → Party Mode before Correct Course.
- Root cause unclear across layers → Investigate before fixes.
- Native UI controls remain without justification → Dev Remediation.
- Browser behavior unverified for UI changes → Dev Remediation verification.
- Backend/API contract mismatch → Dev Remediation plus backend tests.
- ATDD missing corrected acceptance path → TEA ATDD.
- Automation coverage gap → TEA Automation.
- Test-review findings unresolved → TEA Test Review or Dev Remediation.
- Code-review findings unresolved → Code Review or Dev Remediation.
- Traceability gaps → owning stage based on gap type.
- User requests next story before remediation passes → summarize blockers and refuse advancement until they explicitly override the quality gate.
