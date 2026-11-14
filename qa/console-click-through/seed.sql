-- Demo data the click-through asserts on: categories, outcomes and attributes on a handful of
-- public skills from the open-source sync. Local dev stacks only; idempotent, safe to run twice.
--
--   psql "$SKILLMASTER_DB_URL" -f seed.sql
--
-- It writes `operator` + `confirmed` rows, the one source the read model shows across versions
-- without an extraction run (P1 has the agent path; P0 has no writer of its own).

UPDATE skill SET category_key = 'data-analysis'     WHERE name = 'ab-testing';
UPDATE skill SET category_key = 'content-creation'  WHERE name = 'ai-seo';
UPDATE skill SET category_key = 'ai-collaboration'  WHERE name IN ('brainstorming', 'mattpocock-grilling');
UPDATE skill SET category_key = 'ai-agent'          WHERE name = 'dispatching-parallel-agents';
UPDATE skill SET category_key = 'office-efficiency' WHERE name = 'executing-plans';
UPDATE skill SET category_key = 'dev-programming'   WHERE name IN ('mattpocock-code-review', 'mattpocock-diagnosing-bugs');

UPDATE skill
   SET outcome = '一份可以直接拿去跑的实验设计，含样本量与判定口径。',
       outcome_source = 'operator', outcome_confidence = 1.0,
       outcome_updated_at = '2026-11-14T00:00:00Z'
 WHERE name = 'ab-testing';

UPDATE skill
   SET outcome = '一份拆好的并行任务清单，以及每个 agent 的输入与验收点。',
       outcome_source = 'operator', outcome_confidence = 1.0,
       outcome_updated_at = '2026-11-14T00:00:00Z'
 WHERE name = 'dispatching-parallel-agents';

UPDATE skill
   SET outcome = '一份按严重度排好的代码审查意见，每条都指到具体改动。',
       outcome_source = 'operator', outcome_confidence = 1.0,
       outcome_updated_at = '2026-11-14T00:00:00Z'
 WHERE name = 'mattpocock-code-review';

-- Attributes: three on one skill (a full row), four on another (three + `+N`), and the two
-- defaults on a third to prove the read model hides `prompt-only` / `free`.
INSERT INTO skill_label (id, skill_id, definition_id, source, confidence, evidence, vocabulary_version,
                         review_state, created_at, updated_at)
SELECT 'demo:' || s.name || ':' || d.id, s.id, d.id, 'operator', 1.0, '{}'::jsonb, 'v65-demo',
       'confirmed', '2026-11-14T00:00:00Z', '2026-11-14T00:00:00Z'
  FROM skill s
  JOIN label_definition d ON d.id IN ('runtime:python', 'runtime:online-service', 'pricing_type:paid')
 WHERE s.name = 'ab-testing'
ON CONFLICT (skill_id, definition_id) DO NOTHING;

INSERT INTO skill_label (id, skill_id, definition_id, source, confidence, evidence, vocabulary_version,
                         review_state, created_at, updated_at)
SELECT 'demo:' || s.name || ':' || d.id, s.id, d.id, 'operator', 1.0, '{}'::jsonb, 'v65-demo',
       'confirmed', '2026-11-14T00:00:00Z', '2026-11-14T00:00:00Z'
  FROM skill s
  JOIN label_definition d ON d.id IN ('runtime:node', 'runtime:api-key')
 WHERE s.name = 'ai-seo'
ON CONFLICT (skill_id, definition_id) DO NOTHING;

INSERT INTO skill_label (id, skill_id, definition_id, source, confidence, evidence, vocabulary_version,
                         review_state, created_at, updated_at)
SELECT 'demo:' || s.name || ':' || d.id, s.id, d.id, 'operator', 1.0, '{}'::jsonb, 'v65-demo',
       'confirmed', '2026-11-14T00:00:00Z', '2026-11-14T00:00:00Z'
  FROM skill s
  JOIN label_definition d ON d.id IN ('runtime:python', 'runtime:node', 'runtime:browser', 'runtime:macos')
 WHERE s.name = 'dispatching-parallel-agents'
ON CONFLICT (skill_id, definition_id) DO NOTHING;

INSERT INTO skill_label (id, skill_id, definition_id, source, confidence, evidence, vocabulary_version,
                         review_state, created_at, updated_at)
SELECT 'demo:' || s.name || ':' || d.id, s.id, d.id, 'operator', 1.0, '{}'::jsonb, 'v65-demo',
       'confirmed', '2026-11-14T00:00:00Z', '2026-11-14T00:00:00Z'
  FROM skill s
  JOIN label_definition d ON d.id IN ('runtime:shell', 'runtime:docker', 'pricing_type:paid')
 WHERE s.name = 'executing-plans'
ON CONFLICT (skill_id, definition_id) DO NOTHING;

INSERT INTO skill_label (id, skill_id, definition_id, source, confidence, evidence, vocabulary_version,
                         review_state, created_at, updated_at)
SELECT 'demo:' || s.name || ':' || d.id, s.id, d.id, 'operator', 1.0, '{}'::jsonb, 'v65-demo',
       'confirmed', '2026-11-14T00:00:00Z', '2026-11-14T00:00:00Z'
  FROM skill s
  JOIN label_definition d ON d.id IN ('runtime:prompt-only', 'pricing_type:free')
 WHERE s.name = 'brainstorming'
ON CONFLICT (skill_id, definition_id) DO NOTHING;

-- The account running the click-through needs one public tag of its own, or the public-tags page
-- is empty and those assertions pass vacuously. `v65-demo` is the local demo account the README
-- names; on a stack without it this block is a no-op.
DO $$
DECLARE
  uid text;
  ns  text;
  sid text := 'demo-my-tagged-skill';
BEGIN
  SELECT id INTO uid FROM app_user WHERE handle = 'v65-demo';
  IF uid IS NULL THEN RETURN; END IF;
  SELECT id INTO ns FROM namespace WHERE owner_user_id = uid ORDER BY created_at LIMIT 1;
  IF ns IS NULL THEN RETURN; END IF;

  INSERT INTO skill (id, namespace_id, name, description, frontmatter, created_by,
                     created_at, updated_at, visibility)
       VALUES (sid, ns, 'my-tagged-skill', '点选验收用的自己的 skill，带一个公开标签。',
               '{}', uid, '2026-11-14T00:00:00Z', '2026-11-14T00:00:00Z', 'private')
  ON CONFLICT (id) DO NOTHING;

  INSERT INTO skill_public_tag (skill_id, tag_key, tag_name, created_at)
       VALUES (sid, 'experiment', '实验', '2026-11-14T00:00:00Z')
  ON CONFLICT (skill_id, tag_key) DO NOTHING;
END $$;

SELECT s.name, s.category_key, count(l.id) AS labels
  FROM skill s
  LEFT JOIN skill_label l ON l.skill_id = s.id
 WHERE s.deleted_at IS NULL AND s.visibility = 'public'
 GROUP BY s.name, s.category_key
HAVING count(l.id) > 0 OR s.category_key IS NOT NULL
 ORDER BY s.category_key, s.name;
