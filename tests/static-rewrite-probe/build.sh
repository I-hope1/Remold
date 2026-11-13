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
B=b
rm -rf "$B"
mkdir -p "$B/v1" "$B/v2" "$B/reader" "$B/probe" "$B/logs"
EN=(-J-Duser.language=en -J-Duser.country=US)
echo "== fixtures =="
"$J21/bin/javac$EXE" "${EN[@]}" -d "$B/v1" src/v1/test/Holder.java
"$J21/bin/javac$EXE" "${EN[@]}" -d "$B/v2" src/v2/test/Holder.java
"$J21/bin/javac$EXE" "${EN[@]}" -cp "$B/v1" -d "$B/reader" src/reader/Reader.java
echo "== probe =="
"$J21/bin/javac$EXE" "${EN[@]}" --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp "$AGENT$SEP$B/v1$SEP$B/reader" -d "$B/probe" src/probe/Probe.java src/probe/ProbeAgent.java
cat > "$B/manifest.txt" <<'MANIFEST'
Premain-Class: probe.ProbeAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
MANIFEST
"$J21/bin/jar$EXE" cfm "$B/agent.jar" "$B/manifest.txt" -C "$B/probe" probe/ProbeAgent.class
echo "== shape =="
"$J21/bin/javap$EXE" -p -c "$B/v1/test/Holder.class" | grep -E "VAL|ConstantValue|tag" | head -8 || true
echo "build ok: $B"
