#!/usr/bin/env bash
set -e
TARGET="/tmp/demo-target"
REPO="https://github.com/eugenp/tutorials.git"

if [ ! -d "$TARGET" ]; then
  git clone $REPO $TARGET
fi

RESULT=$(npx java-vibe-guard "$TARGET" --json)
CRITICAL=$(echo "$RESULT" | jq '.issues[] | select(.severity=="CRITICAL")')
EXIT_CODE=0

echo "$CRITICAL" | jq -c '.' | while read -r issue; do
  RULE=$(echo "$issue" | jq -r '.ruleId')
  FILE=$(echo "$issue" | jq -r '.file')
  LINE=$(echo "$issue" | jq -r '.line')

  echo "============================"
  echo "RULE: $RULE"
  echo "FILE: $FILE:$LINE"

  case "$RULE" in
    VIBE-001) echo "LAB: https://github.com/Joaquinriosheredia/Java-Production-Labs/tree/main/04_outbox_kafka"
              echo "RUN: cd 04_outbox_kafka && ./benchmark/run-benchmark.sh" ;;
    VIBE-003) echo "LAB: https://github.com/Joaquinriosheredia/Java-Production-Labs/tree/main/07_postgres_tuning"
              echo "RUN: cd 07_postgres_tuning && ./benchmark/run-benchmark.sh" ;;
    VIBE-004) echo "LAB: https://github.com/Joaquinriosheredia/Java-Production-Labs/tree/main/01_virtual_threads"
              echo "RUN: cd 01_virtual_threads && ./benchmark/run-benchmark.sh" ;;
    VIBE-005) echo "LAB: https://github.com/Joaquinriosheredia/Java-Production-Labs/tree/main/05_saga_pattern"
              echo "RUN: cd 05_saga_pattern && ./benchmark/run-benchmark.sh" ;;
    VIBE-006) echo "LAB: https://github.com/Joaquinriosheredia/Java-Production-Labs/tree/main/08_kafka_streams"
              echo "RUN: cd 08_kafka_streams && ./benchmark/run-benchmark.sh" ;;
  esac

  EXIT_CODE=1
done

exit $EXIT_CODE
