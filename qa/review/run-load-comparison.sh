#!/usr/bin/env bash
# Controlled equivalent idle protocol workload; diagnostic, not replacement acceptance.
set -uo pipefail
cd "$(dirname "$0")/../.."
node qa/build-client-test.mjs
for version in 0.2.1 0.3.0; do
 work=$(mktemp -d /tmp/bridge-load-${version}.XXXX)
 cp "backend/target/bridge-room-successor-${version}.jar" "$work/backend.jar"
 sha256sum "$work/backend.jar" > "$work/artifact.txt"
 java -Xlog:gc*:file="$work/gc.log":time,uptime,level,tags -jar "$work/backend.jar" >"$work/backend.log" 2>&1 & backend=$!
 (cd frontend; exec node node_modules/vite/bin/vite.js --host 127.0.0.1) >"$work/vite.log" 2>&1 & frontend=$!
 trap 'kill "$backend" "$frontend" ${monitor:-} ${clients:-} 2>/dev/null || true; wait 2>/dev/null || true' EXIT
 trap 'exit 130' INT TERM
 for attempt in $(seq 1 100);do curl -s http://127.0.0.1:5173/api/rooms | grep -q UNAUTHORIZED && break;sleep .25;done
 (while true;do date -u +%FT%TZ;ps -eo pid,ppid,pcpu,rss,nlwp,etime,comm; sleep 2;done) >"$work/process.log" 2>&1 & monitor=$!
 clients=""
 for i in 1 2 3;do QA_IDLE_SECONDS=600 QA_RESULT_FILE="$work/client-$i-results.json" node qa/client-transport.mjs >"$work/client-$i.log" 2>&1 & clients="$clients $!";done
 QA_DIAGNOSTIC=1 QA_VERSION="load-${version}" QA_EXPECT_FIXED=1 QA_DURATION=600 python qa/heartbeat-stability.py >"$work/native.log" 2>&1 & clients="$clients $!"
 echo "CONTROLLED_LOAD_STARTED version=$version evidence=$work"
 for pid in $clients;do wait "$pid";echo "process=$pid status=$?" >>"$work/process-exits.txt";done
 kill "$backend" "$frontend" "$monitor" 2>/dev/null || true
 wait "$backend" "$frontend" "$monitor" 2>/dev/null || true
 echo "CONTROLLED_LOAD_COMPLETED version=$version evidence=$work"
 cat "$work/process-exits.txt"
 clients="";trap - EXIT
 trap 'exit 130' INT TERM
 # Raw diagnostic logs stay in /tmp; never package runtime session data.
done
