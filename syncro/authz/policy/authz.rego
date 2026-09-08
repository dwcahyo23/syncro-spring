package syncro.authz

# Default-deny: every authorization decision must be explicitly allowed by a rule.
default allow := false

allow if super_admin

allow if {
  not super_admin
  mutation_allowed
}

allow if {
  not super_admin
  not mutation_allowed
  read_allowed
}

super_admin if input.subject.roles[_] == "SUPER_ADMIN"

# The twelve requireMutationRole services (story 9-4) + WahaTemplate allow-list:
# mutations on these endpoints are allowed only for MANAGER_MAINTENANCE (SUPER_ADMIN
# bypasses above). Paths use `*` for a single path segment and `**` for a tail; the
# subject's action is normalized (template braces -> `*`) before glob matching.
core_mutation_paths := {
  "/api/v1/plants",
  "/api/v1/plants/*",
  "/api/v1/machine-groups",
  "/api/v1/machine-groups/*",
  "/api/v1/machine-groups/*/section",
  "/api/v1/machine-groups/*/shift-config",
  "/api/v1/sections",
  "/api/v1/sections/*",
  "/api/v1/teams",
  "/api/v1/teams/*",
  "/api/v1/teams/*/members",
  "/api/v1/teams/*/members/*",
  "/api/v1/teams/*/machines",
  "/api/v1/teams/*/machines/*",
  "/api/v1/machine-responsibilities",
  "/api/v1/machine-responsibilities/*",
  "/api/v1/machines",
  "/api/v1/machines/*",
  "/api/v1/machines/*/shift-config",
  "/api/v1/machine-sparepart-installations",
  "/api/v1/machine-sparepart-installations/*",
  "/api/v1/spareparts",
  "/api/v1/spareparts/*",
  "/api/v1/spareparts/*/image",
  "/api/v1/spareparts/*/price-entries",
  "/api/v1/spareparts/*/approve",
  "/api/v1/spareparts/*/reject",
  "/api/v1/sparepart-taxonomies",
  "/api/v1/sparepart-taxonomies/*",
  "/api/v1/notification/templates",
}

# Work-order category mutation (story 10-1): global config gate — SECTION_LEADER and
# above may mutate; mirrors the in-service requireCategoryRole set exactly (9-5 parity).
# Category reads (GET) are allowed for any authenticated user via read_allowed below.
category_mutation_paths := {
  "/api/v1/work-order-categories",
  "/api/v1/work-order-categories/*",
}

# Alert commands are action-scoped in service (any authenticated user with plant access
# can acknowledge/resolve — SparepartAlertCommandService.loadAndCheckAccess); the
# SUPER_ADMIN-only resolve-override path is deliberately NOT here, so it stays
# default-deny for everyone but SUPER_ADMIN, matching the in-service gate exactly.
alert_mutation_paths := {
  "/api/v1/alerts",
  "/api/v1/alerts/*",
  "/api/v1/alerts/*/acknowledge",
  "/api/v1/alerts/*/resolve",
}

# Workorder create/assign (story 10-2): create is open to the five create roles;
# assign only to the four leadership roles (STAFF_MAINTENANCE and PRODUCTION_LEADER
# cannot assign — FR-113 parity with WorkOrderService.requireAssignRole). The
# "01"-only and scope checks stay in-service (rego is coarser — it cannot see the
# request body). Mirrors WorkOrderService gates exactly (9-5 parity pattern).
workorder_create_paths := {
  "/api/v1/workorders",
}

# Sparepart request creation (story 12-1): five-role allow set mirroring workorder-create
# parity (MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE,
# TECHNICIAN). Service gate is authoritative for scope/executor assignment.
sparepart_request_paths := {
  "/api/v1/sparepart-requests",
}

# Sparepart request transition and MRE (story 12-2): seven-role allow set
# (MANAGER_MAINTENANCE/MAINTENANCE_LEADER/SECTION_LEADER/INVENTORY_MAINTENANCE/
#  STOREKEEPER/STAFF_MAINTENANCE/TECHNICIAN). SUPER_ADMIN is covered by the
# generic SUPER_ADMIN mutation_allowed rule. Service gate is authoritative for
# scope/role/ownership checks.
sparepart_request_transition_paths := {
  "/api/v1/sparepart-requests/*/transition",
  "/api/v1/sparepart-requests/*/mre",
}

# Sparepart request approval (story 12-3): three-leader allow set — the coarse gate.
# SUPER_ADMIN is covered by the generic rule; SoD (requester != approver), tier selection
# and in-scope checks stay service-side (rego cannot see the body or the request state).
sparepart_request_approval_paths := {
  "/api/v1/sparepart-requests/*/approve",
}

# Sparepart request completion (story 12-4, FR-144): coarse three-role allow set
# (INVENTORY_MAINTENANCE, STOREKEEPER; SUPER_ADMIN via the generic rule). Scope and
# state checks stay service-side (rego cannot see the body or the request state).
sparepart_request_completion_paths := {
  "/api/v1/sparepart-requests/*/complete",
}

# Sparepart stock (story 12-4, FR-146): mutations for INVENTORY_MAINTENANCE/STOREKEEPER
# (SUPER_ADMIN via the generic rule). Reads (GET) flow through generic read_allowed.
sparepart_stock_paths := {
  "/api/v1/sparepart-stock",
  "/api/v1/sparepart-stock/*",
  "/api/v1/sparepart-stock/*/adjust",
}

# Inventory locations (story 18-2, blueprint E1): location lifecycle mutations for
# MANAGER_MAINTENANCE/INVENTORY_MAINTENANCE (SUPER_ADMIN via the generic bypass;
# STOREKEEPER deliberately NOT granted). The surface is list/create at the collection
# path and get/update at /{id} — a single `*` matches one segment, so no deeper
# subpaths exist. Reads (GET) flow through generic read_allowed.
inventory_location_paths := {
  "/api/v1/inventory-locations",
  "/api/v1/inventory-locations/*",
}

