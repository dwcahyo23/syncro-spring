# Sprint Change Proposal — ORM Maturation: Redesign Syncro-Spring Mengikuti Blueprint Syncro (Node/Prisma)

**Date:** 2026-08-31
**Author:** Correct Course workflow (bmad-correct-course) + Brainstorming session (bmad-brainstorming)
**Status:** For review & approval
**Scope classification:** **Major** (fundamental replan — PM/Architect involvement)

---

## Section 1: Issue Summary

### 1.1 Trigger
Stakeholder (Yusuf) membandingkan dua codebase Syncro:
- **SYNCRO-SPRING** (`E:\01 DEV\SYNCRO-SPRING`) — Java 25, Spring Boot 4.0.6, JPA + Flyway (V1..V68)
- **SYNCRO** (`E:\01 DEV\SYNCRO`) — Node.js, TypeScript, **Prisma ORM** + PostgreSQL, pnpm/turbo monorepo

Setelah analisa mendalam kedua ORM, ditemukan bahwa **SYNCRO (Node/Prisma) jauh lebih mature** di sisi model data. Sebelumnya sudah ada sprint change proposal (2026-08-31) untuk fix workorder assign+session — saat brainstorming diperluas, terungkap bahwa akar masalahnya bukan hanya UI, tapi **fondasi ORM syncro-spring yang belum selengkap syncro**.

### 1.2 Core Problem
Syncro-spring kekurangan ~40 model yang sudah ada di syncro (Prisma), di antaranya yang paling kritikal:
1. **Tidak ada multi-assignment** (`WorkAssignment`) — 1 WO hanya 1 `assigned_technician_id`, padahal kerja nyata melibatkan banyak teknisi
2. **Repair session tidak terhubung ke assignment** dan tidak mendukung backdate + teknisi pilihan (`WorkLog` punya start/end bebas + stopped_reason)
3. **Rating hanya per-WO**, tidak per-work-log per-teknisi (`WorkLogRating`, `WorkOrderQualityRatingTechnician`)
4. **Role kaku enum** `ApplicationRole`, tidak bisa di-mapping oleh SUPER_ADMIN di UI (`JobTitle` + `SystemRole` + `RolePermissionMapping`)
5. **Format ID WO salah**: `WO-YYMM-XXXXX` (dash) → harus `WO-YYMMXXXX` (tanpa dash)
6. **Belum ada**: MachineArea, Inventory (location/transfer/reservation), PM checksheet/schedule/execution, KPI monthly tables, IATF compliance (NC/8D/calibration/ECN), auth-login-audit, webhook, signature-use, WhatsApp message log

### 1.3 Key Enabler
**Masih fase development — data real ada di database lain, semua data saat ini seed dummy.** Artinya: perubahan struktur ORM **tidak berdampak migrasi data real**, DB bisa di-reset dan di-seed ulang kapan saja. Ini kondisi ideal untuk redesign schema dari nol tanpa beban backward-compatibility.

---

## Section 2: Impact Analysis

### 2.1 Epic Impact

| Epic | Dampak |
|---|---|
| **Epic 9 (Org Structure)** | Section → ditambah Department/JobTitle/SystemRole/MachineArea |
| **Epic 10 (Workorder)** | WorkAssignment + WorkLog + rating per log + format ID |
| **Epic 11 (Preventive)** | Upgrade ke PMChecksheet/Schedule/Execution + PMWorkOrder |
| **Epic 12 (Sparepart/Inventory)** | Tambah InventoryLocation/Transfer/Reservation |
| **Epic 13 (Sync)** | Sesuaikan dengan struktur WO baru |
| **Epic 14 (Notification)** | Tambah WhatsAppMessageLog + webhook |
| **Epic 15+ (IATF/Compliance)** | NC/8D/Calibration/ECN — diadopsi dari roadmap |
| **KPI/Dashboard** | KPI monthly tables (materialized) menggantikan hitung on-the-fly |

### 2.2 Story Impact
Hampir semua story Phase 2 (Epic 9–14) **terdampak** karena skema inti (work_orders, auth_users, machines) berubah. Karena development + reset DB, ini bukan tambal sulam migration tapi **redesign terpadu**.

