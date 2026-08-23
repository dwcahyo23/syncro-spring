---
title: 'Deferred-work bundle 14: BOM series LIKE escape - underscore prefixes no longer over-match'
type: 'bugfix'
created: '2026-08-23'
baseline_revision: 'b8525e5'
final_revision: '3bfa5a2'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/deferred-work.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** (DW-121) `SparepartRepository.findCodesByPrefix` runs `upper(code) like concat(upper(:prefix), '%')` with NO escape declaration while the prefix derives from `machine.getCode()` - and MACHINE_CODE_PATTERN legally admits `_`. An underscore in a machine code acts as a single-character wildcard during BOM-series collision detection, so a sibling code differing at that position inflates `maxSeries` and makes the next generated BOM number SKIP sequence values.

**Approach:** Escape the prefix before the query (`\` -> `\\`, `%` -> `\%`, `_` -> `\_`, defensive even though machine codes cannot contain `%` or `\`) inside `SparepartService.nextBomCode(prefix)`, and declare `escape '\'` on the JPQL like. Trailing `%` appended by the query stays an intentional wildcard.

## Boundaries & Constraints

**Always:**
- Keep repository signature `findCodesByPrefix(@Param("prefix") String)` unchanged; escaping happens in the SERVICE before the call (repository stays a dumb query).
- `bomCodeForUpdate` Java-side `startsWith(prefix)` comparison remains UNESCAPED (plain string logic).

**Block If:**
- Any existing test pins wildcard semantics of the prefix LIKE -> HALT blocking condition wildcard semantics pinned elsewhere.

**Never:**
- Do NOT touch other repositories or normalizeSearch helpers.
- Do NOT change series formatting (%03d) or the >=999 rejection.

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartService.java` -- escape prefix in nextBomCode(String)
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartRepository.java` -- add escape '\' to findCodesByPrefix
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/application/SparepartServiceIntegrationTest.java` -- collision scenario test

## Tasks & Acceptance

**Execution:**
- [ ] SparepartService.java -- escapeLike helper applied to prefix
- [ ] SparepartRepository.java -- escape '\' clause
- [ ] SparepartServiceIntegrationTest.java -- DW-121 collision scenario

**Acceptance Criteria:**
- Given machines MCH_A and MCHA1 sharing plant+taxonomy refs, when a sparepart already exists for MCHA1 (series 001) and the FIRST sparepart for MCH_A is created, then its code ends with 001 (underscore did not leak into MCHA1s series).

</intent-contract>

## Spec Change Log

_Empty until first review loopback._

## Review Triage Log

### 2026-08-23 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 3 (low)
- defer: 1 (recorded as DW-123)
- reject: 3 (concurrent-create race and >=999 DuplicateSparepartException semantics are pre-existing adjacent concerns untouched by this diff; upper() safety on escaped ASCII punctuation verified non-issue)
- addressed_findings:
  - `high` `patch` collision test was VACUOUS - fixture MCHA1 differs from MCH_A at two positions so the pre-fix wildcard could never match it (test passed identically with and without the fix) -> fixture replaced with MCHAA, identical except the underscore slot, making the test a true red/green guard; added startsWith assertion on the sibling code
  - `medium` `patch` repository method now requires LIKE-escaped input with no signature hint -> renamed to findCodesByEscapedPrefix with contract javadoc pointing at SparepartService.nextBomCode
  - `low` `defer` identical three-replace escape chains now exist in five places across four services -> consolidation into a shared escapeLikePattern helper recorded as DW-123 (touches modules outside this bundle Never-boundary)

## Verification

**Commands:**
- mvn test -Dtest="SparepartServiceIntegrationTest" -- green incl. DW-121 case

## Auto Run Result

**Status:** done

**Summary:** (DW-121) `nextBomCode` now LIKE-escapes the BOM prefix (`\`->`\\`, `%`->`\%`, `_`->`\_`) before `findCodesByEscapedPrefix`, and the repository query declares `escape '\'`. An underscore in a machine code no longer acts as a single-character wildcard during BOM-series collision detection, so sibling machines whose codes differ only at an underscore position can no longer inflate maxSeries and skip sequence numbers. The trailing `%` appended by concat remains an intentional wildcard.

**Files changed:**
- `SparepartService.java` — escapeLike chain applied to prefix in nextBomCode
- `SparepartRepository.java` — escape clause + rename to findCodesByEscapedPrefix with contract javadoc
- `SparepartServiceIntegrationTest.java` — machineWithCode helper + red/green collision test (MCHAA vs MCH_A, both series 000)

**Review findings breakdown:** 0 intent_gap, 0 bad_spec, 3 patches across 2 fix groups + 1 defer recorded as DW-123 (shared escapeLikePattern consolidation), plus 1 high-value reviewer catch that the ORIGINAL collision test was vacuous - fixture corrected to a single-position difference making it a true guard.

**Follow-up review recommendation:** false — three-line production change; test strengthened to single-position red/green; suite green.

**Verification performed:**
- SparepartServiceIntegrationTest 16/16 PASS incl. DW-121 collision case
- Compile clean; repository rename applied at sole call site

**Residual risks:** concurrent creates on one prefix still race outside this diff (pre-existing); five duplicated escape chains await DW-123 consolidation.
