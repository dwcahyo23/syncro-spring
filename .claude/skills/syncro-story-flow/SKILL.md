---
name: syncro-story-flow
description: Runs reusable BMad and TEA story workflow. Use when user says 'run story workflow', 'jalankan workflow story', or 'workflow bmad tea'.
---

# syncro-story-flow

## Overview

This skill orchestrates one Syncro story through BMad Method Phase 4 and TEA checkpoints using existing skills, not a new agent. Act as a story-flow conductor: keep scope on one story id/key, run each stage in the right order, continue autonomously through low-risk stages, and route fixes back to the right stage.

## Inputs

- Required: story id/key or clear next-story intent, e.g. `2-6`, `2-6-install-spareparts`, or `next`.
- Optional flags in plain language: `auto`, `auto phase 4`, `auto tea`, `auto phase 4 tea`, `skip ATDD`, `skip automation`, `skip trace`, `review only`, `resume`, `commit after done`.
- Recommended commands:
  - `/syncro-story-flow 2-6 auto phase 4 tea`
  - `/syncro-story-flow next auto phase 4 tea`
  - `/syncro-story-flow resume auto phase 4 tea`
  - `/syncro-story-flow 2-6 review only`

If no story is supplied, start with Sprint Status and ask the user to choose one story before continuing.

## Core Rules

- Run each long stage in a fresh context window when practical; tell the user before starting it.
- Default to auto-run: continue to the next low-risk stage after recording concise evidence, without asking for confirmation.
- Stop only for story selection, ambiguous resume position, unresolved findings, failed required checks, destructive actions, external/shared-state actions, or commit/push gates.
- Never skip a required BMad Method stage unless the user explicitly chooses a resume/review-only path.
- TEA stages are optional but recommended for API, UI, data integrity, auth, migration, or operational-risk stories; auto-run them when risk applies.
- If any stage reports unresolved findings or failed checks, route back to the stage that owns the fix instead of advancing.
- Auto-detect resume position from sprint status, story file status, existing ATDD/test/trace artifacts, current git diff, and prior workflow evidence before asking where to continue.
- Commit is an explicit final gate only: prepare one scoped commit when the user asked `commit after done` or approves the Closeout commit prompt; never push unless separately asked.
- Prefer project scripts and stable command shapes listed in `CLAUDE.md`; do not invent alternate equivalent commands during long-run automation.
- For UI verification, use MCP Playwright browser tools and collect console/network evidence; do not run native Playwright CLI unless the user explicitly asks.
- Start backend/web servers in the background only when required and not already reachable on the standard ports from `CLAUDE.md`.
- When invoking downstream skills, do not interrupt their internal checkpoints; the syncro stage completes only when the downstream skill reaches completion, returns a terminal halt, or asks for user input itself.
- TEA test commands must use repository/project-defined commands from `CLAUDE.md` or package scripts; do not assume `npm run test:e2e` unless that script exists.

## Automation Modes

- `auto`: choose the safest default at downstream prompts and continue until a stop condition appears.
- `auto phase 4`: run Create/Validate, Dev Story, Code Review, and Closeout without optional TEA unless risk requires it.
- `auto tea`: run ATDD, Automation, and Traceability when the story touches API, UI, data integrity, auth, migration, operational risk, or acceptance criteria ambiguity.
- `auto phase 4 tea`: run the full Phase 4 + TEA path by default: Create/Validate → ATDD → Dev Story → Automation → Code Review → Traceability → Closeout.

In any auto mode, if a downstream skill offers a non-risky continue/skip choice, choose the path that preserves evidence quality and advances the workflow. Do not stop for preference prompts unless the choice affects scope, data loss, shared state, commit, push, secrets, dependency changes, database reset, or destructive action.

## Stage Order

### 1. Orient

Invoke `bmad-sprint-status` or inspect sprint status if already loaded. Confirm target story key, current status, and whether this is a new run, resume, or review-only run.

For resume/autodetect, inspect only evidence needed to choose next stage: story status, story tasks/checklist, existing TEA artifacts, relevant tests, and current git status/diff summary. State detected last completed stage, next recommended stage, and confidence. If confidence is low, ask the user to choose from the plausible stages.

Continue automatically when confidence is high. Stop only with: `Detected possible next stages: <options>. Choose one or stop.` when evidence is ambiguous.

