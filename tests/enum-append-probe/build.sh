#!/usr/bin/env bash
# Build the enum-append probe: javac21 bytes and real JDK8-javac bytes.
set -euo pipefail
cd "$(dirname "$0")"

# DCEVM-capable JDK 21 (JBR) and a real JDK 8 javac. Override with env vars.
J21="${JBR21_HOME:-F:/files/java/jdks/jbrsdk_jcef-21.0.9}"
J8="${JDK8_HOME:-F:/files/java/jdks/jdk-1.8}"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe"; SEP=";";; *) EXE=""; SEP=":";; esac
for tool in javac javap jar; do
  [ -x "$J21/bin/$tool$EXE" ] || { echo "missing $J21/bin/$tool$EXE (set JBR21_HOME)" >&2; exit 1; }
done
[ -x "$J8/bin/javac$EXE" ] || { echo "missing $J8/bin/javac$EXE (set JDK8_HOME)" >&2; exit 1; }
B=b
rm -rf "$B"
mkdir -p "$B/21/v1" "$B/21/v1body" "$B/21/v2" "$B/21/v2ctor" "$B/21/v2body" "$B/21/swv1v2/probe" "$B/8/v1" "$B/8/v2" "$B/probe" "$B/logs"

EN=(-J-Duser.language=en -J-Duser.country=US)

echo "== javac21 =="
"$J21/bin/javac$EXE" "${EN[@]}" -d "$B/21/v1"     src/v1/test/Color.java
"$J21/bin/javac$EXE" "${EN[@]}" -d "$B/21/v1body" src/v1body/test/Color.java
"$J21/bin/javac$EXE" "${EN[@]}" -d "$B/21/v2"     src/v2/test/Color.java
"$J21/bin/javac$EXE" "${EN[@]}" -d "$B/21/v2ctor" src/v2ctor/test/Color.java
"$J21/bin/javac$EXE" "${EN[@]}" -d "$B/21/v2body" src/v2body/test/Color.java

echo "== javac8 (real JDK8 javac, inline \$VALUES build) =="
"$J8/bin/javac$EXE" "${EN[@]}" -d "$B/8/v1" src/v1/test/Color.java
"$J8/bin/javac$EXE" "${EN[@]}" -d "$B/8/v2" src/v2/test/Color.java

echo "== probe sources referencing v2 members (BlueReader, SwV2) =="
"$J21/bin/javac$EXE" "${EN[@]}" -cp "$B/21/v2" -d "$B/probe" src/probe/BlueReader.java src/probe/SwV2.java

echo "== same-name consumer recompiled against v2 (SwitchMap rebuild fixture) =="
"$J21/bin/javac$EXE" "${EN[@]}" -cp "$B/21/v2" -d "$B/21/swv1v2" src/swv1v2/probe/SwV1.java
"$J21/bin/javap$EXE" -p -c "$B/21/swv1v2/probe/SwV1.class" | grep -E 'getstatic|SwitchMap|tableswitch|lookupswitch' | head -8

echo "== probe sources referencing v1 members (Probe, SwV1, ProbeAgent) =="
"$J21/bin/javac$EXE" "${EN[@]}" --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp "$B/21/v1$SEP$B/probe" -d "$B/probe" \
  src/probe/Probe.java src/probe/SwV1.java src/probe/ProbeAgent.java

echo "== agent jar =="
cat > "$B/manifest.txt" <<'EOF'
Premain-Class: probe.ProbeAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
EOF
"$J21/bin/jar$EXE" cfm "$B/agent.jar" "$B/manifest.txt" -C "$B/probe" probe/ProbeAgent.class
"$J21/bin/jar$EXE" tf "$B/agent.jar" | sed 's/^/  jar: /'

echo "== bytecode shape check =="
for f in "$B/21/v2/test/Color.class" "$B/8/v2/test/Color.class"; do
  echo "-- $f"
  "$J21/bin/javap$EXE" -p -c "$f" | grep -E 'values\(\)|anewarray|putstatic.*\$VALUES' | head -8 || true
done
echo "== v2body files =="
ls "$B/21/v2body/test/"
echo "== v2body Color\$1 =="
"$J21/bin/javap$EXE" -p -c "$B/21/v2body/test/Color\$1.class" | head -40
echo "== v2body Color nestMembers =="
"$J21/bin/javap$EXE" -v "$B/21/v2body/test/Color.class" | grep -A3 "NestMembers" | head -6
