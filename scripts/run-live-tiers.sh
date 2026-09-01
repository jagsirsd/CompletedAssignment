#!/usr/bin/env bash
# Orchestrates all ten concurrent live-load tiers.
# Threads step 1→7, then hold at 7 while load steps to 1M total:
#
#   100K/1T  200K/2T  300K/3T  400K/4T  500K/5T
#   600K/6T  700K/7T  800K/7T  900K/7T  1M/7T
#
# Usage: ./scripts/run-live-tiers.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OVERALL_START=$(date +%s)

# Format: "total_rows threads"
STEPS=(
  "100000 1"
  "200000 2"
  "300000 3"
  "400000 4"
  "500000 5"
  "600000 6"
  "700000 7"
  "800000 7"
  "900000 7"
  "1000000 7"
)

STEP_NUM=0
for STEP in "${STEPS[@]}"; do
  STEP_NUM=$(( STEP_NUM + 1 ))
  read -r TOTAL THREADS <<< "$STEP"

  echo ""
  echo "========================================"
  echo " Step $STEP_NUM/10 — $TOTAL rows  /  $THREADS threads"
  echo "========================================"
  TIER_START=$(date +%s)

  bash "$SCRIPT_DIR/live-load.sh" "$TOTAL" "$THREADS"

  echo "Step elapsed: $(( $(date +%s) - TIER_START ))s"
  echo "Sleeping 5s for CDC to catch up..."
  sleep 5
done

echo ""
echo "All live-load tiers complete. Total elapsed: $(( $(date +%s) - OVERALL_START ))s"
