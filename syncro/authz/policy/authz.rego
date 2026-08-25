package syncro.authz

# Default-deny: every authorization decision must be explicitly allowed by a rule.
default allow := false

allow if input.subject.roles[_] == "SUPER_ADMIN"

super_admin if input.subject.roles[_] == "SUPER_ADMIN"

# Placeholder read set until story 9.4 extends the role taxonomy; 9.5 owns real
# per-action rules. Returned as a JSON array at /v1/data/syncro/authz/actions.
actions := ["health.read"] if super_admin

actions := [] if not super_admin
