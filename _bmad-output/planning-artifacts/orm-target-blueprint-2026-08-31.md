# ORM Target Blueprint — Prisma (SYNCRO) → JPA (SYNCRO-SPRING)

**Date:** 2026-08-31
**Status:** Foundation (Fase 0) — sprint-change-proposal-2026-08-31.md §4
**Source:** `E:\01 DEV\SYNCRO\syncro\packages\database\prisma\schema.prisma` (authoritative blueprint)
**Target:** `E:\01 DEV\SYNCRO-SPRING\syncro\apps\backend` — Spring Boot 4.0.6, JPA, Flyway fresh V1..

---

## Konvensi Pemetaan

| Prisma (Node) | JPA (Syncro-Spring) |
|---|---|
| `model X` | `@Entity @Table(name="x")` di `com.syncro.<context>.infrastructure.db.XEntity` |
| `String @id @default(uuid())` | `@Id UUID` (kecuali entitas khusus seperti work_orders yang pakai VARCHAR PK) |
| `String` FK + `@relation` | `@ManyToOne(fetch=LAZY)` ATAU kolom UUID polos (mengikuti AD-3/AD-4 cross-aggregate: workorder pakai UUID polos) |
| `enum X` | `@Enumerated(EnumType.STRING)` + CHECK constraint (uppercase) |
| `DateTime` | `Instant` (UTC), kolom `TIMESTAMPTZ` |
| `Decimal` | `BigDecimal` |
| `Boolean @default(true)` | `boolean` / `Boolean` + DEFAULT |
| `String[]` (array) | JSONB `@JdbcTypeCode(SqlTypes.JSON)` atau tabel pivot — **prefer tabel pivot untuk relasi** |
| `Json` | JSONB `@JdbcTypeCode(SqlTypes.JSON)` |
| `@@unique` / `@@index` | `uq_*` / `idx_*` constraint di migration |
| Migration | Flyway fresh `V1__...` (AD-22 dev-phase reset) |
| Repository | `JpaRepository` + `@Repository`, named query mengikuti konvensi existing |

**Konvensi nama tabel:** `snake_case`, plural; kolom `snake_case`; FK `{singular}_id`; index `idx_<table>_<cols>`; unique `uq_<table>_<cols>`.

---

## Modul A — Org Structure & Identity

### A1. Plant → `plants` (sudah ada, adaptasi)
| Prisma | JPA |
|---|---|
| `Plant { id, code @unique, name, created_at }` | `PlantEntity` (sudah ada) — id UUID, code unique, name, timestamps. Tambah relasi ke entitas baru di bawah. |

### A2. `Department` → `departments` (perluasan V58 existing)
| Prisma | JPA |
|---|---|
| `Department { id, plant_id, name, spv_id?, mg_id?, is_active, timestamps }` | `DepartmentEntity` — @ManyToOne Plant, @ManyToOne User (spv, mg via relation name), `is_active`. Unique `(plant_id, name)`. |
| `@@unique([plant_id, name])` | `uq_departments_plant_name` |

### A3. `DepartmentUser` → `department_users` (pivot, sudah ada sebagai `department_members` — **rename/adaptasi**)
| Prisma | JPA |
|---|---|
| `DepartmentUser { id, department_id, user_id, assigned_by, assigned_at }` | `DepartmentUserEntity` — id UUID, @ManyToOne Department, @ManyToOne User, @ManyToOne User assigner. Unique `(department_id, user_id)`. |

### A4. `JobTitle` → `job_titles` (sudah ada V58, perkuat)
| Prisma | JPA |
|---|---|
| `JobTitle { id, code @unique, name, description?, binding_scope, is_active, default_system_role_id? }` | `JobTitleEntity` — code unique, `binding_scope` enum (NONE/PLANT/AREA), @ManyToOne SystemRole (default). |
| `enum JobBindingScope { NONE PLANT AREA }` | `JobBindingScope` enum (JPA) |

### A5. `SystemRole` → `system_roles` (BARU)
| Prisma | JPA |
|---|---|
| `SystemRole { id, code @unique, name, level, is_active, description? }` | `SystemRoleEntity` — code unique, level int, is_active. |

