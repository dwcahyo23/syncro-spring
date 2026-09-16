---
title: 'Stamp leader signature & company logo into completed work-order print templates'
type: 'feature'
created: '2026-09-16'
status: 'done'
baseline_commit: 038fd84
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Completed work-order reports must carry the section leader's signature and the company logo, positioned inside the admin-authored WYSIWYG template. Today there is no way to create a stored signature through the UI (the `/auth/user-signatures` backend exists but has zero frontend consumers), the print pipeline has no image tokens, and the leader's identity is never resolved from the machine→group→section relationship chain.

**Approach:** Leaders draw/upload their signature once on a settings page (Privy-style: password-verified at save, like the retired SYNCRO NestJS app). Printing a work order in a terminal status resolves its section leader via existing DB relations and exposes presigned logo + leader-signature URLs; the template substitution layer turns new `{{logo}}` and `{{tandaTangan}}` tokens into `<img>` tags inside the rendered layout.

## Boundaries & Constraints

**Always:** Signature resolution walks the existing chain only: `work_orders.machine_id → machines.machine_group_id → machine_groups.section_id → sections.leader_user_id → user_signatures.user_id (UNIQUE)`; when `section_id` or `leader_user_id` is null, fall back to `machine_responsibilities WHERE machine_id = ? AND level = 'LEADER'` (earliest assignment). No new tables, no new FKs. Stamp only when WO status ∈ {PENDING_REVIEW, CLOSED}; any other status renders no signature. Images render via short-lived presigned URLs resolved at print time — never store presigned URLs in template HTML. Password verification at signature save mirrors the old repo: bcrypt compare + `signature_failed_attempts`/`signature_blocked_until` (3 strikes, already in schema) with 15-minute block; wrong password never leaks whether a signature exists. Null-tolerant presign everywhere (presign failure → no image, report still prints — `PmExecutionReportService.buildSignature` precedent). The existing per-WO approve flow (`signature_uses`, file upload at approve) is untouched.

**Ask First:** Changing the terminal-status set; making print-time stamping write a `signature_uses` audit row; extending tokens to the PREVENTIVE template; any canvas JSON endpoint (reuse multipart POST instead).

**Never:** No Privy/external e-signature integration, no PDF backend, no base64 data-URL storage, no storing object keys inside template `body_html`, no `style`/`data-*` additions to the sanitizer allowlist.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy: WO CLOSED, leader has signature | print-report GET | `leaderSignaturePresignedUrl` populated; `{{tandaTangan}}` renders `<img class="print-signature">` | N/A |
| WO OPEN/IN_PROGRESS/PENDING_SPAREPART | print-report GET | `leaderSignaturePresignedUrl: null`; token renders as empty text node (no broken image) | N/A |
| Section has no leader, machine has LEADER responsibility | CLOSED WO | fallback user's signature rendered | N/A |
| Neither section leader nor responsibility, or no stored signature | CLOSED WO | null URL; token renders nothing; report prints | N/A |
| Save signature with wrong password | POST user-signatures | 401 INVALID_CREDENTIALS; attempts++, at 3 → 423 SIGNATURE_BLOCKED until unblock window | lockout columns |
| Canvas export save | page → toBlob PNG | same multipart endpoint, contentType `image/png` | client guard: reject empty canvas |
| `{{logo}}` token, logo unconfigured | print page | `logoPresignedUrl: null` → renders nothing | N/A |

</frozen-after-approval>

## Code Map

- `syncro/apps/backend/.../maintenance/application/WorkorderPrintReportService.java` — `get()` aggregate; add leader-signature resolution beside `buildSignature` (status-gated); machine already loaded via `findByIdWithPlantAndGroup`. DTOs: `maintenance/api/WorkorderPrintReportDtos.java` (`PrintReportSignatureView` precedent).
- `syncro/apps/backend/.../org/infrastructure/SectionRepository.java` (`findById`), `.../org/infrastructure/SectionEntity.java` (`getLeaderUserId()`), `.../masterdata/infrastructure/MachineGroupEntity.java` (`getSectionId()`), `.../machine/infrastructure/MachineResponsibilityRepository.java` (`findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc`), `.../auth/infrastructure/UserSignatureRepository.java` (`findByUserId`), `.../storage/application/ObjectStorageService.java` (`presignGetUrl`).
- `syncro/apps/backend/.../auth/api/UserSignatureController.java` + `.../auth/application/UserSignatureService.java` — add required `password` multipart part + lockout handling; `PasswordEncoder` bean exists (auth module).
- `syncro/apps/web/src/components/syncro/print-template-view.tsx` — pipeline is `substitutePlaceholders(sanitizeHtml(html), values)`; extend values contract for image tokens.
- `syncro/apps/web/src/lib/print-template.ts` — `REPORT_TEMPLATE_PLACEHOLDERS.WORK_ORDER` += `logo`, `tandaTangan`; `WorkOrderPlaceholderValues` type is the compile-time contract; `substitutePlaceholders` (text-node TreeWalker) needs an image-token branch.
- `syncro/apps/web/src/features/workorders/components/workorder-print-report.tsx` — already fetches logo (`useCompanyLogo`); wire new `leaderSignaturePresignedUrl` into the view passed to `PrintTemplateView` (~line 266).
- `syncro/apps/web/src/components/syncro/rich-text-editor.tsx` — placeholder dropdown is generic over `REPORT_TEMPLATE_PLACEHOLDERS[reportType]`: new tokens appear automatically; add an "Insert image token" hint only if needed.
- Reference port: `E:\01 DEV\SYNCRO\syncro\apps\web\src\features\profile\signature-canvas.tsx` (86-line pointer-event canvas, PNG export) + `signature-page.tsx` (password-verify-first UX).
- `syncro/apps/web/src/features/settings/hooks/use-company-logo.ts` + `dashboard/settings/logo/page.tsx` — pattern for the new `dashboard/settings/signature` page; API client via `npm run generate:api` (Orval) after backend change.
- `syncro/apps/web/src/styles/print.css` — `.print-logo` / `.print-signature` classes already exist and are print-safe.

