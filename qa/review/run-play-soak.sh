#!/usr/bin/env bash
# Isolated four-client PLAYING diagnostic; no concurrent compiler/test JVMs.
set -uo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d /tmp/bridge-playing-only.XXXX)
cp backend/target/bridge-room-successor-0.3.0.jar "$work/backend.jar"
sha256sum "$work/backend.jar" > "$work/artifact.txt"
java -Xlog:gc*:file="$work/gc.log":time,uptime,level,tags -jar "$work/backend.jar" >"$work/backend.log" 2>&1 & backend=$!
(cd frontend; exec node node_modules/vite/bin/vite.js --host 127.0.0.1) >"$work/vite.log" 2>&1 & frontend=$!
trap 'kill "$backend" "$frontend" ${monitor:-} ${client:-} 2>/dev/null || true; wait 2>/dev/null || true' EXIT
trap 'exit 130' INT TERM
for attempt in $(seq 1 100);do curl -s http://127.0.0.1:5173/api/rooms | grep -q UNAUTHORIZED && break;sleep .25;done
(while true;do date -u +%FT%TZ;ps -eo pid,ppid,pcpu,rss,nlwp,etime,comm;sleep 2;done) >"$work/process.log" 2>&1 & monitor=$!
echo "PLAYING_ONLY_STARTED evidence=$work"
QA_GAME_IDLE_SECONDS=600 QA_RESULT_FILE=./review/playing-only-results.json node qa/client-auto-play.mjs >"$work/client.log" 2>&1 & client=$!
wait "$client";status=$?
echo "PLAYING_ONLY_COMPLETED status=$status evidence=$work"
cat "$work/client.log"
exit "$status"
