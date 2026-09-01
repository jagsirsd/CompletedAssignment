#!/usr/bin/env bash
# Concurrent API live load — spawns <threads> background processes each
# firing grpcurl CreateItem calls, exercising the full CDC round-trip:
#   Spring Boot → PostgreSQL WAL → Debezium → Kafka → CdcEventConsumer → Redis
#
# Usage:   ./scripts/live-load.sh <total_rows> <threads>
# Example: ./scripts/live-load.sh 100000 3

set -euo pipefail

TOTAL=${1:?Usage: live-load.sh <total_rows> <threads>}
THREADS=${2:-1}
APP_HOST=${APP_HOST:-localhost}
GRPC_PORT=${GRPC_PORT:-9090}

PER_THREAD=$(( TOTAL / THREADS ))
REMAINDER=$(( TOTAL - PER_THREAD * THREADS ))
START=$(date +%s)

echo "Live load: $TOTAL rows  |  $THREADS threads  |  ~$PER_THREAD rows/thread"

run_thread() {
  local t=$1
  local count=$2
  local ok=0
  local fail=0

  for i in $(seq 1 "$count"); do
    if grpcurl -plaintext -d "{\"name\":\"live-t${t}-${i}-${RANDOM}\",\"description\":\"desc-t${t}-${i}-${RANDOM}\"}" \
        "${APP_HOST}:${GRPC_PORT}" item.v1.ItemService/CreateItem >/dev/null 2>&1; then
      ok=$(( ok + 1 ))
    else
      fail=$(( fail + 1 ))
    fi
  done

  echo "  Thread $t done — ok: $ok  failed: $fail  (${count} total)"
}

# Give the last thread the remainder rows so total always adds up exactly
pids=()
for t in $(seq 1 "$THREADS"); do
  if [ "$t" -eq "$THREADS" ]; then
    ROWS=$(( PER_THREAD + REMAINDER ))
  else
    ROWS=$PER_THREAD
  fi
  run_thread "$t" "$ROWS" &
  pids+=($!)
done

for pid in "${pids[@]}"; do
  wait "$pid"
done

FINAL=$(docker compose exec -T db \
  psql -U user mydb -t -A -c "SELECT COUNT(*) FROM items")
ELAPSED=$(( $(date +%s) - START ))
echo "Live load done. Table total: $FINAL  |  Elapsed: ${ELAPSED}s"