# Inventory location stock balances (story 18-3, blueprint E2): per-location stock
# mutations for INVENTORY_MAINTENANCE/STOREKEEPER (SUPER_ADMIN via the generic bypass).
# A single `*` matches exactly one path segment, so each depth is enumerated
# explicitly: collection (list/create), the material-code segment, and /adjust.
# Reads (GET) flow through generic read_allowed. Plant scope is service-side (rego
# cannot see the location's plant).
inventory_location_stock_paths := {
  "/api/v1/inventory-locations/*/stock-balances",
  "/api/v1/inventory-locations/*/stock-balances/*",
  "/api/v1/inventory-locations/*/stock-balances/*/adjust",
}

# Inventory transfers (story 18-4, blueprint E3): transfer lifecycle mutations for
# INVENTORY_MAINTENANCE/STOREKEEPER/MANAGER_MAINTENANCE (SUPER_ADMIN via the generic
# bypass). A single `*` matches exactly one path segment, so each depth is enumerated
# explicitly: collection (list/create), /{id} (get), /{id}/approve, /{id}/reject.
# Reads (GET) flow through generic read_allowed. SoD, plant scope and the state
# machine are service-side (rego cannot see the body or the transfer state).
inventory_transfer_paths := {
  "/api/v1/inventory-transfers",
  "/api/v1/inventory-transfers/*",
  "/api/v1/inventory-transfers/*/approve",
  "/api/v1/inventory-transfers/*/reject",
}

# Inventory reservation mutations (story 18-5, blueprint E4): the same three-role
# allow set as inventory transfer creates (INVENTORY_MAINTENANCE/STOREKEEPER/
# MANAGER_MAINTENANCE; SUPER_ADMIN via the generic bypass). A single `*` matches
# exactly one path segment, so each depth is enumerated explicitly: collection
# (list/create), /{id} (get), /{id}/consume, /{id}/cancel. Reads (GET) flow through
# generic read_allowed. Plant scope and the state machine are service-side (rego
# cannot see the body or the reservation state).
inventory_reservation_paths := {
  "/api/v1/inventory-reservations",
  "/api/v1/inventory-reservations/*",
  "/api/v1/inventory-reservations/*/consume",
  "/api/v1/inventory-reservations/*/cancel",
}

workorder_assign_paths := {
  "/api/v1/workorders/*/assign",
}

# Workorder multi-technician assignments (story 17-1, blueprint B3, AD-17):
# the same four-role allow set as the single-tech assign path — SUPER_ADMIN,
# MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER. STAFF_MAINTENANCE
# and TECHNICIAN cannot assign (FR-113 parity with WorkOrderService.requireAssignRole).
# The drop endpoint uses the same coarse gate. Reads (GET) flow through read_allowed.
workorder_assignment_paths := {
  "/api/v1/workorders/*/assignments",
  "/api/v1/workorders/*/assignments/*/drop",
}

# Workorder status transitions (story 10-3): the assigned executor (TECHNICIAN or
# STAFF_MAINTENANCE) and the in-scope leadership may drive the lifecycle. Rego is
# coarser than the service — it cannot see the body, the assignment or the scope — so
# the five-role allow set is only the coarse gate; the service actor gate is
# authoritative. AUDITOR/PRODUCTION_LEADER/INVENTORY_MAINTENANCE/STOREKEEPER stay
# default-deny on the path.
workorder_transition_paths := {
  "/api/v1/workorders/*/transition",
}

# Workorder repair sessions (story 10-4): the same executor + leadership role set as
# transitions — sessions are local operational fields the assigned executor or in-scope
# leader logs against an IN_PROGRESS workorder; scope/status are service-side. A single
# `*` matches one path segment, so start (/sessions) and stop (/sessions/stop) need
# separate entries. Session reads (GET) flow through generic read_allowed.
workorder_session_paths := {
  "/api/v1/workorders/*/sessions",
  "/api/v1/workorders/*/sessions/stop",
}

# Workorder evidence & technical drawings (story 10-5): the same executor + leadership
# role set as sessions/transitions — attachments are local operational fields uploaded
# by the assigned executor or an in-scope leader; scope is service-side. Reads (GET)
# flow through generic read_allowed (any authenticated user).
workorder_evidence_paths := {
  "/api/v1/workorders/*/attachments",
  "/api/v1/workorders/*/attachments/*",
}

# Workorder reports, CP/CPK & FMEA (story 10-6): the same executor + leadership role set
# as sessions/evidence — the report is a local operational field written by the assigned
# executor or an in-scope leader; scope is service-side. Reads (GET) flow through generic
# read_allowed (any authenticated user).
workorder_report_paths := {
  "/api/v1/workorders/*/report",
  "/api/v1/workorders/*/report/cpk",
}

# Workorder todos & kanban (story 10-7): the same executor + leadership role set as
# sessions/evidence — todos are local operational fields created/assigned/completed/reordered
# by the assigned executor or an in-scope leader; scope is service-side. Reads (GET) flow
# through generic read_allowed (any authenticated user). The kanban endpoint (GET /kanban)
# is a pure read that always flows through read_allowed. Each `*` matches one path segment,
# so the action subpaths (/assign, /complete, /reorder) are listed explicitly.
workorder_todo_paths := {
  "/api/v1/workorders/*/todos",
  "/api/v1/workorders/*/todos/*",
  "/api/v1/workorders/*/todos/*/assign",
  "/api/v1/workorders/*/todos/*/complete",
  "/api/v1/workorders/*/todos/*/reorder",
}

