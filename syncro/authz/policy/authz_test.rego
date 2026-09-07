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

# Story 18-1: BOM review transitions are two-segment subpaths — `*` matches one
# segment, so approve/reject need explicit core_mutation_paths entries.
test_manager_sparepart_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/spareparts/abc/approve"}
}

test_manager_sparepart_reject_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/spareparts/abc/reject"}
}

test_inventory_sparepart_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/spareparts/abc/approve"}
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

# -- Workorder create/assign (story 10-2): the five create/assign roles may mutate;
#    TECHNICIAN/AUDITOR denied; reads stay any-authenticated -------------------------

test_staff_workorder_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders"}
}

test_section_leader_workorder_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/workorders"}
}

test_production_leader_workorder_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/workorders"}
}

test_maintenance_leader_workorder_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/workorders"}
}

test_manager_workorder_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders"}
}

test_section_leader_workorder_assign_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/workorders/WO-240900001/assign"}
}

test_super_admin_workorder_assign_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/workorders/WO-240900001/assign"}
}

test_technician_workorder_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders"}
}

test_technician_workorder_assign_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-240900001/assign"}
}

test_auditor_workorder_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/workorders"}
}

test_staff_workorder_assign_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-240900001/assign"}
}

# -- Work assignments (story 17-1): the same four-role allow set as assign --------

test_section_leader_workorder_assignment_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/workorders/WO-240900001/assignments"}
}

test_manager_workorder_assignment_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders/WO-240900001/assignments"}
}

test_maintenance_leader_workorder_assignment_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/workorders/WO-240900001/assignments"}
}

test_super_admin_workorder_assignment_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/workorders/WO-240900001/assignments"}
}

test_section_leader_workorder_assignment_drop_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/workorders/WO-240900001/assignments/7b7c6d5e-1111-2222-3333-444455556666/drop"}
}

test_manager_workorder_assignment_drop_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders/WO-240900001/assignments/7b7c6d5e-1111-2222-3333-444455556666/drop"}
}

test_technician_workorder_assignment_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-240900001/assignments"}
}

test_technician_workorder_assignment_drop_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-240900001/assignments/7b7c6d5e-1111-2222-3333-444455556666/drop"}
}

test_staff_workorder_assignment_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-240900001/assignments"}
}

test_production_leader_workorder_assignment_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/workorders/WO-240900001/assignments"}
}

test_auditor_workorder_assignment_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/workorders/WO-240900001/assignments"}
}

test_anonymous_workorder_assignment_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/workorders/WO-240900001/assignments"}
}

test_auditor_workorder_assignment_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/WO-240900001/assignments"}
}

test_maintenance_leader_workorder_assignment_drop_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/workorders/WO-240900001/assignments/7b7c6d5e-1111-2222-3333-444455556666/drop"}
}

test_super_admin_workorder_assignment_drop_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/workorders/WO-240900001/assignments/7b7c6d5e-1111-2222-3333-444455556666/drop"}
}

test_staff_workorder_assignment_drop_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-240900001/assignments/7b7c6d5e-1111-2222-3333-444455556666/drop"}
}

test_storekeeper_workorder_assignment_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/workorders/WO-240900001/assignments"}
}

test_inventory_workorder_assignment_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/workorders/WO-240900001/assignments"}
}

test_production_leader_workorder_assign_denied if {
  not authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/workorders/WO-240900001/assign"}
}

test_staff_workorder_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "GET /api/v1/workorders"}
}

test_auditor_workorder_list_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders"}
}

test_technician_workorder_list_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/workorders"}
}

# -- Workorder transition (story 10-3): the executor roles (TECHNICIAN, STAFF_MAINTENANCE)
#    plus leadership are allowed; AUDITOR/PRODUCTION_LEADER/INVENTORY_MAINTENANCE/
#    STOREKEEPER denied ---------------------------------------------------------

test_staff_workorder_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-2409-00001/transition"}
}

test_technician_workorder_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-2409-00001/transition"}
}

test_section_leader_workorder_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/workorders/WO-2409-00001/transition"}
}

test_maintenance_leader_workorder_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/workorders/WO-2409-00001/transition"}
}

test_manager_workorder_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders/WO-2409-00001/transition"}
}

test_super_admin_workorder_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/workorders/WO-2409-00001/transition"}
}

test_auditor_workorder_transition_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/workorders/WO-2409-00001/transition"}
}

test_production_leader_workorder_transition_denied if {
  not authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/workorders/WO-2409-00001/transition"}
}

test_inventory_workorder_transition_denied if {
  not authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/workorders/WO-2409-00001/transition"}
}

test_storekeeper_workorder_transition_denied if {
  not authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/workorders/WO-2409-00001/transition"}
}

# -- Repair sessions (story 10-4): same five-role allow set as transitions -------------

test_staff_workorder_session_start_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-2409-00001/sessions"}
}

test_staff_workorder_session_stop_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-2409-00001/sessions/stop"}
}

test_technician_workorder_session_start_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-2409-00001/sessions"}
}

test_technician_workorder_session_stop_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-2409-00001/sessions/stop"}
}

test_section_leader_workorder_session_start_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/workorders/WO-2409-00001/sessions"}
}

test_maintenance_leader_workorder_session_stop_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/workorders/WO-2409-00001/sessions/stop"}
}

test_manager_workorder_session_start_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders/WO-2409-00001/sessions"}
}

test_super_admin_workorder_session_stop_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/workorders/WO-2409-00001/sessions/stop"}
}

test_auditor_workorder_session_start_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/workorders/WO-2409-00001/sessions"}
}

test_auditor_workorder_session_stop_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/workorders/WO-2409-00001/sessions/stop"}
}

test_production_leader_workorder_session_start_denied if {
  not authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/workorders/WO-2409-00001/sessions"}
}

test_staff_workorder_session_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "GET /api/v1/workorders/WO-2409-00001/sessions"}
}

# -- Workorder evidence & technical drawings (story 10-5): same five-role allow set as
#    sessions; reads any-authenticated --------------------------------------------

test_technician_workorder_evidence_upload_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-2409-00001/attachments"}
}

test_staff_workorder_evidence_upload_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-2409-00001/attachments"}
}

test_section_leader_workorder_evidence_put_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "PUT /api/v1/workorders/WO-2409-00001/attachments/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_maintenance_leader_workorder_evidence_delete_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "DELETE /api/v1/workorders/WO-2409-00001/attachments/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_manager_workorder_evidence_upload_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders/WO-2409-00001/attachments"}
}

