# PRD Quality Review — Syncro Maintenance Workorder, Preventive & OPA Authorization (re-validation, 2026-08-24)

## Overall verdict

The revision pass closed the prior review's load-bearing gaps: the production-leader role is now defined, priced and delivered as a first-class actor (Glossary roles §3, FR-110/124/181); approval threshold, upload bound, and the rating-dimensions config all have concrete defaults; §10 re-states every Phase 1 contract the FRs depend on; and §6.1 now carries a P0/P1/P2 spine. This is a strong, decision-carrying PRD that now matches its own ambition — six of seven dimensions hold up cleanly. The one residual risk is exactly where the prior review flagged it and the revision only half-fixed it: the numeric/NFR defaults in Done-ness (sync SLA window unbounded, FR-173 a meta-consequence). Everything else is nits.

## Decision-readiness — strong

Every previously buried or dangling decision is now surfaced and owned. The production-leader in-app access question is fully decided: PRODUCTION_LEADER role defined (§3), auto-login mechanism specified with phone-number binding and normal-login fallback (FR-181), the trust model deferred honestly with a `[NOTE FOR PM]` on PIN/OTP hardening (§6.2), and indexed as an assumption (§9 FR-181). FR-142 now states its cost basis and default tiers as a decision, not a placeholder. Addendum A1 (TanStack Table v9) and A4 (OPA sidecar wiring) are genuine mechanism decisions with rejected-alternative rationale (card/grid-first UX rejected; WASM/IR rejected). Open Questions (§8) remain genuinely open — Q3, Q5, Q7 are real forks with no planted answer.

### Findings
- **[low]** FR-142 no-est-price rule reads ambiguously (§4.4) — "the request falls below threshold and requires section-leader approval regardless of quantity" couples two phrases that fight ("below threshold" vs "regardless of quantity"). A cost that can't be computed is being pinned to the base approval tier, which is a defensible rule — but it reads like leftover placeholder prose. *Fix:* "when est. price is unavailable, the request is approved at the base (section-leader) tier; quantity does not escalate it."

## Substance over theater — strong

Still nothing here is furniture, and the revision added rather than padded. All seven JTBD entries (§2.1) drive features; the Production Leader JTBD now has three (FR-110 breakdown creation, FR-181 4-hour ack, FR-124 rating). Journey coverage widened with UJ-6 (Budi, Production Leader) and a named UJ-5 (Rina) — the section-leader tilt is reduced, not eliminated, but every remaining journey carries role-bearing path detail. The Vision (§1) is unchanged and still product-specific ("preventive checks run on the calendar, not on counters"). Feature-specific NFRs (§4.8) stay concrete ("WAHA credentials/phones must never be logged").

### Findings
- **[low]** Journey coverage still skews section-leader (§2.3) — UJ-1, UJ-3, UJ-4 (plus UJ-2, a workshop/section journey) versus none for Manager, Maintenance Leader, or Auditor — the three roles that own the dashboard surface (§4.7). *Fix:* one short Manager or Auditor journey, or a line in §2.3 noting dashboards are covered by UJ-1/3/4.

## Strategic coherence — strong

Both prior coherence gaps are closed. §6.1 now has a real prioritization spine (P0 execution spine → P1 full maintenance execution → P2 insight & quality) that matches the thesis: workorder/OPA/sync first, insight after execution is operational. The orphan production-leader rating JTBD is implemented by FR-124, which is correctly separated from technician ratings (bound to the workorder, separate quality view). SMs validate the thesis (SM-1 turnaround, SM-2 compliance, SM-3 zero-permission-violation) with the counter-metric (SM-C1) intact. SM↔FR references all resolve.

## Done-ness clarity — adequate

The mass of FRs clears a high bar: state machines exact, consequences crisp ("Invalid transitions return `INVALID_STATE_TRANSITION`", "Cumulative MTTR equals sum of session durations"), the previously unresolvable Phase 1 contracts now re-stated in-doc (§10), and the threshold/role-bound defaults that were missing are now present. But the revision closed only one of the two unbounded-value gaps it was handed, in the exact dimension story creation leans on hardest — so this stays adequate rather than rising to strong.

