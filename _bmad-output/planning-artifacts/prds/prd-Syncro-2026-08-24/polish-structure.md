# Structural Review — PRD + Addendum (Syncro Maintenance/Preventive/OPA)

Scope: structure only. Findings are prioritized; each carries file path and a suggested fix.

---

## Critical

### C1. Assumptions Index roundtrip is broken in 3 places
`prd.md` §9 (lines 689–700) vs inline tags.

- **Orphan index entry §4.2 FR-110** (line 691) — tagged `[ASSUMPTION inline]` but **no inline `[ASSUMPTION]`** exists in FR-110 (line 187). The `WO-YYMMxxxx` id format is asserted as fact in §3 Glossary and in FR-110's consequences, so the entry is arguably stale regardless.
- **Orphan index entry §4.8 FR-181** (line 700) — tagged `[ASSUMPTION inline]` but **no inline `[ASSUMPTION]`** exists in FR-181 (lines 601–610). Phone-binding is stated as prose in the FR and in the §6.2 deferred note.
- **Unindexed inline tag §4.2 FR-116** (line 243) — `[ASSUMPTION: max 10 MB per file, configurable]` has **no §9 entry**.

*Fix:* add inline `[ASSUMPTION: ...]` tags at FR-110 and FR-181, and add a §9 entry for FR-116. Then every index entry round-trips and every inline tag is indexed.

---

## High

### H1. Addendum duplicates PRD feature content instead of holding only deferred/rejected rationale
`addendum.md` header (line 3) states: "Deferred, rejected-alternative rationale, and implementation notes live here, not in the PRD." Six of seven addendum sections re-state chosen designs already in the PRD:

- **A2** (lines 13–25) re-prints both canonical state machines already in `prd.md` §3 (lines 117–119) and §4.2/§4.4 descriptions.
- **A3** (lines 27–38) re-states FR-150…FR-154 (transaction, watermark, lock, `sheet_no` ordering, quarantine, UTC, notification hygiene, no push-back).
- **A4** (lines 40–47) re-states FR-160…FR-164 (sidecar HTTP, minimal input, allowed-actions endpoint, Rego/CI/bundles, decision-log masking/retention, decision_id correlation).
- **A5** (lines 49–54) re-states FR-142 (est-cost formula, tiers, SoD) and §6.2 (UMR deferral).
- **A6** (lines 56–60) re-states FR-121, FR-124, FR-174.
- **A7** (lines 62–66) re-states FR-181 and the §6.2 deferral note.

Only **A1** (TanStack Table v9, UI) plus the reference-system detail/rationale sentences are genuinely addendum-only.

*Fix:* keep A1; for A2–A7 retain only what the PRD does **not** carry — i.e. the reference-implementation contrast, rejected-alternative rationale (WASM, card-first UI, auto-login hardening), and cross-file pointers ("see FR-xxx"). Point back to PRD FRs instead of repeating mechanism text.

### H2. FR-142 cross-references the wrong FR (self/mis-reference)
`prd.md` line 389: "Estimated price is sourced from the latest price entry **(FR-144)**" — FR-144 is *Complete new-item requests* (line 403). The latest-price-entry source is the Phase 1 `sparepart_price_entries` flow (FR-080), as the addendum itself says at line 52 ("FR-080/FR-144").

*Fix:* change `(FR-144)` → `(FR-080, Phase 1)`; the PENDING_COMPLETION completion data stays as the new-item source. (If keeping the Phase 1 price FR out of scope, reference it via the §10 contract list — see L5.)

### H3. "OQ-1" reference is dangling — §8 questions are unlabeled
`prd.md` line 284 (FR-121 assumption): "…see OQ-1". §8 Open Questions (lines 677–686) is a bare numbered list (1–7) with **no `OQ-n` identifiers**. "OQ-1" resolves to nothing.

*Fix:* label §8 items `OQ-1 … OQ-7` (or change the inline reference to "§8 #1").

---

## Medium

### M1. UJ-2 protagonist role not in the role taxonomy
`prd.md` line 52: "UJ-2. Dayat (**Workshop Leader**)". "Workshop Leader" appears nowhere in §3 roles or §2.1 JTBD. Every other UJ names an existing role (Section Leader, Storekeeper, Production Leader). Presumably it means SECTION_LEADER of the WORKSHOP section.