test_super_admin_workorder_evidence_delete_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "DELETE /api/v1/workorders/WO-2409-00001/attachments/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_workorder_evidence_upload_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/workorders/WO-2409-00001/attachments"}
}

test_production_leader_workorder_evidence_delete_denied if {
  not authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "DELETE /api/v1/workorders/WO-2409-00001/attachments/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_workorder_evidence_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/WO-2409-00001/attachments"}
}

test_auditor_workorder_evidence_single_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/WO-2409-00001/attachments/7b7c6d5e-1111-2222-3333-444455556666"}
}

# -- Workorder report & CP/CPK (story 10-6): same five-role allow set as evidence;
#    reads any-authenticated --------------------------------------------------------

test_technician_workorder_report_put_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "PUT /api/v1/workorders/WO-2409-00001/report"}
}

test_staff_workorder_report_put_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "PUT /api/v1/workorders/WO-2409-00001/report"}
}

test_section_leader_workorder_report_put_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "PUT /api/v1/workorders/WO-2409-00001/report"}
}

test_maintenance_leader_workorder_report_put_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "PUT /api/v1/workorders/WO-2409-00001/report"}
}

test_manager_workorder_report_put_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "PUT /api/v1/workorders/WO-2409-00001/report"}
}

test_technician_workorder_cpk_upload_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "PUT /api/v1/workorders/WO-2409-00001/report/cpk"}
}

test_section_leader_workorder_cpk_delete_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "DELETE /api/v1/workorders/WO-2409-00001/report/cpk"}
}

test_auditor_workorder_report_put_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "PUT /api/v1/workorders/WO-2409-00001/report"}
}

test_auditor_workorder_report_get_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/WO-2409-00001/report"}
}

test_auditor_workorder_cpk_upload_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "PUT /api/v1/workorders/WO-2409-00001/report/cpk"}
}

test_production_leader_workorder_report_put_denied if {
  not authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "PUT /api/v1/workorders/WO-2409-00001/report"}
}

# -- Workorder todos & kanban (story 10-7): same five-role allow set as reports;
#    reads (list todos, kanban) any-authenticated ----------------------------------

test_technician_workorder_todo_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-2409-00001/todos"}
}

test_staff_workorder_todo_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-2409-00001/todos"}
}

test_section_leader_workorder_todo_assign_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "PUT /api/v1/workorders/WO-2409-00001/todos/7b7c6d5e-1111-2222-3333-444455556666/assign"}
}

test_maintenance_leader_workorder_todo_complete_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "PUT /api/v1/workorders/WO-2409-00001/todos/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

test_manager_workorder_todo_reorder_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "PUT /api/v1/workorders/WO-2409-00001/todos/7b7c6d5e-1111-2222-3333-444455556666/reorder"}
}

test_super_admin_workorder_todo_delete_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "DELETE /api/v1/workorders/WO-2409-00001/todos/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_workorder_todo_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/workorders/WO-2409-00001/todos"}
}

test_auditor_workorder_todo_assign_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "PUT /api/v1/workorders/WO-2409-00001/todos/7b7c6d5e-1111-2222-3333-444455556666/assign"}
}

test_production_leader_workorder_todo_complete_denied if {
  not authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "PUT /api/v1/workorders/WO-2409-00001/todos/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

test_auditor_workorder_todo_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/WO-2409-00001/todos"}
}

test_auditor_workorder_todo_single_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/WO-2409-00001/todos/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_staff_workorder_kanban_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "GET /api/v1/workorders/kanban"}
}

test_anonymous_workorder_todo_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/workorders/WO-2409-00001/todos"}
}

# -- Workorder ratings (story 10-8): five-role allow set (MANAGER_MAINTENANCE,
#    SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE, PRODUCTION_LEADER) on the
#    mutation paths; TECHNICIAN/AUDITOR denied; reads any-authenticated; rating-dimension
#    mutations SUPER_ADMIN-only ------------------------------------------------

test_section_leader_workorder_rating_technician_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/workorders/WO-2409-00001/ratings/technician"}
}

test_manager_workorder_rating_technician_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders/WO-2409-00001/ratings/technician"}
}

test_maintenance_leader_workorder_rating_technician_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/workorders/WO-2409-00001/ratings/technician"}
}

test_staff_workorder_rating_workorder_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-2409-00001/ratings/workorder"}
}

test_production_leader_workorder_rating_workorder_allowed if {
  authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/workorders/WO-2409-00001/ratings/workorder"}
}

test_super_admin_workorder_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/workorders/WO-2409-00001/ratings/technician"}
}

test_technician_workorder_rating_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-2409-00001/ratings/technician"}
}

test_auditor_workorder_rating_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/workorders/WO-2409-00001/ratings/workorder"}
}

test_auditor_workorder_rating_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/WO-2409-00001/ratings"}
}

test_auditor_workorder_ratings_page_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/ratings"}
}

test_super_admin_rating_dimension_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/rating-dimensions"}
}

test_manager_rating_dimension_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/rating-dimensions"}
}

test_section_leader_rating_dimension_put_denied if {
  not authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "PUT /api/v1/rating-dimensions/SPEED"}
}

test_technician_rating_dimension_delete_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "DELETE /api/v1/rating-dimensions/SPEED"}
}

test_auditor_rating_dimension_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/rating-dimensions"}
}

test_anonymous_workorder_rating_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/workorders/WO-2409-00001/ratings/technician"}
}

# -- Work logs (story 17-2, blueprint B4, AD-18): same five-role allow set as
#    sessions/transitions; reads any-authenticated -----------------------------

test_manager_workorder_worklog_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs"}
}

test_section_leader_workorder_worklog_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs"}
}

test_maintenance_leader_workorder_worklog_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "PUT /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_staff_workorder_worklog_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs"}
}

test_technician_workorder_worklog_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs"}
}

test_technician_workorder_worklog_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "PUT /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_super_admin_workorder_worklog_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "PUT /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_workorder_worklog_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs"}
}

test_auditor_workorder_worklog_update_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "PUT /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_production_leader_workorder_worklog_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs"}
}

test_inventory_workorder_worklog_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs"}
}

test_storekeeper_workorder_worklog_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs"}
}

test_anonymous_workorder_worklog_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs"}
}