### Findings
- **[medium]** SM-5 sync SLA window still unbounded (§7, FR-150) — "sync job succeeds within SLA window" remains an undefined term that SM-5 measures against; an engineer still cannot call FR-150/FR-151 done or compute SM-5. *Fix:* inline a default, e.g. "succeeds within a configurable window; v1 default 15 minutes (Phase 1 Epic 6 interval)".
- **[low]** FR-173 consequence is a meta-requirement (§4.7) — "Units/window/freshness documented" states that documentation must exist but not what the units/window are or where they live. *Fix:* state units (hours), the default window (e.g. rolling 12 months), and a freshness definition inline.
- **[low]** FR-142 no-price rule ambiguity (see Decision-readiness) also affects done-ness — the boundary behavior of an unpriceable request is a testable consequence but worded so a test writer cannot be sure of the expected tier.

## Scope honesty — strong

The production-leader silent omission is fully resolved: role defined, access surfaced in FRs, security hardening explicitly deferred (§6.2 "Auto-login WA link security hardening… v1 trusts the phone number matching") with a `[NOTE FOR PM]` at a genuine tension. Non-Goals (§5) still do real work. §6.2 de-scopes honestly with three NOTE FOR PM callouts at real deferrals (cost fields, FMEA hook, shift-config operating days). Open-items density is proportionate: 7 OQs + 11 assumptions + 3 NOTE FOR PMs on a status-`draft` internal-tool PRD.

## Downstream usability — strong

§10 Phase 1 Contract References makes the doc self-contained for the five contracts the FRs depend on (standard error shape, NFR-013a, UX-DR-019, FR-078, Epic 5 patterns) — the prior self-containment break is fixed. State enums are now anchored in the Glossary (§3 "Workorder lifecycle", "Sparepart request lifecycle") in addition to §4 prose and A2. All six UJs have named protagonists. The role taxonomy (§3) anchors the vocabulary that was previously floating — though two informal references survived the pass (mechanical notes).

## Shape fit — strong

Capability-spec shape remains right for a multi-role internal tool, with UJs only where they carry role-bearing journeys and brownfield handling still genuinely good (FR-100+ reserved block, FR-078/Epic 5 reuse named). The addendum adds technical depth (TanStack Table v9 decision, OPA wiring, state enums, cost basis) without bloating the PRD body — the right division of labor for a chain-top doc.

### Findings
- **[low]** UJ-2 "Workshop Leader" protagonist still not explicitly mapped (§2.3) — the new role taxonomy makes the inference safe (SECTION_LEADER over the WORKSHOP section), but the UJ header still doesn't say it. *Fix:* "Dayat (Section Leader, WORKSHOP section)". Non-blocking.

## Mechanical notes

- **Assumptions Index roundtrip now two-way except two entries.** 10 of 12 assumptions carry inline `[ASSUMPTION: …]` tags at the point of use (§4.1 FR-101, §4.2 FR-113/116/121, §4.3 FR-130, §4.4 FR-141/142, §4.6 FR-160/162, §4.7 FR-175). Two index entries — **§4.2 FR-110** (WO-YYMMxxxx id format) and **§4.8 FR-181** (WA link auto-login phone binding) — are indexed as "[ASSUMPTION inline]" but have **no inline tag** in the body. Note FR-110's id format is also asserted as fact in the Glossary and in FR-110's consequence, so the index entry is arguably stale regardless. *Fix:* add inline tags at FR-110 and FR-181, or drop the entries.
- **Residual informal role references.** "maintenance-leader+" (FR-101) and "manager-global" (FR-174) survive the new role taxonomy; "admin" (FR-121) was correctly fixed to SUPER_ADMIN/MANAGER_MAINTENANCE. *Fix:* map the two survivors to exact role names.
- **§0 does not reference addendum.md.** Downstream workflows that read only prd.md will miss the TanStack Table v9 decision (A1) that FR-119/FR-170-174 tabular views depend on. *Fix:* one line in §0 pointing at the addendum.
- **Glossary case/synonym drift persists (minor):** "Cross-Plant Team" (Glossary) vs "cross-plant team" (§4.1); "Sparepart Request" (Glossary + §4.4 title) vs "sparepart request" (body). Single-word "sparepart"/"workorder" otherwise consistent.
- **FR-116 type-list inconsistency resolved** (WebP now in both description and accepted-types consequence).
- **ID continuity clean:** no duplicates; reserved gaps (106–109, 124–129, 135–139, 148–149, 155–159, 165–169, 176–179) intentional; SM↔FR references all resolve; OQ numbering now 1–7.
- **Required sections present** for an internal-tool, chain-top PRD, now including §10 cross-references. No dedicated NFR section — acceptable if Phase 1 carries module NFRs, but this module's new surfaces (sync SLA, see Done-ness) still want a bound.