### A6. `RolePermissionMapping` → `role_permission_mappings` (BARU)
| Prisma | JPA |
|---|---|
| `RolePermissionMapping { id, system_role_id, menu_feature_id, domain_id?, is_granted }` | `RolePermissionMappingEntity` — @ManyToOne SystemRole, @ManyToOne MenuFeature, @ManyToOne DomainContext. Unique `(system_role_id, menu_feature_id, domain_id)`. |

### A7. `MenuFeature` → `menu_features` (BARU)
| Prisma | JPA |
|---|---|
| `MenuFeature { id, code @unique, module, name, is_active }` | `MenuFeatureEntity` — code unique (mis. `cmms:wo:read`), module (cmms/admin/inventory/pm). |

### A8. `DomainContext` → `domain_contexts` (BARU)
| Prisma | JPA |
|---|---|
| `DomainContext { id, code @unique, name }` | `DomainContextEntity` — code unique (maintenance/production/inventory). |

### A9. `UserJobBinding` → `user_job_bindings` (BARU)
| Prisma | JPA |
|---|---|
| `UserJobBinding { id, user_id, job_title_id, assigned_by, assigned_at }` | `UserJobBindingEntity` — unique `(user_id)` (1 user = 1 job title). |

### A10. `UserRoleBinding` → `user_role_bindings` (BARU)
| Prisma | JPA |
|---|---|
| `UserRoleBinding { id, user_id, system_role_id, is_override, assigned_by, assigned_at }` | `UserRoleBindingEntity` — unique `(user_id, system_role_id)`. |

### A11. `MachineArea` → `machine_areas` (BARU — pengganti sebagian konsep MachineGroup)
| Prisma | JPA |
|---|---|
| `MachineArea { id, plant_id, code?, name, ... }` (dari relasi `Machine.area_id`) | `MachineAreaEntity` — @ManyToOne Plant, name. **Catatan:** syncro-spring memakai `machine_groups` — keputusan: pertahankan `machine_groups` sebagai kompatibilitas, TAMBAH `machine_areas` sebagai area/lokasi opsional pada machine. Atau aliaskan `machine_areas` = `machine_groups` bila tidak bentrok. (DECISION POINT) |

### A12. `PlantWorkingCalendar` + `PlantWorkingCalendarDate` → `plant_working_calendars` + `plant_working_calendar_dates` (BARU)
| Prisma | JPA |
|---|---|
| `PlantWorkingCalendar { id, plant_id, year, workweek_mode, timestamps }` | `PlantWorkingCalendarEntity` — unique `(plant_id, year)`, `workweek_mode` enum (FIVE_DAY/SIX_DAY). |
| `PlantWorkingCalendarDate { id, working_calendar_id, date @db.Date, reason? }` | `PlantWorkingCalendarDateEntity` — unique `(working_calendar_id, date)`. |

### A13. `User` → `auth_users` (perluasan existing)
| Prisma | JPA |
|---|---|
| `User { id, email @unique, phone_number? @unique, display_name, password_hash, is_active, plant_id?, department_id?, ... }` | `AuthUserEntity` (existing) — tambah relasi @ManyToOne Plant, @ManyToOne Department, `phone_verified_at`, `force_password_change`, `failed_login_attempts`, `locked_at`, `lock_reason`. |

---

## Modul B — Workorder Execution (CORE)

### B1. `WOCategory` → `work_order_categories` (perluasan existing)
| Prisma | JPA |
|---|---|
| `WOCategory { id, plant_id?, code, name, description?, requires_rating, is_active }` | `WorkOrderCategoryEntity` (existing) — TAMBAH kolom `plant_id` (nullable, global category bila null), `requires_rating` boolean. |

