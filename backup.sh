#!/usr/bin/env bash
# Backup del database Supabase garsal-apps
# Uso: bash backup.sh
# Richiede: pg_dump installato localmente

set -e

DB_HOST="db.jajlmmdsjlvzgcxiiypk.supabase.co"
DB_PORT=5432
DB_USER="postgres"
DB_NAME="postgres"

# Directory dove salvare i backup (cartella corrente se non esiste backups/)
BACKUP_DIR="$(dirname "$0")/backups"
mkdir -p "$BACKUP_DIR"

TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
FILENAME="${BACKUP_DIR}/backup_${TIMESTAMP}.sql"

# Chiedi la password se non è già in ambiente
if [ -z "$PGPASSWORD" ]; then
  read -s -p "Password database Supabase: " PGPASSWORD
  echo
  export PGPASSWORD
fi

echo "▶ Connessione a ${DB_HOST}..."

pg_dump \
  --host="${DB_HOST}" \
  --port="${DB_PORT}" \
  --username="${DB_USER}" \
  --dbname="${DB_NAME}" \
  --no-password \
  --format=plain \
  --no-owner \
  --no-acl \
  --schema=public \
  --file="${FILENAME}"

SIZE=$(du -sh "${FILENAME}" | cut -f1)
echo "✓ Backup salvato: ${FILENAME} (${SIZE})"
