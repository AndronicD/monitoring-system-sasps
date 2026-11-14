#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build.sh
mkdir -p build/test-classes
find src/test/java -name '*.java' | sort > build/test-sources.txt
javac --release 17 --add-modules jdk.httpserver -Xlint:all -Werror \
  -cp build/classes -d build/test-classes @build/test-sources.txt
java -ea --add-modules jdk.httpserver -cp build/classes:build/test-classes ro.sasps.monitoring.TestSuite