### B2. `WorkOrder` → `work_orders` (REDESIGN)
| Prisma | JPA (target) |
|---|---|
| `id String @id @default(uuid())` | Tetap `VARCHAR(50)` PK (AD-3 dual-source). **Format ID INTERNAL: `WO-YYMMXXXX` tanpa dash** (mis. `WO-260800001`). |
| `wo_number String @unique` | `id` (VARCHAR) — kolom id tetap unik; jika perlu `wo_number` terpisah, tambah kolom. |
| `status WorkOrderStatus` | **6 status baru**: `OPEN, IN_PROGRESS, PENDING_SPAREPART, PENDING_REVIEW, CLOSED` + `CANCELLED` (dari OPEN/IN_PROGRESS). **Ganti enum 8-status lama.** |
| `source WorkOrderSource` | **DP5 RESOLVED: `source` hanya `EXTERNAL | INTERNAL`** — mempertahankan dual-source semantics (external `sheet_no`, internal `WO-YYMMXXXX`). TIDAK pakai WHATSAPP/WEB/SYSTEM. |
| `machine_id, plant_id, category_id` | UUID kolom polos (AD-3) atau @ManyToOne — ikuti existing (UUID polos). |
| `reported_by String` | `reported_by` UUID → User |
| `description, attachment_url?` | existing description; attachment_url → diganti `workorder_attachments` (sudah ada). |
| `assigned_technician_id String?` | TETAP ada = teknisi utama/lead (FR-113). **Eksekutor nyata = work_assignments (AD-17).** |
| `closed_at?` | `closed_at` Instant nullable |
| `created_at, updated_at` | existing |

### B3. `WorkAssignment` → `work_assignments` (BARU — AD-17)
| Prisma | JPA |
|---|---|
| `WorkAssignment { id, parent_type, parent_id, work_order_id, technician_id, assigned_by, assigned_at, dropped_at?, dropped_by?, is_active }` | `WorkAssignmentEntity` — id UUID, @ManyToOne WorkOrder, @ManyToOne User (technician), @ManyToOne User (assigner/dropper), `parent_type` enum (CORRECTIVE_WO), `assigned_at`, `dropped_at`, `is_active`. Unique `(work_order_id, technician_id, assigned_at)`. |
| `enum WorkAssignmentParentType { CORRECTIVE_WO }` | `WorkAssignmentParentType` enum |
| `@@unique([work_order_id, technician_id, assigned_at])` | `uq_work_assignments_wo_tech_at` |

### B4. `WorkLog` → `work_logs` (BARU — AD-18)
| Prisma | JPA |
|---|---|
| `WorkLog { id, work_assignment_id, work_order_id, technician_id, start_time, end_time?, stopped_reason?, notes?, activity_note, completion_note? }` | `WorkLogEntity` — id UUID, @ManyToOne WorkAssignment, @ManyToOne WorkOrder, @ManyToOne User (technician). `start_time`/`end_time` Instant (backdate allowed, min = work_order.created_at), `stopped_reason` enum, `activity_note` wajib, `completion_note` opsional. |
| `enum WorkLogStoppedReason { WAITING_SPAREPART SHIFT_END COMPLETED OTHER }` | `WorkLogStoppedReason` enum |

### B5. `WorkOrderAuditLog` → `work_order_status_history` (existing) + perluasan
| Prisma | JPA |
|---|---|
| `WorkOrderAuditLog { id, wo_id, from_status, to_status, actor_id?, actor_type, notes?, occurred_at }` | Existing `work_order_status_history` sudah menutupi; TAMBAH `actor_type` enum (USER/SYSTEM/BOT) bila perlu. |

---

## Modul C — Rating

### C1. `WorkLogRatingCriterion` + `WorkLogRatingCriterionCategory` → `work_log_rating_criteria` + `work_log_rating_criterion_categories` (BARU)
| Prisma | JPA |
|---|---|
| `WorkLogRatingCriterion { id, name, description?, min_score=1, max_score=5, plant_id?, is_active, sort_order, created_by }` | `WorkLogRatingCriterionEntity` |
| `WorkLogRatingCriterionCategory { id, criterion_id, category_id }` | pivot criterion ↔ WOCategory, unique `(criterion_id, category_id)` |

### C2. `WorkLogRating` → `work_log_ratings` (BARU)
| Prisma | JPA |
|---|---|
| `WorkLogRating { id, work_log_id, criterion_id, score, rated_by, rated_at, remarks? }` | `WorkLogRatingEntity` — unique `(work_log_id, criterion_id)` |

### C3. `WorkOrderRatingCriterion` + `...Category` → `work_order_rating_criteria` + `..._categories` (BARU)
Analog C1 untuk rating WO.

