# Claude Project Instructions

## Repository Layout

- Application source code will live under `syncro/`.
- Keep BMad workflow and planning artifacts under `_bmad/` and `_bmad-output/`.
- Keep Claude/agent workflow files under `.claude/`, `.agent/`, and `.agents/` when they are project-shared.
- Do not create or use separate worktrees for normal project work unless the user explicitly asks.

## Branching

- Use `main` as the default branch for project work.
- Do not create, delete, rename, or switch branches unless the user explicitly asks.
- Do not push unless the user explicitly asks.

## Commit Authorization

- Only create commits when the user explicitly asks for a commit.
- User asking to edit files is not permission to commit.
- User approving one commit is not permission for future commits.
- Do not amend, squash, rebase, reset, force-push, or rewrite history unless the user explicitly asks.
- Never skip hooks with `--no-verify` or equivalent unless the user explicitly asks.

## Pre-Commit Inspection Protocol

Before every commit, run and inspect:

1. `git status --short`
2. `git diff --stat`
3. `git diff`
4. `git diff --cached`
5. `git log -5 --oneline`
6. `git branch --show-current`

If any command reveals unexpected files, wrong branch, secrets, generated output, or unrelated changes, stop and ask.

## Staging Rules

- Stage only specific intended files or directories.
- Prefer exact paths, for example: `git add -- syncro/apps/backend syncro/apps/web _bmad-output/planning-artifacts`.
- Avoid `git add -A`, `git add .`, and broad wildcard staging unless the user explicitly asks and scope is already verified.
- Do not stage files outside requested scope.
- Do not stage deleted files unless deletion is explicitly intended and confirmed.

## What May Be Committed

Allowed by default when related to requested work:

- Application source under `syncro/`, including backend, web, shared packages, tests, and project config.
- Infrastructure-as-code and local development templates under `syncro/infra/` or root `infra/`, excluding runtime data and secrets.
- Scripts under `scripts/` when they support project development, testing, build, or maintenance.
- Tests under `tests/` or app-local test folders.
- Documentation under `docs/` and required root docs.
- BMad artifacts under `_bmad/` and `_bmad-output/`.
- Shared Claude/agent workflow files under `.claude/`, `.agent/`, and `.agents/`, excluding user-local settings.
- Root config files required by tooling, for example `.gitignore`, package manager config, build config, lint config, formatter config, and CI config.

## What Must Not Be Committed

Never commit:

- `.env` or `.env.*` except `.env.example`.
- Credentials, tokens, private keys, certificates, API keys, WAHA secrets, database passwords, or service account files.
- Local database volumes or infrastructure runtime data.
- `node_modules/`, package manager caches, build output, coverage output, or generated binaries.
- `.next/`, `dist/`, `build/`, `target/`, `.gradle/`, `.turbo/`, `.vercel/`, or similar generated directories.
- Logs, temporary files, cache files, OS/editor files.
- User-local Claude settings such as `.claude/settings.local.json`.
- Large binary assets unless the user explicitly confirms they belong in Git.

If forbidden content appears necessary, stop and ask for explicit confirmation and safer alternative.

## Push Rules

- Do not push unless the user explicitly asks.
- Before push, verify branch with `git branch --show-current` and confirm intended remote/branch.
- Push only commits relevant to requested scope.
- Do not force-push unless the user explicitly asks for force-push and understands impact.
- Never force-push to `main`.

## Commit Message Rules

- Use concise imperative subject line.
- Body should explain why the change exists, not list every file.
- Mention scope when useful: `backend`, `web`, `infra`, `BMad`, `docs`, or `syncro`.
- End every Claude-created commit with:

```text
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

Example:

```text
Add Syncro project baseline

Capture workflow rules and project guardrails so future implementation under syncro/ can be committed safely.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

## Post-Commit Verification

After every commit:

1. Run `git status --short`.
2. Run `git log -1 --oneline`.
3. Confirm working tree is clean or explain remaining intentional changes.
4. Report branch name and commit hash.

## Handling Hook or Test Failures

- If a hook fails, do not bypass it.
- Diagnose and fix underlying issue when within scope.
- If fix is outside scope, stop and ask.
- After fixing, create a new commit attempt; do not amend previous commits unless explicitly asked.

## Destructive Git Commands

Do not run these unless the user explicitly requests exact action and understands impact:

- `git reset --hard`
- `git clean -fd` or stronger
- `git checkout -- <path>` / `git restore <path>` to discard changes
- `git branch -D`
- `git push --force` or `--force-with-lease`
- Interactive or history-rewriting rebase

When in doubt, ask first.