# Workorder ratings (story 10-8): a single five-role allow set
# (MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE,
# PRODUCTION_LEADER) — the service gate is authoritative for who-can-rate-whom
# (section leader for technician, PRODUCTION_LEADER for workorder). TECHNICIAN is the
# ratee, not a rater, and stays default-deny on these paths. Reads (GET) flow through
# generic read_allowed. The ratings page (GET /ratings) is a pure read at the list level.
workorder_rating_paths := {
  "/api/v1/workorders/*/ratings",
  "/api/v1/workorders/*/ratings/technician",
  "/api/v1/workorders/*/ratings/workorder",
}

# Workorder work logs (story 17-2, blueprint B4, AD-18): the same executor + leadership
# role set as sessions/ratings — work logs are per-assignment execution sessions recorded
# by the assigned executor or an in-scope leader; scope is service-side. The create
# (POST), update (PUT) and list (GET) endpoints are covered by the same path set. GET
# also flows through generic read_allowed (any authenticated user).
workorder_worklog_paths := {
  "/api/v1/workorders/*/work-logs",
  "/api/v1/workorders/*/work-logs/*",
}

# Rating dimension paths (story 10-8): mutations are SUPER_ADMIN-only; reads generic.
# Listed separately from core_mutation_paths because they are not "core" — they are
# workorder config but not in the Epic 9 core set.
rating_dimension_paths := {
  "/api/v1/rating-dimensions",
  "/api/v1/rating-dimensions/*",
}

# Work-log rating paths (story 17-4, blueprint C2, FR-121): the same five-role allow
# set as workorder_rating_paths (MANAGER_MAINTENANCE, SECTION_LEADER,
# MAINTENANCE_LEADER, STAFF_MAINTENANCE, PRODUCTION_LEADER) — the service gate is
# authoritative for who-can-rate-whom (in-scope section leader for work-log ratings).
# TECHNICIAN is the ratee, not a rater, and stays default-deny on these paths. Reads
# (GET) flow through generic read_allowed (any authenticated user).
workorder_worklog_rating_paths := {
  "/api/v1/workorders/*/work-logs/*/ratings",
}

# Work-log rating criterion paths (story 17-4, blueprint C1, AD-14): mutations are
# SUPER_ADMIN-only (the super_admin top-level bypass handles it, so no non-admin role
# matches this set — default-deny for everyone else); reads flow through generic
# read_allowed. Mirrors rating_dimension_paths exactly.
workorder_worklog_criterion_paths := {
  "/api/v1/workorders/work-log-rating-criteria",
  "/api/v1/workorders/work-log-rating-criteria/*",
}

# Workorder quality ratings & criteria (story 17-5, blueprint C4-C6, FR-124): the same
# five-role allow set as workorder_rating_paths (MANAGER_MAINTENANCE, SECTION_LEADER,
# MAINTENANCE_LEADER, STAFF_MAINTENANCE, PRODUCTION_LEADER) — the service gate is
# authoritative for who-can-rate (PRODUCTION_LEADER of the affected line for submission,
# SUPER_ADMIN exempt). TECHNICIAN is the ratee, not a rater, and stays default-deny on
# these paths. Reads (GET) flow through generic read_allowed (any authenticated user).
workorder_quality_rating_paths := {
  "/api/v1/workorders/*/quality-rating",
}

# Workorder quality rating criterion paths (story 17-5, blueprint C3, AD-14): mutations
# are SUPER_ADMIN-only (the super_admin top-level bypass handles it, so no non-admin role
# matches this set — default-deny for everyone else); the criteria list (GET
# /quality-rating-criteria) is a pure read flowing through generic read_allowed. Mirrors
# workorder_worklog_criterion_paths exactly.
workorder_quality_criterion_paths := {
  "/api/v1/workorders/quality-rating-criteria",
  "/api/v1/workorders/quality-rating-criteria/*",
}

# Preventive programs & schedules (story 11-1): program mutations are allowed for the
# four-role set (MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER,
# STAFF_MAINTENANCE) — the service gate is authoritative for scope (section leader needs
# the machine's group, staff needs plant access). Schedule reads (GET) flow through
# generic read_allowed (any authenticated user).
preventive_program_paths := {
  "/api/v1/preventive-programs",
  "/api/v1/preventive-programs/*",
  "/api/v1/preventive-programs/*/generate",
}

# Preventive schedule mutations (story 11-2): checklist submit/amend, evidence upload/
# delete, approve, skip. Same four-role allow set as preventive_program_paths. GET paths
# flow through generic read_allowed.
preventive_schedule_mutation_paths := {
  "/api/v1/preventive-schedules/*/checklist",
  "/api/v1/preventive-schedules/*/evidence",
  "/api/v1/preventive-schedules/*/evidence/*",
  "/api/v1/preventive-schedules/*/approve",
  "/api/v1/preventive-schedules/*/skip",
}

# PM frequencies (story 19-1, blueprint F1): global master-data mutations for the
# four-role set (MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER,
# STAFF_MAINTENANCE) — no scope dimension, role gate only (SUPER_ADMIN via the
# generic bypass). Reads (GET) flow through generic read_allowed.
pm_frequency_paths := {
  "/api/v1/pm-frequencies",
  "/api/v1/pm-frequencies/*",
}

# PM checksheets (story 19-1, blueprint F2/F3): create/revise for the four-role set;
# approve for the leader subset. A single `*` matches exactly one path segment, so
# each depth is enumerated explicitly: collection (list/create), /{id} (get — and
# /active, which the same `*` matches), /{id}/revise, /{id}/approve. The service
# gate is authoritative for scope (machine plant/group) and the leader-only approve
# role (STAFF_MAINTENANCE passes rego but is denied approve in service).
pm_checksheet_paths := {
  "/api/v1/pm-checksheets",
  "/api/v1/pm-checksheets/*",
  "/api/v1/pm-checksheets/*/revise",
  "/api/v1/pm-checksheets/*/approve",
}

