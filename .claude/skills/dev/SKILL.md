---
name: dev
description: "BMad Phase 4 one-story implementation loop. Use when user says /dev or asks to implement next story."
---

# Dev Shortcut

Run Syncro BMad Phase 4 implementation loop for exactly one story.

## Rules

- Speak in configured communication language.
- Build one story at a time.
- Do not commit unless user explicitly asks.
- Do not switch branches unless user explicitly asks.
- If current branch is not appropriate for implementation, stop and report branch mismatch.
- Do not implement directly from `_bmad-output/planning-artifacts/epics.md` when story creation is required.
- Do not continue through product, UX, architecture, acceptance-criteria, or scope conflict. Route to `/pivot`.

## Workflow

1. Run `bmad-sprint-status` semantics first: locate `{implementation_artifacts}/sprint-status.yaml`, summarize story/epic state, and identify next action.
2. If `sprint-status.yaml` is missing, tell user to run `bmad-sprint-planning` before development.
3. If no story is `ready-for-dev` and none is `in-progress`, run or invoke `bmad-create-story` for next backlog story.
4. If a story is `in-progress`, continue that story before starting another.
5. If a story is `ready-for-dev`, invoke `bmad-dev-story` for that story.
6. Follow `bmad-dev-story` strictly: complete all acceptance criteria, tasks/subtasks, Dev Agent Record, File List, Change Log, and Status updates.
7. Run tests/checks matching touched scope:
   - backend: Maven tests/checks
   - web: package tests/lint/typecheck/build when available
   - infra: config validation/smoke checks where applicable
8. If UI changed, run app and verify golden path in browser before claiming complete.
9. When implementation complete, invoke or recommend `bmad-code-review` before next story.
10. Report changed files, tests run, unresolved risks, and next recommended BMad workflow.

## Exit Conditions

Stop and ask or route to `/pivot` when:

- acceptance criteria conflict with PRD/architecture/UX
- story lacks enough detail for safe implementation
- required secrets/services are unavailable
- tests fail outside current scope
- user asks for multi-story scope expansion
