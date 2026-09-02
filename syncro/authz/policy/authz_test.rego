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