## Tasks & Acceptance

**Execution:**
- [x] `UserSignatureController/Service` — password-verified store (checkSignaturePassword gate, auth_users V22 counter, 3-strike 15-min block, disabled-account reject); unit `UserSignatureServiceTest` (LEAD-PW-001…007) + `UserSignatureControllerTest` (wrong→401, blocked→423, never stores) + `UserSignatureLockoutIntegrationTest` (cross-transaction commit proof) — gate for signature creation.
- [x] `WorkorderPrintReportService` + DTOs — resolve section leader (chain + LEADER-responsibility fallback), active-section + enabled-leader guards, null-tolerant presign for ANY storage error, null unless status ∈ {PENDING_REVIEW, CLOSED}; unit `WorkorderPrintReportServiceTest` (LEAD-SIG-001…004) + `WorkOrderControllerTest` view shape.
- [x] `print-template.ts` + `print-template-view.tsx` — add `logo`/`tandaTangan` placeholder keys + `PrintImageValue`/`PrintTokenValue`; image-token values render as `<img>` (or nothing when unavailable); vitest covers the matrix rows (render, null→nothing, mixed tokens, isImageValue guard).
- [x] `workorder-print-report.tsx` — pass logo + leaderSignature image values into `PrintTemplateView`; page-level tests prove the last hop (token→rendered `<img src/class>` at position, and both-null→nothing).
- [x] New `dashboard/settings/signature` page (ported canvas → PNG Blob → multipart with password; preview via GET /me; 401 INVALID_CREDENTIALS vs session-expiry discrimination; disabled-account path) + `signature` nav entry under Configuration + en/id i18n (`settings.signature.*`, `navigation.items.signature`).
- [x] Edge-matrix unit tests for substitution — covers I/O table.

**Acceptance Criteria:**
- Given a CLOSED WO whose machine's section has a leader with a stored signature, when printing with a template containing `{{tandaTangan}}`, then the leader's signature image appears at the authored position on screen and paper.
- Given a WO still IN_PROGRESS, when printing, then no signature renders even if the template carries the token.
- Given a leader without any signature, when saving via canvas with wrong password 3×, then save is blocked for 15 minutes.
- Given an old saved template without image tokens, when printing, then behavior is byte-identical to today.

## Spec Change Log

- **Review round 1 (step-04, 2026-09-16) — lockout location.** Finding: the Always block said lockout columns live on `user_signatures`, but that row does not exist before a user's first save, so the frozen AC "3 wrong attempts block saving" was unreachable for exactly the first-time population the feature serves. Amended the non-frozen Code Map/tasks to move the counter to `auth_users` (V22 migration), mirroring the login lockout already there; frozen AC preserved and now satisfiable. KEEP: `checkSignaturePassword` returns an outcome (no throw) + `REQUIRES_NEW` so the counter commits through the controller's 401 — pinned by `UserSignatureLockoutIntegrationTest`.
- **Review round 1 — leader sanity.** Added active-section and enabled-leader guards to `buildLeaderSignature`/`resolveLeaderUserId` (deactivated/disbanded leaders no longer stamp). Presign now catches any `RuntimeException` → null (never break the print page), not just `ObjectStorageException`.
- **Review round 1 — verification gaps closed.** Added page-level image-token tests (last hop was unobserved) and the cross-transaction lockout integration test (the security promise was mock-only before). Verification commands updated; Orval regen dropped as a task (print-types/use-user-signature hand-mirror DTOs per house pattern; nothing generated consumes them).

## Deferred (out of this story's single goal)

- Preventive-template image tokens ({{logo}}/{{tandaTangan}}) — `PreventivePlaceholderValues` already accepts image values, but the preventive print page and its placeholder list are untouched (spec Ask-First named it out of scope).
- A `{{leaderName}}` text token so a name prints under the stamp; `displayName` is resolved but not yet a template token.
- Default (non-template) layout stamping — the fixed signature card still governs there; image tokens only apply inside a custom layout.
- Attempt-budget feedback ("attempt 1 of 3") and a `Retry-After` on the 423.

## Design Notes

