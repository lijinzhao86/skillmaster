-- v69: the operations database. Only what the import needs today.
--
-- `job_run` is deliberately generic: the kind says which task ran, and labeling,
-- sampling and evaluation become new kinds rather than new tables. The import's own
-- per-entry facts live in `import_entry`, because nothing else has that shape.

CREATE TABLE job_run (
  id          TEXT PRIMARY KEY,
  kind        TEXT NOT NULL,
  trigger     TEXT NOT NULL,
  status      TEXT NOT NULL,
  params      TEXT NOT NULL DEFAULT '{}',
  summary     TEXT NOT NULL DEFAULT '{}',
  started_at  TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ,
  error       TEXT
);

CREATE INDEX job_run_kind_started ON job_run (kind, started_at DESC);

-- One row per entry the run looked at. `result` is the same vocabulary the run
-- summary counts: created / replayed / blocked / failed.
CREATE TABLE import_entry (
  id            TEXT PRIMARY KEY,
  run_id        TEXT NOT NULL REFERENCES job_run (id) ON DELETE CASCADE,
  binding       TEXT NOT NULL,
  revision      TEXT,
  platform_name TEXT NOT NULL,
  skill_id      TEXT,
  draft_id      TEXT,
  result        TEXT NOT NULL,
  note          TEXT,
  created_at    TIMESTAMPTZ NOT NULL,
  UNIQUE (run_id, binding, revision)
);

CREATE INDEX import_entry_run ON import_entry (run_id);

-- One row per task kind, held for the life of a run. `FOR UPDATE NOWAIT` on it is
-- the whole mutex: a crashed process releases the row lock when its connection dies,
-- so there is no stale "running" flag to clean up.
CREATE TABLE job_lock (
  kind        TEXT PRIMARY KEY,
  holder      TEXT NOT NULL,
  acquired_at TIMESTAMPTZ NOT NULL
);

INSERT INTO job_lock (kind, holder, acquired_at) VALUES
  ('import', 'seed', '2026-11-14T00:00:00Z');
