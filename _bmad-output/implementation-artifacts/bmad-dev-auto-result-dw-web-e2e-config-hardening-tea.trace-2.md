---
status: done
---

TEA Trace Requirements workflow (`bmad-testarch-trace`) completed for `dw-web-e2e-config-hardening`.

## What was produced (under `_bmad-output/test-artifacts/`)

- `traceability-matrix-dw-web-e2e-config-hardening.md` — requirements-to-tests traceability matrix + Phase 2 gate report.
- `e2e-trace-summary-story-dw-web-e2e-config-hardening.json` — machine-readable coverage/gate summary.
- `gate-decision-story-dw-web-e2e-config-hardening.json` — gate signal (FAIL).
- `tea-trace-coverage-matrix-2026-08-08-story-dw-web-e2e-config-hardening.json` — Phase 1 coverage matrix (temp path recorded in the report frontmatter).

## Coverage oracle

- **Basis:** acceptance_criteria (formal requirements) — spec `Tasks & Acceptance` (AC-1..AC-5).
- **Resolution mode:** formal_requirements; **confidence:** high; **sources:** spec + test-design + atdd-checklist + automation-summary.

## Mapping summary (5 ACs against working-tree tests)

- AC-1 (`BASE_URL` set → baseURL/webServer.url/derived command, no hardcoded port) — **PARTIAL**: base-URL value actively unit-locked (`config.test.ts`), command/wiring locked only by skipped RED-phase E2E scaffolds (`WH-AC1`/`WH-P1-04`).
- AC-2 (`BASE_URL` unset → descriptive fail-fast) — **FULL** (unit suite + recorded `--list` probe).
- AC-3 (`API_URL` set → helpers/specs target it, no `localhost:8080`) — **FULL** (unit suite + api consumers + collection gate + grep guard).
- AC-4 (`API_URL` unset → lazy import, call-site throw) — **FULL** (unit suite + token-gated skip).
- AC-5 (biome/tsc/`--list` pass + full enumeration) — **PARTIAL**: verified in parent-repo isolated harness (not this dependency-less worktree); full enumeration via `test:api` config only (DW-10).

## Gate decision: FAIL (deterministic, gate-eligible)

- P0 coverage 75% (required 100%) — AC-1 missing an active wiring/command regression lock.
- P1 coverage 0% (AC-5 toolchain/enumeration evidence not reproducible in this worktree).
- Overall coverage 60% (minimum 80%).

Skipped tests are documented by-design RED-phase/activation scaffolds, not failures. Recommendations: activate/accept the E2E wiring scaffolds (or add a config-level assertion on `playwright.config.ts`), wire `test:api`/`test:api:check` into CI, and re-run `biome check` + `tsc --noEmit` in the parent repo; then re-trace.