### C4. `WorkOrderQualityRating` → `work_order_quality_ratings` (BARU)
| Prisma | JPA |
|---|---|
| `WorkOrderQualityRating { id, work_order_id, status(PENDING/SUBMITTED/EXPIRED), due_at, submitted_at?, submitted_by?, cleanliness_score?, tidiness_score?, speed_score?, remarks? }` | `WorkOrderQualityRatingEntity` — unique `(work_order_id)` |

### C5. `WorkOrderQualityRatingTechnician` → `work_order_quality_rating_technicians` (BARU — multi teknisi)
| Prisma | JPA |
|---|---|
| `{ id, quality_rating_id, work_assignment_id, technician_id }` | pivot, unique `(quality_rating_id, technician_id)` |

### C6. `WorkOrderQualityRatingScore` → `work_order_quality_rating_scores` (BARU)
| Prisma | JPA |
|---|---|
| `{ id, quality_rating_id, criterion_id, score }` | unique `(quality_rating_id, criterion_id)` |

---

## Modul D — Sparepart Master / BOM

### D1. `SparepartCategory` → `sparepart_categories` (BARU; atau aliaskan dengan taxonomy)
| Prisma | JPA |
|---|---|
| `{ id, code @unique, name, description?, is_active }` | `SparepartCategoryEntity`. **Catatan:** existing memakai `sparepart_taxonomy(dimension=CATEGORY)`. Keputusan: gunakan tabel dedicated baru (cleaner), migrasi dari taxonomy bila perlu. (DECISION POINT) |

### D2. `SparepartKind` / `SparepartBrand` / `SparepartSeries` → `sparepart_kinds` / `sparepart_brands` / `sparepart_series` (BARU)
- `SparepartKind { id, category_id, code, name }` unique `(category_id, code)`
- `SparepartBrand { id, code @unique, name }`
- `SparepartSeries { id, category_id, kind_id, brand_id?, code, name }` unique `(category_id, kind_id, brand_id, code)`

### D3. `SparepartMaster` → `spareparts` (REDESIGN existing)
| Prisma | JPA (target) |
|---|---|
| `SparepartMaster { id, machine_id, category_id, kind_id, manufacturer_id?, series_id?, hierarchy_identity_key @unique, bom_serial, bom_code @unique, bom_code_version, review_status, description?, rejection_reason?, created_by?, is_active }` | `SparepartEntity` (existing) — TAMBAH `hierarchy_identity_key` (unique), `bom_serial`, `bom_code` (unique), `bom_code_version`, `review_status` enum (PENDING_REVIEW/ACTIVE/REJECTED), `rejection_reason`. |
| `enum BomReviewStatus { PENDING_REVIEW ACTIVE REJECTED }` | `BomReviewStatus` enum |
| `@@unique([machine_id, category_id, kind_id, bom_serial])` | `uq_spareparts_machine_cat_kind_serial` |

---

## Modul E — Inventory

### E1. `InventoryLocation` → `inventory_locations` (BARU)
| Prisma | JPA |
|---|---|
| `{ id, plant_id, code, name, description?, is_active }` | `InventoryLocationEntity` — unique `(plant_id, code)` |

### E2. `InventoryStockBalance` → `inventory_stock_balances` (BARU — perluasan sparepart_stock)
| Prisma | JPA |
|---|---|
| `{ id, sparepart_master_id, location_id, available, reserved, consumed, minimum_stock }` | `InventoryStockBalanceEntity` — unique `(sparepart_master_id, location_id)`. Menggantikan/berdampingan `sparepart_stock(material_code, plant_id)`. (DECISION POINT: migration dari stock lama) |

### E3. `InventoryTransfer` → `inventory_transfers` (BARU)
| Prisma | JPA |
|---|---|
| `{ id, sparepart_master_id, source_location_id, destination_location_id, quantity, status(PENDING_APPROVAL/APPROVED/REJECTED), requested_by, reviewed_by?, rejection_reason?, reviewed_at? }` | `InventoryTransferEntity` |

