-- v65: 13 public shelves, extensible label definitions, and a skill's expected result.
--
-- The label model is two tables: `label_definition` is the platform vocabulary, `skill_label` is
-- one claim attached to one skill. Adding a dimension is an insert, not a schema change (ADR 0192).
-- `outcome` is a one-line claim on the skill row, not a label: it has one value and belongs beside
-- the description it must not replace.
--
-- The category migration is deliberately reversible: `skill_category_mapping` records the old key,
-- state and order before the old rows are retired, and `skill_category_assignment_snapshot`
-- records **each skill's own old key** before the remap. The snapshot is what makes the rollback
-- correct: the mapping is many-to-one (`doc` and `office` both become `office-efficiency`), so a
-- reverse UPDATE driven by the key-level mapping alone would pick one of the two at random.

-- ---------------------------------------------------------------------------
-- Categories: 10 -> 13, old words retired
-- ---------------------------------------------------------------------------

CREATE TABLE skill_category_mapping (
  old_key        TEXT PRIMARY KEY,
  new_key        TEXT NOT NULL,
  old_state      TEXT NOT NULL,
  old_sort_order INTEGER NOT NULL,
  note           TEXT
);

INSERT INTO skill_category_mapping (old_key, new_key, old_state, old_sort_order, note)
SELECT key,
       CASE key
         WHEN 'doc' THEN 'office-efficiency'
         WHEN 'office' THEN 'office-efficiency'
         WHEN 'data' THEN 'data-analysis'
         WHEN 'dev' THEN 'dev-programming'
         WHEN 'design' THEN 'design-media'
         WHEN 'media' THEN 'design-media'
         WHEN 'writing' THEN 'content-creation'
         WHEN 'research' THEN 'knowledge-management'
         WHEN 'ops' THEN 'it-ops-security'
         WHEN 'security' THEN 'it-ops-security'
       END,
       state, sort_order,
       'v65 10 -> 13; mapped by main output (ADR 0191 decision 5)'
FROM skill_category;

-- Old rows keep their identity and name; they are retired, and their order moves out of the way
-- so the new rows can use 1..13. `ops` was stopped, not enabled: the mapping table keeps that fact.
UPDATE skill_category SET state = 'retired', sort_order = sort_order + 100;

INSERT INTO skill_category (key, name, state, sort_order, created_at) VALUES
  ('office-efficiency', '办公效率',       'enabled', 1,  '2026-11-14T00:00:00Z'),
  ('content-creation', '内容创作',       'enabled', 2,  '2026-11-14T00:00:00Z'),
  ('dev-programming',  '开发编程',       'enabled', 3,  '2026-11-14T00:00:00Z'),
  ('data-analysis',    '数据分析',       'enabled', 4,  '2026-11-14T00:00:00Z'),
  ('design-media',     '设计多媒体',     'enabled', 5,  '2026-11-14T00:00:00Z'),
  ('ai-agent',         'AI Agent',       'enabled', 6,  '2026-11-14T00:00:00Z'),
  ('knowledge-management', '知识管理',  'enabled', 7,  '2026-11-14T00:00:00Z'),
  ('business-ops',     '商业运营',       'enabled', 8,  '2026-11-14T00:00:00Z'),
  ('education',        '教育学习',       'enabled', 9,  '2026-11-14T00:00:00Z'),
  ('professional',     '行业专业',       'enabled', 10, '2026-11-14T00:00:00Z'),
  ('it-ops-security',  'IT 运维与安全',  'enabled', 11, '2026-11-14T00:00:00Z'),
  ('life-service',     '生活服务',       'enabled', 12, '2026-11-14T00:00:00Z'),
  ('ai-collaboration', 'AI 协作',        'enabled', 13, '2026-11-14T00:00:00Z');

-- **One row per skill, before the remap.** This is the rollback's only source of the original key:
-- several old words share one new word, so the key-level mapping cannot say which one a skill had.
CREATE TABLE skill_category_assignment_snapshot (
  skill_id    TEXT PRIMARY KEY REFERENCES skill(id) ON DELETE CASCADE,
  old_key     TEXT NOT NULL,
  migrated_at TIMESTAMPTZ NOT NULL
);

INSERT INTO skill_category_assignment_snapshot (skill_id, old_key, migrated_at)
SELECT id, category_key, '2026-11-14T00:00:00Z'
  FROM skill
 WHERE category_key IS NOT NULL;

