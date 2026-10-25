#!/bin/sh
# Periodic PostgreSQL backups for the compose "backup" service.
# Writes custom-format dumps (pg_restore-able) to $BACKUP_DIR and deletes
# ones older than $BACKUP_KEEP_DAYS. Connection comes from the standard
# PG* variables (PGHOST, PGUSER, PGPASSWORD, PGDATABASE).
set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
INTERVAL_HOURS="${BACKUP_INTERVAL_HOURS:-24}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"

backup_once() {
    stamp=$(date +%Y%m%d-%H%M%S)
    target="$BACKUP_DIR/netadmin-$stamp.dump"
    # Write to a temp name first so a half-written dump never looks complete.
    if pg_dump --format=custom --no-owner --file="$target.partial"; then
        mv "$target.partial" "$target"
        echo "$(date -Iseconds) backup ok: $target ($(du -h "$target" | cut -f1))"
    else
        rm -f "$target.partial"
        echo "$(date -Iseconds) backup FAILED" >&2
        return 1
    fi
    find "$BACKUP_DIR" -name 'netadmin-*.dump' -type f -mtime +"$KEEP_DAYS" -print -delete
}

mkdir -p "$BACKUP_DIR"
if [ "${1:-}" = "--once" ]; then
    backup_once
    exit $?
fi
while true; do
    backup_once || true
    sleep $((INTERVAL_HOURS * 3600))
done
