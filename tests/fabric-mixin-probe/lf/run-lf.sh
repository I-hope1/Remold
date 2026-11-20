#!/usr/bin/env bash
# Driver for the load-frontier / mixin-target-index probe (real Fabric 1.21.1 KnotServer).
#
# Prerequisite: a scratch Loom project set up as in ../README.md "Setup", plus:
#   - src/main/java/com/example/probe/LoadFrontierProbe.java   (copied from ../)
#   - src/main/java/com/example/probe/StringTarget.java        (string-target fixture)
#   - src/main/java/com/example/mixin/StringTargetMixin.java   (copied from ../)
#   - "StringTargetMixin" added to example_mod.mixins.json "mixins"
#   - "com.example.probe.LoadFrontierProbe" added to fabric.mod.json entrypoints.main
#
# Usage: SCRATCH=/path/to/loom-project bash run-lf.sh
set -euo pipefail
SCRATCH="${SCRATCH:?set SCRATCH to the scratch Loom project}"
JBR="${JBR:-/opt/idea/jbr}"

cd "$SCRATCH"
chmod +x gradlew
JAVA_HOME="$JBR" ./gradlew --offline compileJava processResources generateRemoldScript

python3 - <<'PY'
s = open('build/remold/remold.args', encoding='utf-8').read()
s = s.replace('-Dfabric.dli.env=client', '-Dfabric.dli.env=server')
s = s.replace('KnotClient', 'KnotServer')
if '-Dfabric.development=true' not in s:
    s = s.replace('-Dfabric.classPathGroups', '-Dfabric.development=true\n-Dfabric.classPathGroups', 1)
if '--nogui' not in s:
    s = s.replace('net.fabricmc.devlaunchinjector.Main', 'net.fabricmc.devlaunchinjector.Main\n--nogui')
open('build/remold/server.args', 'w', encoding='utf-8').write(s)
PY

printf 'eula=true\n' > eula.txt
printf 'online-mode=false\nlevel-name=world\nmax-tick-time=-1\n' > server.properties

timeout 300 "$JBR/bin/java" @build/remold/server.args 2>&1 | tee /tmp/lf-run.log >/dev/null
grep "LF-PROBE" /tmp/lf-run.log | sed 's/.*\[LF-PROBE\] //'
