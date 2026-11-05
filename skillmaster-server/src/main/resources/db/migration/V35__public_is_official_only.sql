-- Only namespaces marked official may publish a skill (ADR 0146).
--
-- **The marker is a column, not a configuration string.** A namespace's slug is the account's
-- handle, and registration lets a person choose their handle; a list of slugs in configuration
-- would therefore let anybody claim an "official" name by registering it. This column is written
-- only here and by an operator's migration/seed; registration leaves it false.
ALTER TABLE namespace ADD COLUMN official BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN namespace.official IS
  'Whether skills in this namespace may be public (ADR 0146). Only migrations/operators set it; '
  'registration leaves it false. The runtime reads this column, never a configured slug.';

-- The one official namespace at the moment this version ships: the reserved one V2 seeds.
UPDATE namespace SET official = TRUE WHERE slug = 'skillmaster';

-- Rows that were public under the old rule must leave the public catalogue when the rule changes.
-- The write path now refuses `public` outside an official namespace, while `private` still works, so
-- authors keep a path back to private after this migration. The rows are not deleted; their authors
-- keep the content and can still use it privately.
UPDATE skill s
   SET visibility = 'private',
       public_rung = NULL,
       updated_at = to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
  FROM namespace n
 WHERE s.namespace_id = n.id
   AND s.visibility = 'public'
   AND n.official = FALSE;
