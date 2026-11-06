-- v49: the signals a public skill carries — where it came from, what license applies, who used it.
--
-- Source is a typed connection, not a set of per-platform columns. A new platform is a new adapter
-- and a new `kind`; the table stays the same. Signals are an adapter-defined JSON array rendered by
-- kind, never by platform name.

CREATE TABLE skill_source (
  id         TEXT PRIMARY KEY,
  skill_id   TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  role       TEXT NOT NULL CHECK (role IN ('origin', 'mirror')),
  kind       TEXT NOT NULL,
  locator    TEXT NOT NULL,
  path       TEXT NOT NULL DEFAULT '',
  label      TEXT NOT NULL,
  url        TEXT,
  created_at TEXT NOT NULL,
  UNIQUE (skill_id, role, kind, locator)
);

-- One origin per skill; a mirror is optional.
CREATE UNIQUE INDEX skill_source_one_origin
  ON skill_source (skill_id) WHERE role = 'origin';

CREATE TABLE skill_source_snapshot (
  source_id        TEXT NOT NULL REFERENCES skill_source(id) ON DELETE CASCADE,
  skill_version_id TEXT NOT NULL REFERENCES skill_version(id) ON DELETE CASCADE,
  revision         TEXT,
  signals          TEXT NOT NULL DEFAULT '[]',
  status           TEXT NOT NULL,
  fetched_at       TEXT NOT NULL,
  PRIMARY KEY (source_id, skill_version_id)
);

-- The license belongs to the version, because a later version can come from another commit with
-- another license. The full text is small and lives here rather than in a version file: it must not
-- change the content digest, and it must not be swept by the blob GC that only follows version_file.
CREATE TABLE skill_version_license (
  version_id          TEXT PRIMARY KEY REFERENCES skill_version(id) ON DELETE CASCADE,
  spdx                TEXT NOT NULL,
  name                TEXT NOT NULL,
  copyright           TEXT,
  status              TEXT NOT NULL,
  license_text        TEXT,
  source_path         TEXT,
  source_url          TEXT,
  detail              TEXT NOT NULL DEFAULT '{}',
  fetched_at          TEXT NOT NULL
);

-- One row per account per day per skill. Repeated loads on the same day add nothing; the console
-- reads a 30-day count of distinct accounts, never the rows themselves.
CREATE TABLE skill_usage_day (
  skill_id   TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  day        TEXT NOT NULL,
  account_id TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  PRIMARY KEY (skill_id, day, account_id)
);

-- Existing official skills predate the metadata field; the platform is their source, and every
-- public skill must have one. The snapshot is pending until the first refresh (which is a no-op for
-- an official source).
INSERT INTO skill_source (id, skill_id, role, kind, locator, path, label, url, created_at)
SELECT 'src-' || s.id, s.id, 'origin', 'official', n.slug || '/' || s.name, '', '官方', NULL,
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
  FROM skill s
  JOIN namespace n ON n.id = s.namespace_id
 WHERE n.official = TRUE
ON CONFLICT DO NOTHING;

INSERT INTO skill_source_snapshot (source_id, skill_version_id, revision, signals, status,
                                   fetched_at)
SELECT src.id, s.current_version_id, NULL, '[]', 'pending',
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
  FROM skill_source src
  JOIN skill s ON s.id = src.skill_id
 WHERE src.role = 'origin' AND s.current_version_id IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO skill_version_license (version_id, spdx, name, copyright, status, license_text,
                                   source_path, source_url, detail, fetched_at)
SELECT s.current_version_id, 'NOASSERTION', '未声明', NULL, 'missing', NULL, NULL, NULL, '{}',
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
  FROM skill s
  JOIN namespace n ON n.id = s.namespace_id
 WHERE n.official = TRUE AND s.current_version_id IS NOT NULL
ON CONFLICT DO NOTHING;
