#!/usr/bin/env bash
#
# v65 category migration — read-only dry run.
#
# Runs V51 inside one transaction against a database that is still at V50, prints what it would
# change, then ROLLBACKs. Nothing persists: PostgreSQL's DDL is transactional, so no table, row or
# Flyway history entry survives the rollback. The real run is Flyway; this is the preview A10
# asks for, and the rollback statement lives in the migration's own comment.
#
# Usage: dry-run-v65-category-migration.sh [database]
#        SKILLMASTER_DB_NAME=skillmaster_v65 dry-run-v65-category-migration.sh
#        PSQL=/path/to/psql dry-run-v65-category-migration.sh skillmaster_test_v65
#
# Refuses to run when V51 is already applied (the tables exist): the point is to look before the
# migration happens, not to replay it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATION="$SCRIPT_DIR/../src/main/resources/db/migration/V51__public_labels_and_thirteen_shelves.sql"
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
  "SELECT to_regclass('public.skill_category_mapping') IS NOT NULL")"
if [ "$ALREADY" = "t" ]; then
  echo "V51 is already applied to '$DB_NAME' (skill_category_mapping exists); nothing to preview." >&2
  exit 1
fi

"$PSQL" -w -d "$DB_NAME" -v ON_ERROR_STOP=1 <<SQL
BEGIN;
\i '$MIGRATION'

\echo '--- old word -> new shelf ---'
SELECT old_key, new_key, old_state, old_sort_order
  FROM skill_category_mapping ORDER BY old_key;

\echo '--- 13 new shelves ---'
SELECT key, name, sort_order
  FROM skill_category WHERE state = 'enabled' ORDER BY sort_order;

-- V51 has already moved every skill onto its new shelf, so the old keys survive only in the
-- snapshot. Counting from `skill.category_key = old_key` here would print 0 for every shelf.
-- The list starts from the 13 enabled shelves, not from the mapping: the six shelves with no old
-- word still get a row, so "0" is a shown answer rather than a missing one.
\echo '--- skills that would move, per new shelf ---'
SELECT c.key AS new_key, c.sort_order, count(snap.skill_id) AS skills
  FROM skill_category c
  LEFT JOIN skill_category_mapping m ON m.new_key = c.key
  LEFT JOIN skill_category_assignment_snapshot snap ON snap.old_key = m.old_key
 WHERE c.state = 'enabled'
 GROUP BY c.key, c.sort_order
 ORDER BY c.sort_order;

\echo '--- skills with no shelf (unchanged) ---'
SELECT count(*) AS skills FROM skill WHERE category_key IS NULL;

ROLLBACK;
SQL

echo "Dry run complete: nothing was written."
