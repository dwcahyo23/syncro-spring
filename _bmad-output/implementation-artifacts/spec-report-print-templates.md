---
title: 'Template Laporan Cetak: WYSIWYG TipTap + placeholder {{...}} untuk WO & Preventif'
type: 'feature'
created: '2026-09-15'
status: 'done'
review_loop_iteration: 1
baseline_commit: 'HEAD'
context:
  - '{project-root}/_bmad-output/project-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Halaman cetak laporan (WO & Preventif) hard-code layout-nya; leader tidak bisa mengatur format resmi (garis tabel, kop, urutan bagian) tanpa sentuhan kode. Keputusan split DW-168 berubah bentuk sesuai arahan user: bukan editor per-WO, melainkan **template layout cetak global — 1× setting per jenis laporan**, dengan editor rich text (tabel + garis) dan kerangka placeholder `{{...}}` untuk data.

**Approach:** Navigasi baru **Template Laporan** (`/report-templates`, group Konfigurasi, role SUPER_ADMIN & MANAGER_MAINTENANCE) berisi dua tab — Work Order, Preventif. Tiap tab = TipTap editor (toolbar: bold/italic/underline/list/heading/**sisip tabel** + baris/kolom) menulis HTML. Backend menyimpan satu baris per jenis (`report_print_templates`, upsert atomik pola WahaTemplateService). Saat mencetak: jika template ada, render HTML-nya dengan `{{key}}` diganti data laporan nyata (DOMPurify-sanitized), **lalu blok non-teks bawaan — tanda tangan/approval, bukti, tabel part, tabel sesi — SELALU dirender menempel di bawah template** (keputusan human 2026-09-15 via review: audit trail kertas tidak boleh bisa hilang karena kelalaian admin; template menguasai kop/naratif/CPK/urutan bagian teks, bukan blok tanda tangan/cs); jika belum ada → layout bawaan sekarang tidak berubah. Halaman ReportDialog isi-teks-per-WO tetap apa adanya.

## Boundaries & Constraints

**Always:** Tanpa perubahan endpoint data laporan yang ada (`/{id}/print-report`, dsb.) — substitusi placeholder murni di klien dari data yang sudah dimuat. Inventaris placeholder = field yang tersedia di komponen cetak sekarang (WO: `{{nomor}} {{tanggal}} {{mesin}} {{kategori}} {{status}} {{teknisi}} {{deskripsi}} {{kronologis}} {{analisis}} {{korektif}} {{pencegahan}} {{cpk}} {{fmea}} {{stopTime}}`; Preventif: padanannya — turunkan persis saat implementasi dari kedua komponen cetak). Placeholder dalam field kosong diganti "—". Substitusi hanya di text node. Template disimpan verbatim; **render selalu lewat DOMPurify** (allowlist tip + `table/thead/tbody/tr/th/td/colgroup/col` + `colspan/rowspan/class`, tanpa `style`/event — border tabel dari CSS print `:where(table)`). GET template boleh dibaca semua authenticated user (dipakai halaman cetak); hanya SUPER_ADMIN/MANAGER_MAINTENANCE yang PUT. `body_html` dibatasi server 200_000 char.

**Ask First:** HALT bila substitusi text-node tidak bisa menjaga tabel TipTap tetap utuh (mis. placeholder di dalam atribut) — jangan perluas DOMPurify tanpa izin. HALT bila layout bawaan preventif ternyata tidak memuat data setara untuk placeholder yang dijanjikan.

**Never:** Tanpa PDF server-side. Tanpa library baru selain `@tiptap/react`, `@tiptap/starter-kit`, `@tiptap/extension-table{,-row,-cell,-header}`, `dompurify` (satu paket approval, diminta eksplisit user "pakai garis table"). Tanpa perubahan fungsi ReportDialog/field teks per-WO. Scratch files tak di-commit.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected | Error |
|---|---|---|---|
| Simpan template WO | HTML bertabel + `{{kronologis}}` | PUT 200; reload tab menampilkan formattan utuh | 403 non-admin; >200k → 400 |
| Cetak WO, template ada | print page | layout custom, semua `{{key}}` terganti data, tabel bergaris; blok tanda tangan/bukti/part/sesi bawaan menempel di bawahnya | — |
| Cetak WO, template kosong | default | layout bawaan lama persis | — |
| Placeholder data kosong | `{{cpk}}` null | "—" | — |
| Template Preventif | simpan → cetak jadwal | tab & jenis independen, tak saling timpa | — |
| Payload jahat tersimpan (bug) | `<img onerror>`/`<script>` | DOMPurify strip saat render | — |
| Staf biasa membuka /report-templates | role STAFF | menu tak tampil; akses URL → unauthorized | 403 API |
| Kontrak upsert | dua PUT paralel jenis sama | satu baris menang tanpa dup (ON CONFLICT) | — |

</frozen-after-approval>

## Code Map

- `syncro/apps/backend/.../notification/application/WahaTemplateService.java:44` — atomik `INSERT ... ON CONFLICT` (resep DW-77) → tiru untuk `ReportPrintTemplateService`; `WahaTemplateEntity/Controller/Dtos` jadi cetak biru struktur modul (baru di `com.syncro.maintenance.report` atau modul `report` — ikut konvensi paket terdekat).
- `db/migration/V20__...sql` — terbaru; template = **V21__create_report_print_templates.sql** (`report_type` UNIQUE CHECK IN ('WORK_ORDER','PREVENTIVE'), `body_html TEXT`, `updated_by/updated_at`).
- `syncro/apps/web/src/features/workorders/components/workorder-print-report.tsx` — konsumen 1: cabang render template vs bawaan; inventaris `{{key}}` dari data di sini.
- `syncro/apps/web/src/features/preventive/components/preventive-report.tsx` — konsumen 2; placeholder Preventif dari datanya.
- `sidebar-items.ts` group Konfigurasi + `next.config.mjs` rewrite `/:locale(id|en)/report-templates` → `(main)/dashboard/report-templates` (pola alias maintenance yang baru jadi).
- Baru: `src/components/syncro/rich-text-editor.tsx` (TipTap + toolbar tabel), `src/lib/html.ts` `sanitizeHtml`, `src/features/report-templates/*` (page 2 tab), hook `use-report-print-template` (GET/PUT `/api/v1/report-templates/{type}` — hand-written fetch seperti preseden kpi; catat DW regenerasi Orval bila perlu).
- `WorkOrderAccessGuard`/ pola gate role untuk endpoint PUT; audit tulis `updated_by`.

## Tasks & Acceptance

**Execution:**
- [x] V21 migration + entity/repo/service/controller/DTO + gate role + test integrasi (upsert atomik, 403 non-admin, 200k guard) — backend.
- [x] `package.json`: tiptap core+starter-kit+4 tabel ext + dompurify; `npm install`.
- [x] `rich-text-editor.tsx` + toolbar tabel; `html.ts` sanitizeHtml (+allowlist tabel) + vitest strip `<script>/onerror`, pertahankan `<table>`.
- [x] Halaman Template Laporan 2 tab + simpan/beban; sidebar+rewrite+protected matcher `/report-templates`.
- [x] Print page WO & Preventif: fetch template (senyap bila 404), render substitusi+sanitize, fallback bawaan.
- [x] i18n `id`/`en` kunci baru (nav, tab, toolbar, preview-toast) via check-translations.

**Acceptance:**
- AC1 admin simpan layout bertabel di tab WO → halaman Cetak WO memakai layout itu dengan semua `{{key}}` terganti; WO tanpa field terisi menampilkan "—".
- AC2 hapus/belum ada template → kedua halaman cetak identik dengan sebelum change.
- AC3 STAFF tidak melihat menu dan PUT-nya 403.
- AC4 payload `<script>`/`onerror` terselip tidak dieksekusi saat cetak.

## Design Notes

Satu baris per jenis (global, bukan per-plant) = "1× setting" persis kata user; per-plant belakangan bila diminta (tambah kolom plant_id = migrasi, mudah). Placeholder teks-belaka agar template tetap HTML biasa; tabel butuh class-based styling karena `style` di-strip sanitizer — CSS print memberi border default `table, th, td`.

## Verification

**Commands:** `mvn -q compile`, `mvn test -Dtest=ReportPrintTemplate*`, `npx tsc --noEmit`, `npx biome check src`, `npx vitest run src/lib/html.test.ts src/lib/print-template.test.ts src/proxy.test.ts src/navigation/sidebar/report-templates-nav-contract.test.ts src/features/report-templates src/features/workorders/components/workorder-print-report.test.tsx src/features/preventive/components/preventive-report.test.tsx`, `node scripts/check-translations.mjs`, `npm run build`.
**Manual:** Playwright :3001 — login admin → /report-templates → tab WO → sisip tabel + placeholder → Simpan → buka Cetak WO → layout custom; matikan template (clear) → bawaan kembali; cek tab Preventif independen; logout → /id/report-templates → login guard.

## Suggested Review Order

**Keputusan terbesar: template vs blok wajib (renegotiasi human saat review)**

- Branching: template menguasai kop/naratif, empat blok non-teks selalu menempel di bawah
  [`workorder-print-report.tsx:267`](../../syncro/apps/web/src/features/workorders/components/workorder-print-report.tsx#L267)

- Padanan preventif (checklist + bukti + signature selalu di bawah template)
  [`preventive-report.tsx:116`](../../syncro/apps/web/src/features/preventive/components/preventive-report.tsx#L116)

**Backend — kontrak penyimpanan global**

- Tabel tunggal per jenis; upsert ON CONFLICT (pola DW-77), audit updated_by
  [`ReportPrintTemplateService.java:45`](../../syncro/apps/backend/src/main/java/com/syncro/report/application/ReportPrintTemplateService.java#L45)

- Skema: UNIQUE(report_type) + CHECK dua nilai + FK auth_users
  [`V21__create_report_print_templates.sql`](../../syncro/apps/backend/src/main/resources/db/migration/V21__create_report_print_templates.sql)

- GET terbuka (dipakai cetak), PUT khusus SUPER_ADMIN/MANAGER, cap 200k
  [`ReportPrintTemplateController.java`](../../syncro/apps/backend/src/main/java/com/syncro/report/api/ReportPrintTemplateController.java)

**Kontrak placeholder klien**

- Inventaris per jenis + substitusi text-node-only + "—" untuk kosong
  [`print-template.ts`](../../syncro/apps/web/src/lib/print-template.ts)

- Peta 14/10 token ter-type penuh — salah nama token = tsc gagal
  [`workorder-print-report.tsx:106`](../../syncro/apps/web/src/features/workorders/components/workorder-print-report.tsx#L106)

- Render hasil: sanitize DOMPurify → substitusi
  [`print-template-view.tsx:21`](../../syncro/apps/web/src/components/syncro/print-template-view.tsx#L21)

**Editor & halaman admin**

- TipTap + TableKit + inserter placeholder; tanpa atribut style (border dari CSS print)
  [`rich-text-editor.tsx`](../../syncro/apps/web/src/components/syncro/rich-text-editor.tsx)

- 2 tab, RoleGuard, simpan/hapus/muat ulang; Clear = "" → fallback
  [`report-templates-page-content.tsx`](../../syncro/apps/web/src/features/report-templates/report-templates-page-content.tsx)

**Peripherals**

- Guard /report-templates (test anonim 307 + mutation-proven)
  [`proxy.ts:21`](../../syncro/apps/web/src/proxy.ts#L21)

- Alias URL publik + redirect group (pola maintenance)
  [`next.config.mjs`](../../syncro/apps/web/next.config.mjs)

- Pin kontrak: 42/42 (sanitizer 7, substitusi 10, proxy 6, nav 3, hooks 5, print pages 11)
  [`workorder-print-report.test.tsx`](../../syncro/apps/web/src/features/workorders/components/workorder-print-report.test.tsx)