### E4. `InventoryReservation` → `inventory_reservations` (BARU)
| Prisma | JPA |
|---|---|
| `{ id, sparepart_master_id, location_id, quantity, remaining_quantity, status(ACTIVE/CONSUMED/CANCELLED/EXPIRED), reference_type, reference_id, requested_by, consumed_by?, cancelled_by?, ... }` | `InventoryReservationEntity` |

---

## Modul F — PM (Preventive) Execution Model

### F1. `PMFrequency` → `pm_frequencies` (BARU)
| Prisma | JPA |
|---|---|
| `{ id, code @unique, name, description?, sort_order, is_active }` | `PMFrequencyEntity` |

### F2. `PMChecksheet` → `pm_checksheets` (BARU — pengganti preventive_programs)
| Prisma | JPA |
|---|---|
| `{ id, machine_id, frequency_id, revision_no, revision_reason?, is_active, supersedes?, approved_by?, approved_at?, effective_date?, created_by }` | `PMChecksheetEntity` — unique `(machine_id, frequency_id, revision_no)`, self-FK `supersedes` (revisi). |

### F3. `ActiveChecksheet` → `active_checksheets` (BARU)
| Prisma | JPA |
|---|---|
| `{ machine_id, frequency_id, checksheet_id }` | composite PK `(machine_id, frequency_id)`, unique `checksheet_id`. |

### F4. `PMChecklistCategory` + `PMChecklistItem` → `pm_checklist_categories` + `pm_checklist_items` (BARU)
- `PMChecklistItem { checksheet_id, category_id, sequence, parameter_text, check_method, input_type(MEASUREMENT/OK_NG), unit?, lsl?, nominal?, usl?, is_critical_flag, reference_document?, calibration_instrument_id? }`

### F5. `PMSchedule` + `PMScheduleDate` → `pm_schedules` + `pm_schedule_dates` (BARU)
- `PMSchedule { plant_id, machine_id, checksheet_id, checksheet_revision_no, frequency_id, frequency_code, frequency_name, year, status(DRAFT/PENDING_SPV_APPROVAL/PENDING_PRODUCTION_APPROVAL/APPROVED/ACTIVE), submitted_by?, submitted_at?, approved_by_spv?, approved_at_spv?, approved_by_prod?, approved_at_prod?, warnings(Json) }` — unique `(plant_id, machine_id, checksheet_id, year)`
- `PMScheduleDate { schedule_id, planned_date, status(SCHEDULED/EXECUTED/MISSED/RESCHEDULED) }` — unique `(schedule_id, planned_date)`

### F6. `PMWorkOrder` → `pm_work_orders` (BARU)
| Prisma | JPA |
|---|---|
| `{ machine_id, template_id, frequency_id, frequency_code, frequency_name, template_revision, status(SCHEDULED/ASSIGNED/IN_PROGRESS/COMPLETED/OVERDUE), assigned_technician_id?, scheduled_date, started_at?, completed_at?, certificate_url? }` | `PMWorkOrderEntity` |

### F7. `PMExecution` → `pm_executions` (BARU)
| Prisma | JPA |
|---|---|
| `{ pm_wo_id @unique, schedule_date_id? @unique, technician_id, spv_verifier_id?, technician_signature_id?, technician_signed_at?, spv_signature_id?, spv_signed_at?, started_at, completed_at?, has_ng_items, ng_count, finding_wo_id? }` | `PMExecutionEntity` — unique `pm_wo_id`, `schedule_date_id` |

### F8. `PMExecutionItem` → `pm_execution_items` (BARU)
| Prisma | JPA |
|---|---|
| `{ execution_id, checklist_item_id, sequence, category_name, parameter_text, check_method, input_type, is_critical_flag, unit?, lsl?, nominal?, usl?, actual_value?, is_ok?, is_ng, ng_notes?, ng_photo_url?, is_blocked, blocked_wo_code?, blocking_wo_id?, ng_resolved_at?, ng_resolution_notes?, spv_verified_at?, spv_verifier_id?, spv_signature_id?, filled_at? }` | `PMExecutionItemEntity` — @ManyToOne PMExecution, @ManyToOne PMChecklistItem, @ManyToOne WorkOrder (blockingWO). |

---