test_auditor_workorder_worklog_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/WO-2409-00001/work-logs"}
}

test_auditor_workorder_worklog_single_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666"}
}

# -- Work log ratings (story 17-4, blueprint C2, FR-121): same five-role allow set as
#    workorder_rating_paths; TECHNICIAN/AUDITOR denied; reads any-authenticated -----

test_manager_workorder_worklog_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

test_section_leader_workorder_worklog_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

test_maintenance_leader_workorder_worklog_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

test_staff_workorder_worklog_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

test_production_leader_workorder_worklog_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

test_super_admin_workorder_worklog_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

test_technician_workorder_worklog_rating_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

test_auditor_workorder_worklog_rating_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

test_inventory_workorder_worklog_rating_denied if {
  not authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

test_storekeeper_workorder_worklog_rating_denied if {
  not authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

test_anonymous_workorder_worklog_rating_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

test_auditor_workorder_worklog_rating_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

test_technician_workorder_worklog_rating_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/workorders/WO-2409-00001/work-logs/7b7c6d5e-1111-2222-3333-444455556666/ratings"}
}

# -- Work log rating criteria (story 17-4, blueprint C1, AD-14): mutations SUPER_ADMIN-only;
#    every non-admin role default-denied; reads any-authenticated -------------------

test_super_admin_worklog_criterion_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/workorders/work-log-rating-criteria"}
}

test_super_admin_worklog_criterion_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "PUT /api/v1/workorders/work-log-rating-criteria/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_super_admin_worklog_criterion_delete_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "DELETE /api/v1/workorders/work-log-rating-criteria/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_manager_worklog_criterion_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders/work-log-rating-criteria"}
}

test_section_leader_worklog_criterion_put_denied if {
  not authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "PUT /api/v1/workorders/work-log-rating-criteria/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_technician_worklog_criterion_delete_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "DELETE /api/v1/workorders/work-log-rating-criteria/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_worklog_criterion_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/work-log-rating-criteria"}
}

test_anonymous_worklog_criterion_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/workorders/work-log-rating-criteria"}
}

# -- Workorder quality ratings & criteria (story 17-5, blueprint C4-C6, FR-124):
#    same five-role allow set as workorder_rating_paths; TECHNICIAN/AUDITOR denied;
#    reads any-authenticated -----------------------------------------------------

test_manager_workorder_quality_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders/WO-2409-00001/quality-rating"}
}

test_section_leader_workorder_quality_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/workorders/WO-2409-00001/quality-rating"}
}

test_maintenance_leader_workorder_quality_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/workorders/WO-2409-00001/quality-rating"}
}

test_staff_workorder_quality_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-2409-00001/quality-rating"}
}

test_production_leader_workorder_quality_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/workorders/WO-2409-00001/quality-rating"}
}

test_super_admin_workorder_quality_rating_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/workorders/WO-2409-00001/quality-rating"}
}

test_technician_workorder_quality_rating_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-2409-00001/quality-rating"}
}

test_auditor_workorder_quality_rating_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/workorders/WO-2409-00001/quality-rating"}
}

test_inventory_workorder_quality_rating_denied if {
  not authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/workorders/WO-2409-00001/quality-rating"}
}

test_storekeeper_workorder_quality_rating_denied if {
  not authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/workorders/WO-2409-00001/quality-rating"}
}

test_anonymous_workorder_quality_rating_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/workorders/WO-2409-00001/quality-rating"}
}

test_auditor_workorder_quality_rating_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/WO-2409-00001/quality-rating"}
}

test_technician_workorder_quality_rating_criteria_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/workorders/quality-rating-criteria"}
}

test_auditor_workorder_quality_rating_criteria_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/workorders/quality-rating-criteria"}
}

test_manager_workorder_quality_rating_criteria_mutation_denied if {
  not authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders/quality-rating-criteria"}
}

test_anonymous_workorder_quality_rating_criteria_read_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "GET /api/v1/workorders/quality-rating-criteria"}
}

# -- Preventive programs & schedules (story 11-1): four-role mutation allow set
#    (MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE);
#    TECHNICIAN/AUDITOR denied; schedule reads any-authenticated ------------------

test_manager_preventive_program_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/preventive-programs"}
}

test_section_leader_preventive_program_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "PUT /api/v1/preventive-programs/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_maintenance_leader_preventive_program_generate_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/preventive-programs/7b7c6d5e-1111-2222-3333-444455556666/generate"}
}

test_staff_preventive_program_delete_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "DELETE /api/v1/preventive-programs/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_super_admin_preventive_program_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/preventive-programs"}
}

test_technician_preventive_program_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/preventive-programs"}
}

test_auditor_preventive_program_update_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "PUT /api/v1/preventive-programs/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_preventive_schedules_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/preventive-schedules"}
}

test_anonymous_preventive_program_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/preventive-programs"}
}

# -- Preventive schedule mutations (story 11-2): same four-role allow set ------------

test_manager_preventive_schedule_checklist_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/preventive-schedules/7b7c6d5e-1111-2222-3333-444455556666/checklist"}
}

test_section_leader_preventive_schedule_evidence_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/preventive-schedules/7b7c6d5e-1111-2222-3333-444455556666/evidence"}
}

test_maintenance_leader_preventive_schedule_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/preventive-schedules/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_staff_preventive_schedule_skip_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/preventive-schedules/7b7c6d5e-1111-2222-3333-444455556666/skip"}
}

test_technician_preventive_schedule_checklist_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/preventive-schedules/7b7c6d5e-1111-2222-3333-444455556666/checklist"}
}

test_auditor_preventive_schedule_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/preventive-schedules/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

# -- Sparepart requests (story 12-1): five-role mutation allow set -----------------

test_manager_sparepart_request_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/sparepart-requests"}
}

test_section_leader_sparepart_request_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/sparepart-requests"}
}

test_maintenance_leader_sparepart_request_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/sparepart-requests"}
}

test_staff_sparepart_request_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/sparepart-requests"}
}

test_technician_sparepart_request_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/sparepart-requests"}
}

test_auditor_sparepart_request_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/sparepart-requests"}
}

test_anonymous_sparepart_request_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/sparepart-requests"}
}

# -- Sparepart request transition + MRE (story 12-2): eight-role allow set -----------

test_manager_sparepart_request_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_section_leader_sparepart_request_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_maintenance_leader_sparepart_request_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_staff_sparepart_request_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_technician_sparepart_request_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_inventory_sparepart_request_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_storekeeper_sparepart_request_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_inventory_sparepart_request_mre_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/mre"}
}