### 2.3 Artifact Conflicts
- **ARCHITECTURE-SPINE (AD-1..AD-16)**: AD-3 (format ID), AD-4 (state machine), AD-6 (MTTR/MTBF), AD-11 (stock), AD-14 (rating), AD-15 (role taxonomy) — semua perlu revisi
- **PRD 2026-08-24 (FR-100..FR-181)**: FR-113 (assign singular → multi), FR-115 (sessions), FR-121/124 (rating per teknisi), FR-002/003 (role)
- **Epics.md**: Epic 9–14 stories perlu dirombak
- **sprint-status.yaml**: status stories perlu reset sesuai redesign
- **UX design**: dialog assign/session baru

### 2.4 Technical Impact
- **Backend**: redesign package `maintenance`, `org`, `auth`, `masterdata`, `inventory`(baru), `compliance`(baru)
- **Migration**: reset Flyway, schema baru V1.. (fresh)
- **Frontend**: table-first + kanban virtualized, role-mapping UI untuk SUPER_ADMIN, dialog Assign & Work
- **OPA**: dipertahankan, input diperluas dengan role data-driven

---

## Section 3: Recommended Approach

**Selected: Major — fundamental replan + redesign schema dari nol.**

### Rationale
1. Data dummy + DB bisa reset → tidak ada alasan mempertahankan schema lama yang tambal sulam
2. Mengadopsi blueprint syncro (Prisma) yang sudah terbukti mature lebih baik daripada mengulang proses desain dari nol
3. Menghindari 40+ migration tambal sulam; cukup 1 set migration terpadu V1.. fresh
4. OPA dipertahankan (keputusan arsitektur yang benar) — hanya `subject.roles` diperkaya dari data role

### Trade-offs
- **Risiko tinggi**: mengubah fondasi inti → perlu phased implementation, bukan big-bang
- **Waktu**: lebih lama di awal, tapi menghindari utang teknis jangka panjang
- **Kehilangan** struktur migration lama → tidak masalah (development)

### Effort / Risk
- Total: **Major** — High effort, Medium-High risk (mitigasi: phased)
- Backend schema redesign: High
- Frontend: Medium-High
- OPA + role mapping: Medium

---

## Section 4: Detailed Change Proposals

### 4.1 Skema Inti (Modul A & B) — Workorder & Org

**Adopsi dari Prisma:**

| Konsep SYNCRO (Prisma) | Desain di SYNCRO-SPRING |
|---|---|
| `WorkAssignment` (multi-teknisi, assigned_by/dropped_at/is_active) | Tabel `work_assignments` (UUID PK, work_order_id FK, technician_id FK, assigned_by, assigned_at, dropped_at, is_active) |
| `WorkLog` (start/end bebas/backdate, stopped_reason, activity_note) | Tabel `work_logs` (work_assignment_id FK, start_time, end_time, stopped_reason enum, notes) |
| `WorkOrder.wo_number` `WO-YYMMXXXX` | Ubah generator: `WO-%s%05d` (tanpa dash) → `WO-260800001` |
| `WorkOrder.status` 6 enum | `OPEN, IN_PROGRESS, PENDING_SPAREPART, PENDING_REVIEW, CLOSED` (ganti 8-status) |
| `WOCategory` (requires_rating) | `work_order_categories` + kolom `requires_rating` |
| `JobTitle` + `SystemRole` + `RolePermissionMapping` | Tabel baru `job_titles`, `system_roles`, `role_permission_mappings` + `menu_features`; OPA input `subject.roles` = job_title + system_role |

### 4.2 Rating (Modul C)

| Prisma | Desain |
|---|---|
| `WorkLogRating` + `WorkLogRatingCriterion` + `...CriterionCategory` | `work_log_ratings` (work_log_id, criterion_id, score, rated_by) |
| `WorkOrderQualityRating` + `...Technician` + `...Score` | `work_order_quality_ratings` (per WO, multi teknisi) + `..._technicians` + `..._scores` |

### 4.3 Sparepart & Inventory (Modul D & E)

| Prisma | Desain |
|---|---|
| `SparepartMaster` (hierarchy_identity_key, bom_code, bom_serial, review_status) | Extend `spareparts` + `sparepart_review_status` |
| `InventoryLocation` + `InventoryStockBalance` + `InventoryTransfer` + `InventoryReservation` | Tabel baru `inventory_locations`, `inventory_stock_balances`, `inventory_transfers`, `inventory_reservations` |

