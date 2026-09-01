#!/usr/bin/env bash
# Runs all seven cumulative bulk-seed tiers in sequence:
#   1M → 5M → 10M → 20M → 30M → 40M → 50M
# Pauses 10s between tiers to let the CDC pipeline drain.
#
# Usage: ./scripts/run-seed-tiers.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TIERS=(1000000 5000000 10000000 20000000 30000000 40000000 50000000)
OVERALL_START=$(date +%s)

for TARGET in "${TIERS[@]}"; do
  echo ""
  echo "========================================"
  echo " Seeding to ${TARGET} rows"
  echo "========================================"
  TIER_START=$(date +%s)
  bash "$SCRIPT_DIR/seed-data.sh" "$TARGET"
  echo "Tier elapsed: $(( $(date +%s) - TIER_START ))s"
  echo "Sleeping 10s for CDC pipeline to drain..."
  sleep 10
done

echo ""
echo "All bulk tiers complete. Total elapsed: $(( $(date +%s) - OVERALL_START ))s"
