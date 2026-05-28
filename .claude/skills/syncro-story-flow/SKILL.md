---
name: syncro-story-flow
description: Runs reusable BMad and TEA story workflow. Use when user says 'run story workflow', 'jalankan workflow story', or 'workflow bmad tea'.
---

# syncro-story-flow

## Overview

This skill orchestrates one Syncro story through BMad Method Phase 4 and TEA checkpoints using existing skills, not a new agent. Act as a story-flow conductor: keep scope on one story id/key, run each stage in the right order, halt for human confirmation after every stage, and route fixes back to the right stage.

## Inputs

- Required: story id/key or clear next-story intent, e.g. `2-6`, `2-6-install-spareparts`, or `next`.
- Optional flags in plain language: `skip ATDD`, `skip automation`, `skip trace`, `review only`, `resume`.

If no story is supplied, start with Sprint Status and ask the user to choose one story before continuing.

## Core Rules

- Run each stage in a fresh context window when practical; tell the user this before starting long stages.
- Never skip a required BMad Method stage unless the user explicitly chooses a resume/review-only path.
- Every stage ends with a halt: summarize evidence, ask for confirmation, and wait before invoking the next skill.
- TEA stages are optional but recommended for API, UI, data integrity, auth, migration, or operational-risk stories.
- If any stage reports unresolved findings or failed checks, route back to the stage that owns the fix instead of advancing.
- Do not commit or push unless the user explicitly asks after the workflow result.

## Stage Order

### 1. Orient

Invoke `bmad-sprint-status` or inspect sprint status if already loaded. Confirm target story key, current status, and whether this is a new run, resume, or review-only run.

Halt prompt: `Continue with story <story-key>? [Y] continue / [N] choose another / [R] review-only resume`.

### 2. Create and Validate Story

For new or next stories, invoke `bmad-create-story` with the story id/key. If the create workflow has a validate action available, run validation before development.

Skip only when an existing story file is already `ready-for-dev` or `review`, and the user confirms resume.

Halt prompt: `Story context ready. Continue to TEA ATDD or skip to dev? [ATDD] / [Dev] / [Stop]`.

### 3. TEA ATDD

Recommended before development when acceptance criteria touch API contracts, UI behavior, auth, data integrity, migrations, or external integrations. Invoke `bmad-testarch-atdd` with the story file/key.

If skipped, record the user's reason in the conversation summary for later review.

Halt prompt: `ATDD complete or skipped. Continue to dev? [Y] / [N]`.

### 4. Dev Story

Invoke `bmad-dev-story` with the story file/key. The dev stage owns implementation, targeted checks, and story evidence updates.

If dev leaves failures, blockers, or unmapped acceptance criteria, halt and keep status in progress.

Halt prompt: `Dev evidence reviewed. Continue to TEA automation? [TA] / [Review] / [Stop]`.

### 5. TEA Automation

Recommended after dev when user-visible UI, API edge cases, or coverage gaps remain. Invoke `bmad-testarch-automate` with the story file/key.

If no new tests are needed, require the automation workflow or user to state why existing evidence is enough.

Halt prompt: `Automation complete or skipped. Continue to code review? [Y] / [N]`.

### 6. Code Review

Invoke `bmad-code-review` with `<story-key> with test artifact`. If review finds patches, let that workflow handle its confirmation choices and fixes.

If review fixes code, require relevant checks before proceeding.

Halt prompt: `Code review resolved. Continue to TEA traceability gate? [Trace] / [Done without trace] / [Stop]`.

### 7. TEA Traceability Gate

Recommended before marking story done. Invoke `bmad-testarch-trace` with the story file/key to map ACs to evidence and gate quality.

If trace finds gaps, route back to Dev Story or TEA Automation depending on whether the gap is implementation or evidence/test coverage.

Halt prompt: `Trace gate result reviewed. Mark story done / keep in progress / choose next story?`.

### 8. Closeout

Summarize:

- Story key and final status.
- Skills run and any skipped TEA stages with reasons.
- Checks passed/failed.
- Remaining action items.
- Whether commit is needed.

Offer next actions: commit, run next story through `syncro-story-flow`, rerun review, or stop.

## Resume Paths

- `review only`: skip to Code Review for an already implemented story.
- `resume after dev`: start at TEA Automation unless user chooses Code Review.
- `resume after review`: start at TEA Traceability Gate if review patches are resolved.
- `next`: use Sprint Status to select next story, then begin at Create and Validate Story.

## Failure Routing

- Story unclear or unverifiable → back to `bmad-create-story` validation.
- ATDD fixture/token gaps → continue only if user accepts planned scaffolds as non-active evidence.
- Dev tests fail → stay in `bmad-dev-story`.
- Automation finds coverage gaps → run or update `bmad-testarch-automate`.
- Code review findings unresolved → stay in `bmad-code-review` or route to dev for fixes.
- Traceability gaps → route to dev for implementation gaps, automation for evidence gaps, or story update for ambiguous ACs.
