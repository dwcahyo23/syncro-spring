# Prose Editorial Review — prd.md & addendum.md

Clinical copy-edit pass (Microsoft-style baseline). Prose only; no structural changes; domain vocabulary (`workorder`, `sparepart`, `ON_PROCUREMENT`, `sheet_no`, etc.) left stable. Issues are grouped high → medium → low. Each entry gives location, the exact original passage, and the exact suggested rewording.

---

## HIGH (blocks or seriously distorts comprehension)

### H1 — `prd.md:183` (4.2 Description) — word-order error reverses meaning
**Original:** "FMEA tag, todo/kanban, status-by-leader, ratings, and history machine derive from the workorder."
**Suggested:** "FMEA tag, todo/kanban, status-by-leader, ratings, and machine history derive from the workorder."
**Why:** "history machine" is an inverted compound — the intended term is "machine history" (as used in FR-118). As written, the sentence is unparseable without guesswork.

### H2 — `prd.md:487` (4.6 Description) — sentence fragment (missing subject)
**Original:** "OPA is the decision point for every authorization request. Deployed as a sidecar HTTP service; Spring backend calls it with a minimal input (subject, resource, action, context); the frontend consumes an allowed-actions endpoint for rendering."
**Suggested:** "OPA is the decision point for every authorization request. It is deployed as a sidecar HTTP service; the Spring backend calls it with minimal input (subject, resource, action, context), and the frontend consumes an allowed-actions endpoint for rendering."
**Why:** The second "sentence" begins with a past participle and has no subject; the chain of semicolons also forces three clauses into one run. Adding "It is" and replacing the second semicolon with a conjunction restores grammar.

### H3 — `prd.md:603` (FR-181) — run-on sentence + gerund-as-subject ambiguity
**Original:** "When a workorder has been in IN_PROGRESS (net of ON_PROCUREMENT) for more than 4 hours, a WAHA message is sent to the PRODUCTION_LEADER with a direct link; the link auto-authenticates the leader (bound to their registered WA number) and opens their task list showing which workorders are acknowledged vs not and which closed workorders still need their maintenance rating. Acknowledging is recorded in the timeline + audit."
**Suggested:** "When a workorder has been in IN_PROGRESS (net of ON_PROCUREMENT) for more than 4 hours, a WAHA message with a direct link is sent to the PRODUCTION_LEADER. The link auto-authenticates the leader (bound to their registered WA number) and opens a task list showing which workorders are acknowledged vs pending and which closed workorders still need the leader's maintenance rating. The acknowledgment is recorded in the timeline and audit."
**Why:** One sentence packs a condition, an event, and a nested "showing which… and which…" clause; splitting at the first semicolon eases reading. "Acknowledging is recorded" reads as if the verb itself is recorded — "The acknowledgment is recorded" is unambiguous. (Also standardizes the "vs" terminology — see M2.)

---

## MEDIUM (awkward, inconsistent, or ambiguous phrasing)

### M1 — `prd.md:102` & `prd.md:506` — comma splice (inconsistent with addendum)
**Original (102):** "the frontend uses it to render menus/buttons, enforcement remains server-side."
**Original (506):** "Allowed actions are recomputed from OPA per request; hiding is UX only, enforcement remains server-side."
**Suggested:** "the frontend uses it to render menus/buttons; enforcement remains server-side."
**Suggested (506):** "Allowed actions are recomputed from OPA per request; hiding is UX only; enforcement remains server-side." (or split into two sentences)
**Why:** Two independent clauses joined by a comma. The same idea is written correctly with a semicolon at `addendum.md:30` ("Hiding is UX only; enforcement remains server-side."), so the comma-splice version is also internally inconsistent.

### M2 — `prd.md:80`, `prd.md:608`, `addendum.md:44` — inconsistent contrast terminology
**Originals:** "which workorders are acknowledged vs not" (80); "acknowledged vs pending acks and rated vs unrated closed workorders" (608); "workorders acknowledged vs pending, and closed workorders rated vs unrated" (addendum 44).
**Suggested:** Standardize the contrast pair as "acknowledged vs pending" everywhere (e.g. 80: "showing which workorders are acknowledged vs pending and which closed workorders still need his maintenance rating"; 608: "The task list distinguishes acknowledged vs pending and rated vs unrated closed workorders").
**Why:** Three different phrasings ("vs not", "vs pending acks", "vs pending") describe the same UI distinction; a fixed pair prevents readers from wondering whether "not" and "pending" differ.

### M3 — `prd.md:371` — inconsistent taxonomy adjectives
**Original:** "SPAREPART with electric/mechanic taxonomy"
**Suggested:** "SPAREPART with electrical/mechanical taxonomy"
**Why:** The same pair appears as "mechanical/electrical" at `prd.md:318` and `prd.md:324`. "electric/mechanic" is both a different ordering and non-parallel adjective forms.

