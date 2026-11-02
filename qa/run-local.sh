#!/usr/bin/env bash
# Run from any directory after building backend and installing frontend dependencies.
set -euo pipefail
cd "$(dirname "$0")/.."
node qa/build-client-test.mjs
run_dir=$(mktemp -d /tmp/bridge-qa-runtime.XXXXXX)
cp backend/target/bridge-room-successor-0.6.3.jar "$run_dir/backend.jar"
sha256sum "$run_dir/backend.jar" | cut -d" " -f1 > qa/runtime-artifact-sha256.txt
log_dir=$(mktemp -d /tmp/bridge-qa-logs.XXXX)
echo "QA_RUNTIME_LOG_DIRECTORY=$log_dir"
java_args=()
if [ "${QA_TELEMETRY:-0}" = 1 ]; then
 telemetry=$(mktemp -d /tmp/bridge-final-telemetry.XXXX)
 java_args=("-Xlog:gc*:file=$telemetry/gc.log:time,uptime,level,tags")
 echo "QA_TELEMETRY_DIRECTORY=$telemetry"
fi
java "${java_args[@]}" -jar "$run_dir/backend.jar" >"$log_dir/backend.log" 2>&1 & backend=$!
(cd frontend; exec node node_modules/vite/bin/vite.js --host 127.0.0.1) >"$log_dir/vite.log" 2>&1 & frontend=$!
trap 'for pid in $backend $frontend ${monitor:-} ${stability:-} ${waiting:-} ${game:-} ${autoplay:-}; do kill "$pid" 2>/dev/null || true; done; wait 2>/dev/null || true; rm -rf "$run_dir"' EXIT
if [ "${QA_TELEMETRY:-0}" = 1 ]; then
 (while true; do date -u +%FT%TZ; ps -eo pid,ppid,pcpu,rss,nlwp,etime,comm; sleep 2; done) >"$telemetry/process.log" 2>&1 & monitor=$!
fi
ready=false
for attempt in $(seq 1 80); do
 if curl -s http://127.0.0.1:5173/api/rooms | grep -q UNAUTHORIZED; then ready=true; break; fi
 sleep .25
done
$ready || { echo 'Loopback servers did not become ready; inspect printed runtime log directory'; exit 1; }
if [ "${QA_ONLY_SOAK:-0}" != 1 ]; then
python qa/integration.py
python qa/security.py
python qa/game-integration.py
python qa/game-silent-disconnect.py
python qa/play-integration.py
python qa/play-guards.py
python qa/trick-feedback.py
python qa/history-privacy.py
python qa/forfeit-integration.py
python qa/completed-score-forfeit.py
python qa/lifecycle-v04.py
python qa/archive-rollover.py
python qa/operation-timers.py
node qa/client-timer.mjs
fi
if [ "${QA_SERVER_PAUSE_TEST:-0}" = 1 ]; then
 QA_SERVER_PID="$backend" python qa/server-pause.py
fi
if [ "${QA_STABILITY_SECONDS:-0}" -gt 0 ]; then
 QA_VERSION=0.6.3 QA_EXPECT_FIXED=1 QA_DURATION="$QA_STABILITY_SECONDS" python qa/heartbeat-stability.py & stability=$!
 QA_IDLE_SECONDS="$QA_STABILITY_SECONDS" node qa/client-transport.mjs & waiting=$!
 QA_GAME_IDLE_SECONDS="$QA_STABILITY_SECONDS" node qa/client-game.mjs & game=$!
 QA_GAME_IDLE_SECONDS="$QA_STABILITY_SECONDS" node qa/client-auto-play.mjs & autoplay=$!
 wait "$waiting"
 wait "$game"
 wait "$stability"
 wait "$autoplay"
else
 node qa/client-transport.mjs
 node qa/client-game.mjs
fi
