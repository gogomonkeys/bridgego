#!/usr/bin/env bash
# Offline deterministic test against the final built production classes.
set -euo pipefail
cd "$(dirname "$0")/../.."
root="$PWD"
work=$(mktemp -d /tmp/bridge-send-review.XXXXXX)
trap 'rm -rf "$work"' EXIT
(cd "$work"; unzip -q "$root/backend/target/bridge-room-successor-0.6.0.jar" 'BOOT-INF/classes/*' 'BOOT-INF/lib/*')
classpath="$work/BOOT-INF/classes:$work/BOOT-INF/lib/*"
java -cp "$classpath" qa/review/CompileReview.java qa/review/HeartbeatSendDelayReview.java "$work"
java -cp "$work:$classpath" com.bridge.room.HeartbeatSendDelayReview fixed
printf '%s\n' '[{"case":"delayed synchronous send receives full six-second Pong budget","status":"PASS","synthetic_send_seconds":7,"response_budget_seconds":6}]' > qa/review/send-delay-results.json
