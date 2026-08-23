---
title: 'Deferred-work bundle 7: pilot verification tooling hardening — enforceable verdicts in verify-pilot.ps1'
type: 'chore'
created: '2026-08-23'
baseline_revision: '1f8c3c1'
status: 'in-review'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/deferred-work.md'
warnings: ['multiple-goals']
---

<intent-contract>

## Intent

**Problem:** Five open deferred-work items reduce verify-pilot.ps1 from evidence tool to cosmetic reporter: (DW-105) the NOTIFICATION section only prints job rows — "no FAIL path" means job anomalies never fail the run; (DW-106) the ALERT section asserts the 90.00 snapshot but never asserts the alert status, so a corrupt/non-OPEN status sails through; (DW-107) alert↔job trace_id cross-correlation is left to human eyeballing; (DW-110) a drifted counter (neither 890 nor 900) is reported as-is with no drift warning, hiding wrap-around deltas; (DW-111) the quarantine matrix hard-FAILs on any telemetry_quarantine row, contradicting the spec's "document as pre-existing" path and making that scenario unexecutable.

**Approach:** Extend verify-pilot.ps1 only. Add an opt-in `-ExpectAlertStatus` parameter mirroring the existing `-ExpectCounting` pattern plus a hard status-membership assertion (OPEN/ACKNOWLEDGED); give the NOTIFICATION section real verdicts (required TECHNICIAN job when jobs exist, status-enum membership, alert↔job trace_id equality); WARN on non-canonical counter values with a `-ExpectCounting` pinning hint; add a `-QuarantineWarnOnly` switch so the documented-as-pre-existing quarantine path becomes executable without weakening the default FAIL.

## Boundaries & Constraints

**Always:**
- Keep Windows PowerShell 5.1 compatibility (existing header promise): no ternary operator, no null-coalescing `??`, no `-ErrorAction Ignore`, ASCII-safe additions to comments/strings where the file already avoids Unicode-sensitive output.
- `-ExpectAlertStatus` accepts only `OPEN` or `ACKNOWLEDGED`; any other value exits 1 with a usage error BEFORE any probe runs (parameter validation, same spirit as the whitespace guard).
- Status-membership assertion: when the ALERT section observes exactly one non-RESOLVED alert, its status must be `OPEN` or `ACKNOWLEDGED` — anything else is FAIL (corruption signal). With `-ExpectAlertStatus` set, additionally FAIL when the observed status differs from the expected one.
- NOTIFICATION verdicts apply ONLY when an active alert id is resolved AND at least one job row exists (async dispatch means zero jobs stays INFO): FAIL if no `TECHNICIAN|`-prefixed row exists (level 1 is always created first); FAIL if any row's status field is not in the documented set (PENDING/ROUTING_FAILED/SENT/EXHAUSTED/ESCALATED/CANCELLED/RATE_LIMITED).
- Trace correlation: when an active alert has a non-empty trace_id, every job row with a non-empty trace_id must equal it — first mismatch FAILs naming alert trace_id, job level, and job trace_id.
- Non-canonical counter: when a machine_counter_states row exists and its counting is neither `890` nor `900` and `-ExpectCounting` was not supplied, print WARN explaining drift/wrap-around risk and pointing at `-ExpectCounting` for pinning (INFO text upgraded to WARN; no new FAIL).
- Quarantine: add `[switch]$QuarantineWarnOnly`; when set, quarantine-row findings use Print-Warn instead of Print-Fail (root-cause hint lines stay). Default behavior unchanged (FAIL).
- Update the .SYNOPSIS/.DESCRIPTION comment block to document the two new parameters.

**Block If:**
- The docker compose stack cannot be started for a live run AND the PowerShell parser check cannot confirm the modified script parses → HALT with blocking condition `verify-pilot changes unverifiable`.