### M4 — `prd.md:145` — awkward "maintenance-leader+" phrasing
**Original:** "`machine_groups.section_id` is set via a maintenance-leader+ mutation and audit-logged."
**Suggested:** "`machine_groups.section_id` is set by a mutation from MAINTENANCE_LEADER or above, and the mutation is audit-logged."
**Why:** "maintenance-leader+ mutation" is cryptic; the doc already expresses the same idea as "role >= SECTION_LEADER" (`prd.md:210`), so "MAINTENANCE_LEADER or above" is the established idiom. (Also avoids the ambiguous attachment of "audit-logged".)

### M5 — `prd.md:613` — wrong preposition
**Original:** "Notifications must be non-blocking to business logic (outbox pattern, no inline send)."
**Suggested:** "Notifications must be non-blocking for business logic (outbox pattern, no inline send)."
**Why:** "non-blocking to X" is non-idiomatic; "for" (or "must not block business logic") is standard.

### M6 — `addendum.md:23` — ambiguous "push-back"
**Original:** "No push-back: external system has no API; MRE code is recorded manually as reference only."
**Suggested:** "No write-back: the external system has no API; the MRE code is recorded manually as a reference only."
**Why:** "push-back" is colloquial and ambiguous (could suggest pushback in a decision sense). The intended meaning is "no write-back to the external system", matching §5 "No live integration with the external procurement system".

### M7 — `prd.md:564` — fragment missing a verb
**Original:** "Insufficient-data state when fewer than 2 breakdown workorders exist."
**Suggested:** "An insufficient-data state is shown when fewer than 2 breakdown workorders exist."
**Why:** This is a complete sentence in the body of a requirement, not a bullet-list item; it needs a subject and verb.

### M8 — `prd.md:183` — awkward "safe under transaction+lock"
**Original:** "created internally (source INTERNAL, auto-generated `WO-YYMMxxxx`, safe under transaction+lock)"
**Suggested:** "created internally (source INTERNAL, auto-generated `WO-YYMMxxxx`, protected by transaction and row lock)"
**Why:** "safe under" is vague, and FR-110 already names the mechanism as "transaction + row lock" (`prd.md:193`); the phrasing here should match.

### M9 — `prd.md:82` — awkward "never misses which items"
**Original:** "Budi knows the maintenance status without chasing phone calls, and never misses which items still need his rating."
**Suggested:** "Budi knows the maintenance status without chasing phone calls and never misses an item that still needs his rating."
**Why:** "misses which items" takes an indirect question where a direct object is meant; "misses an item" reads naturally.

### M10 — `addendum.md:45` — missing article
**Original:** "security hardening noted as PRD §6.2 deferred item for multi-plant rollout."
**Suggested:** "security hardening is noted as a PRD §6.2 deferred item for multi-plant rollout."
**Why:** Missing indefinite article ("a … deferred item") and a dropped verb make the clause elliptical.

---

## LOW (minor polish; style-level)

### L1 — `prd.md:230` (FR-115 title) & `prd.md:633` — hyphenation of "multi repair sessions"
**Original:** "Record multi repair sessions" / "multi repair sessions, parent-child close rule"
**Suggested:** "Record multi-repair sessions" (both locations), or "Record multiple repair sessions".
**Why:** Compound modifier needs a hyphen or expansion.

### L2 — `prd.md:12`, `prd.md:380` (and similar) — "+" as a prose conjunction
**Original:** "dual-source: synced from the internal system + internally created" (12); "each transition records a timeline event + audit" (380).
**Suggested:** "synced from the internal system or created internally" (12); "each transition records a timeline event and an audit entry" (380).
**Why:** "+" is fine in compact spec labels but reads as shorthand inside flowing sentences; "or"/"and" carry the same meaning in prose.

### L3 — `addendum.md:43` — "that PRODUCTION_LEADER" reads oddly
**Original:** "opening the link auto-authenticates that PRODUCTION_LEADER."
**Suggested:** "opening the link auto-authenticates the corresponding PRODUCTION_LEADER."
**Why:** "that" is deictic without a prior specific mention; "the corresponding" clarifies which leader.

### L4 — `prd.md:16` — momentary misparse of "the single place the team runs"
**Original:** "Syncro is the single place the maintenance team runs and tracks every repair, preventive check, and sparepart purchase across plants."
**Suggested:** "Syncro is the single place where the maintenance team runs and tracks every repair, preventive check, and sparepart purchase across plants."
**Why:** The dropped relative "where" lets "runs" momentarily parse as intransitive ("the team runs and tracks…" is fine, but "the place … runs" collides).

### L5 — `prd.md:622` — redundant "self-service … self-"
**Original:** "No self-service technician self-rating — technicians are rated by their section leader."
**Suggested:** "No technician self-rating — technicians are rated by their section leader."
**Why:** "self-service" + "self-rating" repeats the self- idea; dropping the first preserves the point (self-rating is the real non-goal).

---

## Summary

| Severity | Count |
|----------|-------|
| High     | 3     |
| Medium   | 10    |
| Low      | 5     |
| **Total**| **18** |

Top prose issues: (1) word-order error "history machine" → "machine history" (H1); (2) sentence-fragment/dangling participle at §4.6 (H2); (3) inconsistent "acknowledged vs not / pending" terminology across prd and addendum (M2). No issues found in: frontmatter, code spans, state-machine strings, or quoted contract references (intentionally skipped).
