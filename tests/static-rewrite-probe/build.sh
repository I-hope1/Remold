#!/usr/bin/env bash
# Build the static-rewrite A/B probe (needs JBR 21 + the remold agent jar for StageASkeleton).
set -euo pipefail
cd "$(dirname "$0")"
J21="${JBR21_HOME:-F:/files/java/jdks/jbrsdk_jcef-21.0.9}"
ROOT="$(cd ../.. && pwd -W 2>/dev/null || pwd)"
AGENT="$ROOT/core/build/libs/remold-agent.jar"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe"; SEP=";";; *) EXE=""; SEP=":";; esac
[ -x "$J21/bin/javac$EXE" ] || { echo "missing $J21/bin/javac$EXE (set JBR21_HOME)" >&2; exit 1; }
[ -f "$AGENT" ] || { echo "missing $AGENT (run ./gradlew :core:shadowJar first)" >&2; exit 1; }
# JetBrains Runtime ships java/javac but no jar tool; allow JAR_TOOL or a PATH jar for packaging the probe agent.
JAR=""
for cand in "${JAR_TOOL:-}" "$J21/bin/jar$EXE" "$(command -v jar 2>/dev/null || true)"; do
  if [ -n "$cand" ] && [ -x "$cand" ]; then JAR="$cand"; break; fi
done
[ -n "$JAR" ] || { echo "missing jar tool: set JAR_TOOL (JBR has no bin/jar)" >&2; exit 1; }
B=b
rm -rf "$B"
mkdir -p "$B/v1" "$B/v2" "$B/reader" "$B/probe" "$B/gatev1" "$B/gatev2" "$B/logs"
EN=(-J-Duser.language=en -J-Duser.country=US)
echo "== fixtures =="
"$J21/bin/javac$EXE" "${EN[@]}" -d "$B/v1" src/v1/test/Holder.java
"$J21/bin/javac$EXE" "${EN[@]}" -d "$B/v2" src/v2/test/Holder.java
"$J21/bin/javac$EXE" "${EN[@]}" -cp "$B/v1" -d "$B/reader" src/reader/Reader.java
# gate fixtures: v1 has no annotation, v2 needs remold.annotation on the compile classpath.
"$J21/bin/javac$EXE" "${EN[@]}" -d "$B/gatev1" src/gatev1/test/GateHolder.java
"$J21/bin/javac$EXE" "${EN[@]}" -cp "$AGENT" -d "$B/gatev2" src/gatev2/test/GateHolder.java
echo "== probe =="
"$J21/bin/javac$EXE" "${EN[@]}" --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp "$AGENT$SEP$B/v1$SEP$B/reader" -d "$B/probe" src/probe/Probe.java src/probe/ProbeAgent.java
cat > "$B/manifest.txt" <<'MANIFEST'
Premain-Class: probe.ProbeAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
MANIFEST
"$JAR" cfm "$B/agent.jar" "$B/manifest.txt" -C "$B/probe" probe/ProbeAgent.class
# JBR ships no javap either; fall back to the jar tool's JDK, else skip the (informational) shape dump.
JAVAP=""
for cand in "$(dirname "$JAR")/javap$EXE" "$(command -v javap 2>/dev/null || true)"; do
  if [ -n "$cand" ] && [ -x "$cand" ]; then JAVAP="$cand"; break; fi
done
echo "== shape =="
if [ -n "$JAVAP" ]; then
  "$JAVAP" -p -c "$B/v1/test/Holder.class" | grep -E "VAL|ConstantValue|tag" | head -8 || true
  echo "== gate shape (v2: runtime init, no ConstantValue) =="
  "$JAVAP" -p -v "$B/gatev2/test/GateHolder.class" | grep -E "MSG|ConstantValue|RemoldReinit" | head -8 || true
else
  echo "javap unavailable; skipping shape dump"
fi
echo "build ok: $B"