test_storekeeper_sparepart_request_mre_allowed if {
  authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/mre"}
}

test_super_admin_sparepart_request_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_auditor_sparepart_request_transition_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_production_leader_sparepart_request_transition_denied if {
  not authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_auditor_sparepart_request_mre_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/mre"}
}

test_anonymous_sparepart_request_transition_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_auditor_sparepart_request_transition_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666"}
}

# -- Sparepart request approval (story 12-3): three-leader allow set ----------------

test_manager_sparepart_request_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_maintenance_leader_sparepart_request_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_section_leader_sparepart_request_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_super_admin_sparepart_request_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_technician_sparepart_request_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_staff_sparepart_request_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_inventory_sparepart_request_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_storekeeper_sparepart_request_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_auditor_sparepart_request_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_anonymous_sparepart_request_approve_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

# -- Sparepart request completion (story 12-4, FR-144): inventory/stores + SUPER_ADMIN;
#    leaders/technicians/staff/auditor denied ---------------------------------------

test_inventory_sparepart_request_complete_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

test_storekeeper_sparepart_request_complete_allowed if {
  authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

test_super_admin_sparepart_request_complete_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

test_technician_sparepart_request_complete_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

test_staff_sparepart_request_complete_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

test_section_leader_sparepart_request_complete_denied if {
  not authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

test_auditor_sparepart_request_complete_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

test_anonymous_sparepart_request_complete_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/sparepart-requests/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

# -- Sparepart stock (story 12-4, FR-146): inventory/stores mutations; reads any-auth ----

test_inventory_sparepart_stock_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/sparepart-stock"}
}

test_storekeeper_sparepart_stock_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "PUT /api/v1/sparepart-stock/MC-0001"}
}

test_inventory_sparepart_stock_adjust_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/sparepart-stock/MC-0001/adjust"}
}

test_super_admin_sparepart_stock_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/sparepart-stock"}
}

test_technician_sparepart_stock_mutation_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/sparepart-stock"}
}

test_manager_sparepart_stock_mutation_denied if {
  not authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/sparepart-stock"}
}

test_section_leader_sparepart_stock_mutation_denied if {
  not authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/sparepart-stock/MC-0001/adjust"}
}

test_auditor_sparepart_stock_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/sparepart-stock?plantId=7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_sparepart_stock_reorder_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/sparepart-stock/reorder-warnings?plantId=7b7c6d5e-1111-2222-3333-444455556666"}
}

test_anonymous_sparepart_stock_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/sparepart-stock"}
}

# -- Inventory locations (story 18-2, blueprint E1): MANAGER_MAINTENANCE/
#    INVENTORY_MAINTENANCE mutations; STOREKEEPER/TECHNICIAN denied; reads any-auth --

test_manager_inventory_location_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/inventory-locations"}
}

test_manager_inventory_location_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "PUT /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_inventory_inventory_location_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/inventory-locations"}
}

test_inventory_inventory_location_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "PUT /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_super_admin_inventory_location_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/inventory-locations"}
}

test_storekeeper_inventory_location_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/inventory-locations"}
}

test_storekeeper_inventory_location_update_denied if {
  not authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "PUT /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_technician_inventory_location_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/inventory-locations"}
}

test_technician_inventory_location_update_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "PUT /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_inventory_location_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/inventory-locations?plantId=7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_inventory_location_get_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_anonymous_inventory_location_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/inventory-locations"}
}

# -- Inventory location stock balances (story 18-3, blueprint E2): INVENTORY_MAINTENANCE/
#    STOREKEEPER mutations at every enumerated depth; SUPER_ADMIN via generic bypass;
#    MANAGER_MAINTENANCE/TECHNICIAN/STAFF denied; reads any-auth -------------------

test_inventory_location_stock_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666/stock-balances"}
}

test_storekeeper_location_stock_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666/stock-balances"}
}

test_inventory_location_stock_adjust_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666/stock-balances/MC-0001/adjust"}
}

test_storekeeper_location_stock_adjust_allowed if {
  authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666/stock-balances/MC-0001/adjust"}
}

test_super_admin_location_stock_adjust_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666/stock-balances/MC-0001/adjust"}
}

test_technician_location_stock_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666/stock-balances"}
}

test_technician_location_stock_adjust_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666/stock-balances/MC-0001/adjust"}
}

test_manager_location_stock_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666/stock-balances"}
}

test_staff_location_stock_adjust_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666/stock-balances/MC-0001/adjust"}
}

test_auditor_location_stock_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666/stock-balances"}
}

test_anonymous_location_stock_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/inventory-locations/7b7c6d5e-1111-2222-3333-444455556666/stock-balances"}
}

# -- Inventory transfers (story 18-4, blueprint E3): INVENTORY_MAINTENANCE/
#    STOREKEEPER/MANAGER_MAINTENANCE mutations at every enumerated depth;
#    SUPER_ADMIN via generic bypass; TECHNICIAN/STAFF_MAINTENANCE/anonymous denied;
#    reads any-auth -------------------------------------------------------------

test_inventory_transfer_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/inventory-transfers"}
}

test_storekeeper_transfer_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/inventory-transfers"}
}

test_manager_transfer_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/inventory-transfers"}
}

test_inventory_transfer_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/inventory-transfers/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_manager_transfer_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/inventory-transfers/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_inventory_transfer_reject_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/inventory-transfers/7b7c6d5e-1111-2222-3333-444455556666/reject"}
}

test_storekeeper_transfer_reject_allowed if {
  authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/inventory-transfers/7b7c6d5e-1111-2222-3333-444455556666/reject"}
}

test_super_admin_transfer_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/inventory-transfers/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_technician_transfer_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/inventory-transfers"}
}

test_technician_transfer_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/inventory-transfers/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_staff_transfer_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/inventory-transfers"}
}

test_staff_transfer_reject_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/inventory-transfers/7b7c6d5e-1111-2222-3333-444455556666/reject"}
}

test_auditor_transfer_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/inventory-transfers"}
}

test_auditor_transfer_get_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/inventory-transfers/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_anonymous_transfer_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/inventory-transfers"}
}

