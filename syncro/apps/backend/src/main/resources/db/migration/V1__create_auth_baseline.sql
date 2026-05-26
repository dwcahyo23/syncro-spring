CREATE TABLE auth_users (
  id UUID PRIMARY KEY,
  login_identifier VARCHAR(255) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  application_role VARCHAR(32) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_auth_users_login_identifier UNIQUE (login_identifier),
  CONSTRAINT ck_auth_users_application_role CHECK (application_role IN ('SUPER_ADMIN', 'MANAGE', 'VIEWER'))
);
