#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Anonymous-class (Mixin$N) hot-swap probe — real Fabric 1.21.1 KnotServer,
# real Remold -javaagent, mod loaded from run/mods/*.jar (jar is the only
# bytecode provider path, exactly like the real client where build/classes or
# the packaged jar is the provider and the freshly compiled classes are not).
#
# Stages (rationale in README.md next to this script):
#   S0   baseline: Mixin handler without an anonymous class      -> poke=hook-s0
#   S1   R3:  handler gains its FIRST anonymous class            -> RED: reload
#        fails (ClassNotFoundException Mingin$1 -> innerClass is null)
#   S1b  R3b: same anonymous body, outer bytes reshaped (real retry), and
#        AnonTargetMixin$1.class copied onto the real classpath -> poke=anon-v1
#   S2   R5:  outer Mixin bytes byte-identical, ONLY
#        AnonTargetMixin$1.class carries anon-v2; both .class files are
#        (re)written the way a full compile writes them            -> RED: poke
#        stays anon-v1
#   S3   control: no file change at all                          -> poke unchanged,
#        no redefine/reload activity
#
# Exit codes:
#   0  GREEN  — S1 reached anon-v1 and S2 reached anon-v2 (implementation fixed)
#   1  RED    — both documented defects reproduced (current baseline)
#   2  HARNESS ERROR — build/launch/timeout/precondition failure (probe broke)
#   3  MIXED  — only one of the two defects reproduced
#
# Env knobs:
#   JBR_HOME               DCEVM JDK 21 (default C:/Users/ASUS/.jdks/JBR-21)
#   REMOLD_BUILD_JAVA_HOME JDK used to build the repo jars (default JAVA_HOME,
#                          else C:/Users/ASUS/.jdks/openjdk-25.0.1)
#   ANON_SKIP_BUILD=1      reuse already built repo jars
#   ANON_START_TIMEOUT     seconds to wait for the S0 baseline (default 300)
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "$0")" && pwd -W)"
REPO="$(cd "$HERE/../../.." && pwd -W)"
JBR="${JBR_HOME:-C:/Users/ASUS/.jdks/JBR-21}"
JAVA="$JBR/bin/java"
BUILD_JAVA="${REMOLD_BUILD_JAVA_HOME:-${JAVA_HOME:-C:/Users/ASUS/.jdks/openjdk-25.0.1}}"
START_TIMEOUT="${ANON_START_TIMEOUT:-300}"

PROJ="$HERE/build/proj"
STAGES="$HERE/stages"
WORK="$HERE/build/anon"
EVIDENCE="$HERE/evidence/anon-r3r5-red.txt"
LOG="$PROJ/run/anon.log"
OUT="$PROJ/out/production"
CPFIX="$PROJ/run/probe-classpath"
SRC_MIXIN="$PROJ/src/main/java/com/example/anon/AnonTargetMixin.java"
CLS="$PROJ/build/classes/java/main/com/example/anon"

log() { printf '[anon] %s\n' "$*"; }
die() { log "HARNESS ERROR: $*"; exit 2; }

# --------------------------------------------------------------- repo jars
build_jars() {
  if [ "${ANON_SKIP_BUILD:-0}" = "1" ]; then
    log "skipping repo build (ANON_SKIP_BUILD=1)"
  else
    log "building remold agent + mixin/fabric plugins with $BUILD_JAVA"
    mkdir -p "$WORK"
    ( cd "$REPO" && JAVA_HOME="$BUILD_JAVA" ./gradlew --offline -q \
        :core:shadowJar :plugin-mixin:jar :plugin-fabric:jar ) >"$WORK/build-jars.log" 2>&1 \
      || { tail -30 "$WORK/build-jars.log"; die "repo gradle build failed"; }
  fi
  AGENT="$REPO/core/build/libs/remold-agent.jar"
  PLUGIN_MIXIN="$REPO/plugin-mixin/build/libs/remold-plugin-mixin-0.2.5.jar"
  PLUGIN_FABRIC="$REPO/plugin-fabric/build/libs/remold-plugin-fabric-0.2.5.jar"
  for f in "$AGENT" "$PLUGIN_MIXIN" "$PLUGIN_FABRIC"; do
    [ -f "$f" ] || die "missing jar: $f"
  done
}

