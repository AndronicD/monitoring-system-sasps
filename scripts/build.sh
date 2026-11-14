#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/classes
find src/main/java -name '*.java' | sort > build/main-sources.txt
javac --release 17 --add-modules jdk.httpserver -Xlint:all -Werror \
  -d build/classes @build/main-sources.txt
jar --create --file build/resource-monitor.jar --main-class sasps.monitoring.Main -C build/classes .
echo "Built build/resource-monitor.jar"
