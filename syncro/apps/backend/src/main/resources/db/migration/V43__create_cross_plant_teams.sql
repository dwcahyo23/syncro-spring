-- Story 9-2: cross-plant teams (AD-13). A team is an expiry-dated collection of
-- members and target machines; at derive time the org module resolves the target
-- machines' machine-group ids into the machineGroupIds scope used for SQL filtering.
-- Teams are runtime admin config (no pilot seed); expiry is evaluated lazily per
-- derive via Clock — no scheduled job and no stored `active` column.
CREATE TABLE teams (
  id UUID PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_teams_name_not_blank CHECK (btrim(name) <> '')
);

-- PostgreSQL UNIQUE constraints accept column lists only, so case-insensitive
-- name uniqueness is a unique index (V4/V42 pattern).
CREATE UNIQUE INDEX uq_teams_lower_name ON teams (lower(name));

CREATE TABLE team_members (
  team_id UUID NOT NULL,
  user_id UUID NOT NULL,
  PRIMARY KEY (team_id, user_id),
  CONSTRAINT fk_team_members_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
  CONSTRAINT fk_team_members_user FOREIGN KEY (user_id) REFERENCES auth_users(id) ON DELETE RESTRICT
);

CREATE INDEX idx_team_members_user_id ON team_members(user_id);

CREATE TABLE team_machines (
  team_id UUID NOT NULL,
  machine_id UUID NOT NULL,
  PRIMARY KEY (team_id, machine_id),
  CONSTRAINT fk_team_machines_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
  CONSTRAINT fk_team_machines_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE RESTRICT
);

CREATE INDEX idx_team_machines_machine_id ON team_machines(machine_id);

-- Audit: add TEAM to the entity_type CHECK (V31/V38/V42 drop/re-add pattern).
-- Team audit rows use plantId=null (V16 audit_log.plant_id is nullable); a
-- cross-plant team by definition spans plants.
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM'));