# ------------------------------------------------------------------ scaffold
scaffold() {
  log "scaffolding Loom project at $PROJ"
  mkdir -p "$PROJ" "$WORK"
  # Keep the Gradle/Loom caches of previous runs, always refresh the sources.
  rm -rf "$PROJ/src"
  cp -r "$HERE/project/src" "$PROJ/src"
  cp "$HERE/project/build.gradle.kts" "$HERE/project/settings.gradle.kts" \
     "$HERE/project/gradle.properties" "$PROJ/"
  # Reuse the Gradle wrapper the repository already tracks (same version, no
  # duplicate wrapper jar inside the probe fixture).
  cp "$REPO/gradlew" "$REPO/gradlew.bat" "$PROJ/"
  cp -r "$REPO/gradle" "$PROJ/"
  rm -rf "$PROJ/run/world" "$PROJ/run/mods" "$PROJ/run/probe-classpath" "$OUT" "$PROJ/out"
  mkdir -p "$PROJ/run/mods" "$CPFIX" "$OUT"
  printf 'eula=true\n' > "$PROJ/run/eula.txt"
  cat > "$PROJ/run/server.properties" <<'EOF'
online-mode=false
level-name=world
gamemode=creative
spawn-protection=0
max-players=2
view-distance=4
sync-chunk-writes=false
EOF
}

gradle_compile() {  # tag
  ( cd "$PROJ" && JAVA_HOME="$JBR" ./gradlew --offline -q compileJava ) >"$WORK/compile-$1.log" 2>&1 \
    || { tail -40 "$WORK/compile-$1.log"; die "compileJava failed for stage $1"; }
}

build_and_deploy() {
  log "building the mod jar (gradle anonEnv)"
  ( cd "$PROJ" && JAVA_HOME="$JBR" ./gradlew --offline -q anonEnv ) >"$WORK/env.log" 2>&1 \
    || { tail -40 "$WORK/env.log"; die "gradle anonEnv failed"; }
  local jar cp
  jar="$(grep '^JAR=' "$PROJ/build/anon/env.txt" | sed 's/^JAR=//')"
  cp="$(grep '^CP=' "$PROJ/build/anon/env.txt" | sed 's/^CP=//')"
  [ -f "$jar" ] || die "mod jar not produced: $jar"
  cp "$jar" "$PROJ/run/mods/"
  # Provider path = the deployed mod jar. The Gradle output dirs must NOT stay on
  # the runtime classpath, otherwise the newly compiled "$1.class" is visible to
  # Mixin's bytecode provider and R3 cannot reproduce. Assert, do not trust.
  CP_RUNTIME="$(printf '%s' "$cp" | tr '\\' '/' | tr ';' '\n' \
      | grep -vE '/build/(classes/java/main|resources/main)$' | paste -sd ';' -)"
  printf '%s' "$CP_RUNTIME" | tr ';' '\n' | grep -qE '/build/(classes/java/main|resources/main)$' \
    && die "project output dirs are still on the runtime classpath"
  # run/probe-classpath is the "real classpath" the S1b workaround drops
  # AnonTargetMixin$1.class into (a plain directory on -cp, read live).
  CP_RUNTIME="$CPFIX;$CP_RUNTIME"
  cp -r "$PROJ/build/classes/java/main/." "$OUT/"
  log "classpath entries: $(printf '%s' "$CP_RUNTIME" | tr ';' '\n' | wc -l) (mod jar is in run/mods)"
}

launch() {
  log "launching KnotServer (cwd=$PROJ/run)"
  mkdir -p "$PROJ/build/anon"
  {
    echo "-XX:+IgnoreUnrecognizedVMOptions"
    echo "-XX:+AllowEnhancedClassRedefinition"
    echo "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
    echo "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED"
    echo "--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED"
    echo "--enable-native-access=ALL-UNNAMED"
    echo "-Dfile.encoding=UTF-8"
    echo "-Dfabric.development=true"
    echo "-Dremold.agent.debug=true"
    # The dev-mode guard would quarantine the deployed mod jar (it assumes loose
    # classes win); this probe deliberately loads the mod FROM THE JAR.
    echo "-Dremold.agent.mods_quarantine=false"
    echo "-Danon.dir=$PROJ/run"
    echo "-javaagent:$AGENT=$OUT"
    echo "-Dremold.plugins=mixin,fabric"
    echo "-Dremold.plugin.jars=$PLUGIN_MIXIN;$PLUGIN_FABRIC"
    echo "-cp"
    echo "\"$CP_RUNTIME\""
    echo "net.fabricmc.loader.impl.launch.knot.KnotServer"
    echo "--nogui"
  } > "$PROJ/build/anon/server.args"
  rm -f "$LOG"
  ( cd "$PROJ/run" && exec "$JAVA" @"$PROJ/build/anon/server.args" ) >"$LOG" 2>&1 &
  SERVER_PID=$!
  log "server pid=$SERVER_PID"
}

