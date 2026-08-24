---
name: Technology Verification Review — Syncro Maintenance & OPA Authorization
type: review
target: _bmad-output/planning-artifacts/architecture/architecture-Syncro-2026-08-24/ARCHITECTURE-SPINE.md
reviewer: technology-verification (web-researched 2026-08-24)
created: 2026-08-24
---

# Technology Verification Review — Architecture Spine

**Verdict:** The spine's committed technology decisions are overwhelmingly well-researched and current. Every OPA-specific claim checks out against OPA's own documentation, including the exact `POST /v1/data/syncro/authz/allow` decision-path format and the sidecar-HTTP integration pattern. OPA 1.19.1 is real and is the current latest release, verified on GitHub on the same date the spine cites. The only material gap is the inherited **Next.js 16.2.6** pin, which sits below the security-patched `16.2.11` and is being pushed toward the 16.3 line; one inherited **Spring Boot 4.0.6** note (not current in its own 4.0.x patch line). All other named technologies (Garage, WAHA, TanStack Table v9, java-opa-sdk, Java 25) were confirmed to exist and fit.

Sources consulted (2026-08-24): GitHub `open-policy-agent/opa` releases; OPA docs `rest-api`, `integration`, `ir`, `wasm`; `open-policy-agent/java-opa-sdk` repo; Next.js release blog; spring.io Spring Boot project page + releases + system-requirements; npm registry `@tanstack/react-table`; garagehq.deuxfleurs.fr; `devlikeapro/waha`.

---

## Findings by severity

### HIGH

**H1 — Next.js `16.2.6` (spine Stack table, line 183; Structural Seed line 207) is below the security-patched 16.2.11 and behind the current 16.3 line.**
- What the web confirms:
  - Next.js 16.2 is real (GA Mar 2026) and 16.2.6 is a valid 16.2 patch. But the July 20, 2026 security release states: *"Upgrade to 16.2.11 (Active LTS) or 15.5.21 (Maintenance LTS) now to address 4 HIGH and 5 MEDIUM severity vulnerabilities."*
  - 16.3 is now available (Aug 2026), and an Aug 26, 2026 scheduled security release patches **only 16.3 and 15.5** — i.e. 16.2.x is exiting active security support, so 16.2.6 is both unpatched against known HIGH CVEs and approaching end-of-life.
- Impact: The spine records 16.2.6 as "inherited," which is an accurate provenance tag, but it should not be read as a current/verified pin. The frontend is being actively built against this stack; shipping on 16.2.6 means starting from a version with 4 HIGH + 5 MEDIUM known fixes missing.
- Fix: Bump the inherited manifest to `>=16.2.11` immediately (one patch release), and note in the spine's Stack table that the frontend should move to 16.3 for continued security support. Add a "security-reviewed" note rather than bare "inherited."

---

### MEDIUM

**M1 — Spring Boot `4.0.6` (spine Stack table, line 182) is real and Java 25-compatible, but not current in its own patch line.**
- What the web confirms:
  - `4.0.6` exists on GitHub releases; the 4.0.x line now ends at **4.0.8** (released Aug 21, 2026); current stable is **4.1.1** (Aug 20, 2026) with **4.2.0-M1** in preview.
  - Spring Boot 4.1.x system-requirements: *"requires at least Java 17 and is compatible with versions up to and including Java 26."* Java 25 is therefore fully within the supported range — the `Java 25 / Spring Boot 4.0.6` pairing is valid.
- Impact: Not wrong, and correctly marked "inherited, authoritative," but the "authoritative" label is stale by two patches (4.0.7, 4.0.8) in the same line and one minor (4.1.x). Since Spring Boot patch releases are bug/security fixes, 4.0.6 is mildly behind.
- Fix: Bump to `4.0.8` (low-risk patch within the same minor) when the manifest is next touched; keep "inherited, authoritative" provenance. Optionally note 4.1.x as the current line for the next major upgrade.

