# PRD Quality Review — Syncro Maintenance Workorder, Preventive & OPA Authorization (2026-08-24)

## Overall verdict

This is a strong, decision-carrying internal-tool PRD: the execution core (workorders, preventive, sparepart, OPA) is dense and testable, the "operational without IoT, authorization as backbone" thesis is coherent, and the brownfield inheritance is handled well (FR-100+ reserved block, FR-078/Epic 5 reuse named). What's at risk is downstream execution: three Phase 1 contract references (standard error shape, NFR-013a, UX-DR-019) are unresolvable from this doc alone, the production-leader role is silently load-bearing without any access definition in a security-sensitive module, and a few parameter/NFR values (approval threshold, size limit, SLA window) are undefined where engineers will hit them first. Scope honesty and shape fit are good; Done-ness is adequate rather than strong purely on these gaps.

## Decision-readiness — strong

Decisions are stated as decisions, not considerations: derived section leadership over manual role assignment (FR-102 "without any additional role assignment"), OPA sidecar over WASM/IR (Assumption §4.6), browser-print over server PDF (§4.7), manual MRE (FR-145 "No auto-generation occurs"). Trade-offs name what was given up: cost fields "structurally anticipated … but not reported in v1" (§6.2), FMEA tag as "a hook for a future FMEA module" (§6.2), no telemetry dependency (§5). The six Open Questions (§8) are genuinely open — Q1, Q3, Q5 are real forks with no planted answer.

The one thing that reads as a buried decision: the PRD has already decided production leaders authenticate in-app to acknowledge the 4-hour ack (UJ-6 "they acknowledge it in the app"), which is a real product/access decision with no owner surfaced.

### Findings
- **[low]** In-app production-leader authentication is a buried decision (§2.3 UJ-6; FR-181) — the PRD commits to production leaders having an app login to acknowledge, yet no role, onboarding, or access decision is surfaced anywhere. *Fix:* promote to an Open Question or an `[ASSUMPTION]` with an index entry.

## Substance over theater — strong

Nothing here is furniture. All seven JTBD entries (§2.1) drive features: Manager→dashboards FR-170-174, Leader→plant scope FR-163, Section Leader→FR-102/103, Technician→FR-115/116, Storekeeper→FR-140-146, Production Leader→FR-181/UJ-6, Auditor→FR-164. The Vision (§1) is product-specific ("capture a breakdown workorder… preventive checks run on the calendar, not on counters") and cannot be swapped into another PRD unchanged. There is no NFR boilerplate — feature-specific NFRs (FR-147 rate limiting, FR-181 non-blocking, "WAHA credentials/phones must never be logged" §4.8) are concrete and product-anchored.

### Findings
- **[low]** Journey coverage skew (§2.3) — three of six UJs are section-leader journeys (UJ-1 Eko, UJ-3 Dini, UJ-4 Taryo) while Manager, Maintenance Leader, and Auditor — three of the seven JTBD — have no journey. Reads slightly journey-heavy for the roles that own the dashboards. *Fix:* add one manager or auditor journey, or trim to the highest-signal roles.

## Strategic coherence — adequate

The thesis is explicit and the features serve it: workorder lifecycle + calendar-based preventive + sparepart = "execution without IoT"; OPA = "authorization is the backbone, not an afterthought" (§1). Success Metrics validate the thesis rather than measure activity: SM-1 turnaround, SM-2 preventive compliance, SM-3 authorization correctness are outcome metrics, and a counter-metric exists (SM-C1 rating inflation against objective MTTR/on-time). SM↔FR cross-references all resolve.

Two coherence gaps. First, §6.1 In Scope is a flat list of nine feature areas; nothing distinguishes the must-have execution spine (workorder + OPA) from satellite capability (dashboards, WAHA) — only stock OP/OQ is even marked "optional feature". Second, the Production Leader JTBD claims they "rate the maintenance team" (§2.1) but no FR delivers production-leader rating; FR-121 is strictly section-leader-rates-technician, and Non-Goals only carve out "no self-service technician self-rating" (§5).

