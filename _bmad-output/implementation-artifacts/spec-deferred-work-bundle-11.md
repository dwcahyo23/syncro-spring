---
title: 'Deferred-work bundle 11: field-aware machine validation errors'
type: 'refactor'
created: '2026-08-23'
baseline_revision: 'd1d4bb3'
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

**Problem:** (DW-30) `MachineExceptionHandler` maps EVERY `MachineValidationException` to a hardcoded fieldErrors `{code: "Invalid value."}`, so Story 3.6 optional-telemetry-config rejections blame the `code` field the client never touched, and query-param violations (page/size/sort) get nonsense attribution. The exception itself carries zero context; all eight throw sites in `MachineService` raise an identical bare instance.

**Approach:** Give `MachineValidationException` an immutable fieldErrors map plus a no-arg constructor preserving today generic shape. Annotate each throw site with the field(s) it validates: required command fields (plantId/machineGroupId/status), optionalTelemetryFields with per-violation reasons (length / underscore / pattern / reserved / count), and query params (page, size, sort, code). The handler emits the populated map under the existing ErrorResponse.fieldErrors key; the no-field constructor keeps the legacy body as fallback for any unannotated future throw site.

## Boundaries & Constraints

**Always:**
- Keep HTTP 400 and top-level code VALIDATION_ERROR unchanged; only fieldErrors becomes field-aware.
- Optional-telemetry reasons distinguish five causes: too long (>64), underscore-prefixed, pattern mismatch, reserved base name, over-count (>10).
- Multiple missing command fields produce multiple entries in one response (collect all).
- Query-param validations annotate their own param name; machine-code normalization annotates code.
- Frontend machine-management.tsx already renders fieldErrors; NO frontend changes.

**Block If:**
- An existing backend test asserting the exact legacy fieldErrors.code detail fails AND represents a deliberate external contract -> HALT blocking condition legacy validation body pinned elsewhere.

**Never:**
- Do NOT touch other exception handlers, DTO records, or non-machine modules.
- Do NOT change validation rules (thresholds, patterns, limits), only which field gets blamed.
- Do NOT add i18n machinery. No frontend edits.

## Code Map

- `MachineService.java` -- exception class (~line 378) + 8 throw sites (249, 268, 275, 290, 297, 309, 322, 326)
- `MachineExceptionHandler.java` -- machineValidation() handler (~line 61)
- `MachineControllerTest.java` -- extend with field-aware assertions

## Tasks & Acceptance

**Execution:**
- [ ] `MachineService.java` -- add fieldErrors map + map constructor to MachineValidationException; annotate throw sites
- [ ] `MachineExceptionHandler.java` -- handler passes exception.getFieldErrors() when non-empty, legacy fallback otherwise
- [ ] `MachineControllerTest.java` -- new tests per matrix

**Acceptance Criteria:**
- Given optionalTelemetryFields containing "_temp", when create/update runs, then 400 with fieldErrors.optionalTelemetryFields mentioning the underscore rule (no longer blaming code).
- Given a reserved or pattern-invalid or oversized or over-count config, when validated, then fieldErrors.optionalTelemetryFields carries the matching reason.
- Given missing plantId/machineGroupId/status combinations, when create runs, then fieldErrors lists every missing required field.
- Given size=0 or page=-1 or bad sort on list, when called, then fieldErrors names that param.
- Given a bare no-arg throw path, when handled, then legacy `{code: "Invalid value."}` body preserved.
- Given existing controller tests asserting top-level VALIDATION_ERROR, when suite runs, they pass unchanged.

</intent-contract>

## Spec Change Log

_Empty until first review loopback._

## Review Triage Log

### 2026-08-23 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 6 (low)
- defer: 2 (medium)
- reject: 8 (legacy fallback code-blame moot — zero bare no-arg call sites remain in main; fail-fast telemetry strategy pre-existing; key allowlist over-engineering for internal exception; blank-entry silent drop codified by pre-existing 3.6-SVC-008; sort direction silent coercion pre-existing adjacent; summary wording self-reference; escalation-timeline dead line outside perimeter; sentinel-display scenarios contract-impossible)
- addressed_findings:
  - `low` `patch` Map.copyOf discarded LinkedHashMap insertion order -> unmodifiableMap(copy) preserves plantId/machineGroupId/status ordering end to end
  - `high` `patch` rejection reasons did not name the offending entry (reserved case inconsistently quoted) -> all four per-entry reasons now uniformly quote the entry ("'_x' must not start with an underscore." etc.)
  - `medium` `patch` passthrough test covered only a single entry -> extended to two keys serialized together
  - `medium` `patch` dedupe-before-over-count intersection untested -> new integration test proves 20 raw entries collapsing to 10 unique are accepted
  - `defer` `medium` reserved-name/dedupe matching is case-sensitive while pattern allows uppercase -> "COUNTING" config can persist optional.COUNTING shadowing base counting (TelemetryPayload.parse equally case-sensitive); needs a cross-layer case-policy decision, not a local fix
  - `defer` `medium` machine form renders field slots only for plantId/machineGroupId/code/status -> new optionalTelemetryFields key surfaces via toast/formError but has no dedicated input slot yet

## Verification

**Commands:**
- mvn test -Dtest="MachineControllerTest" -- expected: green incl. new field-aware cases
- mvn test-compile -- expected: clean

## Auto Run Result

**Status:** done

**Summary:** (DW-30) `MachineValidationException` now carries an immutable, order-preserving `fieldErrors` map; all eight throw sites in `MachineService` were annotated with the field they actually validate — required command fields are collected together (plantId/machineGroupId/status), optional-telemetry config rejections carry per-cause reasons uniformly quoting the offending entry (length / underscore / pattern / reserved), and query-param normalizations blame page/size/sort/code. The handler emits the populated map verbatim under the existing `ErrorResponse.fieldErrors` key and keeps the legacy `{code: "Invalid value."}` body only for unannotated throws. Frontend machine form already renders fieldErrors keyed this way, so 4-field errors surface in-place immediately.

**Files changed:**
- `MachineService.java` — exception gains fieldErrors + map ctor; validateCommand collects all missing fields; optionalFieldRejectionReason extracted with entry-quoting messages; page/size/sort/code sites annotated
- `MachineExceptionHandler.java` — handler signature takes exception; emits populated map or legacy fallback
- `MachineServiceIntegrationTest.java` — reserved/underscore/pattern assertions now check exact quoted reasons; NEW over-count reason test; NEW multi-missing-field collection test; NEW dedupe-collapse-before-over-count test
- `MachineControllerTest.java` — NEW passthrough test asserting two field keys serialize to JSON

**Review findings breakdown:** 0 intent_gap, 0 bad_spec, 6 patches applied across 4 fix groups, 2 deferrals (case-insensitive reserved/dedupe policy across payload+persist layers; frontend slot for optionalTelemetryFields key), 8 rejected as pre-existing/moot/out-of-scope.

**Follow-up review recommendation:** false — error-shaping change localized to one module; behavior additions pinned by integration tests; both suites green.

**Verification performed:**
- `MachineControllerTest` 22/22 PASS (incl. new passthrough with two field keys)
- `MachineServiceIntegrationTest` 26/26 PASS (incl. exact-reason assertions for underscore/reserved/pattern/over-count, multi-missing collection, dedupe-collapse)
- `test-compile` clean

**Residual risks:** case-sensitivity of reserved-name matching allows uppercase variants to persist as shadow optional fields (deferred — requires cross-layer TelemetryPayload/persist policy decision); frontend has no dedicated input slot for the new optionalTelemetryFields key yet (surfaces via generic toast/formError).