*Fix:* retitle to "Dayat (Section Leader, WORKSHOP section)" for consistency with UJ-1/UJ-3/UJ-4.

### M2. Canonical state machines duplicated within the PRD
`prd.md` §3 (lines 117–119) declares them "canonical"; §4.2 Description (line 183) and FR-141 (line 380) re-print the same sequences, and the "ON_PROCUREMENT means waiting for part" explanation appears in both §3 and §4.2.

*Fix:* keep the canonical enums in §3 only; in §4.2/§4.4 reference "Glossary §3" and describe only the auto-set/resume semantics.

### M3. WAHA/escalation scope split across two feature sections
`prd.md` FR-147 (line 428, §4.4) covers request escalation + WAHA notification steps; §4.8 (lines 586–614) covers "workorder and sparepart-request lifecycle steps" — FR-180's "part READY" event overlaps FR-147. §4.8's Description claim overlaps FR-147's scope.

*Fix:* either move FR-147's notification aspect under §4.8 (leaving escalation config in §4.4) or narrow §4.8's description to workorder events and keep request notifications in FR-147. Add one cross-reference either way.

### M4. NFR handling is inconsistent — only §4.8 has "Feature-specific NFRs"
`prd.md` lines 612–614 embed an NFR block inside §4.8; no other feature section carries one, and there is no top-level NFR section (NFRs are otherwise only referenced via Phase 1 contracts, §10).

*Fix:* either promote a short "4.9 Cross-cutting NFRs" or move the two lines into §5/§6.2 as explicit non-goals/constraints so the pattern is uniform.

---

## Low

### L1. Role casing / synonym drift
`prd.md` mixes human-name casing with role constants: "section leader" / "Section Leader" / "SECTION_LEADER"; "maintenance leader" / "maintenance-leader" / "MAINTENANCE_LEADER"; "manager" (FR-142 tier, line 394; FR-120, line 273; FR-174, line 576) vs `MANAGER_MAINTENANCE`; "Storekeeper" (UJ-5) vs `STOREKEEPER`; "inventory roles" (FR-144) vs `INVENTORY_MAINTENANCE`. §2.x human-facing prose may keep title case, but **within §4 feature text**, role tokens should be the constant form (e.g. `MANAGER_MAINTENANCE`, not "manager").

*Fix:* normalize §4 feature text to the §3 role constants; reserve title case for §2 persona prose.

### L2. Inline `[NOTE FOR PM:]` tags unindexed
`prd.md` §6.2 (lines 652, 656, 657, 658) carries four `[NOTE FOR PM: ...]` tags. Not assumptions, so §9 correctly excludes them, but there is no corresponding PM-facing surface and they are easy to lose.

*Fix:* either add a small "Notes for PM" subsection (§6.3) or leave as-is if PM reads §6.2 routinely.

### L3. FR-124 carries an inline "Out of Scope:" while no other FR does
`prd.md` lines 313–314. The only per-feature out-of-scope block in the doc.

*Fix:* fold into §6.2 ("Production-side dashboards — deferred") for uniformity, keeping FR-124 purely functional.

### L4. FR-110 index entry content vs inline fact duplication
Even after C1 is fixed, the §9 FR-110 entry and §3 Glossary both assert the id format; §9 entries should reference (not restate) glossary facts.

*Fix:* §9 FR-110 entry → "Internal id format `WO-YYMMxxxx` (see Glossary §3)."

### L5. §10 Phase 1 Contract References omits FR-080
`prd.md` §10 (lines 702–712) lists FR-078, NFR-013a, UX-DR-019, Epic 5, standard error shape — but not the `sparepart_price_entries` price-entry FR that FR-142/A5 depend on (Phase 1 Epic 8).

*Fix:* after H2, add the price-entry FR (FR-080) to §10 so the consumer does not need to chase the Phase 1 PRD to resolve the FR-142 cost basis.

---

## Summary counts

| Severity | Count |
|---|---|
| Critical | 1 (3 sub-broken roundtrip items: FR-110, FR-181, FR-116) |
| High | 3 |
| Medium | 4 |
| Low | 5 |
