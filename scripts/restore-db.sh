#!/usr/bin/env bash
# Restores a backup into a SEPARATE database and compares its row counts with the live database.
# The live database is never touched; replacing it is a manual step (see README, "Backups").
#
#   bash scripts/restore-db.sh backups/german_reader-20261109T030000Z.dump
#   KEEP_RESTORE=1 bash scripts/restore-db.sh <file>   keep the restored database (to switch to it afterwards)
#
# The restored database is called german_reader_restore_check (RESTORE_DB to change; it must not be the live one).
# An existing database of that name is replaced only if its name ends in _restore_check.
# DB_USER overrides the database owner if it differs from the container's POSTGRES_USER.
set -euo pipefail

cd "$(dirname "$0")/.."
file="${1:?usage: scripts/restore-db.sh <backup.dump>}"
target="${RESTORE_DB:-german_reader_restore_check}"
[ -f "$file" ] || { echo "No such file: $file" >&2; exit 1; }
[[ "$target" =~ ^[a-z_][a-z0-9_]*$ ]] || { echo "RESTORE_DB must be a plain lower-case name" >&2; exit 1; }

# Runs SQL from stdin in the container as the database owner, over the local socket (no password). $1 = database.
psql_in() {
  docker compose exec -T -e DB_USER="${DB_USER:-}" postgres \
    sh -c 'psql -v ON_ERROR_STOP=1 -U "${DB_USER:-$POSTGRES_USER}" -d "$1" -tA' sh "$1"
}

live=$(docker compose exec -T postgres sh -c 'printf %s "$POSTGRES_DB"')
if [ "$target" = "$live" ]; then
  echo "Refusing to restore into the live database '$live'." >&2
  exit 1
fi

exists=$(echo "SELECT 1 FROM pg_database WHERE datname = '$target'" | psql_in postgres)
if [ -n "$exists" ]; then
  if [[ "$target" != *_restore_check ]]; then
    echo "Database '$target' already exists; drop it yourself or choose another RESTORE_DB." >&2
    exit 1
  fi
  echo "DROP DATABASE \"$target\"" | psql_in postgres > /dev/null
fi
echo "CREATE DATABASE \"$target\"" | psql_in postgres > /dev/null

echo "Restoring $file into '$target'..."
docker compose exec -T -e DB_USER="${DB_USER:-}" postgres \
  sh -c 'pg_restore -U "${DB_USER:-$POSTGRES_USER}" -d "$1" --no-owner --no-privileges --exit-on-error' sh "$target" \
  < "$file"

counts="
SELECT 'schema version', max(version::int)::text FROM flyway_schema_history WHERE success
UNION ALL SELECT 'app_user', count(*)::text FROM app_user
UNION ALL SELECT 'reading_document', count(*)::text FROM reading_document
UNION ALL SELECT 'vocabulary_entry', count(*)::text FROM vocabulary_entry
UNION ALL SELECT 'vocabulary_daily_stats', count(*)::text FROM vocabulary_daily_stats
UNION ALL SELECT 'translation_cache', count(*)::text FROM translation_cache"

echo
echo "Rows (restored | live now; live may have changed since the backup):"
paste -d'|' <(echo "$counts" | psql_in "$target") <(echo "$counts" | psql_in "$live" | cut -d'|' -f2) \
  | awk -F'|' '{ printf "  %-24s %10s | %s\n", $1, $2, $3 }'

if [ "${KEEP_RESTORE:-0}" = "1" ]; then
  echo
  echo "Kept database '$target'. See README (Backups) to switch to it."
else
  echo "DROP DATABASE \"$target\"" | psql_in postgres > /dev/null
  echo
  echo "Restore check OK; '$target' dropped again."
fi
