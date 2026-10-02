#!/usr/bin/env bash
#
# Creates the integration-test database if it does not already exist. Idempotent, so it is
# safe to run before every build.
#
# Why a real PostgreSQL instead of Testcontainers or an embedded server: the design leans on
# PostgreSQL-specific behaviour (partial indexes, ON CONFLICT, bytea, collation, and later
# pg_bigm), and testing against a different server than the one we run on would weaken
# exactly the guarantees we care about.
#
# This script is now the *second* way to get that database. The first is the repository's
# compose.yaml, whose initdb script creates the same test database; this one is for machines
# that would rather run their own PostgreSQL than a container.
#
# The tests themselves do NOT run this script; they fail loudly and point here, because a
# silently skipped integration suite is a suite that rots.
set -euo pipefail

DB_NAME="${SKILLMASTER_TEST_DB_NAME:-skillmaster_test}"
PSQL="${PSQL:-psql}"

# The name is interpolated into DDL below, so refuse anything that is not a plain identifier.
if [[ ! "$DB_NAME" =~ ^[A-Za-z_][A-Za-z0-9_]{0,62}$ ]]; then
  echo "Refusing: SKILLMASTER_TEST_DB_NAME='$DB_NAME' is not a valid identifier." >&2
  exit 1
fi

if ! command -v "$PSQL" >/dev/null 2>&1; then
  echo "psql not found on PATH. Install PostgreSQL, or set PSQL to a client binary." >&2
  exit 1
fi

if ! "$PSQL" -w -d postgres -tAc "SELECT 1" >/dev/null 2>&1; then
  echo "Cannot reach PostgreSQL (tried: $PSQL -w -d postgres)." >&2
  echo "Start it first, e.g. 'brew services start postgresql@16'." >&2
  exit 1
fi

if [ "$("$PSQL" -w -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'")" = "1" ]; then
  echo "database '$DB_NAME' already exists"
else
  "$PSQL" -w -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$DB_NAME\""
  echo "created database '$DB_NAME'"
fi
