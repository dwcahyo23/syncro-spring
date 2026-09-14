# QA Findings — Work Order Table (simulasi Playwright, 2026-09-14)

Dijalankan: backend :8081 + web :3001, login `admin@syncro.dev`, seluruh operasi lewat UI workorder table / kanban (tanpa inject script ke backend). Skenario: buat→assign multi-teknisi→kerja 3 jam→butuh sparepart→tunggu part→lanjut kerja.

## A. Temuan terkonfirmasi dari simulasi

### Blocking (alur operasional tidak jalan)

1. **Tidak ada UI "Buat Work Order" sama sekali.**
   Backend punya `POST /api/v1/workorders` (`WorkOrderController.java:180`) tapi tidak ada satu pun caller di `apps/web`. Tabel kerja hanya untuk WO yang sudah ada (seed/sinkronisasi). Skenario "dari membuat workorder" → **mustahil lewat web**.

2. **Multi-teknisi tidak pernah tampil di UI mana pun.**
   Simulasi: WO-260801007 di-assign ke Technician 01 + 02 → dua `POST /assignments` sukses (201, 201). Hasilnya:
   - Kolom "Teknisi" di tabel tetap `-`.
   - Kanban: `assignedTechnicianId: null` (list view backend hanya punya satu teknisi legacy).
   - Menu Aksi tidak menampilkan nama teknisi.
   Leader tidak bisa memverifikasi penugasan yang barusan ia simpan.

3. **Dialog "Tugaskan & Kerjakan" tidak idempoten — sesi kedua rusak.**
   Setelah submit, membuka dialog lagi: semua teknisi tampil **unchecked** (UI tidak membaca `GET /{id}/assignments` yang sudah tersedia di backend). Klik simpan lagi → `POST /assignments` ulang untuk teknisi yang sama → **409 duplicate** (dan seluruh batch gagal karena submit serial tanpa toleransi).
   → Kebutuhan riil "kerja 8–10, lanjut 14–16, lanjut besok" tidak bisa diinput: tiap sesi butuh buka dialog + tick teknisi yang sama, dan itu menabrak 409.

4. **Tidak ada halaman detail work order.**
   `GET /{id}/work-logs` dan `GET /{id}/assignments` ada di backend; web tidak pernah memanggilnya. Setelah work log tersimpan, datanya hilang dari pandangan — tidak ada timeline pengerjaan, tidak ada daftar part, tidak ada riwayat status. Operasional "table only" tidak mendukung audit/verifikasi leader.

