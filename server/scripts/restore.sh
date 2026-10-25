#!/bin/sh
# Restore a dump made by backup.sh into the running compose database.
#   ./scripts/restore.sh backups/netadmin-20261025-030000.dump
# Stops the API while restoring so nothing writes in between.
set -eu

dump="${1:?usage: $0 backups/netadmin-YYYYmmdd-HHMMSS.dump}"
[ -f "$dump" ] || { echo "no such file: $dump" >&2; exit 1; }
cd "$(dirname "$0")/.."

printf 'Restore %s over the current database? Everything newer is lost. [y/N] ' "$dump"
read -r answer
[ "$answer" = "y" ] || [ "$answer" = "Y" ] || { echo "cancelled"; exit 1; }

docker compose stop api
docker compose exec -T db pg_restore --clean --if-exists --no-owner -U netadmin -d netadmin < "$dump"
docker compose start api
echo "restored $dump"