## Modul G — KPI Materialization

### G1–G7 → tabel KPI (BARU)
| Prisma | JPA |
|---|---|
| `KpiTarget { plant_id, month @db.Date, monthly_breakdown_target?, mtbf_target_days?, mttr_target_minutes?, oee_quality_percent?, oee_performance_percent?, created_by }` | `KpiTargetEntity` — unique `(plant_id, month)` |
| `KpiMonthlyBreakdown { plant_id, month, count }` | unique `(plant_id, month)` |
| `KpiMtbfMonthly { plant_id, machine_id, month, mtbf_days? }` | unique `(machine_id, month)` |
| `KpiMttrMonthly { plant_id, month, wall_clock_mttr_minutes?, actual_working_mttr_minutes? }` | unique `(plant_id, month)` |
| `KpiMarMonthly { plant_id, month, planned_available_minutes, downtime_minutes, mar_percent?, source_status, source_message? }` | unique `(plant_id, month)` |
| `KpiTechnicianMonthly { plant_id, technician_id, month, average_rating?, total_wo, first_time_fix_rate? }` | unique `(plant_id, technician_id, month)` |
| `KpiPmCompletionMonthly { plant_id, month, completion_rate?, completed_count, planned_count, source_status, source_message? }` | unique `(plant_id, month)` |
| `KpiAggregateRefreshLog { refresh_key @unique, refreshed_at, status, message? }` | `KpiAggregateRefreshLogEntity` |

---

## Modul H — IATF Compliance

### H1. `NonConformance` → `non_conformances` (BARU)
| Prisma | JPA |
|---|---|
| `{ project_id?, work_order_id?, machine_id?, nc_number @unique, description, root_cause?, corrective_action?, responsible_id, status(OPEN/IN_PROGRESS/CLOSED/VERIFIED), target_close_date?, closed_at? }` | `NonConformanceEntity` |

### H2. `EightDReport` → `eight_d_reports` (BARU)
| Prisma | JPA |
|---|---|
| `{ nc_id @unique, report_number @unique, d1_team(Json), d2_description, d3_containment?, d4_root_cause(Json)?, d5_ca_permanent?, d6_implementation?, d7_lesson_learned?, d8_closure_notes?, status(DRAFT/IN_PROGRESS/CLOSED/EFFECTIVE/INEFFECTIVE), effectiveness_verified_at?, pdf_artifact_url? }` | `EightDReportEntity` |

### H3. `CalibrationInstrument` + `CalibrationRecord` → `calibration_instruments` + `calibration_records` (BARU)
- `CalibrationInstrument { instrument_code @unique, name, model?, serial_number?, location, calibration_frequency_days, last_calibration_date?, next_calibration_date, calibration_body?, status(VALID/EXPIRING_SOON/EXPIRED) }`
- `CalibrationRecord { instrument_id, calibration_date, next_calibration_date, calibration_body, certificate_number, certificate_url?, result, notes?, recorded_by }`

### H4. `EquipmentChangeNotice` → `equipment_change_notices` (BARU)
| Prisma | JPA |
|---|---|
| `{ ecn_number @unique, machine_id, title, description, change_type, justification, status(DRAFT/UNDER_REVIEW/APPROVED/EXECUTED/CLOSED), submitted_by, reviewed_by?, approved_by?, effective_date?, executed_wo_id?, sign_off_at?, before_photo_url?, after_photo_url? }` | `EquipmentChangeNoticeEntity` |

### H5. `MachineSetupBaseline` → `machine_setup_baselines` (BARU)
| Prisma | JPA |
|---|---|
| `{ machine_id, ecn_id? @unique, version, parameters(Json), validated_by, validated_at, is_active }` | unique `(machine_id, version)` |

### H6. `LessonLearned` → `lesson_learned` (BARU)
| Prisma | JPA |
|---|---|
| `{ project_id @unique, machine_id?, project_type?, title, problem_summary, root_cause?, solution, spareparts_used(Json)?, duration_days?, re_cycle_count, tags(String[]) }` | `LessonLearnedEntity` — tags pakai JSONB array |

