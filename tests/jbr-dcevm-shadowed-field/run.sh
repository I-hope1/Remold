#!/usr/bin/env bash
# Runs one control variant in a fresh JVM: run.sh <v12|v123|repeat>
set -uo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
mode=${1:?usage: run.sh <v12|v123|repeat>}
mkdir -p "$ROOT/results"
/opt/idea/jbr/bin/java -XX:+AllowEnhancedClassRedefinition \
  -javaagent:"$ROOT/out/agent.jar" \
  -cp "$ROOT/out/harness" \
  Harness "$ROOT" "$mode" 2>&1 | tee "$ROOT/results/$mode.log"
