#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f build/resource-monitor.jar ]]; then
  echo "Missing JAR. Run: bash scripts/build.sh" >&2
  exit 1
fi
exec java -Xms32m -Xmx64m --add-modules jdk.httpserver -jar build/resource-monitor.jar "$@"
