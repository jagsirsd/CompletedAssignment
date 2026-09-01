#!/usr/bin/env bash
# Concurrent API live load — runs GrpcLoadClient, a small Java gRPC load
# generator (src/main/java/com/example/demo/loadtest/GrpcLoadClient.java)
# bundled in the app's own jar. It opens ONE persistent, HTTP/2-multiplexed
# channel and fires CreateItem calls concurrently across a thread pool,
# instead of spawning a new client process (and connection) per request.
# Exercises the full CDC round-trip:
#   Spring Boot → PostgreSQL WAL → Debezium → Kafka → CdcEventConsumer → Redis
#
# Usage:   ./scripts/live-load.sh <total_rows> <threads>
# Example: ./scripts/live-load.sh 100000 3

set -euo pipefail

TOTAL=${1:?Usage: live-load.sh <total_rows> <threads>}
THREADS=${2:-1}
GRPC_PORT=${GRPC_PORT:-9090}

START=$(date +%s)

docker compose exec -T app java -cp app.jar \
  -Dloader.main=com.example.demo.loadtest.GrpcLoadClient \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  "$TOTAL" "$THREADS" localhost "$GRPC_PORT"

FINAL=$(docker compose exec -T db \
  psql -U user mydb -t -A -c "SELECT COUNT(*) FROM items")
ELAPSED=$(( $(date +%s) - START ))
echo "Live load done. Table total: $FINAL  |  Elapsed: ${ELAPSED}s"
