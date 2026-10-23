-- One flat shelf per skill, drawn from a platform vocabulary. v14, ADR 0078.
--
-- The vocabulary is a table rather than an enum because a shelf outlives the code: it can be
-- stopped (no new assignments, existing ones keep working) and retired (removed from every skill
-- and from the picker) without a deploy. It is deliberately flat and has no parent column — a
-- hierarchy needs a product reason and an ADR, not a spare field.
CREATE TABLE skill_category (
  key        TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  state      TEXT NOT NULL DEFAULT 'enabled',
  sort_order INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  CHECK (state IN ('enabled', 'stopped', 'retired')),
  UNIQUE (sort_order)
);

-- One nullable column is the whole assignment: a skill has zero or one category, so a join table
-- with a unique constraint would be a table that can only ever hold one row per skill.
ALTER TABLE skill ADD COLUMN category_key TEXT REFERENCES skill_category(key);

CREATE INDEX idx_skill_category ON skill(category_key) WHERE deleted_at IS NULL;

-- The first-day vocabulary. `sort_order` is the order the picker shows; gaps are not left because
-- nothing renumbers a vocabulary yet. `ops` starts stopped so the interface has a real disabled
-- word to render rather than a state only a test ever produces.
INSERT INTO skill_category (key, name, state, sort_order, created_at) VALUES
  ('doc',      '文档与演示',   'enabled', 1,  '2026-10-23T00:00:00Z'),
  ('data',     '数据与分析',   'enabled', 2,  '2026-10-23T00:00:00Z'),
  ('dev',      '研发与测试',   'enabled', 3,  '2026-10-23T00:00:00Z'),
  ('design',   '设计与界面',   'enabled', 4,  '2026-10-23T00:00:00Z'),
  ('writing',  '写作与翻译',   'enabled', 5,  '2026-10-23T00:00:00Z'),
  ('research', '研究与检索',   'enabled', 6,  '2026-10-23T00:00:00Z'),
  ('office',   '办公与协作',   'enabled', 7,  '2026-10-23T00:00:00Z'),
  ('ops',      '运维与云',     'stopped', 8,  '2026-10-23T00:00:00Z'),
  ('media',    '图像与音视频', 'enabled', 9,  '2026-10-23T00:00:00Z'),
  ('security', '安全与合规',   'enabled', 10, '2026-10-23T00:00:00Z');

COMMENT ON TABLE skill_category IS
  'The platform''s flat category vocabulary (ADR 0078). Platform data: it changes by migration, never by a user. enabled = assignable; stopped = not assignable but existing assignments keep working; retired = removed from every skill and from the picker.';
COMMENT ON COLUMN skill_category.key IS
  'Stable identifier used in addresses and the CLI. Never shown to a person.';
COMMENT ON COLUMN skill_category.name IS
  'The display name. Unique in practice by editorial rule, not by constraint.';
COMMENT ON COLUMN skill_category.state IS
  'enabled / stopped / retired. See the table comment for what each one permits.';
COMMENT ON COLUMN skill_category.sort_order IS
  'Position in the picker and in `skills categories`. Unique so the order cannot become ambiguous.';
COMMENT ON COLUMN skill_category.created_at IS
  'RFC3339 UTC.';
COMMENT ON COLUMN skill.category_key IS
  'The one category this skill is on, or NULL for none. A foreign key rather than free text so a word cannot enter the vocabulary by typo.';
