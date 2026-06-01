---
name: syncro-story-flow
description: Run Syncro story workflow with BMad Phase 4 and TEA gates. Use when user says /syncro-story-flow, run story workflow, next story, resume story flow, or workflow bmad tea.
---

# syncro-story-flow

## Purpose

Orchestrate one Syncro story through BMad Method Phase 4 and TEA checkpoints. Act as workflow conductor, not replacement for BMad/TEA skills.

Keep scope on one story id/key. Continue autonomously through low-risk stages. Route failures back to owning stage.

## Inputs

- Required: story id/key or clear next-story intent, for example `2-6`, `2-6-install-spareparts`, `next`, or `resume`.
- Optional flags: `auto`, `auto phase 4`, `auto tea`, `auto phase 4 tea`, `skip ATDD`, `skip automation`, `skip trace`, `review only`, `resume`, `commit after done`.
- If no story supplied: run sprint orientation, then ask user to choose story before file changes.

Recommended commands:

```text
/syncro-story-flow 2-6 auto phase 4 tea
/syncro-story-flow next auto phase 4 tea
/syncro-story-flow resume auto phase 4 tea
/syncro-story-flow 2-6 review only
```

## Hard Rules

- One story at a time.
- Default auto-run: continue low-risk stages after recording concise evidence.
- Stop only for story selection, ambiguous resume position, unresolved findings, failed required checks, destructive actions, external/shared-state actions, package/dependency changes, database reset, commit, push, or secrets risk.
- Never skip required BMad stage unless explicit resume/review-only path allows it.
- Run TEA when story touches API, UI, data integrity, auth, migrations, operations, or ambiguous acceptance criteria.
- If stage reports unresolved findings or failed checks, route back to owning stage. Do not advance.
- Prefer stable Syncro commands from `CLAUDE.md`; do not invent equivalent command shapes.
- UI verification uses MCP Playwright browser tools with console/network evidence. Do not run native Playwright CLI unless user explicitly asks.
- Start backend/web in background only when required and standard ports are not reachable.
- Commit only when user requested `commit after done` or explicitly approves closeout commit. Never push unless separately asked.

## Automation Modes

- `auto`: choose safest default at downstream prompts; continue until stop condition.
- `auto phase 4`: run Create/Validate, Dev Story, Code Review, Closeout. Add TEA only when risk requires it.
- `auto tea`: run ATDD, Automation, Traceability when risk applies.
- `auto phase 4 tea`: full path: Create/Validate → ATDD → Dev Story → Automation → Code Review → Traceability → Closeout.

In auto mode, continue through non-risky downstream prompts. Stop for scope, data loss, shared state, commit, push, secrets, dependency changes, database reset, or destructive action.

## Stage Order

### 1. Orient

Use `bmad-sprint-status` or inspect sprint status. Confirm target story key, current status, run type: new, resume, or review-only.

For resume/autodetect, inspect only evidence needed to choose next stage: story status, story tasks/checklist, TEA artifacts, relevant tests, git status/diff summary. State detected last completed stage, next recommended stage, confidence.

If ambiguous, stop with: `Detected possible next stages: <options>. Choose one or stop.`

### 2. Create and Validate Story

For new or next stories, use `bmad-create-story` with story id/key. Validate before development when available.

Skip only when story file already `ready-for-dev` or `review`, and resume is confirmed.

Continue to TEA ATDD when `auto tea` or `auto phase 4 tea` active, or when risk applies. Otherwise continue to Dev Story.

### 3. TEA ATDD

Use `bmad-testarch-atdd` before dev when ACs touch API contracts, UI behavior, auth, data integrity, migrations, or external integrations.

If skipped, record reason.

### 4. Dev Story

Use `bmad-dev-story`. Dev owns implementation, targeted checks, story evidence updates.

If dev leaves failures, blockers, or unmapped ACs, halt and keep status in progress.

### 5. TEA Automation

Use `bmad-testarch-automate` after dev when UI, API edge cases, or coverage gaps exist.

If no new tests needed, require evidence-based reason existing evidence is enough.

### 6. Code Review

Use `bmad-code-review` with story key and test artifact. If review finds patches, let review workflow handle fixes.

If review fixes code, rerun relevant checks before proceeding.

### 7. TEA Traceability Gate

Use `bmad-testarch-trace` to map ACs to tests, browser evidence, backend evidence, and review outcome.

Trace gaps route back: implementation gaps → Dev Story; evidence gaps → TEA Automation; unclear ACs → story update.

### 8. Closeout

Close only when gates pass. Update story evidence/status according to owning BMad workflow conventions; if status location ambiguous, summarize evidence and stop.

Summarize:

- Story key and final status.
- Skills run and skipped TEA stages with reasons.
- Checks passed/failed.
- Remaining action items.
- Commit recommendation with exact scoped paths, or why no commit is safe yet.

If commit approved, follow repo commit rules exactly: inspect `git status --short`, `git diff --stat`, `git diff`, `git diff --cached`, `git log -5 --oneline`, `git branch --show-current`; stage only intended files; create concise imperative commit ending with `Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>`; report status, branch, hash.

## Resume Paths

- `resume`: orient and autodetect next stage from evidence.
- `review only`: skip to Code Review for already implemented story.
- `resume after dev`: start at TEA Automation unless user chooses Code Review.
- `resume after review`: start at TEA Traceability if review patches resolved.
- `resume after trace`: start at Closeout.
- `next`: use Sprint Status to select next story, then Create/Validate.

## Failure Routing

- Story unclear → Sprint Status, then ask.
- Story unverifiable → Create Story validation.
- Dev tests fail → Dev Story.
- Automation coverage gaps → TEA Automation.
- Code review findings unresolved → Code Review or Dev Story.
- Traceability gaps → owning stage by gap type.
- Required skill unavailable → do smallest direct equivalent for evidence collection, record missing skill, continue only if evidence sufficient.
