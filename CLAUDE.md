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
- Shared Hermes Agent or OpenClaw project configuration only when it is deterministic, non-secret, and required for the team to reproduce workflows.
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
- Hermes Agent or OpenClaw local sessions, memory, cache, logs, transcripts, run output, credentials, browser profiles, downloaded files, and generated scratch work.
- Large binary assets unless the user explicitly confirms they belong in Git.

If forbidden content appears necessary, stop and ask for explicit confirmation and safer alternative.

## Hermes Agent and OpenClaw Workspace Rules

- Treat `E:\01 DEV\SYNCRO-SPRING` as a shared workspace; do not let Hermes Agent or OpenClaw write secrets, caches, browser profiles, downloads, or transient run output into committed paths.
- Keep durable project source in `syncro/`; keep tool scratch work in ignored local directories.
- Before committing after Hermes Agent or OpenClaw use, inspect tool-created files carefully and stage only intentional project files.
- If a tool creates generated code under `syncro/`, verify it is source code, not build output, before staging.
- Do not commit tool memory, prompts containing private context, raw transcripts, local MCP settings, API keys, browser storage, or downloaded artifacts.
- If Hermes Agent or OpenClaw config is needed for teammates, commit only sanitized templates or shared config; keep machine-local config ignored.

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

## Syncro Development Commands

Use PowerShell command shapes below so Claude Code auto-allow rules can match consistently during long-running development:

- Start backend: `./syncro/scripts/start-backend.ps1`
- Start web: `./syncro/scripts/start-web.ps1`
- Backend tests: `./syncro/apps/backend/mvnw.cmd -f ./syncro/apps/backend/pom.xml test`
- Backend single test: `./syncro/apps/backend/mvnw.cmd -f ./syncro/apps/backend/pom.xml -Dtest=ClassName test`
- Web typecheck: `npm --prefix ./syncro/apps/web run typecheck`
- Web lint: `npm --prefix ./syncro/apps/web run lint`
- Web unit tests: `npm --prefix ./syncro/apps/web run test`
- Web build: `npm --prefix ./syncro/apps/web run build`

Do not replace these with equivalent `cd`, Bash, native wrapper, or alternate package-manager forms unless the stable command itself is broken. Prefer fixing these scripts over introducing new command shapes.

Preferred story automation commands:

- Full story automation: `/syncro-story-flow <story-id> auto phase 4 tea`
- Next story automation: `/syncro-story-flow next auto phase 4 tea`
- Resume automation: `/syncro-story-flow resume auto phase 4 tea`
- Review-only pass: `/syncro-story-flow <story-id> review only`

Preferred story correction commands:

- Full story correction: `/syncro-correct-story <story-id> auto phase 4 tea blind inspection proxy user`
- Broad UI/UX correction: `/syncro-correct-story <story-id> auto phase 4 tea blind inspection proxy user wds party mode "<correction signal>"`
- Investigation-only correction: `/syncro-correct-story <story-id> investigate only blind inspection proxy user "<what feels wrong>"`
- Resume correction: `/syncro-correct-story resume <story-id> auto tea blind inspection proxy user`
- Review-only correction: `/syncro-correct-story <story-id> review only`

Use `/syncro-correct-story` instead of advancing to the next story when a story is already started, in review, or marked done but has frontend, backend, UI/UX, responsiveness, overflow, workflow, domain-model, or test-evidence gaps. Treat planning artifacts as claims to verify, not the only source of truth; use MCP Playwright browser inspection for user-visible UI quality before declaring correction complete.

## Syncro Long-Run Automation Rules

- Use PowerShell for Syncro commands on Windows.
- Treat backend `8080` and web `3001` as the standard local development ports.
- Start long-running backend or web servers with background execution, not foreground blocking.
- If the needed server is already reachable, reuse it instead of starting another copy.
- If a stable command fails because the script is wrong, fix the script and rerun the same stable command shape.
- Do not create alternate one-off commands to bypass local allow rules.
- Do not kill existing server processes unless the user explicitly asks or the process was started by this session and is clearly stale.
- Backend startup should load only `syncro/.env.example` for local development defaults; never read or modify `.env` or `.env.*` secret files unless explicitly requested.
- Prefer targeted tests first, then broader suites only when the changed scope requires them.
- Avoid package installation, dependency upgrades, Docker resets, database resets, or generated-client rewrites unless the task requires them.

## Web Browser Testing

For interactive web verification, use Playwright MCP browser tools first. Do not run native Playwright browser automation from the CLI unless the user explicitly asks or MCP cannot cover the check.

Forbidden by default unless explicitly requested: `npx playwright`, `playwright test`, `npm run e2e`, native browser automation scripts, or screenshots generated outside MCP.

Expected browser-test flow:

1. Start backend with `./syncro/scripts/start-backend.ps1` in the background when API behavior is needed and port `8080` is not already reachable.
2. Start web with `./syncro/scripts/start-web.ps1` in the background when port `3001` is not already reachable.
3. Navigate and verify with MCP Playwright tools (`browser_navigate`, `browser_snapshot`, `browser_click`, `browser_fill_form`, `browser_wait_for`, `browser_console_messages`, `browser_network_requests`).
4. Check MCP console and network evidence before reporting UI success.
5. If MCP cannot cover a required browser check, explain the gap and ask before using native Playwright.

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
