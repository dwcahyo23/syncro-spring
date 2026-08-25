package syncro.authz_test

import data.syncro.authz

# ---------------------------------------------------------------------------
# Parity matrix: the rego must mirror in-service gate behavior exactly.
# Every (role, method, path) pairing that a real authenticated user could
# attempt is enumerated here.
# ---------------------------------------------------------------------------

# -- SUPER_ADMIN: allowed everywhere ---------------------------------------

test_super_admin_core_mutation_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/machines"}
}

test_super_admin_admin_only_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "GET /api/v1/telemetry/freshness"}
}

test_super_admin_alert_mutation_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/alerts/abc/resolve"}
}

test_super_admin_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "GET /api/v1/machines"}
}

# -- MANAGER_MAINTENANCE: read + core mutation + alert mutation; admin-only deny --

test_manager_core_mutation_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/machines"}
}

test_manager_core_mutation_put_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "PUT /api/v1/plants/abc"}
}

test_manager_core_mutation_delete_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "DELETE /api/v1/spareparts/abc"}
}

test_manager_shift_config_mutation_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/machine-groups/abc/shift-config"}
}

test_manager_waha_mutation_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/notification/templates"}
}

test_manager_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "GET /api/v1/machines"}
}

test_manager_alert_mutation_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/alerts/abc/acknowledge"}
}

test_manager_admin_only_denied if {
  not authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "GET /api/v1/telemetry/freshness"}
}

test_manager_admin_only_worker_denied if {
  not authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "GET /api/v1/notification/worker/status"}
}

# -- STAFF_MAINTENANCE: read allow, core mutation deny, alert mutation allow, admin deny --

test_staff_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "GET /api/v1/machines"}
}

test_staff_core_mutation_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/machines"}
}

test_staff_alert_mutation_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/alerts/abc/resolve"}
}

test_staff_admin_only_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "GET /api/v1/telemetry/freshness"}
}

# -- TECHNICIAN: read allow, core mutation deny, alert mutation allow, admin deny --

test_technician_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/machines"}
}

test_technician_core_mutation_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/machines"}
}

test_technician_alert_mutation_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/alerts/abc/acknowledge"}
}

test_technician_admin_only_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/telemetry/freshness"}
}

# -- SECTION_LEADER: read allow, core mutation deny, alert mutation allow, admin deny --

test_leader_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "GET /api/v1/machines"}
}

test_leader_core_mutation_denied if {
  not authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/machines"}
}

test_leader_alert_mutation_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/alerts/abc/acknowledge"}
}

test_leader_admin_only_denied if {
  not authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "GET /api/v1/telemetry/freshness"}
}

# -- AUDITOR: read allow, core mutation deny, alert mutation allow, admin deny --

test_auditor_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/machines"}
}

test_auditor_core_mutation_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/machines"}
}

test_auditor_alert_mutation_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/alerts/abc/acknowledge"}
}

test_auditor_admin_only_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/telemetry/freshness"}
}

# -- Category (story 10-1): SECTION_LEADER+/MAINTENANCE_LEADER+/MANAGER_MAINTENANCE
#    mutate; TECHNICIAN denied; reads allowed for any authenticated ----------------

test_section_leader_category_post_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/work-order-categories"}
}

test_technician_category_post_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/work-order-categories"}
}

test_technician_category_put_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "PUT /api/v1/work-order-categories/01"}
}

test_auditor_category_get_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/work-order-categories"}
}

test_maintenance_leader_category_put_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "PUT /api/v1/work-order-categories/01"}
}

test_manager_category_post_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/work-order-categories"}
}

test_super_admin_category_mutation_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/work-order-categories"}
}

test_technician_category_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/work-order-categories"}
}

# -- Anonymous (no userId): default deny everywhere ------------------------

test_anonymous_admin_only_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "GET /api/v1/telemetry/freshness"}
}

test_anonymous_read_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "GET /api/v1/machines"}
}

test_anonymous_empty_input_denied if {
  not authz.allow with input as {}
}

test_anonymous_missing_subject_denied if {
  not authz.allow with input as {"subject": {}}
}

# -- Allowed-actions rule parity -------------------------------------------

test_actions_super_admin_has_full if {
  result := authz.actions with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}}
  result == {"*"}
}

test_actions_manager_has_read_write if {
  result := authz.actions with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}}
  result == {"*.read", "*.write"}
}

test_actions_technician_has_read_only if {
  result := authz.actions with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}}
  result == {"*.read"}
}

test_actions_staff_has_read_only if {
  result := authz.actions with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}}
  result == {"*.read"}
}

test_actions_auditor_has_read_only if {
  result := authz.actions with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}}
  result == {"*.read"}
}