# -- Inventory reservations (story 18-5, blueprint E4): INVENTORY_MAINTENANCE/
#    STOREKEEPER/MANAGER_MAINTENANCE mutations at every enumerated depth;
#    SUPER_ADMIN via generic bypass; TECHNICIAN/STAFF_MAINTENANCE/anonymous denied;
#    reads any-auth -------------------------------------------------------------

test_inventory_reservation_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/inventory-reservations"}
}

test_storekeeper_reservation_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/inventory-reservations"}
}

test_manager_reservation_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/inventory-reservations"}
}

test_inventory_reservation_consume_allowed if {
  authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/inventory-reservations/7b7c6d5e-1111-2222-3333-444455556666/consume"}
}

test_storekeeper_reservation_cancel_allowed if {
  authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "POST /api/v1/inventory-reservations/7b7c6d5e-1111-2222-3333-444455556666/cancel"}
}

test_manager_reservation_consume_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/inventory-reservations/7b7c6d5e-1111-2222-3333-444455556666/consume"}
}

test_super_admin_reservation_cancel_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/inventory-reservations/7b7c6d5e-1111-2222-3333-444455556666/cancel"}
}

test_technician_reservation_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/inventory-reservations"}
}

test_technician_reservation_consume_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/inventory-reservations/7b7c6d5e-1111-2222-3333-444455556666/consume"}
}

test_staff_reservation_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/inventory-reservations"}
}

test_staff_reservation_cancel_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/inventory-reservations/7b7c6d5e-1111-2222-3333-444455556666/cancel"}
}

test_auditor_reservation_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/inventory-reservations"}
}

test_auditor_reservation_get_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/inventory-reservations/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_anonymous_reservation_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/inventory-reservations"}
}

# -- Org maintenance (spec-org-maintenance-model): departments, section leader,
#    user master — MANAGER_MAINTENANCE + SUPER_ADMIN mutate; others denied -------

test_manager_department_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/departments"}
}

test_manager_department_members_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "PUT /api/v1/departments/7b7c6d5e-1111-2222-3333-444455556666/members"}
}

test_manager_department_delete_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "DELETE /api/v1/departments/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_super_admin_department_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/departments"}
}

test_staff_department_mutation_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/departments"}
}

test_technician_department_members_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "PUT /api/v1/departments/7b7c6d5e-1111-2222-3333-444455556666/members"}
}

test_auditor_department_delete_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "DELETE /api/v1/departments/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_department_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/departments"}
}

test_manager_section_leader_assign_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "PUT /api/v1/sections/7b7c6d5e-1111-2222-3333-444455556666/leader"}
}

test_super_admin_section_leader_clear_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "DELETE /api/v1/sections/7b7c6d5e-1111-2222-3333-444455556666/leader"}
}

test_technician_section_leader_assign_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "PUT /api/v1/sections/7b7c6d5e-1111-2222-3333-444455556666/leader"}
}

test_auditor_section_leader_clear_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "DELETE /api/v1/sections/7b7c6d5e-1111-2222-3333-444455556666/leader"}
}

test_manager_user_master_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "PUT /api/v1/auth/users/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_super_admin_user_master_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "PUT /api/v1/auth/users/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_technician_user_master_update_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "PUT /api/v1/auth/users/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_user_master_update_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "PUT /api/v1/auth/users/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_user_master_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/auth/users"}
}

test_anonymous_department_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/departments"}
}

# -- PM frequencies (story 19-1, blueprint F1): four-role mutation allow set;
#    TECHNICIAN/AUDITOR/anonymous denied; reads any-authenticated ---------------

test_manager_pm_frequency_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/pm-frequencies"}
}

test_section_leader_pm_frequency_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "PUT /api/v1/pm-frequencies/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_maintenance_leader_pm_frequency_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "PUT /api/v1/pm-frequencies/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_staff_pm_frequency_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/pm-frequencies"}
}

test_super_admin_pm_frequency_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/pm-frequencies"}
}

test_technician_pm_frequency_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-frequencies"}
}

test_auditor_pm_frequency_update_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "PUT /api/v1/pm-frequencies/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_pm_frequency_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/pm-frequencies"}
}

test_anonymous_pm_frequency_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/pm-frequencies"}
}

# -- PM checksheets (story 19-1, blueprint F2/F3): create/revise/approve for the
#    four-role set (the service narrows approve to the leader subset);
#    TECHNICIAN/AUDITOR/anonymous denied; reads any-authenticated ---------------

test_manager_pm_checksheet_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/pm-checksheets"}
}

test_section_leader_pm_checksheet_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/pm-checksheets"}
}

test_maintenance_leader_pm_checksheet_revise_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/pm-checksheets/7b7c6d5e-1111-2222-3333-444455556666/revise"}
}

test_staff_pm_checksheet_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/pm-checksheets"}
}

test_manager_pm_checksheet_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/pm-checksheets/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_section_leader_pm_checksheet_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/pm-checksheets/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_super_admin_pm_checksheet_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/pm-checksheets/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_technician_pm_checksheet_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-checksheets"}
}

test_technician_pm_checksheet_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-checksheets/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_auditor_pm_checksheet_revise_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/pm-checksheets/7b7c6d5e-1111-2222-3333-444455556666/revise"}
}

test_auditor_pm_checksheet_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/pm-checksheets"}
}

test_auditor_pm_checksheet_active_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/pm-checksheets/active"}
}

# STAFF_MAINTENANCE approve is ALLOWED at the rego layer (coarse gate) — the
# service narrows approve to the leader subset. This test pins that deliberate
# split so a future rego tightening can't silently change enforcement semantics.
test_staff_pm_checksheet_approve_allowed_at_rego if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/pm-checksheets/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_anonymous_pm_checksheet_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/pm-checksheets"}
}

# -- PM checklist categories & items (story 19-2, blueprint F4): four-role mutation
#    allow set on both collections and /{id}; TECHNICIAN/AUDITOR/anonymous denied;
#    reads any-authenticated -----------------------------------------------------

test_manager_pm_checklist_category_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/pm-checklist-categories"}
}

test_section_leader_pm_checklist_category_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "PUT /api/v1/pm-checklist-categories/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_maintenance_leader_pm_checklist_category_delete_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "DELETE /api/v1/pm-checklist-categories/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_staff_pm_checklist_item_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/pm-checklist-items"}
}

test_staff_pm_checklist_item_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "PUT /api/v1/pm-checklist-items/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_super_admin_pm_checklist_item_delete_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "DELETE /api/v1/pm-checklist-items/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_technician_pm_checklist_category_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-checklist-categories"}
}