UPDATE skill s
SET category_key = m.new_key
FROM skill_category_mapping m
WHERE s.category_key = m.old_key;

-- Rollback (run by hand, after checking that nothing has been re-categorised since this migration):
--   UPDATE skill s SET category_key = snap.old_key
--     FROM skill_category_assignment_snapshot snap WHERE s.id = snap.skill_id;
--   -- Every shelf here is new. After the snapshot restores the skills that predate it, whatever
--   -- still names one of the 13 was categorised while V51 was live, and has no old word to go back
--   -- to. Clear **all 13**, not just the six new-only shelves: a skill put on a renamed shelf
--   -- after the migration has no snapshot row either, and the DELETE below would stop on its FK.
--   UPDATE skill SET category_key = NULL WHERE category_key IN (
--     'office-efficiency','content-creation','dev-programming','data-analysis','design-media',
--     'ai-agent','knowledge-management','business-ops','education','professional',
--     'it-ops-security','life-service','ai-collaboration');
--   DELETE FROM skill_category WHERE key IN (
--     'office-efficiency','content-creation','dev-programming','data-analysis','design-media',
--     'ai-agent','knowledge-management','business-ops','education','professional',
--     'it-ops-security','life-service','ai-collaboration');
--   UPDATE skill_category c SET state = m.old_state, sort_order = m.old_sort_order
--     FROM skill_category_mapping m WHERE c.key = m.old_key;
--   DROP TABLE skill_category_assignment_snapshot;
--   DROP TABLE skill_category_mapping;
-- The second statement clears any classification written after this migration — author, operator
-- or agent. That is the documented cost of a rollback once the migration has been live.

COMMENT ON TABLE skill_category_mapping IS
  'v65 category migration record: old key, new key and the old state/order. Kept after the migration so a rollback can reverse it.';
COMMENT ON COLUMN skill_category_mapping.old_state IS
  'The old row''s state, including the one stopped word. A rollback restores this, not enabled.';

-- ---------------------------------------------------------------------------
-- Outcome on the read model
-- ---------------------------------------------------------------------------

ALTER TABLE skill ADD COLUMN outcome                         TEXT;
ALTER TABLE skill ADD COLUMN outcome_source                  TEXT;
ALTER TABLE skill ADD COLUMN outcome_confidence              NUMERIC(3,2);
ALTER TABLE skill ADD COLUMN outcome_evidence                JSONB;
ALTER TABLE skill ADD COLUMN outcome_model                   TEXT;
ALTER TABLE skill ADD COLUMN outcome_model_version           TEXT;
ALTER TABLE skill ADD COLUMN outcome_evaluated_version_id    TEXT REFERENCES skill_version(id);
ALTER TABLE skill ADD COLUMN outcome_updated_at              TEXT;

COMMENT ON COLUMN skill.outcome IS
  'v65: one sentence saying what the reader gets. Max 200 Unicode code points (ADR 0181); never replaces description.';
COMMENT ON COLUMN skill.outcome_source IS
  'agent / operator. The read model shows only a value whose evaluated version is current, unless an operator confirmed it.';

-- ---------------------------------------------------------------------------
-- Label vocabulary and claims
-- ---------------------------------------------------------------------------

CREATE TABLE label_definition (
  id             TEXT PRIMARY KEY,
  dimension_key  TEXT NOT NULL,
  dimension_name TEXT NOT NULL,
  value          TEXT NOT NULL,
  label_short    TEXT NOT NULL,
  label_full     TEXT NOT NULL,
  group_key      TEXT NOT NULL,
  filterable     BOOLEAN NOT NULL DEFAULT true,
  state          TEXT NOT NULL DEFAULT 'enabled',
  sort_order     INTEGER NOT NULL,
  created_at     TEXT NOT NULL,
  UNIQUE (dimension_key, value),
  UNIQUE (dimension_key, sort_order),
  CHECK (state IN ('enabled', 'stopped', 'retired'))
);

CREATE INDEX idx_label_definition_filterable
  ON label_definition (dimension_key, state, sort_order) WHERE filterable;

COMMENT ON TABLE label_definition IS
  'v65 platform vocabulary: one row is one value of one dimension. Add a dimension by inserting rows, not by altering this table (ADR 0192).';
COMMENT ON COLUMN label_definition.id IS
  'Stable id; the wire key is dimension_key:value.';