### H7. `HistoricalMachineRecord` → `historical_machine_records` (BARU)
| Prisma | JPA |
|---|---|
| `{ machine_id, import_batch_id, source_file_name, source_row_number, happened_at?, title, problem_summary, root_cause?, solution?, lesson_learned?, downtime_minutes?, tags(String[]), raw_payload(Json), imported_by }` | `HistoricalMachineRecordEntity` |

---

## Modul I — Signature, Auth Audit, Webhook, WhatsApp

### I1. `UserSignature` + `SignatureUse` → `user_signatures` + `signature_uses` (perluasan existing workorder_signatures)
| Prisma | JPA |
|---|---|
| `UserSignature { user_id @unique, bucket, object_key, content_type, signature_failed_attempts, signature_blocked_until? }` | `UserSignatureEntity` (baru, umum) — existing `workorder_signatures` mungkin di-merge. (DECISION POINT) |
| `SignatureUse { signer_id, signature_id?, module, subject_type, subject_id, action, reason?, signature_bucket, signature_object_key, signature_sha256?, signed_artifact_bucket?, signed_artifact_key?, ip_address?, user_agent?, signed_at }` | `SignatureUseEntity` — index `(module, subject_type, subject_id)` |

### I2. `AuthLoginAudit` → `auth_login_audits` (BARU)
| Prisma | JPA |
|---|---|
| `{ user_id?, identifier, ip_address, user_agent?, was_success, failure_reason?, occurred_at }` | `AuthLoginAuditEntity` |

### I3. `PhoneVerificationChallenge` → `phone_verification_challenges` (BARU)
| Prisma | JPA |
|---|---|
| `{ user_id, pending_phone, otp_hash, expires_at, attempt_count, max_attempts=5, resend_available_at, consumed_at? }` | `PhoneVerificationChallengeEntity` |

### I4. `WebhookConfig` + `WebhookDeliveryLog` → `webhook_configs` + `webhook_delivery_logs` (BARU)
| Prisma | JPA |
|---|---|
| `WebhookConfig { name, direction(INBOUND/OUTBOUND), event_types(String[]), endpoint_url?, hmac_secret, is_active, created_by }` | `WebhookConfigEntity` — hmac_secret harus di-mask di log |
| `WebhookDeliveryLog { webhook_config_id, event_type, payload(Json), response_code?, response_body?, latency_ms?, status(PENDING/DELIVERED/FAILED/RETRYING/DLQ), attempt_count, next_retry_at?, }` | `WebhookDeliveryLogEntity` |

### I5. `WhatsAppMessageLog` → `whatsapp_message_logs` (BARU)
| Prisma | JPA |
|---|---|
| `{ waha_message_id @unique, session, chat_id, from_phone?, text?, attachment_url?, payload(Json)?, work_order_id?, user_id?, received_at }` | `WhatsAppMessageLogEntity` |

---

## Pemetaan Enum (Ringkasan)

