#!/usr/bin/env bash
# Run the static-rewrite A/B probe: single-phase vs two-phase. One JVM per mode.
set -uo pipefail
cd "$(dirname "$0")"
J21="${JBR21_HOME:-F:/files/java/jdks/jbrsdk_jcef-21.0.9}"
ROOT="$(cd ../.. && pwd -W 2>/dev/null || pwd)"
AGENT="$ROOT/core/build/libs/remold-agent.jar"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe"; SEP=";";; *) EXE=""; SEP=":";; esac
B=b
LOG="$B/logs/all.log"
: > "$LOG"
CP="$AGENT$SEP$B/probe$SEP$B/v1$SEP$B/reader"
V1="$B/v1/test/Holder.class"
V2="$B/v2/test/Holder.class"
FLAGS=(
  -XX:+AllowEnhancedClassRedefinition
  -javaagent:"$B/agent.jar"
  --add-exports java.base/jdk.internal.misc=ALL-UNNAMED
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
)
run() {
  local label="$1" mode="$2"
  echo "########## $label (mode=$mode) ##########" | tee -a "$LOG"
  "$J21/bin/java$EXE" "${FLAGS[@]}" -cp "$CP" probe.Probe "$mode" "$V1" "$V2" 2>&1 | tee -a "$LOG"
  echo "" | tee -a "$LOG"
}
run "single-phase (redefine v2 -> patch)"        single
run "two-phase (stage A -> patch -> stage C)"    two
grep -E '^OBS (single|two)\.(afterPatch|afterStageC|window|afterStageA|beforeStageC)' "$LOG"