### Findings
- **[medium]** No intra-scope prioritization (§6.1) — nine feature areas listed flat with no must/should/could signal; downstream epic/story creation gets no ordering spine. *Fix:* tier the In Scope list (e.g. P0 execution spine: workorders/OPA/sync; P1: preventive/sparepart; P2: dashboards/WAHA).
- **[medium]** Production Leader "rate the maintenance team" (§2.1) is an orphan JTBD — no FR or consequence implements it, and §5 does not de-scope it. Either add an FR or move the claim to Non-Goals.

## Done-ness clarity — adequate

The bar is high here because this doc feeds story creation, and most FRs clear it: state machines are exact (FR-114 lifecycle, FR-141 request flow), testable consequences are crisp ("Invalid transitions return `INVALID_STATE_TRANSITION`", "Cumulative MTTR equals sum of session durations"), and metric definitions are precise where they count (FR-123 "Response time = time from OPEN to first IN_PROGRESS session start"; FR-173 "MTBF uses `woStopAt` ordering (not id) — avoids the reference bug"). This is the dimension's real strength.

The gaps are at the edges engineers hit immediately:
- Three consequences defer to Phase 1 contracts not resolvable in this doc: "returns standard error shape" (FR-100, FR-160), "Frontend never calls OPA directly (NFR-013a)" (FR-161), "render per UX-DR-019" (FR-170). A story writer cannot call something done without opening the Phase 1 PRD for each.
- FR-142: "Approving a request (>= threshold)" — the threshold's basis is never defined (cost? quantity? role tier?). FR-144 only captures *estimated* price on new-item completion, so there is no cost basis in the data model for a spend threshold.
- Unbounded parameters: FR-116 "size limit enforced" with no value; SM-5/FR-150 "sync job succeeds within SLA window" with no window; FR-173 "Units/window/freshness documented" is a meta-requirement, not a consequence.
- Role vocabulary is used but never anchored: "maintenance-leader+" (FR-101), "admin" (FR-121), "manager-global" (FR-174). Roles are not in the Glossary at all.

### Findings
- **[high]** Cross-PRD contract references unresolvable in-doc (FR-100, FR-160, FR-161, FR-170) — "standard error shape", "NFR-013a", "UX-DR-019" gate done-ness but their definitions live in the Phase 1 PRD. *Fix:* either re-state the contract inline or add a Phase 1 cross-reference appendix with the exact definitions.
- **[high]** FR-142 approval threshold basis undefined (§4.4) — "(>= threshold)" has no definition and no cost field exists to base a spend threshold on. *Fix:* define the threshold (e.g. cost via request quantity × material price) or move to Open Questions with a default.
- **[medium]** Parameter/NFR values unspecified — FR-116 upload size limit has no bound; SM-5/FR-150 "SLA window" is undefined while SM-5 depends on it; FR-173 defers "Units/window/freshness documented" without saying where. *Fix:* put concrete defaults inline (e.g. 10MB, sync SLA window 15 min) with config reference.
- **[medium]** Undefined role vocabulary — "maintenance-leader+" (FR-101), "admin" (FR-121), "manager-global" (FR-174) reference a role ladder never defined; roles are absent from the Glossary. *Fix:* add a role taxonomy to the Glossary and use exact role names in consequences.

## Scope honesty — adequate

Non-Goals (§5) are explicit and do real work — telemetry, procurement integration, OEE, technician self-rating are all carved out in terms that prevent silent assumption. §6.2 de-scopes honestly, including the two `[NOTE FOR PM]` callouts at genuine tensions (cost fields structurally anticipated; FMEA tag as future hook). Open-question density is proportionate: 6 OQs + 8 assumptions + 2 NOTE FOR PMs on a status-`draft` internal-tool PRD is not a blocker.

The failure is the production-leader role: a v1 user with a JTBD, a UJ, and an FR (FR-181 sends them the 4-hour ack), whose in-app access is never defined — not in org (4.1), not in OPA input (FR-163 lists "roles, plants, sections, machine groups, and active teams" with no production-leader role), and not flagged as an assumption or open question. For an authorization-heavy module this is precisely the kind of omission that should carry a tag and an index entry.

### Findings
- **[high]** Production-leader role is a silent omission (§2.1, §2.3 UJ-6, FR-181, FR-163) — a v1 user with a journey and a notification FR has no defined role, scope, or OPA access, and this is neither a Non-Goal nor an Assumption. *Fix:* add a role/access definition (or a Non-Goal for v1 in-app ack) and an index entry.

## Downstream usability — adequate

