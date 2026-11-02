#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
(cd frontend && npm ci --ignore-scripts && npm run build)
for file in index.html sw.js manifest.webmanifest; do
  test -f "frontend/dist/$file" || { echo "Missing production asset: $file" >&2; exit 1; }
done
mvn -B -f backend/pom.xml -Pweb clean package "$@"
echo 'Built backend/target/bridgego-server.jar (not deployed)'
