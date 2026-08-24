-- Story 8-5: machine groups define up to three daily shift windows; machines may
-- override them (machine wins). shift_number is assigned by list order (1..3); a
-- cross-midnight window has end_time < start_time. No JSON: resolution and 8.6
-- operating-time math need typed, indexable rows.
CREATE TABLE machine_group_shift_windows (
  id UUID PRIMARY KEY,
  machine_group_id UUID NOT NULL,
  shift_number SMALLINT NOT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machine_group_shift_windows_machine_group_id FOREIGN KEY (machine_group_id) REFERENCES machine_groups(id) ON DELETE CASCADE,
  CONSTRAINT uq_machine_group_shift_windows_machine_group_shift_number UNIQUE (machine_group_id, shift_number),
  CONSTRAINT ck_machine_group_shift_windows_shift_number CHECK (shift_number BETWEEN 1 AND 3)
);

CREATE TABLE machine_shift_windows (
  id UUID PRIMARY KEY,
  machine_id UUID NOT NULL,
  shift_number SMALLINT NOT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machine_shift_windows_machine_id FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE CASCADE,
  CONSTRAINT uq_machine_shift_windows_machine_shift_number UNIQUE (machine_id, shift_number),
  CONSTRAINT ck_machine_shift_windows_shift_number CHECK (shift_number BETWEEN 1 AND 3)
);