kill_stale_servers() {
  powershell.exe -NoProfile -Command \
    "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*anon*server.args*' } | ForEach-Object { taskkill /F /PID \$_.ProcessId | Out-Null }" \
    >/dev/null 2>&1 || true
}

cleanup() {
  if [ -n "${SERVER_PID:-}" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    log "stopping server $SERVER_PID"
    kill "$SERVER_PID" 2>/dev/null
    sleep 2
    kill -9 "$SERVER_PID" 2>/dev/null
  fi
}
trap cleanup EXIT

# ----------------------------------------------------------------- utilities
wait_for_pattern() {  # pattern timeout label
  local pat="$1" timeout="$2" label="$3" i=0
  while [ "$i" -lt "$timeout" ]; do
    if grep -qF -- "$pat" "$LOG" 2>/dev/null; then return 0; fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      log "server died while waiting for $label"
      tail -25 "$LOG"
      return 1
    fi
    sleep 1; i=$((i+1))
  done
  log "TIMEOUT (${timeout}s) waiting for $label ($pat)"
  tail -15 "$LOG"
  return 1
}

mark() { wc -l < "$LOG"; }
since() { tail -n +"$(( $1 + 1 ))" "$LOG"; }

# Wait for the agent to react to the file change. Returns 1 on timeout but the
# caller only warns: the poke value is the assertion, this is a pacing aid.
wait_activity() {  # marker timeout label
  local marker="$1" timeout="$2" label="$3" i=0
  while [ "$i" -lt "$(( timeout * 5 ))" ]; do
    if since "$marker" | grep -qE '\[Remold\]|\[Mixin\]|HotSwap|DYNAMIC-REDEFINE'; then
      sleep 3   # let the single-threaded re-weave worker finish
      return 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then return 1; fi
    sleep 0.2; i=$((i+1))
  done
  log "WARN: no hot-swap activity observed for $label (continuing; poke value decides)"
  return 1
}

trigger_read() {  # label
  local label="$1" i=0 v
  : > "$PROJ/run/$label.trigger"
  while [ "$i" -lt 150 ]; do
    v="$(poke_of "$label")"
    if [ -n "$v" ]; then printf '%s' "$v"; return 0; fi
    sleep 0.2; i=$((i+1))
  done
  return 1
}

poke_of() {  # label -> value of the last "[ANON] <label> poke=<value>" line
  grep -F -- "[ANON] $1 poke=" "$LOG" 2>/dev/null | tail -1 | sed 's/.*poke=//'
}

sha() { sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }

# ---------------------------------------------------------------------- main
log "repo=$REPO"
log "jbr=$JBR"
kill_stale_servers
build_jars
scaffold
build_and_deploy
launch

wait_for_pattern "[ANON] tick poke=hook-s0" "$START_TIMEOUT" "S0 baseline (poke=hook-s0)" \
  || die "S0 baseline never appeared: the Mixin was not woven on a real Fabric server, so no defect can be attributed"
S0_VALUE="$(grep -F '[ANON] tick poke=' "$LOG" | tail -1 | sed 's/.*poke=//')"
log "S0 poke=$S0_VALUE"
[ "$S0_VALUE" = "hook-s0" ] || die "S0 baseline value unexpected: $S0_VALUE"

# ---------------------------------------------------------------------- S1
M1="$(mark)"
cp "$STAGES/AnonTargetMixin.s1.java" "$SRC_MIXIN"
gradle_compile s1
[ -f "$CLS/AnonTargetMixin\$1.class" ] || die "S1 did not produce AnonTargetMixin\$1.class"
mkdir -p "$WORK"
cp "$CLS/AnonTargetMixin\$1.class" "$WORK/anon-s1.class"
cp "$CLS/AnonTargetMixin.class" "$CLS/AnonTargetMixin\$1.class" "$OUT/com/example/anon/"
wait_activity "$M1" 60 "S1" || true
S1_VALUE="$(trigger_read s1)" || die "S1 poke read timed out"
S1_LOG="$(since "$M1")"
log "S1 poke=$S1_VALUE"

# ---------------------------------------------------------------------- S1b
M1B="$(mark)"
# The client workaround: put the anonymous class onto the real classpath.
mkdir -p "$CPFIX/com/example/anon"
cp "$WORK/anon-s1.class" "$CPFIX/com/example/anon/AnonTargetMixin\$1.class"
cp "$STAGES/AnonTargetMixin.s1b.java" "$SRC_MIXIN"
gradle_compile s1b
cp "$CLS/AnonTargetMixin.class" "$WORK/outer-s1b.class"
cp "$CLS/AnonTargetMixin.class" "$CLS/AnonTargetMixin\$1.class" "$OUT/com/example/anon/"
wait_activity "$M1B" 60 "S1b" || true
S1B_VALUE="$(trigger_read s1b)" || die "S1b poke read timed out"
S1B_LOG="$(since "$M1B")"
log "S1b poke=$S1B_VALUE"

# ---------------------------------------------------------------------- S2
M2="$(mark)"
cp "$STAGES/AnonTargetMixin.s2.java" "$SRC_MIXIN"
gradle_compile s2
cp "$CLS/AnonTargetMixin.class" "$WORK/outer-s2.class"
cp "$CLS/AnonTargetMixin\$1.class" "$WORK/anon-s2.class"
# R5 isolation preconditions ------------------------------------------------
[ "$(sha "$WORK/outer-s2.class")" = "$(sha "$WORK/outer-s1b.class")" ] \
  || die "S2 precondition failed: outer AnonTargetMixin.class changed, R5 is not isolated"
[ "$(sha "$WORK/anon-s2.class")" != "$(sha "$WORK/anon-s1.class")" ] \
  || die "S2 precondition failed: AnonTargetMixin\$1.class did not change"
# A full compile rewrites every .class; the outer file keeps identical bytes.
cp "$WORK/outer-s2.class" "$OUT/com/example/anon/AnonTargetMixin.class"
cp "$WORK/anon-s2.class" "$OUT/com/example/anon/AnonTargetMixin\$1.class"
# Restore the S1b source so a later full compile cannot silently revert the stage.
cp "$STAGES/AnonTargetMixin.s1b.java" "$SRC_MIXIN"
wait_activity "$M2" 60 "S2" || true
S2_VALUE="$(trigger_read s2)" || die "S2 poke read timed out"
S2_LOG="$(since "$M2")"
log "S2 poke=$S2_VALUE"

# ---------------------------------------------------------------------- S3
M3="$(mark)"
sleep 6
S3A_VALUE="$(trigger_read s3a)" || die "S3a poke read timed out"
S3B_VALUE="$(trigger_read s3b)" || die "S3b poke read timed out"
S3_LOG="$(since "$M3")"
log "S3 poke=$S3A_VALUE/$S3B_VALUE"

# ------------------------------------------------------------------ verdicts
R3_RED=0; R3_GREEN=0; R5_RED=0; R5_GREEN=0
[ "$S1_VALUE" = "hook-s0" ] && R3_RED=1
[ "$S1_VALUE" = "anon-v1" ] && R3_GREEN=1
[ "$S2_VALUE" = "anon-v1" ] && R5_RED=1
[ "$S2_VALUE" = "anon-v2" ] && R5_GREEN=1

S1_FAIL_LINES="$(printf '%s\n' "$S1_LOG" | grep -E 'ClassNotFoundException|innerClass is null|Failed to reload|NoClassDefFoundError' | head -4)"
S1B_LINES="$(printf '%s\n' "$S1B_LOG" | grep -E 'reloaded; affected target|re-woven with updated mixin|DYNAMIC-REDEFINE' | head -3)"
S2_LINES="$(printf '%s\n' "$S2_LOG" | grep -E 'no-op|runtime bytecode equals the baseline|reloaded; affected target|re-woven with updated mixin|not registered in the startup Mixin config|DYNAMIC-REDEFINE' | head -4)"
S3_ACTIVITY="$(printf '%s\n' "$S3_LOG" | grep -E 'HotSwap successful|reloaded; affected target|re-woven with updated mixin|DYNAMIC-REDEFINE|no-op' | head -4)"

mkdir -p "$HERE/evidence"
{
  echo "# Anonymous-class (Mixin\$N) hot-swap probe — RED baseline"
  echo "date: $(date -Iseconds)"
  echo "git HEAD: $(cd "$REPO" && git rev-parse HEAD 2>/dev/null)"
  echo "agent jar sha256: $(sha "$AGENT")"
  echo "plugin-mixin sha256: $(sha "$PLUGIN_MIXIN")"
  echo "plugin-fabric sha256: $(sha "$PLUGIN_FABRIC")"
  echo "mod jar (run/mods) sha256: $(sha "$PROJ"/run/mods/*.jar)"
  echo "jbr: $JBR"
  echo "mixer: sponge-mixin 0.8.7 (Fabric KnotServer), minecraft 1.21.1"
  echo "launch: $PROJ/build/anon/server.args (mod loaded from run/mods, Gradle output off the runtime classpath)"
  echo "S2 preconditions: outer s1b=$(sha "$WORK/outer-s1b.class" | cut -c1-16) s2=$(sha "$WORK/outer-s2.class" | cut -c1-16) | anon s1=$(sha "$WORK/anon-s1.class" | cut -c1-16) s2=$(sha "$WORK/anon-s2.class" | cut -c1-16)"
  echo
  echo "=== stage verdicts (end result = AnonTarget.poke() returned by the live class) ==="
  echo "S0 BASELINE  poke=$S0_VALUE  -> PASS (Mixin woven; no anonymous class yet)"
  if [ "$R3_RED" = "1" ]; then
    echo "S1 R3        poke=$S1_VALUE  -> RED HIT (new AnonTargetMixin\$1.class is invisible to the Mixin bytecode provider; reload aborts)"
  elif [ "$R3_GREEN" = "1" ]; then
    echo "S1 R3        poke=$S1_VALUE  -> GREEN (anonymous class hot-swap works)"
  else
    echo "S1 R3        poke=$S1_VALUE  -> UNEXPECTED"
  fi
  if [ "$S1B_VALUE" = "anon-v1" ]; then
    echo "S1b R3b      poke=$S1B_VALUE  -> PASS (anonymous class on the real classpath; reload + target re-weave succeed)"
  else
    echo "S1b R3b      poke=$S1B_VALUE  -> UNEXPECTED (workaround did not restore the hook)"
  fi
  if [ "$R5_RED" = "1" ]; then
    echo "S2 R5        poke=$S2_VALUE  -> RED HIT (only the anonymous body changed; the live body is still v1)"
  elif [ "$R5_GREEN" = "1" ]; then
    echo "S2 R5        poke=$S2_VALUE  -> GREEN (anonymous body change reached the live class)"
  else
    echo "S2 R5        poke=$S2_VALUE  -> UNEXPECTED"
  fi
  if [ "$S3A_VALUE" = "$S2_VALUE" ] && [ "$S3B_VALUE" = "$S2_VALUE" ] && [ -z "$S3_ACTIVITY" ]; then
    echo "S3 CONTROL   poke=$S3A_VALUE/$S3B_VALUE  -> PASS (no change => no redefine, value stable)"
  else
    echo "S3 CONTROL   poke=$S3A_VALUE/$S3B_VALUE  -> FAIL (control produced activity; attribution is not established)"
  fi
  echo
  echo "=== S1 excerpt (R3 failure) ==="
  printf '%s\n' "${S1_FAIL_LINES:-<none>}"
  echo
  echo "=== S1b excerpt (workaround) ==="
  printf '%s\n' "${S1B_LINES:-<none>}"
  echo
  echo "=== S2 excerpt (R5 decision lines) ==="
  printf '%s\n' "${S2_LINES:-<none>}"
  echo
  echo "=== S3 excerpt (must be empty) ==="
  printf '%s\n' "${S3_ACTIVITY:-<none>}"
  echo
  echo "=== S0 startup excerpt ==="
  grep -E 'Loading .* mods|anon_probe|\[Remold\] Watch paths|\[Mixin\] Re-weaving bridge|\[Fabric\] Dev-runtime' "$LOG" | head -8
} > "$EVIDENCE"
cat "$EVIDENCE"

if [ "$R3_RED" = "1" ] && [ "$R5_RED" = "1" ]; then
  log "RESULT: RED baseline reproduced (R3 + R5)"
  exit 1
fi
if [ "$R3_GREEN" = "1" ] && [ "$R5_GREEN" = "1" ]; then
  log "RESULT: GREEN (both anonymous-class hot-swap paths work)"
  exit 0
fi
log "RESULT: MIXED — R3 red=$R3_RED green=$R3_GREEN, R5 red=$R5_RED green=$R5_GREEN"
exit 3
