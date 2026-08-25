package syncro.authz_test

import data.syncro.authz

test_super_admin_allowed if {
	authz.allow with input as {"subject": {"roles": ["SUPER_ADMIN"]}}
}

test_non_super_admin_denied if {
	not authz.allow with input as {"subject": {"roles": ["TECHNICIAN", "MANAGER_MAINTENANCE"]}}
}

test_empty_input_default_deny if {
	not authz.allow with input as {}
}

test_missing_subject_default_deny if {
	not authz.allow with input as {"subject": {}}
}

test_actions_empty_for_regular_user if {
	result := authz.actions with input as {"subject": {"roles": ["TECHNICIAN"], "plantIds": [], "machineGroupIds": [], "activeTeamIds": []}}
	count(result) == 0
}

test_actions_placeholder_set_for_super_admin if {
	result := authz.actions with input as {"subject": {"roles": ["SUPER_ADMIN"]}}
	result == ["health.read"]
}
