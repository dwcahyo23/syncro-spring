CREATE TABLE waha_templates (
    id UUID PRIMARY KEY,
    template_key VARCHAR(64) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX waha_templates_template_key_idx ON waha_templates (template_key);