### 4.4 PM (Modul F)

| Prisma | Desain |
|---|---|
| `PMChecksheet` + `PMFrequency` + `PMSchedule` + `PMScheduleDate` + `PMWorkOrder` + `PMExecution` + `PMExecutionItem` | Upgrade `preventive_programs/schedules` → `pm_checksheets`, `pm_schedules`, `pm_work_orders`, `pm_executions`, `pm_execution_items` |

### 4.5 KPI & IATF (Modul G & H)

| Prisma | Desain |
|---|---|
| `KpiTarget` + `KpiMtbfMonthly` + `KpiMttrMonthly` + `KpiMarMonthly` + `KpiPmCompletionMonthly` + `KpiTechnicianMonthly` | Tabel KPI monthly (materialized) |
| `NonConformance` + `EightDReport` + `CalibrationInstrument` + `CalibrationRecord` + `EquipmentChangeNotice` + `MachineSetupBaseline` + `LessonLearned` | Modul `compliance` baru |

### 4.6 Lainnya (Modul I)

| Prisma | Desain |
|---|---|
| `UserSignature` + `SignatureUse` | Extend existing workorder_signatures |
| `AuthLoginAudit` + `PhoneVerificationChallenge` | Tabel baru |
| `WebhookConfig` + `WebhookDeliveryLog` | Tabel baru (modul `integration`) |
| `WhatsAppMessageLog` | Tabel baru (modul `notification`) |

### 4.7 Frontend
- **Table-first**: table workorder jadi hub utama, Actions dropdown → dialog
- **Dialog Assign & Work**: pilih multi teknisi + work log + backdate (DateTimePicker min=WO created)
- **Role mapping UI**: SUPER_ADMIN bisa map JobTitle/SystemRole per user
- **Kanban**: tetap ada + filter + virtualisasi
- **Format ID** tampil `WO-YYMMXXXX`

### 4.8 OPA
- Dipertahankan sebagai enforcement
- `subject.roles` diperkaya: `application_role` + `job_title` + `system_roles`
- Role mapping via data (bukan enum kaku)

---

## Section 5: Implementation Handoff

| Scope | Klasifikasi | Handoff |
|---|---|---|
| Schema redesign (V1.. fresh) + migrasi | **Major** | PM + Solution Architect |
| Backend modul (org, maintenance, inventory, compliance) | **Major** | Developer agent (per modul, berurutan) |
| Frontend (table-first, dialog, role UI) | **Moderate** | Developer agent (frontend) |
| OPA policy update | **Moderate** | Developer agent (authz) |
| Epics/PRD/Architecture update | **Major** | PO/PM/Architect |

### Phasing (menghindari big-bang)
1. **Fase 0**: Tulis "ORM Target Blueprint" (pemetaan Prisma → JPA lengkap) sebagai artifact fondasi
2. **Fase 1**: Org & Identity (Department/JobTitle/SystemRole/MachineArea) + auth reset
3. **Fase 2**: Workorder core (WorkAssignment + WorkLog + format ID + status baru)
4. **Fase 3**: Rating per log + sparepart master + inventory
5. **Fase 4**: PM checksheet/schedule/execution
6. **Fase 5**: KPI monthly + IATF compliance + webhook/audit/signature
7. **Fase 6**: Frontend (table-first + dialog + role UI + kanban virtualize)
8. **Fase 7**: OPA enrichment + sync hardening + seed ulang

### Success Criteria
1. `work_assignments` (multi-teknisi) + `work_logs` (backdate, stopped_reason) aktif; `WO-YYMMXXXX` tanpa dash
2. Rating per work-log per teknisi tersimpan
3. Role berbasis data + OPA: SUPER_ADMIN bisa mapping role per user di UI
4. Inventory (location/transfer/reservation), PM execution, KPI monthly, IATF compliance ter-migrasi
5. Reset DB + seed ulang berhasil tanpa error; test hijau
6. Frontend table-first + dialog Assign & Work + kanban virtualized

---

*Proposal ditulis oleh Correct Course workflow. Menunggu persetujuan sebelum implementasi.*
