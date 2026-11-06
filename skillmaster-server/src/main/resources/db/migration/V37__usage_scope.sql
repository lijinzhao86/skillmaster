-- v49: usage metrics become M15's own module and a series per purpose (ADR 0153).
-- `scope` is why the number is kept, not who may read it; v49 writes only 'public'.
ALTER TABLE skill_usage_day ADD COLUMN scope TEXT NOT NULL DEFAULT 'public';

ALTER TABLE skill_usage_day DROP CONSTRAINT skill_usage_day_pkey;
ALTER TABLE skill_usage_day ADD PRIMARY KEY (skill_id, day, account_id, scope);

-- The cumulative series answers "how much is it used", the deduplicated one answers "is anybody
-- using it" (ADR 0152); they are separate because the 90-day purge must not erase the total.
CREATE TABLE skill_usage_total (
  skill_id TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  scope    TEXT NOT NULL,
  calls    BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (skill_id, scope)
);