ID discipline is excellent: FR blocks are contiguous with intentional reserved gaps, all seven SM↔FR references resolve, and §0 explains the FR-100+ scheme explicitly. Sections pull out well — descriptions reference "Realizes UJ-x" resolvable in-doc, and the Glossary (§3) anchors the core nouns (workorder, repair session, material code, ON_PROCUREMENT semantics).

Three things burden the next workflow. First, the Phase 1 references (UX-DR-019, NFR-013a, standard error shape, FR-078) force the consumer to chase another document mid-extraction. Second, the Assumptions Index roundtrip is one-directional: all eight `[ASSUMPTION]` tags live only in §9, with no inline tag at the point of use in §4 (verified — the body contains none). Third, the workorder and sparepart-request state machines are defined only in §4.2/§4.4 prose, not anchored in the Glossary, so story creation must scan prose to reconstruct canonical enums.

### Findings
- **[medium]** Phase 1 cross-references break self-containment (FR-161 NFR-013a, FR-170 UX-DR-019, Glossary FR-078) — each requires opening the Phase 1 PRD mid-extraction. *Fix:* inline the referenced contract or link with a resolved path in §0's reference list.

## Shape fit — adequate

The shape is right for the product: an internal, multi-role maintenance tool gets a capability-spec form (FRs grouped by feature area) with UJs only where they carry role-bearing journeys — not the persona-theater density the rubric warns about. Brownfield handling is genuinely good: the FR-100+ numbering avoids Phase 1 collision, Epic 8 FR-078 reuse is noted in the Glossary, and Epic 5 patterns (outbox/worker, rate limit, circuit breaker) are named in FR-147/FR-181.

The fit weaknesses are role-coherence nits rather than shape problems: UJ-2's "Workshop Leader" (Pak Dayat) is a protagonist who does not exist in the JTBD list or role taxonomy — presumably the section leader of the WORKSHOP section, but never stated — and the over-weighted section-leader journeys (see Substance) slightly over-formalize the roles that matter least to the thesis.

### Findings
- **[low]** "Workshop Leader" protagonist undefined (UJ-2) — not in §2.1 JTBD nor the role taxonomy; if it means the WORKSHOP section's leader, say so. *Fix:* map to "Section Leader (WORKSHOP section)" in the UJ header.

## Mechanical notes

- **Assumptions Index roundtrip is broken one-way.** All eight `[ASSUMPTION: …]` tags appear only in §9 (lines 642–649); there are **zero** inline `[ASSUMPTION]` tags in the §4 feature bodies. Index entries must be repeated inline at the point of use.
- **UJ protagonists not uniformly named.** UJ-1 Eko, UJ-2 Dayat, UJ-3 Dini, UJ-4 Taryo carry names; UJ-5 ("storekeeper; a sparepart's stock…") and UJ-6 ("a breakdown workorder has been IN_PROGRESS…") have no named protagonist.
- **State enums not in Glossary.** Workorder lifecycle (`DRAFT → OPEN → ASSIGNED → IN_PROGRESS → ON_PROCUREMENT → …`) and sparepart-request flow (`REQUESTED → ACKED → PROCESSING → …`) are defined only in §4.2/§4.4 prose.
- **Glossary case/synonym drift (minor).** "Cross-Plant Team" (Glossary) vs "cross-plant team" (§4.1); "MRE Code" (Glossary) vs "MRE code" (FR-145); "Sparepart Request" (Glossary) vs "sparepart request" (body). Single-word "sparepart"/"workorder" is otherwise consistent.
- **FR-116 type-list inconsistency.** Description says technical drawings "(PDF/JPEG/PNG)" but the consequence adds `image/webp` to accepted types; align.
- **ID continuity is clean.** No duplicates; reserved gaps (106-109, 124-129, 135-139, 148-149, 155-159, 165-169, 176-179) are intentional blocks. SM↔FR references all resolve.
- **Required sections present** for an internal-tool, chain-top PRD: Vision, JTBD, Glossary, FRs with consequences, Non-Goals, MVP Scope, Success Metrics with counter-metric, Open Questions, Assumptions Index. No dedicated NFR section — acceptable if Phase 1 carries module NFRs, but this module's new surfaces (OPA request latency, sync reliability) have no bounds here (see Done-ness finding).
