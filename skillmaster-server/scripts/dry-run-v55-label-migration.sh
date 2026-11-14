#!/usr/bin/env bash
#
# v72 final-label migration — read-only dry run.
#
# Runs V55 inside one transaction against a database that is still on the pre-v72 label shape,
# prints what it would remove, then ROLLBACKs. PostgreSQL's DDL is transactional, so no table, row
# or Flyway history entry survives the rollback. The real run is Flyway.
#
# Usage: dry-run-v55-label-migration.sh [database]
#        SKILLMASTER_DB_NAME=skillmaster_v72 dry-run-v55-label-migration.sh
#        PSQL=/path/to/psql dry-run-v55-label-migration.sh skillmaster_test_v72
#
# Refuses to run when V55 is already applied (the old columns are gone): the point is to look
# before the migration happens, not to replay it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATION="$SCRIPT_DIR/../src/main/resources/db/migration/V55__final_label_tables.sql"
DB_NAME="${1:-${SKILLMASTER_DB_NAME:-skillmaster}}"
PSQL="${PSQL:-psql}"

if [ ! -f "$MIGRATION" ]; then
  echo "Migration not found: $MIGRATION" >&2
  exit 1
fi

if ! command -v "$PSQL" >/dev/null 2>&1; then
  echo "psql not found on PATH. Install PostgreSQL, or set PSQL to a client binary." >&2
  exit 1
fi

if ! "$PSQL" -w -d "$DB_NAME" -tAc "SELECT 1" >/dev/null 2>&1; then
  echo "Cannot reach database '$DB_NAME' (tried: $PSQL -w -d $DB_NAME)." >&2
  exit 1
fi

ALREADY="$("$PSQL" -w -d "$DB_NAME" -tAc \
  "SELECT to_regclass('public.label_extraction_run') IS NULL")"
if [ "$ALREADY" = "t" ]; then
  echo "V55 is already applied to '$DB_NAME' (label_extraction_run is gone); nothing to preview." >&2
  exit 1
fi

"$PSQL" -w -d "$DB_NAME" -v ON_ERROR_STOP=1 <<SQL
BEGIN;
\i '$MIGRATION'

\echo '--- what the migration kept ---'
SELECT 'skill_label rows' AS fact, count(*)::text AS value FROM skill_label
UNION ALL
SELECT 'skills with an outcome', count(*)::text FROM skill WHERE outcome IS NOT NULL;

\echo '--- old columns are gone ---'
SELECT table_name, column_name
  FROM information_schema.columns
 WHERE table_schema = 'public'
   AND ((table_name = 'skill_label' AND column_name IN ('source', 'confidence', 'review_state',
        'evaluated_version_id', 'evidence', 'run_id'))
     OR (table_name = 'skill' AND column_name LIKE 'outcome_%'))
 ORDER BY table_name, column_name;

\echo '(no rows above means the columns were dropped)'
ROLLBACK;

\echo '--- rolled back: nothing persisted ---'
SQL