---

### LOW / confirmations (claims verified, no change required unless noted)

**L1 — OPA `1.19.1` (lines 152, 185): CONFIRMED, no change.**
- GitHub releases: `v1.19.1` is tagged **[Latest]**, released 17 Aug 2026 — matching the spine's "verified 2026-08-17 (GitHub releases)" note exactly. It is a Go-1.26.6 rebuild (stdlib CVE fixes) on top of v1.19.0.

**L2 — Sidecar HTTP is the OPA-documented integration for non-Go apps (lines 22, 31, 56, AD-1): CONFIRMED, no change.**
- OPA integration docs verbatim: *"To integrate with OPA outside of Go, deploy OPA as a host-level daemon or sidecar container."* and *"Integrating OPA via the REST API is the most common, at the time of writing. OPA is most often deployed either as a sidecar or less commonly as an external service."* Embedded evaluation (Go SDK/WASM/IR) is documented as the alternative, not the default. The sidecar choice is the canonical documented path for a Java/Spring app.

**L3 — Data API path format `POST /v1/data/syncro/authz/allow` (lines 31, 152): CONFIRMED, no change.**
- OPA REST API reference defines `POST /v1/data/{path:.+}` with body `{"input": ...}`; the integration docs' example is literally `POST /v1/data/example/authz/allow` for `package example.authz` rule `allow`. The spine's path matches OPA's documented `<package path>/<rule name>` convention. Bonus: OPA returns HTTP 200 with `{"result": true}` (or `{}` when undefined) — worth codifying in the PolicyDecisionPoint's error/undefined handling.

**L4 — Sidecar management surface (bundles + status + decision logs, lines 33, 153): CONFIRMED.**
- OPA documents the Bundle API, Status API, Decision Log API, and Health API; `opa test` is the documented CLI policy-testing command. All are real and current.

