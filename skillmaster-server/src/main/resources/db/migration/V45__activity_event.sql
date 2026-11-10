-- v58: personal usage history (M17). The detail log is weekly-partitioned on `at`; the owner's
-- per-person table is a daily rollup. Both belong to M17 and no other module names them.
--
-- **No primary key on the log.** It is append-only: nothing reads or updates it by id, and a
-- primary key on a partitioned table must carry the partition key, which would add a unique index
-- to every partition for a property nobody asks for. Identity still makes ids distinct, and ties
-- are broken with `id DESC`.
--
-- `at` is the RFC3339 UTC string every other table in this schema uses. It is the partition key
-- because every online query already carries "the last 90 days", so pruning is free.
CREATE TABLE activity_event (
  id              BIGINT GENERATED ALWAYS AS IDENTITY,
  actor_user_id   TEXT NOT NULL,
  owner_user_id   TEXT,
  kind            TEXT NOT NULL,
  skill_id        TEXT,
  skill_address   TEXT,
  version_id      TEXT,
  version_label   TEXT,
  target          TEXT,
  detail          TEXT NOT NULL DEFAULT '{}',
  search_event_id BIGINT,
  search_rank     INT,
  at              TEXT NOT NULL
) PARTITION BY RANGE (at);

COMMENT ON TABLE activity_event IS
  'One row per search, browse, view, file read, invoke, download or sign-in. Append-only; 90 days online, then the partition is exported and dropped.';
COMMENT ON COLUMN activity_event.actor_user_id IS
  'The account whose action this was. Every query filters on it; anonymous requests are not recorded.';
COMMENT ON COLUMN activity_event.owner_user_id IS
  'The skill owner at write time, for internal analysis. Not indexed: the owner view reads the daily rollup.';
COMMENT ON COLUMN activity_event.kind IS
  'search | browse | skill_view | version_list | version_view | file_list | file_read | invoke | download | login | logout. Open string: a new kind needs no migration.';
COMMENT ON COLUMN activity_event.skill_address IS
  'The ns/name as it was spelled at write time. A later rename leaves history readable.';
COMMENT ON COLUMN activity_event.target IS
  'The query text or the relative path, truncated by the application.';
COMMENT ON COLUMN activity_event.detail IS
  'Per-kind JSON: search results and filters, file path, download pin, login method.';
COMMENT ON COLUMN activity_event.search_event_id IS
  'Invoke only: the search this call was attributed to, or null for a direct call.';
COMMENT ON COLUMN activity_event.search_rank IS
  'Invoke only: the skill''s rank in that search''s results.';

-- 1. The personal read path: one account's last 90 days, newest first. The two kinds are the only
-- ones the page and its stats ever read, so browse/read/download/sign-in volume stays out.
-- `INCLUDE (kind, skill_id)` lets the stats aggregate from the index when the planner chooses.
CREATE INDEX activity_event_actor_at
  ON activity_event (actor_user_id, at DESC, id DESC)
  INCLUDE (kind, skill_id)
  WHERE kind IN ('search', 'invoke');

-- 2. Correlation and page merging: same account, a window of at most 30 minutes, searches only.
CREATE INDEX activity_event_actor_search
  ON activity_event (actor_user_id, at DESC)
  WHERE kind = 'search';

-- 3. Internal inspection by skill. No (owner, skill) variant: owner is an attribute of the skill,
-- so "this skill" is the same set, and the owner view reads the rollup instead.
CREATE INDEX activity_event_skill_at
  ON activity_event (skill_id, at DESC, id DESC);

-- 4. The detail page's fold: fetch every invoke attached to the searches on one page.
CREATE INDEX activity_event_search
  ON activity_event (search_event_id)
  WHERE kind = 'invoke' AND search_event_id IS NOT NULL;

-- The owner's per-person table. Two measures only: invokes (adoption) and versions submitted
-- (contribution). Reads and file writes stay in the detail log; they do not answer a management
-- question. The primary key makes the daily upsert idempotent.
CREATE TABLE skill_collaborator_day (
  skill_id      TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  actor_user_id TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  -- Nullable on purpose: a row created from submissions alone has no activity row to copy the
  -- owner from. The owner view filters by skill_id, so nothing reads this as a key.
  owner_user_id TEXT,
  day           TEXT NOT NULL,
  invokes       INT NOT NULL DEFAULT 0,
  versions      INT NOT NULL DEFAULT 0,
  last_at       TEXT NOT NULL,
  PRIMARY KEY (skill_id, actor_user_id, day)
);

COMMENT ON TABLE skill_collaborator_day IS
  'The owner view: one row per skill, person and UTC day. Rebuilt nightly from activity_event (invokes) and audit_event (submitted versions).';

-- Read shape: one skill × the last 90 days. `owner_user_id` is already known by the caller, so it
-- is carried in the index rather than leading it.
CREATE INDEX skill_collaborator_day_skill
  ON skill_collaborator_day (skill_id, day DESC)
  INCLUDE (actor_user_id, invokes, versions, last_at);