| Prisma enum | JPA enum | Nilai |
|---|---|---|
| `WorkOrderStatus` | `WorkOrderStatus` (REDESIGN) | OPEN, IN_PROGRESS, PENDING_SPAREPART, PENDING_REVIEW, CLOSED, CANCELLED |
| `WorkOrderSource` | `WorkOrderSource` (perluas) | WHATSAPP, WEB, SYSTEM (+ SYNCED/INTERNAL untuk dual-source) |
| `WorkLogStoppedReason` | `WorkLogStoppedReason` | WAITING_SPAREPART, SHIFT_END, COMPLETED, OTHER |
| `JobBindingScope` | `JobBindingScope` | NONE, PLANT, AREA |
| `WorkAssignmentParentType` | `WorkAssignmentParentType` | CORRECTIVE_WO |
| `WorkRatingStatus` | `WorkRatingStatus` | PENDING, SUBMITTED, EXPIRED |
| `BomReviewStatus` | `BomReviewStatus` | PENDING_REVIEW, ACTIVE, REJECTED |
| `InventoryTransferStatus` | `InventoryTransferStatus` | PENDING_APPROVAL, APPROVED, REJECTED |
| `InventoryReservationStatus` | `InventoryReservationStatus` | ACTIVE, CONSUMED, CANCELLED, EXPIRED |
| `PMWOStatus` | `PMWOStatus` | SCHEDULED, ASSIGNED, IN_PROGRESS, COMPLETED, OVERDUE |
| `PMScheduleStatus` | `PMScheduleStatus` | DRAFT, PENDING_SPV_APPROVAL, PENDING_PRODUCTION_APPROVAL, APPROVED, ACTIVE |
| `PMScheduleDateStatus` | `PMScheduleDateStatus` | SCHEDULED, EXECUTED, MISSED, RESCHEDULED |
| `PMItemInputType` | `PMItemInputType` | MEASUREMENT, OK_NG |
| `WorkweekMode` | `WorkweekMode` | FIVE_DAY, SIX_DAY |
| `NCStatus` | `NCStatus` | OPEN, IN_PROGRESS, CLOSED, VERIFIED |
| `EightDStatus` | `EightDStatus` | DRAFT, IN_PROGRESS, CLOSED, EFFECTIVE, INEFFECTIVE |
| `CalibrationStatus` | `CalibrationStatus` | VALID, EXPIRING_SOON, EXPIRED |
| `ECNStatus` | `ECNStatus` | DRAFT, UNDER_REVIEW, APPROVED, EXECUTED, CLOSED |
| `WebhookDeliveryStatus` | `WebhookDeliveryStatus` | PENDING, DELIVERED, FAILED, RETRYING, DLQ |
| `WebhookDir` | `WebhookDir` | INBOUND, OUTBOUND |

---

## DECISION POINTS (RESOLVED 2026-08-31)

1. **MachineGroup vs MachineArea** ✅ — `machine_groups` (kategori) dipertahankan; `machine_areas` (lokasi fisik) ditambahkan sebagai kolom opsional `machines.area_id`.
2. **SparepartCategory vs sparepart_taxonomy** ✅ — Pertahankan `sparepart_taxonomy` (existing); `spareparts` diperluas dengan kolom BOM master (`bom_code`, `hierarchy_identity_key`, `review_status`). Tidak ada tabel taxonomy baru.
3. **sparepart_stock → inventory_stock_balances** ✅ — Redesign: `inventory_locations` + `inventory_stock_balances` menggantikan `sparepart_stock` (dev-phase, reset DB). Seed membuat 1 lokasi default per plant ("GUDANG UTAMA").
4. **user_signatures vs workorder_signatures** ✅ — `user_signatures` + `signature_uses` menggantikan `workorder_signatures`; WO signature menjadi `signature_uses(subject_type=WORK_ORDER)`.
5. **WorkOrder source** ✅ — `source` hanya `EXTERNAL | INTERNAL` (dual-source dipertahankan). TIDAK pakai WHATSAPP/WEB/SYSTEM.

---

## Modul Package (Java)

```
com.syncro
├── org          (Department, DepartmentUser, JobTitle, SystemRole, MenuFeature,
│                 DomainContext, RolePermissionMapping, UserJobBinding,
│                 UserRoleBinding, MachineArea, PlantWorkingCalendar)
├── machine      (Machine, MachineGroup/MachineArea)
├── masterdata   (SparepartCategory/Kind/Brand/Series/Master)
├── maintenance  (WorkOrder, WorkAssignment, WorkLog, WOCategory,
│                 WorkOrderStatusHistory, Rating*, RepairSession→WorkLog)
├── inventory    (InventoryLocation, StockBalance, Transfer, Reservation)
├── preventive   (PMFrequency, PMChecksheet, PMChecklist*, PMSchedule,
│                 PMWorkOrder, PMExecution, PMExecutionItem)
├── kpi          (KpiTarget, Kpi*Monthly, KpiAggregateRefreshLog)
├── compliance   (NonConformance, EightDReport, Calibration*, ECN,
│                 MachineSetupBaseline, LessonLearned, HistoricalMachineRecord)
├── auth         (User, AuthLoginAudit, PhoneVerificationChallenge, UserSignature, SignatureUse)
├── integration  (WebhookConfig, WebhookDeliveryLog)
├── notification (WhatsAppMessageLog)
├── authz        (OPA, PolicyDecisionPoint)
└── sync         (unchanged, adapted to new work_orders schema)
```