**L5 — "`java-opa-sdk` (embedded IR) is early-stage" (Deferred, line 232): CONFIRMED and fair.**
- `open-policy-agent/java-opa-sdk` is real and is now the **official** OPA Java SDK for IR plans (referenced from OPA's integration docs: *"to evaluate IR plans directly in your application, see Swift-OPA for Swift and java-opa-sdk for Java"*). It is actively maintained (commits within hours). But it is **v0.3.0** (pre-1.0, Maven Central `io.github.open-policy-agent`), ~15 stars / 23 forks / 40 open issues, and implements 139+ of ~180 builtins (~77%) with weak categories (crypto 57%, encoding 53%, conversions 33%). "Early-stage" is an accurate characterization of maturity/risk, not an error.

**L6 — "WASM lacks many built-ins" (Deferred, line 232): CONFIRMED and accurate.**
- OPA WASM docs: *"The core language is supported fully but there are a number of built-in functions that are not, and probably won't be natively supported in Wasm (e.g., `http.send`). Built-in functions that are not natively supported can be implemented in the host environment."* Unsupported builtins surface via the `opa_builtinN` import callbacks, so a WASM-embedded policy needing `http.send` or crypto JWT builtins requires host-side implementations. This validates keeping the sidecar as the default and deferring WASM/IR until latency is measured.

**L7 — Garage S3-compatible storage (lines 47, 110, 158, 187): CONFIRMED, no change.**
- Garage is alive and actively funded (NLnet/NGI0 Commons Fund 2025). Site: *"Garage implements the Amazon S3 API and thus is already compatible with many applications."* Single dependency-free binary, low footprint (1 GB RAM). Fits the evidence-images/drawings use case. (Note: the earlier `github.com/deislabs/garage` reference is stale — the project lives at `garagehq.deuxfleurs.fr` / `git.deuxfleurs.fr/Deuxfleurs/garage`. Verify the "existing `ObjectStorageService`" endpoint uses that canonical project.)

**L8 — TanStack Table `v9 (latest)` (line 184): CONFIRMED, no change.**
- npm `@tanstack/react-table` `latest` = **9.1.2**; v9 is the current major. "v9 (latest)" is accurate. Note v9 requires React >= 18 / Node >= 20 — compatible with the Next.js 16.x / React 19 stack.

**L9 — WAHA (lines 46, 159, 228): CONFIRMED, no change.**
- `devlikeapro/waha` (WhatsApp HTTP API) is real, active (7.3k stars, 3 engines: WEBJS/NOWEB/GOWS), Apache-2.0. Fits the notification-outbox integration.

**L10 — Java 25 (line 182): CONFIRMED within Spring Boot 4.x support.**
- Spring Boot 4.1.x supports Java 17–26; Java 25 is a GA JDK (Sept 2025 release train). Valid pairing with Spring Boot 4.0.x.

**L11 — Incidental mentions (Resilience4j, Orval, Flyway, PostgreSQL, /api/v1 REST): no out-of-date risk found.**
- Resilience4j is a maintained resilience library; Orval is the established OpenAPI→TypeScript client generator; Flyway + PostgreSQL + `ddl-auto=validate` are standard, non-version-sensitive claims. No verification issues.

---

## Consolidated stack table

| Name (spine) | Spine claim | Verified state (2026-08-24) | Verdict |
| --- | --- | --- | --- |
| OPA | 1.19.1, sidecar, `POST /v1/data/syncro/authz/allow` | v1.19.1 = [Latest] 17-Aug-2026; sidecar HTTP = documented non-Go pattern; path format exact | Confirmed |
| OPA management | bundle + status + decision log | Bundle/Status/Decision-Log/Health APIs documented | Confirmed |
| Spring Boot | 4.0.6 (inherited) | Real; 4.0.8 current in-line, 4.1.1 current stable | Real, slightly stale — M1 |
| Java | 25 | Supported range 17–26 (SB 4.1.x) | Confirmed |
| Next.js | 16.2.6 (inherited) | Real but < 16.2.11 (4 HIGH/5 MED CVEs); 16.3 current, 16.2 exiting security support | **Flag — H1** |
| TanStack Table | v9 (latest) | npm latest = 9.1.2 | Confirmed |
| Garage | S3-compatible, inherited | Active, S3-API compatible | Confirmed |
| WAHA | inherited | Active, WhatsApp HTTP API | Confirmed |
| java-opa-sdk (deferred) | "early-stage" IR | Official OPA SDK, v0.3.0, 139+/180 builtins | Confirmed fair |
| WASM built-ins (deferred) | "lacks many built-ins" | OPA docs confirm subset + host-side requirement | Confirmed |

---

## Recommended spine changes

1. **H1:** Stack table (line 183) — replace bare `16.2.6 | inherited` with `>=16.2.11 | inherited; security-reviewed 2026-08-24; plan 16.3`. If the manifest bump is out of scope for the spine, add an explicit note that 16.2.6 is known-vulnerable until bumped.
2. **M1:** Stack table (line 182) — note `4.0.8` is the current 4.0.x patch and `4.1.x` the current line; keep "inherited, authoritative."
3. **L7 (cosmetic):** Correct the stale Garage repo association if it appears anywhere in the companion docs (`git.deuxfleurs.fr/Deuxfleurs/garage`).
4. **L3 (optional hardening):** AD-1 (line 56) — add one sentence codifying OPA's response semantics for undefined decisions (HTTP 200, empty `result` → default-deny on the client), since default-deny is the spine's stated posture.
5. **L5 (optional):** Deferred (line 232) — update the embedded-OPA note to reflect that java-opa-sdk is now the official OPA SDK (still pre-1.0 / ~77% builtin coverage), so the revisit trigger can name concrete builtin gaps (crypto, encoding) rather than a generic "early-stage."

No committed OPA decision in the spine is contradicted by current web-verifiable facts.