# PM checklist categories & items (story 19-2, blueprint F4): define/edit of check
# content against an unapproved checksheet revision for the same four-role set as
# pm_checksheet_paths (SUPER_ADMIN via the generic bypass). A single `*` matches
# exactly one path segment: collection (list/create) and /{id} (get/update/delete).
# The service gate is authoritative for the checksheet's machine scope and the
# approved-revision freeze (rego cannot see the checksheet state). Reads (GET) flow
# through generic read_allowed.
pm_checklist_paths := {
  "/api/v1/pm-checklist-categories",
  "/api/v1/pm-checklist-categories/*",
  "/api/v1/pm-checklist-items",
  "/api/v1/pm-checklist-items/*",
}

# PM schedules & schedule dates (story 19-3, blueprint F5): create/submit for the
# four-role set; approve-spv/approve-prod/activate for the leader subset
# (SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE — STAFF_MAINTENANCE
# passes rego on the mutation paths but is denied the leader steps in service).
# The transition endpoint gates on schedule ACTIVE in service. A single `*` matches
# exactly one path segment, so each depth is enumerated explicitly: collection
# (list/create), /{id} (get), /{id}/submit, /{id}/approve-spv, /{id}/approve-prod,
# /{id}/activate, /{id}/dates/{dateId}/transition. Reads (GET) flow through
# generic read_allowed. Plant/group scope is service-side (rego cannot see the
# machine's plant).
pm_schedule_paths := {
  "/api/v1/pm-schedules",
  "/api/v1/pm-schedules/*",
  "/api/v1/pm-schedules/*/submit",
  "/api/v1/pm-schedules/*/approve-spv",
  "/api/v1/pm-schedules/*/approve-prod",
  "/api/v1/pm-schedules/*/activate",
  "/api/v1/pm-schedules/*/dates/*/transition",
}

# PM work orders (story 19-4, blueprint F6): generate/assign/sweep-overdue are
# leader-gated in service (SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE);
# start/complete are assignee-scoped in service (the WO's assigned technician —
# TECHNICIAN is the realistic executor). The coarse rego set is the union of both
# gates: the three leader roles + TECHNICIAN (SUPER_ADMIN via the generic bypass).
# STAFF_MAINTENANCE and the other roles stay default-deny — parity with
# PmWorkOrderService.requireLeaderMutationAccess/requireAssignee (rego cannot see
# the body, the assignment or the schedule state). A single `*` matches exactly one
# path segment, so each depth is enumerated explicitly: collection (list), /{id}
# (get), /generate, /sweep-overdue, /{id}/assign, /{id}/start, /{id}/complete.
# Reads (GET) flow through generic read_allowed. Plant/group scope is service-side.
pm_work_order_paths := {
  "/api/v1/pm-work-orders",
  "/api/v1/pm-work-orders/*",
  "/api/v1/pm-work-orders/generate",
  "/api/v1/pm-work-orders/sweep-overdue",
  "/api/v1/pm-work-orders/*/assign",
  "/api/v1/pm-work-orders/*/start",
  "/api/v1/pm-work-orders/*/complete",
}

# PM executions & execution items (story 19-5, blueprint F7/F8): start/fill/complete
# are assignee-scoped in service (the execution's technician — TECHNICIAN is the
# realistic executor); verify is leader-gated (SECTION_LEADER, MAINTENANCE_LEADER,
# MANAGER_MAINTENANCE). The coarse rego set is the union of both gates: the three
# leader roles + TECHNICIAN (SUPER_ADMIN via the generic bypass). STAFF_MAINTENANCE
# and the other roles stay default-deny — parity with PmExecutionService
# requireAssignee/requireExecutionTechnician/requireLeaderMutationAccess (rego cannot
# see the body, the assignment or the execution state). A single `*` matches exactly
# one path segment, so each depth is enumerated explicitly: collection (list), /{id}
# (get), /start, /{id}/items/{itemId}/fill, /{id}/complete, /{id}/verify, and (story
# 19-6) /{id}/report — the print-report read gate is service-side (19-5 posture), the
# rego entry keeps the enforced-path surface complete. Reads (GET) flow through
# generic read_allowed. Machine scope is service-side.
pm_execution_paths := {
  "/api/v1/pm-executions",
  "/api/v1/pm-executions/*",
  "/api/v1/pm-executions/start",
  "/api/v1/pm-executions/*/items/*/fill",
  "/api/v1/pm-executions/*/complete",
  "/api/v1/pm-executions/*/verify",
  "/api/v1/pm-executions/*/report",
}

# Non-conformances & 8D reports (story 21-1, blueprint H1/H2): NC create/update and
# 8D create/section-update for the six-role workorder-create parity set (SUPER_ADMIN
# via the generic bypass) — mirrors NonConformanceService.requireMutationRole exactly.
# Effectiveness verification is narrower (SUPER_ADMIN/MANAGER_MAINTENANCE) and lives
# in compliance_eight_d_verify_paths below. A single `*` matches exactly one path
# segment, so each depth is enumerated explicitly: collection (list/create), /{id}
# (get/update), /{id}/eight-d (create/get/update). Machine scope and the state
# machine are service-side (rego cannot see the body or the NC state). Reads (GET)
# flow through generic read_allowed.
compliance_nc_paths := {
  "/api/v1/non-conformances",
  "/api/v1/non-conformances/*",
  "/api/v1/non-conformances/*/eight-d",
}

# 8D effectiveness verification (story 21-1): SUPER_ADMIN/MANAGER_MAINTENANCE only —
# parity with EightDReportService.verifyEffectiveness. Other roles stay default-deny.
compliance_eight_d_verify_paths := {
  "/api/v1/non-conformances/*/eight-d/verify-effectiveness",
}

