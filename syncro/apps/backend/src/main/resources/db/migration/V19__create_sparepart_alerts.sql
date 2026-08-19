CREATE TABLE sparepart_alerts (
    id UUID PRIMARY KEY,
    machine_id UUID NOT NULL REFERENCES machines(id),
    machine_sparepart_installation_id UUID NOT NULL REFERENCES machine_sparepart_installations(id),
    threshold_percentage INT NOT NULL,
    current_counter_snapshot BIGINT NOT NULL,
    consumed_production_count_snapshot BIGINT NOT NULL,
    consumed_percentage_snapshot NUMERIC(7,2) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    status_reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX sparepart_alerts_dedup_idx
    ON sparepart_alerts (machine_sparepart_installation_id, threshold_percentage)
    WHERE status != 'RESOLVED';
CREATE INDEX sparepart_alerts_machine_id_idx ON sparepart_alerts (machine_id);
