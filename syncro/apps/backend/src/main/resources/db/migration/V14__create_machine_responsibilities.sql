CREATE TABLE machine_responsibilities (
    id UUID PRIMARY KEY,
    machine_id UUID NOT NULL REFERENCES machines(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
    level VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_machine_user_responsibility UNIQUE (machine_id, user_id)
);

CREATE INDEX idx_machine_responsibilities_machine_id ON machine_responsibilities(machine_id);
CREATE INDEX idx_machine_responsibilities_user_id ON machine_responsibilities(user_id);