test_technician_pm_checklist_item_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-checklist-items"}
}

test_auditor_pm_checklist_category_update_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "PUT /api/v1/pm-checklist-categories/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_pm_checklist_item_delete_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "DELETE /api/v1/pm-checklist-items/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_auditor_pm_checklist_category_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/pm-checklist-categories"}
}

test_auditor_pm_checklist_item_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/pm-checklist-items/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_anonymous_pm_checklist_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/pm-checklist-items"}
}

# -- PM schedules (story 19-3, blueprint F5): four-role mutation allow set on the
#    collection, /{id}, and all action subpaths; TECHNICIAN/AUDITOR/anonymous denied;
#    reads any-authenticated -----------------------------------------------------

test_manager_pm_schedule_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/pm-schedules"}
}

test_section_leader_pm_schedule_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/pm-schedules"}
}

test_maintenance_leader_pm_schedule_submit_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/submit"}
}

test_staff_pm_schedule_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/pm-schedules"}
}

test_manager_pm_schedule_approve_spv_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/approve-spv"}
}

test_section_leader_pm_schedule_approve_prod_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/approve-prod"}
}

test_maintenance_leader_pm_schedule_approve_prod_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/approve-prod"}
}

test_manager_pm_schedule_approve_prod_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/approve-prod"}
}

test_staff_pm_schedule_approve_prod_allowed_at_rego if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/approve-prod"}
}

test_technician_pm_schedule_approve_prod_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/approve-prod"}
}

test_auditor_pm_schedule_approve_prod_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/approve-prod"}
}

test_super_admin_pm_schedule_approve_prod_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/approve-prod"}
}

test_maintenance_leader_pm_schedule_activate_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/activate"}
}

test_super_admin_pm_schedule_activate_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/activate"}
}

test_manager_pm_schedule_date_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/dates/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_section_leader_pm_schedule_date_transition_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/dates/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_technician_pm_schedule_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-schedules"}
}

test_technician_pm_schedule_approve_spv_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/approve-spv"}
}

test_technician_pm_schedule_date_transition_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/dates/7b7c6d5e-1111-2222-3333-444455556666/transition"}
}

test_auditor_pm_schedule_submit_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666/submit"}
}

test_auditor_pm_schedule_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/pm-schedules"}
}

test_auditor_pm_schedule_get_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/pm-schedules/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_anonymous_pm_schedule_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/pm-schedules"}
}

# -- PM work orders (story 19-4, blueprint F6): four-role coarse allow set (the
#    three leader roles + TECHNICIAN as the assignee-scoped executor) on the
#    collection, /{id}, /generate, /sweep-overdue and the action subpaths;
#    STAFF_MAINTENANCE/AUDITOR/anonymous denied; reads any-authenticated --------

test_manager_pm_work_order_generate_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/pm-work-orders/generate"}
}

test_section_leader_pm_work_order_generate_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/pm-work-orders/generate"}
}

test_maintenance_leader_pm_work_order_generate_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/pm-work-orders/generate"}
}

test_section_leader_pm_work_order_assign_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/pm-work-orders/7b7c6d5e-1111-2222-3333-444455556666/assign"}
}

test_manager_pm_work_order_sweep_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/pm-work-orders/sweep-overdue"}
}

test_technician_pm_work_order_start_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-work-orders/7b7c6d5e-1111-2222-3333-444455556666/start"}
}

test_technician_pm_work_order_complete_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-work-orders/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

test_super_admin_pm_work_order_generate_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/pm-work-orders/generate"}
}

test_super_admin_pm_work_order_start_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/pm-work-orders/7b7c6d5e-1111-2222-3333-444455556666/start"}
}

test_staff_pm_work_order_generate_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/pm-work-orders/generate"}
}

test_staff_pm_work_order_assign_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/pm-work-orders/7b7c6d5e-1111-2222-3333-444455556666/assign"}
}

test_staff_pm_work_order_sweep_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/pm-work-orders/sweep-overdue"}
}

test_auditor_pm_work_order_start_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/pm-work-orders/7b7c6d5e-1111-2222-3333-444455556666/start"}
}

test_auditor_pm_work_order_generate_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/pm-work-orders/generate"}
}

test_storekeeper_pm_work_order_assign_denied if {
  not authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u8"}, "action": "POST /api/v1/pm-work-orders/7b7c6d5e-1111-2222-3333-444455556666/assign"}
}

test_auditor_pm_work_order_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/pm-work-orders"}
}

test_auditor_pm_work_order_get_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/pm-work-orders/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_anonymous_pm_work_order_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/pm-work-orders/generate"}
}

# -- PM executions (story 19-5, blueprint F7/F8): four-role coarse allow set (the
#    three leader roles + TECHNICIAN as the assignee-scoped executor) on the
#    collection, /{id}, /start, /{id}/items/{itemId}/fill, /{id}/complete and
#    /{id}/verify; STAFF_MAINTENANCE/AUDITOR/anonymous denied; reads any-authenticated --

test_technician_pm_execution_start_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-executions/start"}
}

test_technician_pm_execution_fill_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/items/8c8d9e0f-1111-2222-3333-444455556666/fill"}
}

test_technician_pm_execution_complete_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

test_section_leader_pm_execution_verify_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/verify"}
}

test_manager_pm_execution_verify_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/verify"}
}

test_maintenance_leader_pm_execution_start_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/pm-executions/start"}
}

test_super_admin_pm_execution_start_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/pm-executions/start"}
}

test_super_admin_pm_execution_verify_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/verify"}
}

test_staff_pm_execution_start_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/pm-executions/start"}
}

test_staff_pm_execution_fill_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/items/8c8d9e0f-1111-2222-3333-444455556666/fill"}
}

test_auditor_pm_execution_complete_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/complete"}
}

test_auditor_pm_execution_verify_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/verify"}
}

test_storekeeper_pm_execution_start_denied if {
  not authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u8"}, "action": "POST /api/v1/pm-executions/start"}
}

test_auditor_pm_execution_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/pm-executions"}
}

test_auditor_pm_execution_get_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666"}
}

# -- PM execution print report (story 19-6, FR-133): GET is a read (any-authenticated,
#    service gate is authoritative); the /{id}/report depth is in pm_execution_paths so
#    the coarse mutation set mirrors the rest of the surface (same role set allowed,
#    others denied) --