# Calibration instruments & records (story 21-2, blueprint H3): instrument CRUD,
# the append-only records history, and the recalibrate event for the six-role
# workorder-create parity set (SUPER_ADMIN via the generic bypass) — mirrors
# CalibrationService's NonConformanceService.requireMutationRole gate exactly.
# A single `*` matches exactly one path segment, so each depth is enumerated
# explicitly: collection (list/create), /{id} (get/update), /{id}/records (list),
# /{id}/records/{recordId} (get), /{id}/recalibrate. Plant scope and the derived
# status are service-side (rego cannot see the body or the dates). Reads (GET)
# flow through generic read_allowed.
compliance_calibration_paths := {
  "/api/v1/calibration-instruments",
  "/api/v1/calibration-instruments/*",
  "/api/v1/calibration-instruments/*/records",
  "/api/v1/calibration-instruments/*/records/*",
  "/api/v1/calibration-instruments/*/recalibrate",
}

# Equipment change notices (story 21-2, blueprint H4): ECN create/update/submit
# for the six-role set — mirrors EquipmentChangeNoticeService
# (requireMutationRole on create/update/submit). Machine scope and the state
# machine are service-side. Reads (GET) flow through generic read_allowed.
compliance_ecn_paths := {
  "/api/v1/equipment-change-notices",
  "/api/v1/equipment-change-notices/*",
  "/api/v1/equipment-change-notices/*/submit",
}

# ECN approval lifecycle (story 21-2): approve/execute/close are
# SUPER_ADMIN/MANAGER_MAINTENANCE only — parity with
# EquipmentChangeNoticeService.requireApprovalRole. Other roles stay default-deny.
compliance_ecn_approval_paths := {
  "/api/v1/equipment-change-notices/*/approve",
  "/api/v1/equipment-change-notices/*/execute",
  "/api/v1/equipment-change-notices/*/close",
}

# Machine setup baselines (story 21-3, blueprint H5): baseline create and the
# /{id}/activate pointer flip for the six-role workorder-create parity set
# (SUPER_ADMIN via the generic bypass) — mirrors MachineSetupBaselineService's
# requireMutationRole gate exactly. A single `*` matches exactly one path
# segment, so each depth is enumerated explicitly: collection (list/create),
# /{id} (get), /{id}/activate. Machine scope and the server-assigned version are
# service-side (rego cannot see the body). Reads (GET) flow through generic
# read_allowed.
compliance_baseline_paths := {
  "/api/v1/machine-setup-baselines",
  "/api/v1/machine-setup-baselines/*",
  "/api/v1/machine-setup-baselines/*/activate",
}

# Lessons learned (story 21-3, blueprint H6): lesson create/update/delete for
# the six-role parity set — mirrors LessonLearnedService's requireMutationRole.
# A single `*` matches exactly one path segment: collection (search/create),
# /{id} (get/update/delete). Event-link validation and scope are service-side.
# Reads (GET) flow through generic read_allowed.
compliance_lesson_paths := {
  "/api/v1/lessons-learned",
  "/api/v1/lessons-learned/*",
}

# Login audits & phone challenges (story 22-2, blueprint I2/I3): audit reads are
# SUPER_ADMIN/AUDITOR-only — read_allowed below excludes these paths from the generic
# any-authenticated read so every other role is default-deny, and an explicit AUDITOR
# rule grants reads (SUPER_ADMIN via the top-level bypass). Mirrors
# AuthLoginAuditService.requireReadRole exactly. Phone-challenge mutations are
# SUPER_ADMIN-only: no non-admin role matches auth_phone_challenge_paths (the top-level
# bypass handles SUPER_ADMIN), mirroring PhoneVerificationService.requireSuperAdmin.
auth_audit_read_paths := {
  "/api/v1/auth/login-audits",
  "/api/v1/auth/login-audits/*",
}

auth_phone_challenge_paths := {
  "/api/v1/auth/phone-challenges",
  "/api/v1/auth/phone-challenges/*",
  "/api/v1/auth/phone-challenges/*/verify",
  "/api/v1/auth/phone-challenges/*/resend",
}

# User signatures (story 22-3, blueprint I1): upload is owner-or-SUPER_ADMIN in service;
# rego admits any authenticated user on the path (coarse gate — ownership is invisible to
# rego), mirroring the alert_mutation_paths posture. Reads (GET /me, GET /{userId}) flow
# through generic read_allowed; reading ANOTHER user's signature is narrowed to
# SUPER_ADMIN in UserSignatureService (rego cannot see the path variable's owner).
user_signature_paths := {
  "/api/v1/auth/user-signatures",
  "/api/v1/auth/user-signatures/*",
}

# Workorder approve (story 22-3): the approve path rides the /api/v1/workorders/**
# enforcement rollout but had no rego allow rule — non-admin leaders were default-deny.
# Grant exactly the in-service requireLeaderAccess set: SECTION_LEADER,
# MAINTENANCE_LEADER, MANAGER_MAINTENANCE (SUPER_ADMIN via the top-level bypass).
# Scope narrowing (machine group/plant) stays service-side (rego cannot see the WO).
workorder_approve_paths := {
  "/api/v1/workorders/*/approve",
}

# Org-maintenance departments (spec-org-maintenance-model): mutations are the
# Phase 1 gate (SUPER_ADMIN|MANAGER_MAINTENANCE). Reads flow through generic
# read_allowed. Members replace + section leader assignment + user-master updates
# are in the same allow set.
department_paths := {
  "/api/v1/departments",
  "/api/v1/departments/*",
  "/api/v1/departments/*/members",
  "/api/v1/machine-areas",
  "/api/v1/machine-areas/*",
  "/api/v1/job-titles",
  "/api/v1/job-titles/*",
  "/api/v1/plant-working-calendars",
  "/api/v1/plant-working-calendars/*",
  "/api/v1/plant-working-calendars/*/dates",
  "/api/v1/plant-working-calendars/*/dates/*",
}

section_leader_paths := {
  "/api/v1/sections/*/leader",
}

user_management_paths := {
  "/api/v1/auth/users/*",
  "/api/v1/user-bindings",
  "/api/v1/user-bindings/*",
  "/api/v1/user-bindings/*/job",
  "/api/v1/user-bindings/*/roles",
  "/api/v1/user-bindings/*/roles/*",
}

