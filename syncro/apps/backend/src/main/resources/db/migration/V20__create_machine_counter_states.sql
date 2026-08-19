CREATE TABLE machine_counter_states
(
    machine_id  UUID        NOT NULL,
    counting    BIGINT      NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_machine_counter_states PRIMARY KEY (machine_id)
);
