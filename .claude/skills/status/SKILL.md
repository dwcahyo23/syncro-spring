---
name: status
description: "BMad Phase 4 sprint status shortcut. Use when user says /status or asks what to do next."
---

# Status Shortcut

Show Syncro BMad Phase 4 implementation status and recommend next action. No code changes.

## Rules

- Read status only unless user explicitly asks for updates.
- Do not edit application code.
- Do not commit.
- Do not switch branches.
- Prefer `bmad-sprint-status` source of truth when `sprint-status.yaml` exists.

## Workflow

1. Locate `{implementation_artifacts}/sprint-status.yaml` from BMad config.
2. If missing, report that sprint tracking is not initialized and recommend `bmad-sprint-planning`.
3. Parse epics, retrospectives, and stories.
4. Count story statuses:
   - `backlog`
   - `ready-for-dev`
   - `in-progress`
   - `review`
   - `done`
5. Detect risks:
   - stale status file
   - story in `review` needing `bmad-code-review`
   - story in `in-progress` that should be finished before next story
   - no `ready-for-dev` story needing `bmad-create-story`
   - orphaned story keys without matching epic
6. Recommend exactly one next workflow:
   - `bmad-dev-story` for first `in-progress` story
   - `bmad-code-review` for first `review` story
   - `bmad-dev-story` for first `ready-for-dev` story
   - `bmad-create-story` for first `backlog` story
   - `bmad-retrospective` when epic work is complete and retrospective remains optional
   - done message when all implementation items are done

## Output

Return:

- sprint status file path
- counts by status
- active/next story key
- risks
- one recommended next command: `/dev`, `/pivot`, or specific BMad workflow
