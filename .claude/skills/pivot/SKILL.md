---
name: pivot
description: "BMad Phase 4 correction shortcut. Use when user says /pivot or scope/design changes during implementation."
---

# Pivot Shortcut

Stop active implementation and route significant change through BMad correction workflow.

## When To Use

Use `/pivot` when any of these happen during Phase 4:

- acceptance criteria conflict with PRD, UX, or architecture
- user changes product behavior after story creation
- architecture choice no longer fits implementation reality
- story scope becomes too large or crosses epic boundaries
- implementation reveals missing requirement or unsafe assumption
- tests expose design-level issue, not local bug
- branch or artifact scope is wrong for requested work

## Rules

- Do not keep coding through unresolved scope/design conflict.
- Do not silently rewrite PRD, UX, architecture, epics, or story intent.
- Do not commit partial pivot work unless user explicitly asks.
- Preserve current work; avoid destructive git commands.
- Use `bmad-correct-course` as source workflow for major changes.

## Workflow

1. Capture pivot trigger in one sentence.
2. Identify affected artifacts:
   - PRD
   - UX design specification
   - architecture
   - epics/stories
   - sprint-status.yaml
   - current code
3. Classify impact:
   - story-local clarification
   - story rewrite needed
   - epic sequencing change
   - architecture/UX/PRD change
4. Recommend route:
   - small story-local clarification: update current story through BMad story process
   - major scope/design change: invoke `bmad-correct-course`
   - investigation needed: invoke `bmad-investigate`
   - status uncertainty: invoke `/status`
5. Ask user for decision only after presenting concrete route and consequence.

## Output

Return:

- pivot trigger
- affected artifacts
- safest next BMad workflow
- what work must pause
- what can safely continue