COMMENT ON COLUMN label_definition.label_full IS
  'Full semantics for extraction evidence / CLI / operators. The console does not render it.';
COMMENT ON COLUMN label_definition.group_key IS
  'language / os / tool / credential / pricing. Colour and icon only; never a filter.';

CREATE TABLE label_extraction_run (
  id                 TEXT PRIMARY KEY,
  skill_id           TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  version_id         TEXT NOT NULL REFERENCES skill_version(id) ON DELETE CASCADE,
  trigger            TEXT NOT NULL,
  status             TEXT NOT NULL,
  model              TEXT,
  model_version      TEXT,
  vocabulary_version TEXT,
  started_at         TEXT,
  finished_at        TEXT,
  labels_written     INTEGER,
  error              TEXT,
  CHECK (status IN ('queued', 'running', 'ok', 'partial', 'failed')),
  CHECK (trigger IN ('publish', 'sync', 'backfill', 'manual'))
);

CREATE INDEX idx_label_extraction_run_skill ON label_extraction_run (skill_id, started_at);
CREATE INDEX idx_label_extraction_run_status ON label_extraction_run (status);

CREATE TABLE skill_label (
  id                     TEXT PRIMARY KEY,
  skill_id               TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  definition_id          TEXT NOT NULL REFERENCES label_definition(id),
  run_id                 TEXT REFERENCES label_extraction_run(id) ON DELETE SET NULL,
  source                 TEXT NOT NULL,
  confidence             NUMERIC(3,2) NOT NULL,
  evidence               JSONB NOT NULL,
  model                  TEXT,
  model_version          TEXT,
  vocabulary_version     TEXT NOT NULL,
  review_state           TEXT NOT NULL DEFAULT 'auto',
  evaluated_version_id   TEXT REFERENCES skill_version(id),
  created_at             TEXT NOT NULL,
  updated_at             TEXT NOT NULL,
  UNIQUE (skill_id, definition_id),
  CHECK (source IN ('agent', 'operator', 'author', 'upstream')),
  CHECK (review_state IN ('auto', 'pending', 'confirmed', 'rejected', 'stale')),
  CHECK (confidence >= 0 AND confidence <= 1)
);

CREATE INDEX idx_skill_label_definition ON skill_label (definition_id, skill_id);
CREATE INDEX idx_skill_label_skill ON skill_label (skill_id);
CREATE INDEX idx_skill_label_review ON skill_label (review_state);

COMMENT ON TABLE skill_label IS
  'v65 one claim about one skill. Multi-select is multiple rows; the unique key is (skill_id, definition_id), not (skill_id, dimension).';
COMMENT ON COLUMN skill_label.evidence IS
  'A short JSON quote/path. No evidence means the row is not written at all.';
COMMENT ON COLUMN skill_label.evaluated_version_id IS
  'Which version this claim was evaluated on. Agent rows display only while it is the current version; operator rows cross versions.';

CREATE TABLE label_audit_sample (
  id            TEXT PRIMARY KEY,
  week          TEXT NOT NULL,
  skill_id      TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  definition_id TEXT NOT NULL REFERENCES label_definition(id) ON DELETE CASCADE,
  sampled_at    TEXT NOT NULL,
  UNIQUE (week, skill_id, definition_id)
);

CREATE INDEX idx_label_audit_sample_week ON label_audit_sample (week);

COMMENT ON TABLE label_audit_sample IS
  'v65 weekly sample. It stores which claims were sampled, never the judgement: the judgement lives in deploy/label-audit.yml (ADR 0193).';

-- ---------------------------------------------------------------------------
-- Vocabulary seed
-- ---------------------------------------------------------------------------
-- The authority for the values and their full meanings is docs/versions/v65-skill-labels/prd.md.
-- The ids are dimension:value so the wire form and the stored identity are the same string.

INSERT INTO label_definition
  (id, dimension_key, dimension_name, value, label_short, label_full, group_key,
   filterable, state, sort_order, created_at)
