#!/usr/bin/env bash
# Build the value-type new-initializer replay E2E probe (real JVM, real redefineClasses).
#
# Needs a JBR/JDK with javac (default /opt/idea/jbr) plus the remold agent jar
# (core/build/libs/remold-agent.jar, produced by `:core:shadowJar`). JetBrains Runtime ships
# java/javac/jcmd but no jar/javap, so packaging the probe agent uses JAR_TOOL.
set -euo pipefail
cd "$(dirname "$0")"

JBR_HOME="${JBR_HOME:-/opt/idea/jbr}"
ROOT="$(cd ../.. && pwd)"
AGENT="$ROOT/core/build/libs/remold-agent.jar"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe"; SEP=";";;
  *)                    EXE="";    SEP=":";;
esac

[ -x "$JBR_HOME/bin/javac$EXE" ] || {
  echo "missing $JBR_HOME/bin/javac$EXE (set JBR_HOME to a JBR/JDK with javac)" >&2
  exit 1
}
[ -f "$AGENT" ] || {
  echo "missing $AGENT (run the :core:shadowJar gradle task first)" >&2
  exit 1
}

# JetBrains Runtime has no bin/jar; allow JAR_TOOL or a full JDK jar (then javap is taken from the same dir).
JAR=""
for cand in "${JAR_TOOL:-}" "$JBR_HOME/bin/jar$EXE" \
            "/home/ubuntu/.jdks/ms-25.0.4.1/bin/jar" \
            "$(command -v jar 2>/dev/null || true)"; do
  if [ -n "$cand" ] && [ -x "$cand" ]; then JAR="$cand"; break; fi
done
[ -n "$JAR" ] || {
  echo "missing jar tool: the JBR SDK has no bin/jar; set JAR_TOOL=<full JDK>/bin/jar" >&2
  exit 1
}

B=b
rm -rf "$B"
mkdir -p "$B/v1" "$B/v2" "$B/staticv1" "$B/staticv2" "$B/reader" "$B/probe" "$B/logs"
JAVAC=("$JBR_HOME/bin/javac$EXE" -J-Duser.language=en -J-Duser.country=US -nowarn)

echo "== fixtures =="
# Shared record + v1 instance host.
"${JAVAC[@]}" -d "$B/v1" src/v1/probe/Vec.java src/v1/probe/Host.java
# v2 instance host (same class name, same Vec type resolved from the v1 output).
"${JAVAC[@]}" -cp "$B/v1" -d "$B/v2" src/v2/probe/Host.java
# Static-final host versions (v1 loaded, v2 fed as bytes).
"${JAVAC[@]}" -cp "$B/v1" -d "$B/staticv1" src/staticv1/probe/StaticHost.java
"${JAVAC[@]}" -cp "$B/v1" -d "$B/staticv2" src/staticv2/probe/StaticHost.java
# Hot readers (compiled against the v1 fixtures).
"${JAVAC[@]}" -cp "$B/v1$SEP$B/staticv1" -d "$B/reader" src/reader/probe/Reader.java

echo "== probe =="
"${JAVAC[@]}" --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp "$AGENT$SEP$B/v1$SEP$B/staticv1$SEP$B/reader" -d "$B/probe" \
  src/probe/Probe.java src/probe/ProbeAgent.java

cat > "$B/manifest.txt" <<'MANIFEST'
Premain-Class: probe.ProbeAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
MANIFEST
"$JAR" cfm "$B/agent.jar" "$B/manifest.txt" -C "$B/probe" probe/ProbeAgent.class

# Shape evidence: the two host versions differ only in the initializer constant, and the static host
# initializes SV at runtime (no ConstantValue) so A4 accepts it as a replay target on enhanced JBR.
JAVAP=""
for cand in "$(dirname "$JAR")/javap$EXE" "$(command -v javap 2>/dev/null || true)"; do
  if [ -n "$cand" ] && [ -x "$cand" ]; then JAVAP="$cand"; break; fi
done
echo "== shape =="
if [ -n "$JAVAP" ]; then
  echo "-- Host v1 field/initializer --"
  "$JAVAP" -p -c "$B/v1/probe/Host.class" | grep -E 'Vec v|new +#|iconst|bipush' | head -12 || true
  echo "-- Host v2 field/initializer --"
  "$JAVAP" -p -c "$B/v2/probe/Host.class" | grep -E 'Vec v|new +#|iconst|bipush' | head -12 || true
  echo "-- StaticHost v1 runtime initializer (expect no ConstantValue) --"
  "$JAVAP" -p -c "$B/staticv1/probe/StaticHost.class" | grep -E 'SV|new +#|putstatic|iconst|bipush' | head -12 || true
  "$JAVAP" -p -v "$B/staticv1/probe/StaticHost.class" | grep -E 'SV;|ConstantValue' | head -8 || true
else
  echo "javap unavailable; skipping shape dump"
fi

echo "build ok: $B (javac: $("$JBR_HOME/bin/java$EXE" -version 2>&1 | head -1))"