### 2. Create and Validate Story

For new or next stories, invoke `bmad-create-story` with the story id/key. If the create workflow has a validate action available, run validation before development.

Skip only when an existing story file is already `ready-for-dev` or `review`, and the user confirms resume.

Continue to TEA ATDD automatically when `auto tea` or `auto phase 4 tea` is active, or when risk applies; otherwise continue to Dev Story.

### 3. TEA ATDD

Recommended before development when acceptance criteria touch API contracts, UI behavior, auth, data integrity, migrations, or external integrations. Invoke `bmad-testarch-atdd` with the story file/key.

If skipped, record the user's reason in the conversation summary for later review.

Continue to Dev Story automatically after ATDD completes or is intentionally skipped.

### 4. Dev Story

Invoke `bmad-dev-story` with the story file/key. The dev stage owns implementation, targeted checks, and story evidence updates.

If dev leaves failures, blockers, or unmapped acceptance criteria, halt and keep status in progress.

Continue to TEA Automation automatically when `auto tea` or `auto phase 4 tea` is active, or when user-visible UI, API edge cases, or coverage gaps apply; otherwise continue to Code Review.

### 5. TEA Automation

Recommended after dev when user-visible UI, API edge cases, or coverage gaps remain. Invoke `bmad-testarch-automate` with the story file/key.

If no new tests are needed, require the automation workflow or user to state why existing evidence is enough.

Continue to Code Review automatically after automation completes or the workflow records why existing evidence is enough.

### 6. Code Review

Invoke `bmad-code-review` with `<story-key> with test artifact`. If review finds patches, let that workflow handle its confirmation choices and fixes.

If review fixes code, require relevant checks before proceeding.

Continue to TEA Traceability Gate automatically when `auto tea` or `auto phase 4 tea` is active, or when review is resolved for a story with implementation/test evidence; skip only for review-only runs or explicit user instruction.

### 7. TEA Traceability Gate

Recommended before marking story done. Invoke `bmad-testarch-trace` with the story file/key to map ACs to evidence and gate quality.

If trace finds gaps, route back to Dev Story or TEA Automation depending on whether the gap is implementation or evidence/test coverage.

Proceed to Closeout automatically when trace passes. Stop when trace has gaps and route to the owning stage.

### 8. Closeout

When all gates pass, update story evidence/status according to the owning BMad workflow's conventions before summarizing. If status update location is ambiguous, summarize the exact evidence and stop instead of guessing.

Summarize:

- Story key and final status.
- Skills run and any skipped TEA stages with reasons.
- Checks passed/failed.
- Remaining action items.
- Commit recommendation with exact scoped paths, or why no commit is safe yet.

If commit is approved, follow repository commit rules exactly: inspect status, diff stat, unstaged diff, staged diff, last five commits, and current branch; stage only intended files; create a concise imperative commit ending with `Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>`; then report status, branch, and commit hash. If inspection shows unrelated/forbidden files, stop and ask.

Offer next actions: commit, run next story through `syncro-story-flow`, rerun review, or stop.

## Resume Paths

- `auto resume` or `resume`: run Orient autodetection and recommend the next stage from evidence.
- `review only`: skip to Code Review for an already implemented story.
- `resume after dev`: start at TEA Automation unless user chooses Code Review.
- `resume after review`: start at TEA Traceability Gate if review patches are resolved.
- `resume after trace`: start at Closeout, including commit gate if approved.
- `next`: use Sprint Status to select next story, then begin at Create and Validate Story.

## Failure Routing

- Story unclear or unverifiable → back to `bmad-create-story` validation.
- ATDD fixture/token gaps → continue only if user accepts planned scaffolds as non-active evidence.
- Dev tests fail → stay in `bmad-dev-story`.
- Automation finds coverage gaps → run or update `bmad-testarch-automate`.
- Code review findings unresolved → stay in `bmad-code-review` or route to dev for fixes.
- Traceability gaps → route to dev for implementation gaps, automation for evidence gaps, or story update for ambiguous ACs.
- Required skill unavailable → do the smallest direct equivalent for evidence collection, record the missing skill, and continue only if the evidence is sufficient; otherwise stop with the missing skill and needed next action.
- Downstream prompt asks whether to continue → continue in auto mode unless it is a stop condition from Core Rules.
