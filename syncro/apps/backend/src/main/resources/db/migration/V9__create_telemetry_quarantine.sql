CREATE TABLE telemetry_quarantine (
  id UUID PRIMARY KEY,
  trace_id VARCHAR(255) NOT NULL,
  topic TEXT NOT NULL,
  raw_payload TEXT NOT NULL,
  rejection_reason VARCHAR(100) NOT NULL,
  rejection_field VARCHAR(100),
  received_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_telemetry_quarantine_received_at ON telemetry_quarantine(received_at DESC);
CREATE INDEX idx_telemetry_quarantine_trace_id ON telemetry_quarantine(trace_id);
