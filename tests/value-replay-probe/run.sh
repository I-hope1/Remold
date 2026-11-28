#!/usr/bin/env bash
# Run the value-type new-initializer replay E2E probe on two JBR builds.
#
#   JBR_HOME     enhanced-capable JBR 21 (default /opt/idea/jbr)      -> scenarios a/b/c/d/e
#   JBR25_HOME   enhanced-capable JBR 25 (default /home/ubuntu/deopt-study/jbr/ext25)
#
# Each scenario is one JVM: real Instrumentation.redefineClasses + InitFix.transform/afterRedefine.
# Every run must exit 0, and (in the same stdout stream) a probe.Reader compile line must precede the
# redefine marker line, which is the "compiled reader before the write" evidence.
set -uo pipefail
cd "$(dirname "$0")"

J21="${JBR_HOME:-/opt/idea/jbr}"
J25="${JBR25_HOME:-/home/ubuntu/deopt-study/jbr/ext25}"
ROOT="$(cd ../.. && pwd)"
AGENT="$ROOT/core/build/libs/remold-agent.jar"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe"; SEP=";";;
  *)                    EXE="";    SEP=":";;
esac

B=b
LOG="$B/logs/all.log"
mkdir -p "$B/logs"
: > "$LOG"

[ -x "$J21/bin/java$EXE" ] || { echo "missing $J21/bin/java$EXE (set JBR_HOME)" >&2; exit 1; }
[ -f "$AGENT" ] || { echo "missing $AGENT (run tests/value-replay-probe/build.sh)" >&2; exit 1; }
[ -f "$B/agent.jar" ] || { echo "missing $B/agent.jar (run tests/value-replay-probe/build.sh)" >&2; exit 1; }

CP="$AGENT$SEP$B/probe$SEP$B/v1$SEP$B/staticv1$SEP$B/reader"
HOST_V1="$B/v1/probe/Host.class"
HOST_V2="$B/v2/probe/Host.class"
SV1="$B/staticv1/probe/StaticHost.class"
SV2="$B/staticv2/probe/StaticHost.class"

# -XX:CompileCommand=dontinline keeps the reader a standalone compiled method (so its C1/C2 compile
# lines are observable and the static folding happens inside the reader body itself).
COMMON=(
  -javaagent:"$B/agent.jar"
  --add-exports java.base/jdk.internal.misc=ALL-UNNAMED
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
  -XX:CompileCommand=dontinline,probe.Reader::read
  -XX:CompileCommand=dontinline,probe.Reader::readStatic
)
PROP=(-Dremold.agent.init_drift.value_replay=true)
ENHANCED=(-XX:+AllowEnhancedClassRedefinition)

FAILED=0

# The probe polls the JVM JIT log until the reader has a tier-4 (C2) compile line BEFORE it proceeds;
# the OBS line carries that JIT log line verbatim. This check asserts the OBS exists and precedes the
# redefine marker in the same (single-writer) stdout stream.
compile_order_ok() { # <runlog> <marker>
  awk -v marker="$2" '
    index($0, "warm.readerCompile.c2") && !first { first = NR }
    index($0, marker) && !at { at = NR }
    END {
      if (first == 0) { print "    no warm.readerCompile.c2 line in stdout"; exit 1 }
      if (at == 0)    { print "    no redefine marker line in stdout"; exit 1 }
      if (first >= at) { print "    reader compile line " first " is not before marker " at; exit 1 }
      print "    reader C2 compile line " first " precedes marker line " at
      exit 0
    }' "$1"
}

run() { # run <jdk-home> <label> <mode> [extra jvm flags...]
  local jdk="$1" label="$2" mode="$3"; shift 3
  local jit="$B/logs/jit-$label.log"
  local runlog="$B/logs/$label.log"
  echo "########## $label (mode=$mode) ##########" | tee -a "$LOG"
  "$jdk/bin/java$EXE" "${COMMON[@]}" "$@" \
    -Xlog:jit+compilation=debug:file="$jit" -Dvr.jitlog="$jit" \
    -cp "$CP" probe.Probe "$mode" "$HOST_V1" "$HOST_V2" "$SV1" "$SV2" \
    2>&1 | tee "$runlog" >> "$LOG"
  local rc=${PIPESTATUS[0]}
  echo "" | tee -a "$LOG"
  if [ "$rc" -ne 0 ]; then
    echo "RUN FAILED: $label (exit $rc)" | tee -a "$LOG"
    FAILED=1
    return
  fi
  echo "RUN OK: $label (exit 0)" | tee -a "$LOG"
  echo "JIT-EVIDENCE $label:" | tee -a "$LOG"
  grep -E 'probe\.Reader::(read|readStatic)' "$jit" | head -6 | sed 's/^/  /' | tee -a "$LOG"
  local marker
  case "$mode" in
    static) marker="OBS static.redefineStageA = ok";;
    *)      marker="OBS $mode.redefine = ok";;
  esac
  # For the skip scenario the first redefine marker is the right barrier; the second round is below it.
  if compile_order_ok "$runlog" "$marker" | tee -a "$LOG"; then :; else
    echo "COMPILE-ORDER FAILED: $label" | tee -a "$LOG"
    FAILED=1
  fi
}

run_all() { # <jdk-home> <label-prefix>
  local jdk="$1" tag="$2"
  run "$jdk" "$tag-instance" instance "${PROP[@]}"
  run "$jdk" "$tag-skip" skip "${PROP[@]}"
  run "$jdk" "$tag-off" off
  run "$jdk" "$tag-static" static "${PROP[@]}" "${ENHANCED[@]}"
  run "$jdk" "$tag-static-plain" static-plain "${PROP[@]}"
}

run_all "$J21" jbr21
if [ -x "$J25/bin/java$EXE" ]; then
  run_all "$J25" jbr25
else
  echo "SKIP jbr25: missing $J25/bin/java$EXE (set JBR25_HOME)" | tee -a "$LOG"
fi

echo "########## readings ##########" | tee -a "$LOG"
grep -E '^OBS ' "$LOG" | grep -v '\.check\.' | sed 's/^/  /' | tee -a "$LOG"
echo "########## reader compile evidence (unified JIT log, tier info) ##########" | tee -a "$LOG"
grep -E '^OBS [a-z-]+\.warm\.readerCompile\.(c1|c2)' "$LOG" | sed 's/ = \[[^]]*\]\[[^]]*\]\[jit,compilation\] */ = /' | tee -a "$LOG"

if [ "$FAILED" -ne 0 ]; then
  echo "VALUE-REPLAY PROBE FAILED (see $LOG)" | tee -a "$LOG"
  exit 1
fi
echo "VALUE-REPLAY PROBE OK (see $LOG)" | tee -a "$LOG"