VALUES
  ('runtime:python',        'runtime', '运行环境', 'python',        'Python',        '需要 Python',                         'language',   true, 'enabled', 1,  '2026-11-14T00:00:00Z'),
  ('runtime:node',          'runtime', '运行环境', 'node',          'Node.js',       '需要 Node.js',                        'language',   true, 'enabled', 2,  '2026-11-14T00:00:00Z'),
  ('runtime:java',          'runtime', '运行环境', 'java',          'Java',          '需要 Java',                           'language',   true, 'enabled', 3,  '2026-11-14T00:00:00Z'),
  ('runtime:c-cpp',         'runtime', '运行环境', 'c-cpp',         'C/C++',         '需要 C/C++',                          'language',   true, 'enabled', 4,  '2026-11-14T00:00:00Z'),
  ('runtime:go',            'runtime', '运行环境', 'go',            'Go',            '需要 Go',                             'language',   true, 'enabled', 5,  '2026-11-14T00:00:00Z'),
  ('runtime:rust',          'runtime', '运行环境', 'rust',          'Rust',          '需要 Rust',                           'language',   true, 'enabled', 6,  '2026-11-14T00:00:00Z'),
  ('runtime:dotnet',        'runtime', '运行环境', 'dotnet',        '.NET',          '需要 .NET',                           'language',   true, 'enabled', 7,  '2026-11-14T00:00:00Z'),
  ('runtime:ruby',          'runtime', '运行环境', 'ruby',          'Ruby',          '需要 Ruby',                           'language',   true, 'enabled', 8,  '2026-11-14T00:00:00Z'),
  ('runtime:php',           'runtime', '运行环境', 'php',           'PHP',           '需要 PHP',                            'language',   true, 'enabled', 9,  '2026-11-14T00:00:00Z'),
  ('runtime:shell',         'runtime', '运行环境', 'shell',         'Shell',         '需要 Shell',                          'language',   true, 'enabled', 10, '2026-11-14T00:00:00Z'),
  ('runtime:macos',         'runtime', '运行环境', 'macos',         'macOS',         '仅 macOS',                            'os',         true, 'enabled', 11, '2026-11-14T00:00:00Z'),
  ('runtime:windows',       'runtime', '运行环境', 'windows',       'Windows',       '仅 Windows',                          'os',         true, 'enabled', 12, '2026-11-14T00:00:00Z'),
  ('runtime:linux',         'runtime', '运行环境', 'linux',         'Linux',         '仅 Linux',                            'os',         true, 'enabled', 13, '2026-11-14T00:00:00Z'),
  ('runtime:browser',       'runtime', '运行环境', 'browser',       '浏览器',        '需要浏览器',                          'tool',       true, 'enabled', 14, '2026-11-14T00:00:00Z'),
  ('runtime:docker',        'runtime', '运行环境', 'docker',        'Docker',        '运行时需要 Docker 引擎（只写 Dockerfile 不算）', 'tool', true, 'enabled', 15, '2026-11-14T00:00:00Z'),
  ('runtime:gpu',           'runtime', '运行环境', 'gpu',           'GPU',           '需要本机/本地集群 NVIDIA GPU（云 GPU 不算）',   'tool', true, 'enabled', 16, '2026-11-14T00:00:00Z'),
  ('runtime:database',      'runtime', '运行环境', 'database',      '数据库',        '需要连接数据库服务（教 SQL / 查公开数据库 API 不算）', 'tool', true, 'enabled', 17, '2026-11-14T00:00:00Z'),
  ('runtime:online-service','runtime', '运行环境', 'online-service','在线服务',      '需要在线服务',                        'tool',       true, 'enabled', 18, '2026-11-14T00:00:00Z'),
  ('runtime:system-permission','runtime','运行环境','system-permission','系统权限',  '需要系统权限',                        'tool',       true, 'enabled', 19, '2026-11-14T00:00:00Z'),
  ('runtime:api-key',       'runtime', '运行环境', 'api-key',       'API key',       '需要 API key',                        'credential', true, 'enabled', 20, '2026-11-14T00:00:00Z'),
  ('runtime:account',       'runtime', '运行环境', 'account',       '账号',          '需要账号',                            'credential', true, 'enabled', 21, '2026-11-14T00:00:00Z'),
  ('runtime:prompt-only',   'runtime', '运行环境', 'prompt-only',   '纯提示词',      '纯提示词',                            'language',   true, 'enabled', 22, '2026-11-14T00:00:00Z'),
  ('pricing_type:free',     'pricing_type', '定价', 'free',        '免费',          '免费',                                'pricing',    true, 'enabled', 1,  '2026-11-14T00:00:00Z'),
  ('pricing_type:paid',     'pricing_type', '定价', 'paid',        '付费',          '付费',                                'pricing',    true, 'enabled', 2,  '2026-11-14T00:00:00Z');
