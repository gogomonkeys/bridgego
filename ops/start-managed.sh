#!/bin/bash
# Default path remains the original game command. No secrets enter arguments or shell output.
set -u
case "${BRIDGE_READONLY_STARTUP_ENABLED:-false}" in
  ''|false) exec java -jar /app/bridgego-server.jar "$@" ;;
  true) ;;
  *) printf '%s\n' '{"status":"CONFIG_REJECTED","summary":null}'; exec java -jar /app/bridgego-server.jar "$@" ;;
esac
stopping=0
child=''
on_stop() {
  local code="$1" pid
  stopping=1
  trap '' TERM INT
  # timeout starts a separate process group. Fallback covers its short pre-setpgid startup interval.
  for pid in ${child:-$(jobs -pr)}; do kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || :; done
  sleep 2
  for pid in ${child:-$(jobs -pr)}; do kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || :; done
  wait 2>/dev/null || :
  exit "$code"
}
trap 'on_stop 143' TERM
trap 'on_stop 130' INT
# Helper receives only its existing credential and exact non-secret configuration.
# The subshell preserves the game's environment; no secret is put in command arguments.
(
  for name in $(compgen -e); do
    case "$name" in
      PATH|LANG|LC_ALL|BRIDGE_READONLY_STARTUP_ENABLED|BRIDGE_READONLY_STARTUP_EXPIRES_AT|BRIDGE_RUNTIME_MODE|BRIDGE_SHEETS_ENABLED|BRIDGE_SHEETS_SPREADSHEET_ID|BRIDGE_DATASET_ID|BRIDGE_COLLECTION_MODE|BRIDGE_GOOGLE_SERVICE_ACCOUNT_JSON) ;;
      *) unset "$name" ;;
    esac
  done
  exec timeout --signal=TERM --kill-after=2s 35s \
    java -Xms16m -Xmx128m -XX:MaxMetaspaceSize=64m -XX:MaxDirectMemorySize=16m \
    -XX:ReservedCodeCacheSize=32m -XX:+ExitOnOutOfMemoryError -XX:-HeapDumpOnOutOfMemoryError \
    -jar /app/bridgego-production-readonly-startup.jar
) 2>/dev/null &
child=$!
status=0
wait "$child" || status=$?
child=''
if [ "$stopping" -ne 0 ]; then exit 143; fi
case "$status" in
  0) ;;
  124|137) printf '%s\n' '{"status":"HELPER_TIMEOUT","summary":null}' ;;
  *) printf '%s\n' '{"status":"HELPER_FAILED","summary":null}' ;;
esac
# No retry and no overlap: game starts only after helper exit. Signals before exec still take on_stop.
exec java -jar /app/bridgego-server.jar "$@"