# Telemetry + notification-worker + sync-observability + webhook-config + WhatsApp
# message-log endpoints are SUPER_ADMIN-only in service (health dashboard, story
# 13-3/FR-153; webhook evidence, story 22-1; message-log evidence, story 22-4).
admin_only_paths := {
  "/api/v1/telemetry/**",
  "/api/v1/notification/worker/**",
  "/api/v1/sync/**",
  "/api/v1/webhooks",
  "/api/v1/webhooks/**",
  "/api/v1/webhook-deliveries",
  "/api/v1/whatsapp-message-logs",
}

mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(core_mutation_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(category_mutation_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(category_mutation_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(category_mutation_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_create_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_create_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_create_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(workorder_create_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "PRODUCTION_LEADER"
  is_mutation
  path_matches(workorder_create_paths)
}

# Sparepart request creation (story 12-1): five-role allow set, coarse gate only.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(sparepart_request_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(sparepart_request_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(sparepart_request_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(sparepart_request_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "TECHNICIAN"
  is_mutation
  path_matches(sparepart_request_paths)
}

# Sparepart request transition + MRE (story 12-2): seven-role allow set
# (SUPER_ADMIN via the generic rule).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(sparepart_request_transition_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(sparepart_request_transition_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(sparepart_request_transition_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(sparepart_request_transition_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "TECHNICIAN"
  is_mutation
  path_matches(sparepart_request_transition_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "INVENTORY_MAINTENANCE"
  is_mutation
  path_matches(sparepart_request_transition_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STOREKEEPER"
  is_mutation
  path_matches(sparepart_request_transition_paths)
}

# Sparepart request approval (story 12-3): three-leader allow set
# (SUPER_ADMIN via the generic rule).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(sparepart_request_approval_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(sparepart_request_approval_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(sparepart_request_approval_paths)
}

# Sparepart request completion (story 12-4): INVENTORY_MAINTENANCE/STOREKEEPER
# (SUPER_ADMIN via the generic rule).
mutation_allowed if {
  input.subject.roles[_] == "INVENTORY_MAINTENANCE"
  is_mutation
  path_matches(sparepart_request_completion_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STOREKEEPER"
  is_mutation
  path_matches(sparepart_request_completion_paths)
}

# Sparepart stock mutations (story 12-4, FR-146): INVENTORY_MAINTENANCE/STOREKEEPER.
mutation_allowed if {
  input.subject.roles[_] == "INVENTORY_MAINTENANCE"
  is_mutation
  path_matches(sparepart_stock_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STOREKEEPER"
  is_mutation
  path_matches(sparepart_stock_paths)
}

# Inventory location mutations (story 18-2): MANAGER_MAINTENANCE/INVENTORY_MAINTENANCE
# (SUPER_ADMIN via the generic bypass). STOREKEEPER/TECHNICIAN stay default-deny on
# these paths — the service gate is authoritative for plant scope.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(inventory_location_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "INVENTORY_MAINTENANCE"
  is_mutation
  path_matches(inventory_location_paths)
}

# Inventory location stock mutations (story 18-3): INVENTORY_MAINTENANCE/STOREKEEPER
# (SUPER_ADMIN via the generic bypass). MANAGER_MAINTENANCE/TECHNICIAN and the other
# roles stay default-deny — parity with InventoryStockService.requireMutationAccess.
mutation_allowed if {
  input.subject.roles[_] == "INVENTORY_MAINTENANCE"
  is_mutation
  path_matches(inventory_location_stock_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STOREKEEPER"
  is_mutation
  path_matches(inventory_location_stock_paths)
}

# Inventory transfer mutations (story 18-4): INVENTORY_MAINTENANCE/STOREKEEPER/
# MANAGER_MAINTENANCE (SUPER_ADMIN via the generic bypass). TECHNICIAN/
# STAFF_MAINTENANCE and the other roles stay default-deny — parity with
# InventoryTransferService.requireCreateRole/requireReviewRole (the service narrows
# STOREKEEPER out of reviews; rego is the coarse gate).
mutation_allowed if {
  input.subject.roles[_] == "INVENTORY_MAINTENANCE"
  is_mutation
  path_matches(inventory_transfer_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STOREKEEPER"
  is_mutation
  path_matches(inventory_transfer_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(inventory_transfer_paths)
}

# Inventory reservation mutations (story 18-5): INVENTORY_MAINTENANCE/STOREKEEPER/
# MANAGER_MAINTENANCE (SUPER_ADMIN via the generic bypass). TECHNICIAN/STAFF_MAINTENANCE
# and the other roles stay default-deny — parity with
# InventoryReservationService.requireMutationRole. The service narrows scope; rego is
# the coarse gate.
mutation_allowed if {
  input.subject.roles[_] == "INVENTORY_MAINTENANCE"
  is_mutation
  path_matches(inventory_reservation_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STOREKEEPER"
  is_mutation
  path_matches(inventory_reservation_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(inventory_reservation_paths)
}

# Assign: leadership roles only (STAFF_MAINTENANCE and PRODUCTION_LEADER excluded).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_assign_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_assign_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_assign_paths)
}

# Workorder assignments (17-1): four-role allow set mirroring workorder_assign_paths
# (STAFF_MAINTENANCE and TECHNICIAN cannot assign — FR-113 parity).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_assignment_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_assignment_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_assignment_paths)
}

# Transition: executor roles + leadership (10.3). STAFF_MAINTENANCE and TECHNICIAN are
# allowed because the assigned executor may start/resume/complete; scope is service-side.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_transition_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_transition_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_transition_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(workorder_transition_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "TECHNICIAN"
  is_mutation
  path_matches(workorder_transition_paths)
}

# Repair sessions: same five-role allow set as transitions (10.3 parity).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_session_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_session_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_session_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(workorder_session_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "TECHNICIAN"
  is_mutation
  path_matches(workorder_session_paths)
}

# Evidence & technical drawings: same five-role allow set as sessions (10.4 parity).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_evidence_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_evidence_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_evidence_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(workorder_evidence_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "TECHNICIAN"
  is_mutation
  path_matches(workorder_evidence_paths)
}

# Reports & CP/CPK: same five-role allow set as evidence (10.5 parity).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_report_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_report_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_report_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(workorder_report_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "TECHNICIAN"
  is_mutation
  path_matches(workorder_report_paths)
}

# Todos & kanban: same five-role allow set as reports (10.6 parity).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_todo_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_todo_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_todo_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(workorder_todo_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "TECHNICIAN"
  is_mutation
  path_matches(workorder_todo_paths)
}

# Ratings & rating dimensions (10.8): five-role allow set for workorder rating paths;
# dimension mutations are SUPER_ADMIN-only (the super_admin top-level bypass handles it,
# so no non-admin role matches rating_dimension_paths — default-deny for everyone else).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_rating_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_rating_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_rating_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(workorder_rating_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "PRODUCTION_LEADER"
  is_mutation
  path_matches(workorder_rating_paths)
}

# Work-log ratings (story 17-4): same five-role allow set as workorder_rating_paths —
# the in-scope section leader rates a completed work log; scope is service-side. Reads
# (GET) flow through generic read_allowed.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_worklog_rating_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_worklog_rating_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_worklog_rating_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(workorder_worklog_rating_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "PRODUCTION_LEADER"
  is_mutation
  path_matches(workorder_worklog_rating_paths)
}

