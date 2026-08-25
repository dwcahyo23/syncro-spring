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

# Telemetry + notification-worker endpoints are SUPER_ADMIN-only in service.
admin_only_paths := {
  "/api/v1/telemetry/**",
  "/api/v1/notification/worker/**",
}

mutation_allowed if {
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
  is_mutation
  path_matches(core_mutation_paths)
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
# read + write, every other role read-only.
actions contains "*" if super_admin

actions contains "*.read" if not super_admin

actions contains "*.write" if {
  not super_admin
  input.subject.roles[_] == "MANAGER_MAINTENANCE"
}
