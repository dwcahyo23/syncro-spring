# Epic 23 Context: Multi-Language (i18n)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Localize the entire Syncro web frontend in Indonesian (default) and English so both local and international plant teams can operate the system in their preferred language. Built on next-intl with locale routing (`/id`, `/en`), ICU MessageFormat catalogs, a persistent navbar language switcher, and locale-aware Intl formatting. Strictly frontend-only: the backend contract (status codes, enum strings, error codes, ISO/UTC timestamps) never changes — localization is display-only.

## Stories

- Story 23.1: Setup next-intl foundation (install/configure, `/[locale]` routing, default id, module-structured catalogs)
- Story 23.2: Localize all frontend UI strings (tables, dialogs, kanban, dashboards, toasts, errors; Intl formatting)
- Story 23.3: Language switcher & persistence (navbar ID/EN toggle, cookie/localStorage, applies on every page)
- Story 23.4: Translation completeness & quality (en fallback, missing-key detection script, optional CI gate)

## Requirements & Constraints

- All user-visible strings come from message catalogs (`en.json`, `id.json`) — no hardcoded strings in components (exception: ICU plural/formatting syntax inside the strings themselves).
- Locales: `id` (default) and `en`. Route shape `/[locale]` → `/id`, `/en`; middleware detects locale; setup must not break Next.js App Router conventions.
- Missing keys in the active locale fall back to English; raw keys must never render to users.
- Dates, numbers, and currencies use locale-aware Intl formatting (`id-ID` vs `en-US`) at the display edge only. Backend stays UTC/ISO; no locale-dependent logic in backend code.
- Language choice persists across sessions (cookie/localStorage) and is applied on next visit; switching re-renders strings (or navigates to the `/[locale]` route) without losing state.
- Coverage scope explicitly includes: workorder/machine/sparepart tables, Assign & Work / report / request-part dialogs, kanban, dashboards, status labels, error messages, toasts.
- Tooling must detect missing keys between `en.json` and `id.json`; a CI check failing on missing translations is preferred (NFR-020 translation completeness gate).
- Catalogs are structured by feature/module (not one flat file per locale).
- MessageFormat is ICU — chosen as familiar to the team's .NET ARB background; keep parameterized messages, avoid string concatenation that breaks translation.

## Technical Decisions

- Library: **next-intl** (standard for Next.js App Router) — getRequestConfig + middleware locale detection.
- Architecture invariant AD-23: multi-language is frontend-only; backend codes remain the contract. Never localize wire data — translate display labels derived from codes (e.g., status enums render via `t()` keyed on the code).
- Next.js 16 App Router; route group under `/[locale]` with default locale `id`.
- Persistence: cookie (preferred for middleware/SSR locale detection) and/or localStorage per story acceptance.
- Error/validation messages arrive from the API as machine-readable codes; the frontend maps codes → translated messages.

## UX & Interaction Patterns

- Language switcher lives in the top navbar (ID/EN toggle), accessible from every page, alongside existing header-right controls (LayoutControls, ThemeSwitcher, PlantScopeSelector).
- Switching language should feel instant — re-render strings without a full reload where possible; the chosen locale survives navigation and revisits.
- Status labels, badges, and toasts must read naturally in both languages; keep Indonesian as the primary operational language (plant users) with English as fallback locale.

## Cross-Story Dependencies

- 23.2 depends on 23.1 (foundation, routing, catalogs); 23.3 depends on 23.1 (needs ≥2 locales served); 23.4 depends on 23.2 (needs populated catalogs to audit).
- No backend or API-contract dependency — Epic 23 touches only the frontend app; other epics' UI stories should adopt `t()` keys as their screens are built, so catalog structure (23.1) should anticipate module ownership matching the feature areas of Epics 9–22.
