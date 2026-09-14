---
title: 'Maintenance nav URL regroup: /maintenance/* alias untuk workorders, preventive, sparepart-requests, stock'
type: 'feature'
created: '2026-09-15'
status: 'done'
review_loop_iteration: 0
baseline_commit: '506d961e82687728a19daedcb28f359525a63095'
context:
  - '{project-root}/_bmad-output/project-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Group sidebar "Pemeliharaan" menunjuk `/dashboard/workorders|preventive|sparepart-requests|stock` — satu-satunya grup yang membocorkan folder internal `(main)/dashboard/*` ke URL publik. Semua grup lain (Dasbor, Konfigurasi) memakai alias bersih (`/analytics`, `/master-data/...`) yang di-rewrite next.config ke lokasi file internal.

**Approach:** `/maintenance/<area>` menjadi URL publik resmi untuk 4 area maintenance, mengikuti pola alias yang sudah ada: file route TIDAK dipindah (tetap di `(main)/dashboard/<area>/`), `next.config.mjs` menambah rewrite alias `/maintenance/*` → internal, plus redirect permanen-tidak (`permanent: false`) dari URL `/dashboard/<area>/*` lama ke `/maintenance/<area>/*` — termasuk deep link bertoken (`/[id]`, `/[id]/print`, `/ack-task-list?token=`) karena link WhatsApp lama dan bookmark harus tetap bekerja. Sidebar + seluruh call-site `Link href` diarahkan ke URL publik baru.

## Boundaries & Constraints

**Always:** Tidak ada perubahan backend — link ack WA yang dibangun `WorkOrderAckWorker.java:173` (`/dashboard/workorders/ack-task-list?token=...`) tetap diproses lewat redirect dengan query string utuh. Query params harus bertahan di semua redirect/rewrite. Grup rute lain (alerts, telemetry, master-data, settings, dsb.) tidak boleh ikut berubah. `src/i18n/routing.test.ts` transform-string test tidak boleh diinterpretasi ulang — verifikasi tetap hijau.

**Ask First:** Jika ternyata pola alias-rewrite menabrak `[...not-found]` atau middleware proxy locale sehingga `/maintenance/*` 404 pada konfigurasi tertentu — HALT, pertimbangkan move folder route (opsi B, lebih besar diffnya) dan laporkan sebelum lanjut.

**Never:** Tanpa perubahan skema/API backend. Tanpa memindah file route (kecuali diizinkan lewat Ask First). Tanpa mengubah halaman cockpit sparepart/warehouse secara fungsional — hanya URL-nya. Tanpa commit scratch files (`console-check*.txt`, `scan-*.tsv`, `__probe_*.mjs`, `qa-01-telemetry.txt`, `snap*.md`, `cockpit.md`).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Sidebar klik Work Orders | group Pemeliharaan | address bar `/id/maintenance/workorders`, tabel tampil | — |
| Bookmark lama | `GET /id/dashboard/workorders` | 307 → `/id/maintenance/workorders` | — |
| WA ack link lama | `/id/dashboard/workorders/ack-task-list?token=X` | redirect mempertahankan `?token=X`, halaman ack jalan | token invalid → perilaku halaman tak berubah |
| Deep link detail | `/en/dashboard/workorders/WO-260900001/print?token=Y` | → `/en/maintenance/workorders/WO-260900001/print?token=Y` | — |
| Cockpit sparepart | `/id/dashboard/sparepart-requests/<uuid>` | → `/id/maintenance/sparepart-requests/<uuid>` | — |
| Link internal (WO id di tabel, eye-detail, row-expand, link part → cockpit) | klik dari tabel | langsung ke `/maintenance/*` tanpa hop redirect | — |
| Command palette | search "workorder" → Enter | navigasi ke `/maintenance/workorders` (turunan sidebarItems) | — |
| Area bukan-maintenance | `/id/alerts`, `/id/analytics`, `/id/dashboard` | tak berubah; `/dashboard` → `/operations-overview` redirect lama tetap | — |
| Locale unknown | `/fr/maintenance/workorders` | tidak ter-rewrite (`:locale(id|en)` gate) → 404 wajar | — |

</frozen-after-approval>

## Code Map

