#!/usr/bin/env bash
# Pure local scoring oracle; no Maven, network or game server needed.
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d /tmp/bridge-score-oracle.XXXXXX)
trap 'rm -rf "$work"' EXIT
source=backend/src/main/java/com/bridge/scoring/DealScoreCalculator.java
java qa/scoring-review/Compile.java "$work" "$source" qa/scoring-review/ScoringOracleReview.java
java -cp "$work" ScoringOracleReview "$source" qa/scoring-review/results.json
