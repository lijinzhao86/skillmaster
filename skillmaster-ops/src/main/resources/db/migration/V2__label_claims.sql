-- v72: fixed-rule labels, and the row that makes a refresh incremental.
--
-- `label_claim` is append-only: the current set of a skill is the latest run's rows, and the older
-- rows answer "why did this change". The evidence and the rule version live here because the
-- production table holds only the final `(skill_id, definition_id)` pair (ADR 0197).

CREATE TABLE label_claim (
  id            TEXT PRIMARY KEY,
  run_id        TEXT NOT NULL REFERENCES job_run (id) ON DELETE CASCADE,
  origin        TEXT NOT NULL,              -- import | refresh
  skill_id      TEXT NOT NULL,
  namespace     TEXT NOT NULL,
  name          TEXT NOT NULL,
  version_id    TEXT,
  definition_id TEXT NOT NULL,
  evidence      TEXT NOT NULL,              -- JSON {kind,file,line,quote}
  rule_id       TEXT NOT NULL,
  rule_version  TEXT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  UNIQUE (run_id, skill_id, definition_id)
);

CREATE INDEX label_claim_skill ON label_claim (skill_id, created_at DESC);

-- One row per skill per evaluation. `result` is what the refresh tallies: labeled / unchanged /
-- push_failed / skipped / failed. Only `labeled` counts as a reason to skip the next time.
CREATE TABLE label_entry (
  id            TEXT PRIMARY KEY,
  run_id        TEXT NOT NULL REFERENCES job_run (id) ON DELETE CASCADE,
  skill_id      TEXT NOT NULL,
  namespace     TEXT NOT NULL,
  name          TEXT NOT NULL,
  version_id    TEXT,
  rule_version  TEXT NOT NULL,
  result        TEXT NOT NULL,
  labels        INTEGER NOT NULL DEFAULT 0,
  note          TEXT,
  created_at    TIMESTAMPTZ NOT NULL,
  UNIQUE (run_id, skill_id)
);

CREATE INDEX label_entry_run ON label_entry (run_id);
CREATE INDEX label_entry_skill ON label_entry (skill_id, created_at DESC);

INSERT INTO job_lock (kind, holder, acquired_at)
VALUES ('label', 'seed', '2026-11-14T00:00:00Z');