5. **Alur sparepart untuk part baru → backend 500.**
   Simulasi di dialog "Ajukan Sparepart" (part baru, tanpa kode material): `POST /api/v1/sparepart-requests` → **500** `"The given id must not be null"` (root cause di `SparepartRequestService` saat resolve taksonomi/type — typeId tidak terkirim karena merek tidak bisa dipilih, lihat #6, dan backend tidak memvalidasi 400 untuk itu).
   Dialog tetap tertutup diam-diam (`Promise.allSettled` + close tanpa cek hasil per item) → user mengira terkirim.

6. **Bug TaxonomySelect (request part):**
   - Setelah "Buat merek “SMC”" (POST 201), dropdown merek **tetap "Tidak ada jenis ditemukan"** — list taksonomi tidak pernah di-invalidate setelah create; cache basi sampai reload.
   - Trigger **Kategori jadi kosong dan disabled** setelah dipilih (`linkedOptions` memfilter item CATEGORY dengan `categoryId` milik kategori itu sendiri → selalu 0 hasil). User kehilangan konteks pilihannya.
   - React console: `Select is changing from uncontrolled to controlled` ×6 (value `""` → `undefined` di `TaxonomySelect`).
   - ~~Dua bug berikut ditemukan saat verifikasi implementasi~~ — keduanya **sudah diperbaiki** (lihat §D):
     - **Floor backdate di dialog detail salah:** `print-report.header.createdAt` adalah string tanggal-saja (`"2026-09-14"` → tengah malam), sehingga sesi 08:00 tampak valid padahal backend menolak (`BACKDATE_BEFORE_WORKORDER` 400) karena WO dibuat 13:23. Perbaikan: `useWorkOrderCreatedInstant` mengambil `createdAt` Instant penuh dari list-row.
     - **Retry setelah submit gagal → 409:** assignment `POST` sukses (201) tapi work-log `POST` gagal (400) — menekan "Simpan" lagi pada dialog yang masih terbuka mengirim ulang assignment → 409 dan seluruh batch mati. Perbaikan: assignment-id hasil POST langsung disimpan ke state, retry reuse id.


7. **Kanban menyembunyikan 6 dari 17 WO.**
   `kanban-board.tsx` merender kolom legacy `DRAFT/OPEN/ASSIGNED/IN_PROGRESS/ON_PROCUREMENT`, sedangkan backend mengirim grup `OPEN/IN_PROGRESS/PENDING_SPAREPART/PENDING_REVIEW`. Terkonfirmasi di board: PENDING_SPAREPART (2) dan PENDING_REVIEW (4) **tidak muncul di kolom manapun** ("Pengadaan" = 0 padahal board-nya harusnya menaungi yang menunggu part).

### Signifikan (UX/konsistensi)

8. **Filter status & peta transisi frontend memakai enum legacy 8-value.**
   - Filter dropdown menawarkan DRAFT, ASSIGNED, ON_PROCUREMENT, DONE → **tidak akan pernah ada hasilnya** (backend 6-value).
   - `NEXT_STATUSES` tidak punya jalur UI `IN_PROGRESS → PENDING_SPAREPART` padahal backend mengizinkannya; satu-satunya cara masuk PENDING_SPAREPART adalah derived dari sparepart request (yang sedang 500 di #5). Work log berhenti karena `WAITING_SPAREPART` tidak menawarkan transisi "menunggu part" — patah di tengah cerita user.
   - `OPEN → [transition apa pun]` kosong: WO Terbuka hanya bisa "Tugaskan & Kerjakan".
9. **Label transisi membingungkan.** PENDING_SPAREPART→IN_PROGRESS berjudul "Mulai" → dialog "Pindahkan ke Mulai — WO-…", toast "Work order dipindahkan ke Mulai". Yang benar "lanjutkan pengerjaan". (Diamati saat eksekusi nyata di WO-260801021.)
10. **i18n:** judul dialog "Request part" tidak ikut berbahasa id; role teknisi mentah `(TECHNICIAN)` / `(STAFF_MAINTENANCE)` tampil di daftar.
11. **State filter hilang saat ganti tab** (Tabel→Kanban→Tabel mereset bulan ke September) — karena `WorkorderTable` unmount.
12. **Tombol menit "Selesai" prefill jam:menit sekarang** (bukan +durasi), untuk sesi 3 jam user wajib ubah 2 field; netral tapi menambah friksi.

## B. Catatan user (2026-09-14) — verbatim

> Saat ini workorder table masih kurang siap, temuanya banyak. Untuk aktual sekarang leader banyak menggunakan workorder table untuk mengisi sekaligus di akhir, jadi step-step workflow bisa di-backdate misalnya pekerjaan sudah selesai tapi leader baru mengisi sorenya, karena kondisi aktual leader/staff sibuk untuk mesin lainnya kadang mengisi di akhir. Pada iot portal web remastered "E:\01 DEV\PRISMA ORM\iot.gm.remastered\iot.portal.web.remastered" juga seperti itu, workorder bisa diisi terakhir. Lalu untuk teknisi pengerjaan bisa siklusnya lebih dari 1, misal dari jam 8 sampai jam 10, lalu lanjut jam 2 sampai jam 4, bisa juga lanjut besoknya — UI sekarang belum bisa mengadopsi seperti itu. Juga belum ada detail untuk workordernya. Jadi operasional workorder pada table only belum mendukung seperti itu.

Implikasi dari catatan tersebut:

Pola kerja leader/staff sebenarnya (dan yang sudah terbukti di `iot.portal.web.remastered`):

- **Isi sekaligus di akhir shift/hari** — WO dikerjakan duluan, paperwork belakangan. Semua tanggal/jam di-backdate. Batas backdate saat ini (≥ `created_at`) cocok, tapi UI harus mengizinkan seluruh lifecycle diisi dari satu tempat dalam satu duduk.
- **Satu teknisi, banyak sesi pengerjaan** — mis. 08:00–10:00, lanjut 14:00–16:00, sisa besok 09:00–11:00. Backend sudah mendukung (N× `POST /work-logs` per assignment), **UI cuma satu log per teknisi per dialog**.
- **Selang-seling dengan procurement** — kerja → tunggu part → kerja lagi. Backend mendukung `IN_PROGRESS ⇄ PENDING_SPAREPART`; UI tidak punya tombol lanjut-part+sesi dan part baru gagal 500.
- Referensi struktur remastered: satu form detail per WO berisi header info + daftar teknisi + `repairDetails[]` (startAt/endAt/mttr/description/teknisi per sesi) + `ppDetails[]` (part) — lifecycle diselesaikan lewat data, bukan wizard status.

## C. Rekomendasi (urutan dampak)

1. **Buat halaman detail WO** (`/workorders/{id}`): timeline assignment + work logs + part requests + riwayat status (semua endpoint GET-nya sudah ada; web tinggal baca). Ini kunci "detail workorder" dan audit.
2. **Buat work order dari web** (form: mesin + kategori + deskripsi; `Idempotency-Key` opsional).
3. **Rombak dialog "Tugaskan & Kerjakan" jadi "Isi Pengerjaan"**:
   - Prefill teknisi yang sudah aktif (GET assignments), tambah sesi = `POST /work-logs` baru untuk assignment yang ada (multi-row waktu per teknisi), tanpa re-assign.
   - Boleh beberapa sesi per teknisi sekali simpan (list sesi + tombol "+ sesi lagi").
4. **Samakan enum FE ke 6-value** (filter, badges, `NEXT_STATUSES`, kolom kanban) + tambah aksi `IN_PROGRESS → PENDING_SPAREPART` ("Tunggu Sparepart") di menu.
5. **Perbaiki dialog Ajukan Sparepart**: invalidate cache taksonomi setelah create (kategori trigger kosong, merek tak muncul), dan submit dengan cek hasil per item + error message backend yang jelas (perbaiki juga 500 → 400 di `SparepartRequestService`).
6. Terjemahan label (Request part, role badge, "Mulai"→"Lanjutkan"), pertahankan state filter antar tab (lift ke URL/`?tab=`).

## D. Status implementasi (2026-09-14, setelah QA) — diverifikasi via Playwright

Yang sudah dikerjakan (semua frontend; `tsc` bersih, `biome` nol error di folder tersentuh):

1. **Enum FE diselaraskan ke 6-value** — filter tabel, badge, `NEXT_STATUSES`, kanban, KPI dashboard, print-report approve-gate. Kanban kini tampil Terbuka/Berlangsung/**Menunggu Sparepart**/Menunggu Tinjauan (6 WO yang tadinya hilang kini muncul). Transisi diberi label edge-aware (`PENDING_SPAREPART→IN_PROGRESS` = "Lanjutkan Pengerjaan", bukan "Mulai").
2. **Dialog "Tugaskan & Kerjakan" dirombak** — prefill assignment aktif (tidak ada lagi 409 saat buka ulang), **N sesi per teknisi** (08–10 + 14–16 + besok), floor backdate pakai `created_at` presisi. Retry-after-failure 409 diperbaiki.
3. **TaxonomySelect request-part diperbaiki** — invalidate cache taksonomi setelah create, trigger Kategori tak lagi kosong/disabled, warning controlled hilang, dialog tidak menutup saat submit gagal sebagian.
4. **Halaman detail Work Order** (`/dashboard/workorders/[id]`) — header, daftar teknisi (Aktif/Selesai), **timeline work-log** (per sesi + durasi + alasan berhenti + catatan), bagian part, narasi laporan, dan tombol "Tugaskan & Kerjakan". Data dikomposisi dari `print-report` + `assignments` + `work-logs` + `created-instant` (backend tak punya `GET /{id}`).
5. **Dialog "Buat Work Order"** di tabel (`POST /workorders`, picker mesin via `EntitySelect`, kategori, deskripsi, `Idempotency-Key`; tombol disembunyikan untuk role yang ditolak backend).

Alur inti terverifikasi end-to-end di UI: buat WO → tabel → link detail → assign teknisi → 2 sesi backdated → **toast sukses, timeline render** (`POST /work-logs 201`). WO uji: `WO-260900001`.

Belum (jeda berikutnya, sesuai §C):
- Backend 500 → 400 untuk part baru tanpa type di `SparepartRequestService`.
- Multi-teknisi di **list row** (`assignedTechnicianName` tetap tunggal/satu) — butuh perubahan read-model backend; halaman detail sudah menyiasatinya via `GET /assignments`.
- State filter antar-tab, i18n "Request part"/role badge.

## E. Ronde 2 (siang 2026-09-14) — permintaan leader "satu layar, tanpa pindah menu" + seed org

Permintaan user: WO ter-assign harus bisa di-update teknisi yang sama tanpa kehilangan riwayat; sesi lama harus terlihat dari dialog; sparepart + status terlihat dari tabel (bukan pindah menu); chart progres bulanan di atas workorder table; link ke user responsibility; seed department Maintenance, section "Machinery: Forming - Rolling" / "Machinery: Machining - CNC", plant GM1+GM2.

Dikerjakan + terverifikasi Playwright (tsc/biome bersih):

1. **Chart strip "Progres work order per bulan"** di atas tabel (`workorder-trend-strip.tsx`, pakai `useWorkorderDashboard` yang sudah ada — buka vs tutup per bulan, tanpa pindah menu).
2. **Expand row tabel** (chevron per baris): panel inline berisi daftar **sesi pengerjaan** (dari `work-logs`) + **permintaan sparepart dengan badge status** (dari `print-report.parts`) + tautan opsional ke halaman detail. Semuanya tanpa pindah halaman.
3. **Dialog Tugaskan & Kerjakan**: blok **"Sesi yang sudah dicatat"** (read-only) tampil saat teknisi ber-assignment aktif dipilih; penomoran sesi baru melanjutkan (Sesi 2 dst.). Re-assign/update teknisi yang sama tidak lagi menyembunyikan pengerjaan lama.
4. **Link user responsibility**: picker teknisi di halaman detail kini di-scope lewat `GET /machine-responsibilities?machineId=<WO mesin>`; fallback global bila mesin belum punya responsibility rows; **teknisi yang sudah ter-assign selalu tetap tampil** (lubang yang ketemu saat verifikasi — Tech 01 sempat hilang dari kandidat).
5. **Seed `db/seed/org-structure-seed.sql`** (diterapkan + diverifikasi idempoten re-run = 0 insert): plant **GM2** baru (GM1 sudah ada via pilot-seed), department **Maintenance** di GM1+GM2, machine group **"Forming - Rolling"** dan **"Machining - CNC"** di bawah section MACHINERY GM1+GM2, + GM2 lengkap (mesin BF-08420/JBF21 di grup Machining - CNC, user technician/staff/leader.gm2, plant assignment, machine responsibilities, working calendar, gudang default, department membership).
   Catatan struktur: `sections.code` dikunci CHECK `MACHINERY|UTILITY|WORKSHOP` (V1) sehingga "Forming - Rolling"/"Machining - CNC" **benar sebagai machine group**, bukan section baru; ingin menjadikannya section butuh migrasi V21 (alter CHECK + SectionType + regenerate orval) — belum dilakukan.

Login seed baru (hash pilot "syncro-pilot-dev"): `technician.gm2@syncro.dev`, `staff.gm2@syncro.dev`, `leader.gm2@syncro.dev`.

### Ronde 2b (sore 2026-09-14) — dua temuan lanjutan user, keduanya diperbaiki

1. **Chart harus ikut filter tabel.** `WorkorderTrendStrip` kini menerima `status` + `categoryCode` dari state filter tabel dan meneruskannya ke `GET /dashboard/workorders` (endpoint-nya memang mendukung keduanya). Terverifikasi: ganti kategori → request baru `?categoryCode=01`. Batasan dicatat: bulan & search tidak bisa diteruskan (dashboard endpoint tidak punya param itu) — chart tetap 12 bulan penuh.
2. **Aksi "Detail" belum ada di menu baris.** Ditambahkan item `Detail` (ikon mata, `Link → /dashboard/workorders/[id]`) di dropdown Aksi, sebelum Ajukan Sparepart. Terverifikasi di menu: [Tugaskan & Kerjakan, transisi…, Detail, Ajukan Sparepart, Laporan, Cetak].

### Ronde 2c (sore 2026-09-14) — keputusan boundary domain leader

User: halaman detail dari workorder table = cockpit **maintenance** (leader machinery/utility/workshop — bukan inventory/gudang). Oleh karena itu:
- **MENGGAJUKAN** sparepart tetap aksi WO ("Ajukan Sparepart" ada di ActionsCell, dipakai juga di detail).
- **MEMPROSES/MENGGANTI STATUS** sparepart (ack/proses/ready/diterima/tutup) adalah flow gudang/inventory — halaman ini TIDAK boleh mengubah status.
- Yang **boleh** tampil: status sparepart + jejak waktunya (kapan PP diajukan → pembaruan status terakhir), read-only.

Implementasi: tombol transisi per part dicabut; bagian "Permintaan Sparepart" kini pakai `GET /sparepart-requests` difilter klien per `workOrderId` → baris read-only berisi kode part, qty, badge status (diterjemahkan), dan garis waktu "Diajukan {tgl} · Diperbarui {tgl}". Terverifikasi UI: dua part uji tampil dengan status + tanggal, tanpa satu pun tombol aksi. Status-change tetap penuh di menu "Permintaan Sparepart" (wilayah gudang). Detail sparepart khusus untuk flow gudang belum dibuat — saat ini list gudang yang memprosesnya.

Catatan: Laporan & Cetak juga tersedia di detail (ActionCell yang sama); ReportDialog kini meng-invalidate cache print-report+report sehingga narasi cockpit ikut segar setelah simpan.

### Ronde 2d (malam 2026-09-14) — cockpit gudang + laporan & penilaian di detail WO

1. **Detail WO kini menampilkan laporan penuh** (kronologis/analisis/korektif/preventif + FMEA/CPK/stop-time/tautan PDF CPK — semua dari agregat print-report) dan **panel Penilaian** (reuse `RatingPanel`: nilai teknisi + nilai work order dari produksi). Panel hanya interaktif saat status **Ditutup/CLOSED** (sesuai aturan server); selain itu tampil pesan "Penilaian baru bisa dilakukan setelah work order ditutup". `executorPool` dibangun dari gabungan technician assignment + work-log.
2. **Halaman detail sparepart request = cockpit gudang** (`/dashboard/sparepart-requests/[id]`): info permintaan, link balik ke WO, badge status, dan section **Alur status** dengan aksi penuh (transition sesuai state machine + approve/complete/MRE dibatasi `allowedActions` server). Sumber data = `GET /sparepart-requests` difilter klien (backend belum punya `GET /{id}`).
3. **Tautan bolak-balik**: baris part di detail WO bisa diklik → cockpit gudang; list gudang punya tombol Detail per baris.

Terverifikasi Playwright: WO aktif → panel rating terkunci dengan pesan; WO CLOSED (`WO-260801023`) → RatingPanel lengkap; cockpit gudang → header + link `WO-260900001` + "Tandai Siap/Dibeli". Boundary tetap terjaga: proses status hanya di wilayah gudang.