- `syncro/apps/web/next.config.mjs:23-47` — `redirects()` (satu entri `/dashboard` lama) + `rewrites()` (pola alias yang ditiru). Tambah 4 rewrite `/maintenance/<area>/:path*` → `/dashboard/<area>/:path*` dan 4 redirect `/:locale(id|en)/dashboard/<area>/:path*` → `/:locale/maintenance/<area>/:path*` (areas: workorders, preventive, sparepart-requests, stock).
- `syncro/apps/web/src/navigation/sidebar/sidebar-items.ts:151-176` — grup 3: ganti 4 `url` ke `/maintenance/*`. Konsumen (app-sidebar, search-dialog/command palette) otomatis ikut — satu sumber kebenaran.
- 13 file call-site `Link href` (19 literal `/dashboard/{workorders|preventive|sparepart-requests|stock}`): `workorder-actions-cell.tsx:196,211`, `workorder-detail.tsx:308,408`, `workorder-row-expand.tsx:111`, `workorder-table.tsx:189`, `sparepart-requests-list.tsx:265`, `sparepart-request-detail.tsx:116,180`, + legacy alias pages `app/[locale]/workorders/page.tsx`, `app/[locale]/preventive/page.tsx`, `app/[locale]/sparepart-requests/page.tsx`, `app/[locale]/workorders/{kanban,ratings}/page.tsx` (saat implementasi: destination redirect mereka diganti ke `/maintenance/<area>` kanonik — hop ganda `/dashboard→/maintenance` dihilangkan; bukan exception lagi).
- `syncro/apps/web/src/app/[locale]/(main)/dashboard/{workorders,preventive,sparepart-requests,stock}/` — file TIDAK dipindah; hanya diakses ulang via rewrite.
- `syncro/apps/backend/.../WorkOrderAckWorker.java:173` — pembuat link WA; TIDAK disentuh (redirect meng-covern).
- `src/i18n/routing.test.ts:42` — memakai string `/dashboard/...` hanya sebagai input transform; harus tetap hijau tanpa edit.

## Tasks & Acceptance

**Execution:**
- [x] `next.config.mjs` — tambah 4 redirect `/dashboard/<area>/:path*` → `/maintenance/<area>/:path*` (`permanent: false`) dan 4 rewrite alias `/maintenance/<area>/:path*` → `/dashboard/<area>/:path*` untuk workorders, preventive, sparepart-requests, stock. Letakkan setelah entri `master-data` agar blok maintenance berkelompok; komentar singkat alasan redirect (WA link + bookmark).
- [x] `sidebar-items.ts:151-176` — 4 `url` grup "Pemeliharaan" → `/maintenance/*`.
- [x] Ganti 19 literal `href` internal (8 file fitur, daftar Code Map) ke `/maintenance/*`; sisakan intentional exception: destination rewrite/redirect internal & halaman alias legacy yang memang menunjuk lokasi file. (Catatan implementasi: aktualnya 9 literal di 6 file fitur — semua yang ada sudah diganti; proxy.ts ikut ditambah `/maintenance` di protectedRoutes + matcher agar auth guard tetap berlaku pada URL kanonik baru.)
- [x] `npm run build` — pastikan route manifest + redirect/rewrite tertata; kemudian probe manual: `/id/dashboard/workorders/ack-task-list?token=x` ter-redirect dengan query utuh. (Diverifikasi via `next start` di :3100: 307 → `/id/maintenance/workorders/ack-task-list?token=X`, query utuh; routes-manifest memuat 4 redirect + 4 rewrite maintenance.)

**Acceptance Criteria:**
- Given user klik keempat item grup Pemeliharaan, then address bar menunjukkan `/id/maintenance/<area>` (bukan `/dashboard/...`) dan halaman tampil normal.
- Given URL lama `/id/dashboard/workorders/WO-x/print?token=Y` (bookmark/WA), then browser mendarat di `/id/maintenance/workorders/WO-x/print?token=Y` — query preserved, halaman render.
- Given semua link internal (WO id tabel, Detail, expand row, part→cockpit, command palette), then tidak ada lagi href `/dashboard/{workorders|preventive|sparepart-requests|stock}` yang muncul di address bar (redirect boleh ada untuk entry lama).
- Given rute non-maintenance (`/id/alerts`, `/id/master-data/machines`, `/id/dashboard`→operations-overview), then tidak ada perubahan perilaku.

