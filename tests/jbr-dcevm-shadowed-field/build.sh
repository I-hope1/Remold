#!/usr/bin/env bash
# Build: minimal agent jar + version class dirs + custom-loader harness + system-loader harness.
# Pure JDK API only (JBR 21), no Remold code, no core/tests modules.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
JB=/opt/idea/jbr/bin

rm -rf "$ROOT/out" "$ROOT/classes"
mkdir -p "$ROOT/out/agent" "$ROOT/out/harness" "$ROOT/out/sys"

$JB/javac -d "$ROOT/out/agent" "$ROOT/agent/Agent.java"
python3 - "$ROOT" <<'PY'
import sys, zipfile, os
root = sys.argv[1]
jar = os.path.join(root, "out", "agent.jar")
with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(root, "agent", "MANIFEST.MF"), "META-INF/MANIFEST.MF")
    for dirpath, _, files in os.walk(os.path.join(root, "out", "agent")):
        for f in files:
            full = os.path.join(dirpath, f)
            z.write(full, os.path.relpath(full, os.path.join(root, "out", "agent")))
print("agent.jar written", jar)
PY

for pair in v1:v1src v2:v2src v3:v3src v3alt:v3alt v2an:v2an v3an:v3an; do
  v=${pair%%:*}; src="$ROOT/${pair##*:}"
  mkdir -p "$ROOT/classes/$v"
  $JB/javac -d "$ROOT/classes/$v" $(find "$src" -name '*.java')
done

$JB/javac -cp "$ROOT/out/agent.jar" -d "$ROOT/out/harness" "$ROOT/Harness.java"
$JB/javac -cp "$ROOT/classes/v1:$ROOT/out/agent.jar" -d "$ROOT/out/sys" "$ROOT/SysMain.java"
echo "BUILD OK"
