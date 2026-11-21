#!/usr/bin/env bash
# Same control on the SYSTEM classloader: run-sys.sh <v12|v123>
set -uo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
mode=${1:?usage: run-sys.sh <v12|v123>}
mkdir -p "$ROOT/results"
/opt/idea/jbr/bin/java -XX:+AllowEnhancedClassRedefinition \
  -javaagent:"$ROOT/out/agent.jar" \
  -cp "$ROOT/out/sys:$ROOT/classes/v1" \
  SysMain "$ROOT" "$mode" 2>&1 | tee "$ROOT/results/sys-$mode.log"
