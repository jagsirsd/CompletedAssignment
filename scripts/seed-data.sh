#!/usr/bin/env bash
# Cumulative bulk seed — inserts rows until the table reaches <target_rows>.
# Uses PostgreSQL generate_series + md5() in 500K-row batches so Debezium
# can stream CDC events between commits rather than after one giant transaction.
#
# Usage:   ./scripts/seed-data.sh <target_rows>
# Example: ./scripts/seed-data.sh 5000000

set -euo pipefail

TARGET=${1:?Usage: seed-data.sh <target_row_count>}
BATCH=500000
START=$(date +%s)

CURRENT=$(docker compose exec -T db \
  psql -U user mydb -t -A -c "SELECT COUNT(*) FROM items")

DELTA=$(( TARGET - CURRENT ))

if [ "$DELTA" -le 0 ]; then
  echo "Already at $CURRENT rows (target $TARGET). Nothing to do."
  exit 0
fi

echo "Current: $CURRENT  |  Target: $TARGET  |  Delta: $DELTA  |  Batch: $BATCH"

INSERTED=0
while [ "$INSERTED" -lt "$DELTA" ]; do
  CHUNK=$(( DELTA - INSERTED < BATCH ? DELTA - INSERTED : BATCH ))

  docker compose exec -T db psql -U user mydb -c "
    INSERT INTO items (name, description)
    SELECT
      'item-' || gs || '-' || substr(md5(gs::text), 1, 10),
      'desc-'  || gs || '-' || substr(md5(gs::text || 'dx'), 1, 20)
    FROM generate_series(1, ${CHUNK}) AS s(gs);
  " > /dev/null

  INSERTED=$(( INSERTED + CHUNK ))
  PCT=$(( (INSERTED * 100) / DELTA ))
  echo "  Inserted $INSERTED / $DELTA  (${PCT}%)"
done

FINAL=$(docker compose exec -T db \
  psql -U user mydb -t -A -c "SELECT COUNT(*) FROM items")
ELAPSED=$(( $(date +%s) - START ))
echo "Done. Total rows in table: $FINAL  |  Elapsed: ${ELAPSED}s"