**Never:**
- Do NOT touch any backend/web production source, migrations, or seed-pilot.ps1.
- Do NOT make quarantine WARN the default or remove any existing FAIL condition.
- Do NOT add external module dependencies (e.g. PSScriptAnalyzer requirement) or a PowerShell test framework.
- Do NOT change verdict exit semantics (non-zero only on FAIL; PASS on zero failures).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Threshold alert healthy | counting=900, one alert status=OPEN snapshot=90.00, TECHNICIAN job SENT with matching trace | ALERT PASS includes status=OPEN; NOTIFICATION PASS; exit 0 | No error |
| Wrong expected status | `-ExpectAlertStatus ACKNOWLEDGED` while alert is OPEN | ALERT FAIL names expected vs observed; exit non-zero | Param validated upfront |
| Corrupt status | Active alert status='DELETED' (not in enum) | ALERT FAIL (status-membership) regardless of -ExpectAlertStatus | FAIL counted |
| Trace mismatch | Job trace_id=`t-b`, alert trace_id=`t-a` | FAIL names both ids and the job level | FAIL counted |
| No TECHNICIAN job | Jobs exist (STAFF only) for active alert | FAIL explains level-1-first invariant | FAIL counted |
| Unknown job status | Row status='WEIRD' | FAIL lists the offending row and allowed set | FAIL counted |
| Drifted counter | counting=12345, no -ExpectCounting | WARN about drift/wrap + -ExpectCounting hint; alert state still reported as-is | No FAIL added |
| Quarantine tolerated | 2 quarantine rows, `-QuarantineWarnOnly` | WARN lines with root-cause hint; run can PASS (exit 0) if nothing else fails | Default path still FAILs |

</intent-contract>

## Code Map

- `syncro/scripts/verify-pilot.ps1` -- the ONLY file changed; param block (~line 78), ALERT branch (~line 357-410), NOTIFICATION section (~line 412-451), quarantine block (~line 334-352)

## Tasks & Acceptance

**Execution:**
- [x] `syncro/scripts/verify-pilot.ps1` -- add `[ValidateSet('OPEN','ACKNOWLEDGED')][string]$ExpectAlertStatus` and `[switch]$QuarantineWarnOnly` params + synopsis docs -- DW-106/DW-111
- [x] `syncro/scripts/verify-pilot.ps1` -- ALERT branch: status-membership FAIL + `-ExpectAlertStatus` comparison FAIL -- DW-106
- [x] `syncro/scripts/verify-pilot.ps1` -- NOTIFICATION section: TECHNICIAN-presence FAIL, status-enum FAIL, alert↔job trace_id equality FAIL -- DW-105/DW-107
- [x] `syncro/scripts/verify-pilot.ps1` -- TELEMETRY: non-canonical-counting WARN with `-ExpectCounting` hint -- DW-110
- [x] `syncro/scripts/verify-pilot.ps1` -- quarantine block honors `$QuarantineWarnOnly` (WARN instead of FAIL) -- DW-111

**Acceptance Criteria:**
- Given a healthy threshold state (counting=900, OPEN alert, TECHNICIAN job, matching traces), when the script runs, then RESULT: PASS and all new assertions hold silently.
- Given `-ExpectAlertStatus X` with observed alert status ≠ X, when the script runs, then a FAIL names both values and the exit code is non-zero.
- Given job rows whose trace_id differs from the alert trace_id, when the script runs, then a FAIL names alert trace, job level, and job trace.
- Given jobs exist without a TECHNICIAN row, or with a status outside the documented set, when the script runs, then corresponding FAILs fire.
- Given a non-canonical counting value without `-ExpectCounting`, when the script runs, then a WARN explains drift/wrap risk (no FAIL).
- Given quarantine rows with `-QuarantineWarnOnly`, when the script runs, then WARN replaces FAIL for that check and the run can exit 0.

## Spec Change Log

_Empty until first review loopback._

## Review Triage Log

### 2026-08-23 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 7 (low)
- defer: 0
- reject: 12 (quarantine switch all-or-nothing is spec design; INFO-to-FAIL contract change IS the DW-105 deliverable; duplicated enum lists are intended fail-closed corruption detection consistent with existing script style; contradictory PASS+FAIL labels cosmetic; `-like 'TECHNICIAN|*'` pre-existing idiom; comment indent cosmetic; alert-trace-NULL gap out of scope and pre-existing; quarantine received_at cutoff would be new design; async TECHNICIAN race impossible given creation-order invariant; EC false-positive on quarantine else-branch (guarded by elseif); empty-line rows already filtered upstream)
- addressed_findings:
  - `low` `patch` -ExpectAlertStatus silently unevaluated outside the exactly-one-alert branch -> added $script:ExpectAlertStatusEvaluated flag + explicit WARN naming the skipped assertion
  - `low` `patch` .EXAMPLE prose implied passing OPEN accepts ACKNOWLEDGED -> reworded to exact-match semantics + documented skip-with-WARN behavior
  - `low` `patch` corrupt status + pinned expectation double-counted FAILs -> membership/mismatch made mutually exclusive (elseif)
  - `medium` `patch` empty job trace_id escaped the inheritance check -> added elseif FAIL for empty trace while alert trace present
  - `low` `patch` drift WARN speculated wrap-around cause and oversold fail-fast -> neutral wording pointing at machine_counter_states history + -ExpectCounting
  - `low` `patch` counting>900 with zero non-RESOLVED alerts passed invisibly -> added threshold-crossing-missing-evidence WARN in non-canonical branch
  - `medium` `patch` multi-line error_detail could create phantom fragment rows hitting the new status/trace checks -> SQL coalesce wraps error_detail with replace(chr(13)/chr(10)) guaranteeing single-line rows

