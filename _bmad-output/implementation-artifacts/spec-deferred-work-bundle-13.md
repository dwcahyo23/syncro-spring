---
title: 'Deferred-work bundle 13: follow-through - machine/group search LIKE escape, optional-fields error slot'
type: 'bugfix'
created: '2026-08-23'
baseline_revision: 'eec899c'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/deferred-work.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** Two deferrals from bundles 11-12 are now actionable: (DW-120) `MachineRepository` (two search queries) and `MachineGroupRepository.search` use bare `like :search` while their services escape `% _ \` into the pattern — identical defect class proven empirically in the audit actor filter (bundle 12): any search term containing an underscore matches zero rows even for exact names, and `%` acts as a wildcard instead of a literal; (DW-119) backend emits fieldErrors.optionalTelemetryFields with precise config-rejection reasons but the machine dialog renders no slot for that key, so it is silently dropped in the UI.

**Approach:** Add `escape '\'` to all three LIKE predicates (SparepartRepository precedent). MachineServiceIntegrationTest + MachineGroupServiceIntegrationTest gain underscore-literal matching tests plus a percent-not-wildcard negative. Machine dialog renders `fieldErrors.optionalTelemetryFields` as a form-level destructive paragraph beside the existing formError block (no input exists for that field today; the slot future-proofs any path that submits optional fields).

## Boundaries & Constraints

**Always:**
- Escape clause syntax mirrors SparepartRepository: `like :search escape '\'` inside the Java text blocks.
- Both MachineRepository queries (unscoped + plant-scoped) get the clause on EVERY like occurrence in those queries.
- UI slot renders only when fieldErrors.optionalTelemetryFields is present; styling matches the existing formError paragraph.
- Search behavior tests assert BOTH directions: term-with-underscore finds the seeded row; term-with-% does NOT widen the match.

**Block If:**
- Adding escape clauses breaks an existing test that pins wildcard semantics deliberately -> HALT blocking condition `wildcard semantics pinned elsewhere`.

**Never:**
- Do NOT change normalizeSearch/normalizeActor escaping logic itself.
- Do NOT touch SparepartRepository or other already-escaped repositories.
- Do NOT add an optionalTelemetryFields input/editor to the machine form (only the error slot).

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineRepository.java` -- two like predicates -- DW-120
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/infrastructure/MachineGroupRepository.java` -- one like predicate -- DW-120
- `syncro/apps/backend/src/test/java/com/syncro/machine/application/MachineServiceIntegrationTest.java` -- underscore/% search tests -- DW-120
- `syncro/apps/backend/src/test/java/com/syncro/masterdata/application/MachineGroupServiceIntegrationTest.java` -- same -- DW-120
- `syncro/apps/web/src/features/master-data/machines/machine-management.tsx` -- optionalTelemetryFields error slot -- DW-119

## Tasks & Acceptance

**Execution:**
- [ ] MachineRepository.java -- escape '\' on both queries like predicates
- [ ] MachineGroupRepository.java -- escape '\' on search predicate
- [ ] MachineServiceIntegrationTest.java -- search literal-underscore positive + percent negative
- [ ] MachineGroupServiceIntegrationTest.java -- same pair
- [ ] machine-management.tsx -- optionalTelemetryFields error slot under formError

**Acceptance Criteria:**
- Given machines whose names contain underscores, when searching with an underscore-containing term, then rows match literally.
- When searching with % in the term, then % is treated literally (no wildcard widening).
- Given a create/update rejected for optionalTelemetryFields reasons, when the dialog renders fieldErrors, then the message appears in the dedicated slot.

</intent-contract>

## Spec Change Log

_Empty until first review loopback._

## Review Triage Log

### 2026-08-23 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 5 (low)
- defer: 2 (recorded: DW-121 findCodesByPrefix unescaped prefix LIKE; machine-management UI test harness absence noted inside DW-119 resolution)
- reject: 5 (UI-wipes-optional-fields is duplicate of open DW-32 design decision; bean-validation terse message vs service detail is pre-existing layered-validation design; tailwind class order matches formError exactly; no server importers concern N/A; Radix-select jsdom limitation documented in test comment instead of forced interaction)
- addressed_findings:
  - `high` `patch` escape-character search term untested - the whole scheme hinges on backslash-doubling happening first -> added "\\" term assertions to both suites
  - `medium` `patch` only the machine.code clause exercised; name/plant/group clauses unprotected against copy-paste drift -> added name-clause case (pump_one) on machine suite
  - `low` `patch` new error paragraph lacked assistive announcement and inherited bare-p convention -> role="alert" added to both formError and optionalTelemetryFields slots

## Verification

**Commands:**
- mvn test -Dtest="MachineServiceIntegrationTest,MachineGroupServiceIntegrationTest" -- green incl. new cases
- npm --prefix syncro/apps/web run test:unit ; npx tsc --noEmit -- green

## Auto Run Result

**Status:** done

**Summary:** (DW-120) Added `escape '\'` to all three remaining bare LIKE predicates — both `MachineRepository` search queries (machine.code/machine.name/plant.code/plant.name) and `MachineGroupRepository.search` (group.name/plant.code/plant.name) — closing the defect class proven empirically in bundle-12: search terms containing underscore matched zero rows and % acted as a wildcard. Pinned by literal-underscore positive, percent-negative, and backslash-term tests in both integration suites plus a name-clause case. (DW-119) Machine dialog now renders `fieldErrors.optionalTelemetryFields` in a role="alert" form-level slot beside formError; formError itself also gained role="alert".

**Files changed:**
- `MachineRepository.java`, `MachineGroupRepository.java` — escape clauses
- `MachineServiceIntegrationTest.java`, `MachineGroupServiceIntegrationTest.java` — DW-120 search tests
- `machine-management.tsx` — optionalTelemetryFields slot + role="alert" on both error paragraphs

**Review findings breakdown:** 0 intent_gap, 0 bad_spec, 5 patches across 3 groups, 2 deferrals recorded as ledger entries (DW-121 findCodesByPrefix unescaped prefix; UI harness absence noted in DW-119 resolution), 5 rejected.

**Follow-up review recommendation:** false — mechanical escape alignment to an existing proven precedent; behavior pinned by four new assertions; suites green.

**Verification performed:**
- MachineServiceIntegrationTest 27/27, MachineGroupServiceIntegrationTest 12/12
- Web vitest 190/190; tsc clean; biome warnings on touched file pre-existing only

**Residual risks:** findCodesByPrefix prefix wildcard behavior unchanged (DW-121); machine dialog still does not submit optionalTelemetryFields (DW-32 design decision governs); UI harness for machines feature absent (noted).