# Work-log rating criteria (story 17-4): no non-admin role matches — mutations are
# SUPER_ADMIN-only (the super_admin top-level bypass handles it); default-deny for
# everyone else. Mirrors rating_dimension_paths.

# Workorder quality ratings & criteria (story 17-5): same five-role allow set as
# workorder_rating_paths — the service gate is authoritative for who-can-rate
# (PRODUCTION_LEADER of the affected line; SUPER_ADMIN exempt). Reads (GET) flow
# through generic read_allowed.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_quality_rating_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_quality_rating_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_quality_rating_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(workorder_quality_rating_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "PRODUCTION_LEADER"
  is_mutation
  path_matches(workorder_quality_rating_paths)
}

# Work logs (story 17-2): same five-role allow set as sessions/transitions — the
# assigned executor or an in-scope leader may create/update work logs; scope is
# service-side. Reads (GET) flow through generic read_allowed.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_worklog_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_worklog_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_worklog_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(workorder_worklog_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "TECHNICIAN"
  is_mutation
  path_matches(workorder_worklog_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(preventive_program_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(preventive_program_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(preventive_program_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(preventive_program_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(preventive_schedule_mutation_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(preventive_schedule_mutation_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(preventive_schedule_mutation_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(preventive_schedule_mutation_paths)
}

# PM frequencies (story 19-1): the same four-role allow set as preventive_program_paths.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(pm_frequency_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(pm_frequency_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(pm_frequency_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(pm_frequency_paths)
}

# PM checksheets (story 19-1): create/revise for the four-role set; approve for
# the leader subset (SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE).
# The service gate is authoritative for scope and the leader-only approve role
# (STAFF_MAINTENANCE is denied approve in service, not in rego).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(pm_checksheet_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(pm_checksheet_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(pm_checksheet_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(pm_checksheet_paths)
}

# PM checklist categories & items (story 19-2): the same four-role allow set as
# pm_checksheet_paths — define/edit is staff-level; scope and the approved-revision
# freeze are service-side.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(pm_checklist_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(pm_checklist_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(pm_checklist_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(pm_checklist_paths)
}

# PM schedules (story 19-3): create/submit for the four-role set; the approve-spv/
# approve-prod/activate leader subset is narrowed in service (STAFF_MAINTENANCE is
# denied the leader steps there, not in rego — the coarse fence stays four-role).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(pm_schedule_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(pm_schedule_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(pm_schedule_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(pm_schedule_paths)
}

# PM work orders (story 19-4): four-role coarse allow set — the three leader roles
# (generate/assign/sweep, narrowed by scope in service) plus TECHNICIAN (the
# assignee-scoped start/complete executor). STAFF_MAINTENANCE and the other roles
# stay default-deny; the service gate is authoritative for scope and assignee.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(pm_work_order_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(pm_work_order_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(pm_work_order_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "TECHNICIAN"
  is_mutation
  path_matches(pm_work_order_paths)
}

# PM executions (story 19-5): four-role coarse allow set — the three leader roles
# (verify, narrowed by scope in service) plus TECHNICIAN (the assignee-scoped
# start/fill/complete executor). STAFF_MAINTENANCE and the other roles stay
# default-deny; the service gate is authoritative for scope and technician.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(pm_execution_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(pm_execution_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(pm_execution_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "TECHNICIAN"
  is_mutation
  path_matches(pm_execution_paths)
}

# Non-conformances & 8D (story 21-1): six-role workorder-create parity set —
# MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER, STAFF_MAINTENANCE,
# PRODUCTION_LEADER (SUPER_ADMIN via the generic bypass). TECHNICIAN/AUDITOR and the
# inventory roles stay default-deny — parity with NonConformanceService
# .requireMutationRole (rego is the coarse gate; scope/transitions are service-side).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(compliance_nc_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(compliance_nc_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(compliance_nc_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(compliance_nc_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "PRODUCTION_LEADER"
  is_mutation
  path_matches(compliance_nc_paths)
}

# 8D effectiveness verification (story 21-1): MANAGER_MAINTENANCE only (SUPER_ADMIN
# via the generic bypass) — parity with EightDReportService.verifyEffectiveness.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(compliance_eight_d_verify_paths)
}