## Prior-findings resolution

| # | Prior finding (severity, dimension) | Status | Evidence in revision |
|---|-------------------------------------|--------|----------------------|
| 1 | In-app production-leader auth is a buried decision (low, Decision-readiness) | **Resolved** | PRODUCTION_LEADER role §3; FR-181 auto-login + phone binding + mismatch fallback; assumption indexed §9; §6.2 hardening deferral with NOTE FOR PM; addendum A7 |
| 2 | Journey coverage skew, Manager/Leader/Auditor have no journey (low, Substance) | **Partially resolved** | UJ-5 named (Rina), UJ-6 added (Budi, Production Leader); Manager/Maintenance Leader/Auditor still journey-less |
| 3 | No intra-scope prioritization in §6.1 (medium, Strategic) | **Resolved** | §6.1 now P0/P1/P2 tiers with explicit feature grouping |
| 4 | Production-leader rating JTBD orphaned, no FR (medium, Strategic) | **Resolved** | FR-124 implemented; separated from technician ratings |
| 5 | Cross-PRD contract refs unresolvable in-doc (high, Done-ness) | **Resolved** | §10 re-states standard error shape, NFR-013a, UX-DR-019, FR-078, Epic 5 patterns |
| 6 | FR-142 approval threshold basis undefined (high, Done-ness) | **Resolved** | FR-142 cost = qty × est. price; tier defaults (≤5M / 5M–50M / >50M IDR) inline + §A5 |
| 7 | Parameter/NFR values unspecified — FR-116 bound, SM-5 SLA, FR-173 (medium, Done-ness) | **Partially resolved** | FR-116 fixed (10 MB assumption); SM-5 SLA window still undefined; FR-173 still meta |
| 8 | Undefined role vocabulary — maintenance-leader+, admin, manager-global (medium, Done-ness) | **Partially resolved** | Role taxonomy added §3; "admin" fixed; "maintenance-leader+" (FR-101) and "manager-global" (FR-174) remain |
| 9 | Production-leader role silent omission (high, Scope honesty) | **Resolved** | Role defined §3; FR-110/124/181; FR-163 input includes roles + A4 production-line scope; §6.2 deferral |
| 10 | Phase 1 refs break self-containment (medium, Downstream) | **Resolved** | §10 self-contained contract definitions |
| 11 | Workshop Leader protagonist undefined (low, Shape fit) | **Partially resolved** | Role taxonomy makes the mapping inferable; UJ-2 header still not explicit |
| M1 | Assumptions roundtrip one-way (tags only in §9) | **Partially resolved** | 10/12 inline now; FR-110 and FR-181 entries still lack inline tags |
| M2 | UJ protagonists not uniformly named | **Resolved** | UJ-5 Rina, UJ-6 Budi |
| M3 | State enums not in Glossary | **Resolved** | Both lifecycle enums in Glossary §3 + A2 |
| M4 | Glossary case/synonym drift | **Unresolved** | Cross-Plant Team, Sparepart Request drift persists (minor) |
| M5 | FR-116 type-list inconsistency | **Resolved** | WebP in both description and consequence |
| M6 | ID continuity | **Resolved** | Still clean |
| M7 | Required sections present | **Resolved** | §10 added; otherwise complete |
