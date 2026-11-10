#!/usr/bin/env bash
# PostgreSQL backup for the Docker Compose stack: one compressed pg_dump file per run, the newest BACKUP_KEEP kept.
#
#   bash scripts/backup-db.sh                      (from anywhere; the postgres service must be running)
#   BACKUP_DIR=/srv/backups BACKUP_KEEP=30 bash scripts/backup-db.sh
#   DB_USER=german_reader bash scripts/backup-db.sh    if the database owner differs from the container's POSTGRES_USER
#
# pg_dump runs inside the postgres container over its local socket, so no password is needed, stored or passed
# on a command line. Files are readable by their owner only and land in ./backups (git-ignored) by default.
set -euo pipefail

cd "$(dirname "$0")/.."
BACKUP_DIR="${BACKUP_DIR:-backups}"
BACKUP_KEEP="${BACKUP_KEEP:-14}"

umask 077
mkdir -p "$BACKUP_DIR"
file="$BACKUP_DIR/german_reader-$(date -u +%Y%m%dT%H%M%SZ).dump"
partial="$file.partial"
trap 'rm -f "$partial"' EXIT

# Custom format: compressed, and pg_restore can restore single tables from it. it_test only exists on development
# machines (integration tests) and is left out.
docker compose exec -T -e DB_USER="${DB_USER:-}" postgres sh -c \
  'pg_dump -U "${DB_USER:-$POSTGRES_USER}" -d "$POSTGRES_DB" --format=custom --no-owner --no-privileges --exclude-schema=it_test' \
  > "$partial"

# A file pg_restore cannot read is not a backup.
if ! docker compose exec -T postgres pg_restore --list < "$partial" > /dev/null; then
  echo "Backup FAILED: pg_restore cannot read the dump" >&2
  exit 1
fi
mv "$partial" "$file"
echo "Backup written: $file ($(du -h "$file" | cut -f1))"

# Keep the newest BACKUP_KEEP backups.
ls -1t "$BACKUP_DIR"/german_reader-*.dump | tail -n +"$((BACKUP_KEEP + 1))" | while read -r old; do
  rm -f -- "$old"
  echo "Removed old backup: $old"
done