## Design Notes

- Verdict philosophy preserved: persisted-state inconsistencies FAIL; environmental/timing conditions stay INFO/WARN (zero jobs after alert creation is async-timing, not corruption — hence gated on jobs existing).
- `-ExpectAlertStatus` follows the `-ExpectCounting` opt-in pattern: the script stays usable at any lifecycle stage while allowing hard stage-pinning in scripted proof runs.
- PS 5.1: use `[ValidateSet()]` on the string param (native, fails fast with a clear error) rather than manual validation blocks.

## Verification

**Commands:**
- Parser check: `powershell -NoProfile -Command "[void][System.Management.Automation.Language.Parser]::ParseFile('E:\01 DEV\SYNCRO-SPRING\syncro\scripts\verify-pilot.ps1', [ref]$null, [ref]$err); $err.Count"` -- expected: 0
- Live smoke: `powershell -NoProfile -File syncro/scripts/verify-pilot.ps1` against the running stack -- expected: sections render, verdicts consistent with current pilot state, exit code matches RESULT line
- Usage-error check: `powershell -NoProfile -Command "& 'syncro\scripts\verify-pilot.ps1' -ExpectAlertStatus BOGUS"` inside a try/catch harness -- expected: parameter validation error before probes

**Manual checks (if no CLI):**
- Inspect the diff: no Unicode introduced in new strings, no removed FAIL paths, quarantine default unchanged.

## Auto Run Result

**Status:** done

**Summary:** Bundled five open deferred-work items (DW-105, DW-106, DW-107, DW-110, DW-111) as a single-file tooling hardening of `syncro/scripts/verify-pilot.ps1`: (DW-105) the NOTIFICATION section gained real verdicts — unknown job status, a job set without a TECHNICIAN-level row, and alert-to-job trace_id mismatches now FAIL; (DW-106) alert status is validated against OPEN/ACKNOWLEDGED (corruption FAIL) with a new opt-in `-ExpectAlertStatus` stage pin that warns (never silently passes) when it cannot be evaluated; (DW-107) trace inheritance between alert and jobs is tool-enforced including the empty-trace case, with error_detail newline-flattened in SQL so multi-line details cannot forge phantom rows; (DW-110) non-canonical counter values WARN with drift guidance and a threshold-crossed-without-alert WARN was added for counting>900; (DW-111) `-QuarantineWarnOnly` makes the documented "pre-existing quarantine rows" scenario executable while the default stays FAIL.

**Files changed:**
- `syncro/scripts/verify-pilot.ps1` — params (`-ExpectAlertStatus`, `-QuarantineWarnOnly`) + synopsis/examples; ALERT status-membership + pinned-status verdicts; NOTIFICATION status-enum/trace-inheritance/TECHNICIAN-presence FAILs; non-canonical-counting and threshold-crossing WARNs; quarantine WARN-only path; error_detail SQL flattening

**Review findings breakdown:** 0 intent_gap, 0 bad_spec, 7 patches applied (unevaluated-assertion WARN, doc rewording, double-FAIL fix, empty-trace FAIL, neutral drift wording, threshold-crossing WARN, SQL newline flattening), 0 deferrals, 12 rejected as noise/by-design/spec-mandated.

**Follow-up review recommendation:** false — single-file ops script, all patched findings low-severity after fixes, live verification green on real pilot state.

**Verification performed:**
- PowerShell parser check: 0 errors
- Param validation: `-ExpectAlertStatus BOGUS` fails binding with exit 1 before any probe
- Live run against running stack (postgres+redis started): RESULT: PASS, exit 0 — membership accepted observed ACKNOWLEDGED alert, TECHNICIAN job present, traces matched, no spurious WARNs
- Pinned mismatch (`-ExpectAlertStatus OPEN` vs observed ACKNOWLEDGED): exactly one FAIL naming expected vs observed, exit 1
- Preflight hard-fail path exercised earlier against stopped stack: clean FAIL + exit 1

**Residual risks:** enum whitelists (alert statuses, job statuses) are hand-copied backend knowledge — a future backend enum addition will fail the script loudly here by design (fail-closed corruption detection); quarantine tolerance via `-QuarantineWarnOnly` cannot distinguish pre-existing rows from fresh ones (accepted tradeoff, documented).
