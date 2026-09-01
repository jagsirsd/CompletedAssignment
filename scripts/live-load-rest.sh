#!/usr/bin/env bash
# Concurrent REST API live load — the HTTP/2 counterpart to live-load.sh.
# Runs RestLoadClient, a small Java HTTP/2 load generator
# (src/main/java/com/example/demo/loadtest/RestLoadClient.java) bundled in
# the app's own jar. It opens ONE persistent HttpClient (HTTP/2, auto-upgraded
# via h2c against Http2Config's Tomcat connector) and fires POST /api/items
# calls concurrently across a thread pool, instead of spawning a new client
# process (and connection) per request. Exercises the full CDC round-trip:
#   Spring Boot → PostgreSQL WAL → Debezium → Kafka → CdcEventConsumer → Redis
#
# Usage:   ./scripts/live-load-rest.sh <total_rows> <threads>
# Example: ./scripts/live-load-rest.sh 100000 3

set -euo pipefail

TOTAL=${1:?Usage: live-load-rest.sh <total_rows> <threads>}
THREADS=${2:-1}
HTTP_PORT=${HTTP_PORT:-8080}

START=$(date +%s)

docker compose exec -T app java -cp app.jar \
  -Dloader.main=com.example.demo.loadtest.RestLoadClient \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  "$TOTAL" "$THREADS" localhost "$HTTP_PORT"

FINAL=$(docker compose exec -T db \
  psql -U user mydb -t -A -c "SELECT COUNT(*) FROM items")
ELAPSED=$(( $(date +%s) - START ))
echo "Live load done. Table total: $FINAL  |  Elapsed: ${ELAPSED}s"
