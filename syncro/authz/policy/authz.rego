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

workorder_assign_paths := {
  "/api/v1/workorders/*/assign",
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

# Rating dimension paths (story 10-8): mutations are SUPER_ADMIN-only; reads generic.
# Listed separately from core_mutation_paths because they are not "core" — they are
# workorder config but not in the Epic 9 core set.
rating_dimension_paths := {
  "/api/v1/rating-dimensions",
  "/api/v1/rating-dimensions/*",
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
}

section_leader_paths := {
  "/api/v1/sections/*/leader",
}

user_management_paths := {
  "/api/v1/auth/users/*",
}

# Telemetry + notification-worker + sync-observability endpoints are SUPER_ADMIN-only
# in service (health dashboard, story 13-3/FR-153).
admin_only_paths := {
  "/api/v1/telemetry/**",
  "/api/v1/notification/worker/**",
  "/api/v1/sync/**",
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

read_allowed if {
  input.subject.userId != null
  is_read
  not path_matches(admin_only_paths)
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