## Design Notes

Pilih rewrite-alias (pola repo: Dasbor & Konfigurasi) di atas physical move: diff ~4x lebih kecil, folder internal `(main)/dashboard` tetap jadi lokasi file tunggal, dan tidak ada risiko lupa satu import. Konsekuensi sadar: `/dashboard/workorders/...` tetap resolve via rewrite bila seseorang menulis href internal — redirect di step 1 memastikan URL publik user selalucanonical `/maintenance/*`. URL lama WA ack tidak perlu digenerate ulang.

## Verification

**Commands:**
- `cd syncro/apps/web && npx tsc --noEmit` — expected: no src errors.
- `npx biome check src` — expected: 0 errors.
- `node scripts/check-translations.mjs` — expected: "no hard failures" (i18n tak berubah, sanity).
- `npx vitest run src/i18n/routing.test.ts` — expected: hijau tanpa modifikasi test.
- `npx vitest run src/navigation/sidebar/maintenance-nav-contract.test.ts src/proxy.test.ts` — expected: pass (kontrak redirect/rewrite + guard auth /maintenance; mutation-check membuktikan test proxy gagal bila guard dicabut).
- `npm run build` — expected: sukes; grep output build untuk `/maintenance/`.
- `grep -rn "dashboard/workorders\|dashboard/preventive\|dashboard/sparepart-requests\|dashboard/stock" src` — expected: sisa hit hanya yang intentional (destination rewrite internal + halaman alias legacy + test string).

**Manual checks:**
- Playwright (web :3001 + backend :8081, login admin): klik 4 item sidebar → URL `/id/maintenance/*`; buka `/id/dashboard/workorders` → redirect; buka detail WO dari tabel → link part ke cockpit → URL `/maintenance/sparepart-requests/<id>`; command palette → workorders.

## Suggested Review Order

**Kontrak route (entry point)**

- Sumber kebenaran URL publik baru — baca blok redirect dulu, komentar menjelaskan alasan WA/bookmark
  [`next.config.mjs:33`](../../syncro/apps/web/next.config.mjs#L33)

- Landing bare `/maintenance` → workorders (parity dengan `/dashboard` → operations-overview)
  [`next.config.mjs:33`](../../syncro/apps/web/next.config.mjs#L33)

- Empat redirect area lama → kanonik, `permanent:false` (307) query-passthrough
  [`next.config.mjs:46`](../../syncro/apps/web/next.config.mjs#L46)

- Rewrite alias ke lokasi file internal; file route sengaja tidak dipindah
  [`next.config.mjs:83`](../../syncro/apps/web/next.config.mjs#L83)

**Auth guard (perubahan keamanan-penting companion)**

- `/maintenance` masuk protectedRoutes — tanpa ini URL kanonik lolos guard login
  [`proxy.ts:15`](../../syncro/apps/web/src/proxy.ts#L15)

- Matcher eksplisit: varian `:path*` + locale-prefixed untuk path bertitik (WO id)
  [`proxy.ts:89`](../../syncro/apps/web/src/proxy.ts#L89)

**Navigasi & call-site**

- Satu sumber grup Pemeliharaan; sidebar + command palette turun otomatis
  [`sidebar-items.ts:157`](../../syncro/apps/web/src/navigation/sidebar/sidebar-items.ts#L157)

- 9 href fitur → `/maintenance/*` (representatif; lihat diff untuk sisanya)
  [`workorder-table.tsx:189`](../../syncro/apps/web/src/features/workorders/components/workorder-table.tsx#L189)

- Alias legacy kini satu hop ke kanonik (representatif; 5 file)
  [`workorders/page.tsx:5`](../../syncro/apps/web/src/app/[locale]/workorders/page.tsx#L5)

**Tests (pinned contract + mutation proof)**

- Guard proxy diuji langsung; mutation-check: cabut `/maintenance` → 2 test gagal
  [`proxy.test.ts`](../../syncro/apps/web/src/proxy.test.ts)

- Kontrak config + sidebar + scan literal href repo-wide dengan whitelist exception
  [`maintenance-nav-contract.test.ts`](../../syncro/apps/web/src/navigation/sidebar/maintenance-nav-contract.test.ts)
