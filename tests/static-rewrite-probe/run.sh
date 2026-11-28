#!/usr/bin/env bash
# Run the static-rewrite A/B probe: single-phase vs two-phase, plus the enhanced-gate negative path.
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
GV1="$B/gatev1/test/GateHolder.class"
GV2="$B/gatev2/test/GateHolder.class"
FLAGS=(
  -XX:+AllowEnhancedClassRedefinition
  -javaagent:"$B/agent.jar"
  --add-exports java.base/jdk.internal.misc=ALL-UNNAMED
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
)
PLAIN_FLAGS=(
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
# Enhanced-off negative path: run the InitFix gate scenario on plain JBR (no
# -XX:+AllowEnhancedClassRedefinition). An existing static final rewrite must be rejected per field,
# no patch task emitted, the warn must name the flag, and the value must stay un-rewritten.
# The probe throws on mismatch -> non-zero exit -> this script fails.
run_gate() {
  local label="$1"
  shift
  echo "########## $label (mode=gate) ##########" | tee -a "$LOG"
  "$J21/bin/java$EXE" "$@" 2>&1 | tee -a "$LOG"
  local rc=${PIPESTATUS[0]}
  echo "" | tee -a "$LOG"
  if [ "$rc" -ne 0 ]; then
    echo "GATE RUN FAILED: $label (exit $rc)" | tee -a "$LOG"
    exit 1
  fi
}
run "single-phase (redefine v2 -> patch)"        single
run "two-phase (stage A -> patch -> stage C)"    two
run_gate "gate-off (plain JBR, no enhanced redefinition)" \
  "${PLAIN_FLAGS[@]}" -cp "$CP" probe.Probe gate "$GV1" "$GV2"
run_gate "gate-on (JBR enhanced control)" \
  "${FLAGS[@]}" -cp "$CP" probe.Probe gate "$GV1" "$GV2"
grep -E '^OBS (single|two)\.(afterPatch|afterStageC|window|afterStageA|beforeStageC)' "$LOG"
grep -E '^OBS gate\.' "$LOG"