test_auditor_pm_execution_report_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/report"}
}

test_technician_pm_execution_report_post_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/report"}
}

test_section_leader_pm_execution_report_post_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/report"}
}

test_manager_pm_execution_report_post_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/report"}
}

test_maintenance_leader_pm_execution_report_post_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/report"}
}

test_staff_pm_execution_report_post_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/report"}
}

test_auditor_pm_execution_report_post_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/report"}
}

test_anonymous_pm_execution_report_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "GET /api/v1/pm-executions/7b7c6d5e-1111-2222-3333-444455556666/report"}
}

test_anonymous_pm_execution_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/pm-executions/start"}
}

# -- Non-conformances & 8D reports (story 21-1, blueprint H1/H2) -----------
# Six-role workorder-create parity set on the NC/8D mutation surface (mirrors
# NonConformanceService.requireMutationRole); effectiveness verification narrows to
# MANAGER_MAINTENANCE/SUPER_ADMIN (mirrors EightDReportService.verifyEffectiveness).
# Reads flow through generic read_allowed for any authenticated user.

test_super_admin_nc_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/non-conformances"}
}

test_manager_nc_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/non-conformances"}
}

test_maintenance_leader_nc_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "PATCH /api/v1/non-conformances/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_section_leader_nc_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "PATCH /api/v1/non-conformances/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_staff_nc_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/non-conformances"}
}

test_production_leader_nc_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/non-conformances"}
}

test_technician_nc_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/non-conformances"}
}

test_auditor_nc_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/non-conformances"}
}

test_technician_nc_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/non-conformances"}
}

test_manager_eight_d_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/non-conformances/7b7c6d5e-1111-2222-3333-444455556666/eight-d"}
}

test_staff_eight_d_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/non-conformances/7b7c6d5e-1111-2222-3333-444455556666/eight-d"}
}

test_super_admin_verify_effectiveness_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/non-conformances/7b7c6d5e-1111-2222-3333-444455556666/eight-d/verify-effectiveness"}
}

test_manager_verify_effectiveness_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/non-conformances/7b7c6d5e-1111-2222-3333-444455556666/eight-d/verify-effectiveness"}
}

test_maintenance_leader_verify_effectiveness_denied if {
  not authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/non-conformances/7b7c6d5e-1111-2222-3333-444455556666/eight-d/verify-effectiveness"}
}

test_technician_nc_update_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "PATCH /api/v1/non-conformances/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_section_leader_verify_effectiveness_denied if {
  not authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/non-conformances/7b7c6d5e-1111-2222-3333-444455556666/eight-d/verify-effectiveness"}
}

test_staff_verify_effectiveness_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/non-conformances/7b7c6d5e-1111-2222-3333-444455556666/eight-d/verify-effectiveness"}
}

test_anonymous_nc_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "GET /api/v1/non-conformances"}
}

# -- Calibration instruments & records (story 21-2, blueprint H3) ------------
# Six-role workorder-create parity set on the instrument/record mutation surface
# (mirrors CalibrationService's requireMutationRole gate); reads flow through
# generic read_allowed for any authenticated user.

test_super_admin_calibration_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/calibration-instruments"}
}

test_manager_calibration_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/calibration-instruments"}
}

test_staff_calibration_recalibrate_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/calibration-instruments/7b7c6d5e-1111-2222-3333-444455556666/recalibrate"}
}

test_section_leader_calibration_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "PATCH /api/v1/calibration-instruments/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_maintenance_leader_calibration_recalibrate_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/calibration-instruments/7b7c6d5e-1111-2222-3333-444455556666/recalibrate"}
}

test_production_leader_calibration_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/calibration-instruments"}
}

test_technician_calibration_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/calibration-instruments"}
}

test_auditor_calibration_recalibrate_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/calibration-instruments/7b7c6d5e-1111-2222-3333-444455556666/recalibrate"}
}

test_technician_calibration_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/calibration-instruments"}
}

test_technician_calibration_records_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/calibration-instruments/7b7c6d5e-1111-2222-3333-444455556666/records"}
}

test_anonymous_calibration_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "GET /api/v1/calibration-instruments"}
}

# -- Equipment change notices (story 21-2, blueprint H4) ----------------------
# Create/update/submit take the six-role parity set; approve/execute/close
# narrow to MANAGER_MAINTENANCE/SUPER_ADMIN (mirrors
# EquipmentChangeNoticeService.requireApprovalRole).

test_super_admin_ecn_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/equipment-change-notices"}
}

test_staff_ecn_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/equipment-change-notices"}
}

test_maintenance_leader_ecn_submit_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/equipment-change-notices/7b7c6d5e-1111-2222-3333-444455556666/submit"}
}

test_section_leader_ecn_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "PATCH /api/v1/equipment-change-notices/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_manager_ecn_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/equipment-change-notices/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_super_admin_ecn_execute_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/equipment-change-notices/7b7c6d5e-1111-2222-3333-444455556666/execute"}
}

test_super_admin_ecn_close_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/equipment-change-notices/7b7c6d5e-1111-2222-3333-444455556666/close"}
}

test_section_leader_ecn_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/equipment-change-notices/7b7c6d5e-1111-2222-3333-444455556666/approve"}
}

test_maintenance_leader_ecn_execute_denied if {
  not authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/equipment-change-notices/7b7c6d5e-1111-2222-3333-444455556666/execute"}
}

test_staff_ecn_close_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/equipment-change-notices/7b7c6d5e-1111-2222-3333-444455556666/close"}
}

test_technician_ecn_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/equipment-change-notices"}
}

test_technician_ecn_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/equipment-change-notices"}
}

test_anonymous_ecn_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "GET /api/v1/equipment-change-notices"}
}

# -- Machine setup baselines (story 21-3, blueprint H5) ----------------------
# Six-role workorder-create parity set on the baseline create/activate surface
# (mirrors MachineSetupBaselineService's requireMutationRole gate); reads flow
# through generic read_allowed for any authenticated user.

test_super_admin_baseline_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/machine-setup-baselines"}
}

test_manager_baseline_activate_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/machine-setup-baselines/7b7c6d5e-1111-2222-3333-444455556666/activate"}
}

test_staff_baseline_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/machine-setup-baselines"}
}

test_section_leader_baseline_activate_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/machine-setup-baselines/7b7c6d5e-1111-2222-3333-444455556666/activate"}
}

test_maintenance_leader_baseline_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/machine-setup-baselines"}
}

