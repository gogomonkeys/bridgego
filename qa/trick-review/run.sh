#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d /tmp/bridge-trick-oracle.XXXXXX)
trap 'rm -rf "$work"' EXIT
classes=${QA_GAME_CLASSES:-backend/target/classes}
source=backend/src/main/java/com/bridge/room/TrickEngine.java
java qa/trick-review/Compile.java "$classes" "$work" "$source" qa/trick-review/TrickOracleReview.java
java -Xmx512m -cp "$work:$classes" com.bridge.room.TrickOracleReview "$source" qa/trick-review/results-rightward.json