Password is required at **creation** only (Privy-analog: identity of the signer is bound when the signature is stored); print-time stamping is display-only and audited implicitly by the report's `signature_uses` approve row that already exists — no new audit on print (Ask First if this changes). `{{tandaTangan}}` name keeps the Indonesian token style of existing keys (`tandaTangan`, not `signature`). The `<img>` is injected after sanitization, src from our own presign → XSS-safe by construction. Deferred (not in scope): PREVENTIVE template tokens, canvas JSON endpoint, replacing the approve-time upload flow.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest='UserSignatureServiceTest,UserSignatureControllerTest,UserSignatureLockoutIntegrationTest,WorkorderPrintReportService*Test,SignatureUseIntegrationTest'` -- expected: green incl. leader-resolution unit tests + the cross-transaction lockout-commit proof (infra: verify Docker first per backend env rules)
- `cd syncro/apps/web && npm run test:unit && npm run check:i18n && npm run build` -- expected: all green (no Orval regen needed — print-types and use-user-signature hand-mirror the DTOs, house pattern for multipart/print views)
**Manual checks:** settings/signature page draw → save (wrong password rejected) → WO CLOSED print preview shows logo + signature at token positions → Ctrl+P preview keeps both on paper → IN_PROGRESS WO shows neither.

## Suggested Review Order

**Password gate + lockout (the security core)**

- Entry point: outcome-returning gate so the counter commits before the caller throws
  [`UserSignatureService.java:102`](../../syncro/apps/backend/src/main/java/com/syncro/auth/application/UserSignatureService.java#L102)
- The REQUIRES_NEW that makes the 3-strike block real
  [`UserSignatureService.java:101`](../../syncro/apps/backend/src/main/java/com/syncro/auth/application/UserSignatureService.java#L101)
- Counter fields on auth_users (moved from user_signatures; see Change Log)
  [`AuthUserEntity.java:118`](../../syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserEntity.java#L118)
- V22 migration adding the columns
  [`V22__auth_users_signature_lockout.sql:8`](../../syncro/apps/backend/src/main/resources/db/migration/V22__auth_users_signature_lockout.sql#L8)
- Controller enforces OK before any store; BLOCKED/INVALID → 423/401
  [`UserSignatureController.java:68`](../../syncro/apps/backend/src/main/java/com/syncro/auth/api/UserSignatureController.java#L68)
- Error envelope mapping for the new exceptions
  [`AuthExceptionHandler.java:177`](../../syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthExceptionHandler.java#L177)

**Leader resolution via existing org relations**

- Chain + guards: terminal status → section(active) → leader(enabled) → stored sig → presign
  [`WorkorderPrintReportService.java:215`](../../syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkorderPrintReportService.java#L215)
- Fallback to earliest LEADER responsibility when the chain is incomplete
  [`WorkorderPrintReportService.java:253`](../../syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkorderPrintReportService.java#L253)
- View contract carrying the stamp to the frontend
  [`WorkorderPrintReportDtos.java:92`](../../syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkorderPrintReportDtos.java#L92)

**Image-token substitution (XSS-safe by ordering)**

- img-node branch runs AFTER DOMPurify with our own presigned URL only
  [`print-template.ts:69`](../../syncro/apps/web/src/lib/print-template.ts#L69)
- Page supplies the two image values at the last hop
  [`workorder-print-report.tsx:122`](../../syncro/apps/web/src/features/workorders/components/workorder-print-report.tsx#L122)
- Token inventory (compile-time contract includes the new keys)
  [`print-template.ts:116`](../../syncro/apps/web/src/lib/print-template.ts#L116)

**Signature settings page (user flow)**

- Password-discriminated error handling (401 code vs session expiry)
  [`page.tsx:45`](../../syncro/apps/web/src/app/[locale]/(main)/dashboard/settings/signature/page.tsx#L45)
- Raw fetch to bypass syncroFetch's auto-logout on 401
  [`use-user-signature.ts:44`](../../syncro/apps/web/src/features/settings/hooks/use-user-signature.ts#L44)
- Canvas → PNG Blob capture handle
  [`signature-canvas.tsx:29`](../../syncro/apps/web/src/components/syncro/signature-canvas.tsx#L29)
- Nav entry under Configuration
  [`sidebar-items.ts:222`](../../syncro/apps/web/src/navigation/sidebar/sidebar-items.ts#L222)

**Peripherals**

- Cross-transaction lockout proof (the commit that mocks cannot catch)
  [`UserSignatureLockoutIntegrationTest.java:36`](../../syncro/apps/backend/src/test/java/com/syncro/auth/application/UserSignatureLockoutIntegrationTest.java#L36)
- Gate unit matrix (LEAD-PW-001…007)
  [`UserSignatureServiceTest.java:235`](../../syncro/apps/backend/src/test/java/com/syncro/auth/application/UserSignatureServiceTest.java#L235)
- Leader-resolution unit matrix (LEAD-SIG-001…004)
  [`WorkorderPrintReportServiceTest.java:206`](../../syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkorderPrintReportServiceTest.java#L206)
- Screen sizing for stamp images (preview equals paper)
  [`print.css:334`](../../syncro/apps/web/src/styles/print.css#L334)