test_production_leader_baseline_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/machine-setup-baselines"}
}

test_technician_baseline_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/machine-setup-baselines"}
}

test_auditor_baseline_activate_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/machine-setup-baselines/7b7c6d5e-1111-2222-3333-444455556666/activate"}
}

test_inventory_maintenance_baseline_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["INVENTORY_MAINTENANCE"], "userId": "u9"}, "action": "POST /api/v1/machine-setup-baselines"}
}

test_technician_baseline_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/machine-setup-baselines"}
}

test_anonymous_baseline_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "GET /api/v1/machine-setup-baselines"}
}

# -- Lessons learned (story 21-3, blueprint H6) -------------------------------
# Create/update/delete take the six-role parity set (mirrors
# LessonLearnedService.requireMutationRole); reads flow through generic
# read_allowed.

test_super_admin_lesson_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/lessons-learned"}
}

test_manager_lesson_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "PATCH /api/v1/lessons-learned/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_staff_lesson_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/lessons-learned"}
}

test_section_leader_lesson_delete_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "DELETE /api/v1/lessons-learned/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_maintenance_leader_lesson_update_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "PATCH /api/v1/lessons-learned/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_production_leader_lesson_create_allowed if {
  authz.allow with input as {"subject": {"roles": ["PRODUCTION_LEADER"], "userId": "u8"}, "action": "POST /api/v1/lessons-learned"}
}

test_technician_lesson_create_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/lessons-learned"}
}

test_auditor_lesson_delete_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "DELETE /api/v1/lessons-learned/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_storekeeper_lesson_delete_denied if {
  not authz.allow with input as {"subject": {"roles": ["STOREKEEPER"], "userId": "u10"}, "action": "DELETE /api/v1/lessons-learned/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_technician_lesson_read_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/lessons-learned"}
}

test_anonymous_lesson_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "GET /api/v1/lessons-learned"}
}

# -- Login audits & phone challenges (story 22-2, blueprint I2/I3) ----------
# Audit reads are SUPER_ADMIN/AUDITOR-only (auth_audit_read_paths is excluded from
# the generic any-authenticated read; an explicit AUDITOR rule grants it). Phone
# challenges are SUPER_ADMIN-only mutations — no non-admin role matches
# auth_phone_challenge_paths, so everyone else is default-deny. Mirrors
# AuthLoginAuditService.requireReadRole / PhoneVerificationService.requireSuperAdmin.

test_super_admin_login_audit_list_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "GET /api/v1/auth/login-audits"}
}

test_auditor_login_audit_list_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/auth/login-audits"}
}

test_auditor_login_audit_detail_allowed if {
  authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "GET /api/v1/auth/login-audits/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_technician_login_audit_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/auth/login-audits"}
}

test_manager_login_audit_denied if {
  not authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "GET /api/v1/auth/login-audits"}
}

test_staff_login_audit_detail_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "GET /api/v1/auth/login-audits/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_anonymous_login_audit_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "GET /api/v1/auth/login-audits"}
}

test_super_admin_phone_challenge_issue_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/auth/phone-challenges"}
}

test_super_admin_phone_challenge_verify_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/auth/phone-challenges/7b7c6d5e-1111-2222-3333-444455556666/verify"}
}

test_super_admin_phone_challenge_resend_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/auth/phone-challenges/7b7c6d5e-1111-2222-3333-444455556666/resend"}
}

test_auditor_phone_challenge_issue_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/auth/phone-challenges"}
}

test_manager_phone_challenge_verify_denied if {
  not authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/auth/phone-challenges/7b7c6d5e-1111-2222-3333-444455556666/verify"}
}

test_technician_phone_challenge_resend_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/auth/phone-challenges/7b7c6d5e-1111-2222-3333-444455556666/resend"}
}

test_anonymous_phone_challenge_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/auth/phone-challenges"}
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

# -- User signatures & workorder approve (story 22-3, blueprint I1) ----------
# Upload is owner-or-SUPER_ADMIN in service; rego admits any authenticated user
# (coarse gate, alert_mutation_paths posture). Reads flow through generic
# read_allowed (narrowed service-side). WO approve grants the three leader roles
# — parity with WorkorderSignatureService.requireLeaderAccess.

test_technician_user_signature_upload_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/auth/user-signatures"}
}

test_super_admin_user_signature_upload_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/auth/user-signatures"}
}

test_anonymous_user_signature_upload_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/auth/user-signatures"}
}

test_technician_user_signature_read_own_allowed if {
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/auth/user-signatures/me"}
}

test_technician_user_signature_read_other_allowed_at_rego if {
  # Coarse gate: reading ANOTHER user's signature is narrowed to SUPER_ADMIN in
  # UserSignatureService (rego cannot see the path variable's owner).
  authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "GET /api/v1/auth/user-signatures/7b7c6d5e-1111-2222-3333-444455556666"}
}

test_anonymous_user_signature_read_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "GET /api/v1/auth/user-signatures/me"}
}

test_section_leader_workorder_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["SECTION_LEADER"], "userId": "u5"}, "action": "POST /api/v1/workorders/WO-2609-00001/approve"}
}

test_maintenance_leader_workorder_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["MAINTENANCE_LEADER"], "userId": "u7"}, "action": "POST /api/v1/workorders/WO-2609-00001/approve"}
}

test_manager_workorder_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["MANAGER_MAINTENANCE"], "userId": "u2"}, "action": "POST /api/v1/workorders/WO-2609-00001/approve"}
}

test_super_admin_workorder_approve_allowed if {
  authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"], "userId": "u1"}, "action": "POST /api/v1/workorders/WO-2609-00001/approve"}
}

test_technician_workorder_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["TECHNICIAN"], "userId": "u4"}, "action": "POST /api/v1/workorders/WO-2609-00001/approve"}
}

test_staff_workorder_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["STAFF_MAINTENANCE"], "userId": "u3"}, "action": "POST /api/v1/workorders/WO-2609-00001/approve"}
}

test_auditor_workorder_approve_denied if {
  not authz.allow with input as {"subject": {"roles": ["AUDITOR"], "userId": "u6"}, "action": "POST /api/v1/workorders/WO-2609-00001/approve"}
}

test_anonymous_workorder_approve_denied if {
  not authz.allow with input as {"subject": {"roles": [], "userId": null}, "action": "POST /api/v1/workorders/WO-2609-00001/approve"}
}