# Calibration instruments & records (story 21-2): six-role workorder-create parity
# set — MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER, STAFF_MAINTENANCE,
# PRODUCTION_LEADER (SUPER_ADMIN via the generic bypass). TECHNICIAN/AUDITOR and the
# inventory roles stay default-deny — parity with CalibrationService (rego is the
# coarse gate; plant scope and derived status are service-side).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(compliance_calibration_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(compliance_calibration_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(compliance_calibration_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(compliance_calibration_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "PRODUCTION_LEADER"
  is_mutation
  path_matches(compliance_calibration_paths)
}

# ECN create/update/submit (story 21-2): the same six-role parity set — mirrors
# EquipmentChangeNoticeService requireMutationRole on those verbs.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(compliance_ecn_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(compliance_ecn_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(compliance_ecn_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(compliance_ecn_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "PRODUCTION_LEADER"
  is_mutation
  path_matches(compliance_ecn_paths)
}

# ECN approve/execute/close (story 21-2): MANAGER_MAINTENANCE only (SUPER_ADMIN
# via the generic bypass) — parity with
# EquipmentChangeNoticeService.requireApprovalRole.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(compliance_ecn_approval_paths)
}

# Machine setup baselines (story 21-3): six-role workorder-create parity set —
# MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER, STAFF_MAINTENANCE,
# PRODUCTION_LEADER (SUPER_ADMIN via the generic bypass). TECHNICIAN/AUDITOR and
# the inventory roles stay default-deny — parity with
# MachineSetupBaselineService (rego is the coarse gate; machine scope and the
# server-assigned version are service-side).
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(compliance_baseline_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(compliance_baseline_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(compliance_baseline_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(compliance_baseline_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "PRODUCTION_LEADER"
  is_mutation
  path_matches(compliance_baseline_paths)
}

# Lessons learned (story 21-3): the same six-role parity set — mirrors
# LessonLearnedService.requireMutationRole on create/update/delete.
mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(compliance_lesson_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(compliance_lesson_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(compliance_lesson_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "STAFF_MAINTENANCE"
  is_mutation
  path_matches(compliance_lesson_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "PRODUCTION_LEADER"
  is_mutation
  path_matches(compliance_lesson_paths)
}

# Phone challenges (story 22-2): SUPER_ADMIN-only mutations — parity with
# PhoneVerificationService.requireSuperAdmin. The top-level super_admin bypass also
# covers SUPER_ADMIN, but this explicit rule makes the gate load-bearing (the set is
# referenced by a rule, not just default-deny accident): no non-SUPER_ADMIN role
# matches auth_phone_challenge_paths, so every other role stays default-deny on the
# issue/verify/resend surface.
mutation_allowed if {
  input.subject.roles[_] == "SUPER_ADMIN"
  is_mutation
  path_matches(auth_phone_challenge_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(department_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(section_leader_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(user_management_paths)
}

mutation_allowed if {
  input.subject.userId != null
  is_mutation
  path_matches(alert_mutation_paths)
}

# User signatures (story 22-3): any authenticated user may POST their own signature —
# the owner-or-SUPER_ADMIN gate is service-side (rego cannot see which user the path
# targets), same posture as alert_mutation_paths.
mutation_allowed if {
  input.subject.userId != null
  is_mutation
  path_matches(user_signature_paths)
}

# Workorder approve (story 22-3): three-role allow set — parity with
# WorkorderSignatureService.requireLeaderAccess (SECTION_LEADER/MAINTENANCE_LEADER/
# MANAGER_MAINTENANCE in scope; SUPER_ADMIN via the top-level bypass). Scope is
# service-side (rego cannot see the workorder's machine).
mutation_allowed if {
  input.subject.roles[_] == "SECTION_LEADER"
  is_mutation
  path_matches(workorder_approve_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MAINTENANCE_LEADER"
  is_mutation
  path_matches(workorder_approve_paths)
}

mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(workorder_approve_paths)
}

read_allowed if {
  input.subject.userId != null
  is_read
  not path_matches(admin_only_paths)
  not path_matches(auth_audit_read_paths)
}

# Login-audit reads (story 22-2): AUDITOR only — SUPER_ADMIN takes the top-level bypass,
# every other role stays default-deny (the exclusion above removes these paths from the
# generic any-authenticated read). Parity with AuthLoginAuditService.requireReadRole.
read_allowed if {
  input.subject.userId != null
  is_read
  input.subject.roles[_] == "AUDITOR"
  path_matches(auth_audit_read_paths)
}

is_mutation if startswith(input.action, "POST ")

is_mutation if startswith(input.action, "PUT ")

is_mutation if startswith(input.action, "PATCH ")

is_mutation if startswith(input.action, "DELETE ")

is_read if startswith(input.action, "GET ")

# glob.match on the path portion of the action against a pattern set. Template braces
# (Spring handler patterns like /api/v1/machines/{machineId}) normalize to `*` so both
# raw request paths and resolved handler patterns match the same rule set.
path_matches(patterns) if {
  normalized := normalized_path(input.action)
  some pattern in patterns
  glob.match(pattern, ["/"], normalized)
}

normalized_path(action) := regex.replace(path_only(action), "\\{[^}]*\\}", "*")

path_only(action) := substring(action, indexof(action, " ") + 1, -1)

# Allowed-actions mirror of the matrix (FR-161): SUPER_ADMIN full, MANAGER_MAINTENANCE
# read + write, SECTION_LEADER and MAINTENANCE_LEADER read + write for category mutations,
# every other role read-only.
actions contains "*" if super_admin

actions contains "*.read" if not super_admin

actions contains "*.write" if {
  not super_admin
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
}

actions contains "*.write" if {
  not super_admin
  input.subject.roles[_] == "SECTION_LEADER"
}

actions contains "*.write" if {
  not super_admin
  input.subject.roles[_] == "MAINTENANCE_LEADER"
}
