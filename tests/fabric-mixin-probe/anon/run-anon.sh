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
# Extension stages (instrument for the "deep water" campaign; ADDED, the S0-S3
# verdicts and their meaning are untouched):
#   S4a  handler allocates the anonymous instance ONCE and the long-lived field
#        AnonTarget.HELD4 keeps it                            -> poke=held-v1
#   S4b  outer bytes byte-identical (sha256-asserted), ONLY the anonymous body
#        changed (held-v1 -> held-v2)                          -> expected: poke=
#        held-v2 (live instance redefined in place); held-v1 = orphaned old class
#   S4c  control: no file change                                -> value stable
#   S7   NEW field with an initializer that allocates an anonymous instance; the
#        live (pre-hot-swap) instance must end up initialized  -> expected:
#        poke=s7=s7-v1. Documented pre-fix reading: poke=s7=null together with
#        "Field init patch refused ... cannot determine protected status of
#        constructor <Target>$Anonymous$<hash>.<init>(<Target>)".
#        The "-fresh" line (new instance => regenerated constructor) is the
#        control that separates "field patch refused" from "stage broken".
#   S7c  control: no file change
#   S6a  baseline: ONE anonymous class, held by AnonTarget.HELD6 -> poke=s6-v1
#   S6b  1->2 SAME-SHAPE anonymous classes, the new one inserted FIRST (the old
#        one is renumbered $1->$2) and only the old body changed (s6-v1->s6-v2)
#        -> expected pre-fix: poke=s6-v1 ([ANON_ALIGN] ... refused to guess ...
#        kept as orphans ... RESTART is required); post-fix: poke=s6-v2
#   S6c  control: no file change
#   S5   @Unique private field + method added by the hot update and read from the
#        handler's anonymous class                          -> expected:
#        poke=s5:uq-tag:uq-m|anon:s5:uq-tag:uq-m (plus "Nest scan incomplete")
#   S5c  control: no file change
#
# Exit codes (S0-S3 keep their exact meaning; the extension adds profiles):
#   0  GREEN  — everything green: S1 reached anon-v1, S2 reached anon-v2, and
#               S4/S5/S7 reached their expected values, S6 matched ANON_EXPECT
#   1  RED    — the documented red baseline of the deep-water campaign is
#               reproduced: controls S4/S5 green, S6 red (as documented) and S7
#               red (documented refusal). S1/S2 may be green (that defect was
#               fixed earlier) or red (older jars)
#   2  HARNESS ERROR — build/launch/timeout/precondition failure (probe broke)
#   3  MIXED  — partial / unexpected readings; the per-stage table says which
#
# Env knobs:
#   JBR_HOME               DCEVM JDK 21 (default C:/Users/ASUS/.jdks/JBR-21)
#   REMOLD_BUILD_JAVA_HOME JDK used to build the repo jars (default JAVA_HOME,
#                          else C:/Users/ASUS/.jdks/openjdk-25.0.1)
#   ANON_SKIP_BUILD=1      reuse already built repo jars
#   ANON_START_TIMEOUT     seconds to wait for the S0 baseline (default 300)
#   ANON_EXPECT=auto|red|green
#                          expected S6 end state. auto (default) accepts either
#                          and picks the matching profile; red requires the
#                          renumbering defect (pre-fix); green requires the fix.
#   ANON_EVIDENCE=<path>   explicit evidence file (default: evidence/anon-ext-red.txt
#                          for RED/MIXED, evidence/anon-ext-green.txt for GREEN)
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "$0")" && pwd -W)"
REPO="$(cd "$HERE/../../.." && pwd -W)"
JBR="${JBR_HOME:-C:/Users/ASUS/.jdks/JBR-21}"
JAVA="$JBR/bin/java"
BUILD_JAVA="${REMOLD_BUILD_JAVA_HOME:-${JAVA_HOME:-C:/Users/ASUS/.jdks/openjdk-25.0.1}}"
START_TIMEOUT="${ANON_START_TIMEOUT:-300}"
ANON_EXPECT="${ANON_EXPECT:-auto}"

PROJ="$HERE/build/proj"
STAGES="$HERE/stages"
WORK="$HERE/build/anon"
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

# Extension stages add/remove anonymous classes. A stale AnonTargetMixin$N.class
# left over from the previous stage would enter the batch as a phantom member, so
# the class output directory is wiped before compiling those stages (Gradle then
# recompiles the fixture from scratch) and out/production is re-synchronised as a
# single tight delete+copy, i.e. one debounced batch.
gradle_compile_fresh() {  # tag
  rm -rf "$PROJ/build/classes/java/main"
  gradle_compile "$1"
  [ -d "$CLS" ] || die "stage $1 produced no class output at $CLS"
}

sync_out() {  # push the fully compiled com/example/anon set into the watch dir
  mkdir -p "$OUT/com/example/anon"
  rm -f "$OUT/com/example/anon"/*.class
  cp "$CLS"/*.class "$OUT/com/example/anon/"
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
  # A missing answer means the probe thread stopped calling the target; dump the
  # tail so the failure is diagnosable instead of a bare timeout.
  log "no [ANON] $label poke= line within 30s; last server lines:"
  tail -12 "$LOG"
  return 1
}

# Wait until the hot-swap pipeline is really quiet again: at least one agent line
# after the marker, then no new agent line for 3 s. The extension stages need this
# (not just "some activity"): a batch that is still re-weaving when the trigger is
# dropped answers with the PREVIOUS stage's value, which would be recorded as a
# false reading.
wait_settle() {  # marker timeout label
  local marker="$1" timeout="$2" label="$3" i=0 seen=0 quiet=0 last=-1 n
  while [ "$i" -lt "$(( timeout * 4 ))" ]; do
    n="$(since "$marker" | grep -cE '\[Remold\]|\[Mixin\]|HotSwap|DYNAMIC-REDEFINE' || true)"
    if [ "${n:-0}" -gt 0 ]; then seen=1; fi
    if [ "$seen" = "1" ] && [ "$n" = "$last" ]; then quiet=$((quiet+1)); else quiet=0; fi
    last="$n"
    if [ "$seen" = "1" ] && [ "$quiet" -ge 12 ]; then
      sleep 1
      return 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then return 1; fi
    sleep 0.25; i=$((i+1))
  done
  log "WARN: hot-swap activity for $label did not settle within ${timeout}s (continuing; poke value decides)"
  return 1
}

poke_of() {  # label -> value of the last "[ANON] <label> poke=<value>" line
  grep -F -- "[ANON] $1 poke=" "$LOG" 2>/dev/null | tail -1 | sed 's/.*poke=//'
}

fresh_of() {  # label -> value of the last "[ANON] <label>-fresh poke=<value>" line
  grep -F -- "[ANON] $1-fresh poke=" "$LOG" 2>/dev/null | tail -1 | sed 's/.*poke=//'
}

sha() { sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }

# ---------------------------------------------------------------------- main
log "repo=$REPO"
log "jbr=$JBR"
log "expect=$ANON_EXPECT"
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
# Do not start hot-swapping while Minecraft is still loading: a batch that runs
# during startup can race the class-loading locks and hang the probe thread
# (observed once: no ticks after S1). Wait for the server to be ready.
wait_for_pattern "Done (" "$START_TIMEOUT" "server ready (Done (...))"   || die "server never finished starting"
sleep 2

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

# =========================================================================
# Extension stages. Every one of them: (a) a stage that establishes its own
# baseline, (b) the change under test, (c) a no-change control window.
# =========================================================================

# ---------------------------------------------------------------------- S4
# Long-lived field holding the anonymous instance; only its body changes.
M4A="$(mark)"
cp "$STAGES/AnonTargetMixin.s4a.java" "$SRC_MIXIN"
gradle_compile_fresh s4a
cp "$CLS/AnonTargetMixin.class" "$WORK/outer-s4a.class"
cp "$CLS/AnonTargetMixin\$1.class" "$WORK/anon-s4a.class"
sync_out
wait_settle "$M4A" 90 "S4a" || true
S4A_VALUE="$(trigger_read s4a)" || die "S4a poke read timed out"
log "S4a poke=$S4A_VALUE"

M4B="$(mark)"
cp "$STAGES/AnonTargetMixin.s4b.java" "$SRC_MIXIN"
gradle_compile_fresh s4b
cp "$CLS/AnonTargetMixin.class" "$WORK/outer-s4b.class"
cp "$CLS/AnonTargetMixin\$1.class" "$WORK/anon-s4b.class"
# S4 isolation preconditions ------------------------------------------------
[ "$(sha "$WORK/outer-s4b.class")" = "$(sha "$WORK/outer-s4a.class")" ] \
  || die "S4 precondition failed: outer AnonTargetMixin.class changed, the 'body only' change is not isolated"
[ "$(sha "$WORK/anon-s4b.class")" != "$(sha "$WORK/anon-s4a.class")" ] \
  || die "S4 precondition failed: AnonTargetMixin\$1.class did not change"
sync_out
wait_settle "$M4B" 90 "S4b" || true
S4B_VALUE="$(trigger_read s4b)" || die "S4b poke read timed out"
S4B_LOG="$(since "$M4B")"
log "S4b poke=$S4B_VALUE"

# S4c control: no file change at all.
M4C="$(mark)"
sleep 6
S4C_VALUE="$(trigger_read s4c)" || die "S4c poke read timed out"
S4C_LOG="$(since "$M4C")"
log "S4c poke=$S4C_VALUE"

# ---------------------------------------------------------------------- S7
# NEW field whose initializer allocates an anonymous-class instance.
M7="$(mark)"
cp "$STAGES/AnonTargetMixin.s7.java" "$SRC_MIXIN"
gradle_compile_fresh s7
cp "$CLS/AnonTargetMixin.class" "$WORK/outer-s7.class"
sync_out
wait_settle "$M7" 90 "S7" || true
S7_VALUE="$(trigger_read s7)" || die "S7 poke read timed out"
S7_FRESH="$(fresh_of s7)"
S7_LOG="$(since "$M7")"
log "S7 poke=$S7_VALUE fresh=$S7_FRESH"

# S7c control: no file change at all.
M7C="$(mark)"
sleep 6
S7C_VALUE="$(trigger_read s7c)" || die "S7c poke read timed out"
S7C_FRESH="$(fresh_of s7c)"
S7C_LOG="$(since "$M7C")"
log "S7c poke=$S7C_VALUE fresh=$S7C_FRESH"

# ---------------------------------------------------------------------- S6
# S6a baseline: ONE anonymous class, held long-term.
M6A="$(mark)"
cp "$STAGES/AnonTargetMixin.s6a.java" "$SRC_MIXIN"
gradle_compile_fresh s6a
cp "$CLS/AnonTargetMixin.class" "$WORK/outer-s6a.class"
cp "$CLS/AnonTargetMixin\$1.class" "$WORK/anon-s6a.class"
sync_out
wait_settle "$M6A" 90 "S6a" || true
S6A_VALUE="$(trigger_read s6a)" || die "S6a poke read timed out"
log "S6a poke=$S6A_VALUE"

# S6b: 1 -> 2 same-shape anonymous classes (renumbering $1 -> $2).
M6B="$(mark)"
cp "$STAGES/AnonTargetMixin.s6b.java" "$SRC_MIXIN"
gradle_compile_fresh s6b
cp "$CLS/AnonTargetMixin.class" "$WORK/outer-s6b.class"
cp "$CLS/AnonTargetMixin\$1.class" "$WORK/anon-s6b-1.class"
cp "$CLS/AnonTargetMixin\$2.class" "$WORK/anon-s6b-2.class"
# S6 preconditions ----------------------------------------------------------
[ -f "$WORK/anon-s6b-1.class" ] && [ -f "$WORK/anon-s6b-2.class" ] \
  || die "S6 precondition failed: the renumbering stage did not produce two anonymous classes"
[ "$(sha "$WORK/anon-s6b-2.class")" != "$(sha "$WORK/anon-s6a.class")" ] \
  || die "S6 precondition failed: the renumbered class is byte-identical to the baseline (no body change)"
sync_out
wait_settle "$M6B" 90 "S6b" || true
S6B_VALUE="$(trigger_read s6b)" || die "S6b poke read timed out"
S6B_LOG="$(since "$M6B")"
log "S6b poke=$S6B_VALUE"

# S6c control: no file change at all.
M6C="$(mark)"
sleep 6
S6C_VALUE="$(trigger_read s6c)" || die "S6c poke read timed out"
S6C_LOG="$(since "$M6C")"
log "S6c poke=$S6C_VALUE"

# ---------------------------------------------------------------------- S5
# @Unique private members added by the hot update and read from the anonymous
# class (Nest access + InitFix field patch for the live instance).
M5="$(mark)"
cp "$STAGES/AnonTargetMixin.s5.java" "$SRC_MIXIN"
gradle_compile_fresh s5
cp "$CLS/AnonTargetMixin.class" "$WORK/outer-s5.class"
sync_out
wait_settle "$M5" 90 "S5" || true
S5_VALUE="$(trigger_read s5)" || die "S5 poke read timed out"
S5_FRESH="$(fresh_of s5)"
S5_LOG="$(since "$M5")"
log "S5 poke=$S5_VALUE fresh=$S5_FRESH"

# S5c control: no file change at all.
M5C="$(mark)"
sleep 6
S5C_VALUE="$(trigger_read s5c)" || die "S5c poke read timed out"
S5C_FRESH="$(fresh_of s5c)"
S5C_LOG="$(since "$M5C")"
log "S5c poke=$S5C_VALUE fresh=$S5C_FRESH"

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

# --- extension stage verdicts ---------------------------------------------
S4_OK=0; S4_RED=0
[ "$S4A_VALUE" = "held-v1" ] && [ "$S4B_VALUE" = "held-v2" ] && S4_OK=1
[ "$S4A_VALUE" = "held-v1" ] && [ "$S4B_VALUE" = "held-v1" ] && S4_RED=1

S7_OK=0; S7_RED=0
[ "$S7_VALUE" = "s7=s7-v1" ] && S7_OK=1
# The documented pre-fix reading: the live instance was never initialized (the
# field patch was refused) while a freshly constructed instance is correct.
if [ "$S7_FRESH" = "s7=s7-v1" ] && [ "$S7_VALUE" != "s7=s7-v1" ]; then S7_RED=1; fi

S6_GREEN=0; S6_RED=0
[ "$S6B_VALUE" = "s6-v2" ] && S6_GREEN=1
[ "$S6A_VALUE" = "s6-v1" ] && [ "$S6B_VALUE" = "s6-v1" ] && S6_RED=1

S5_OK=0; S5_RED=0
[ "$S5_VALUE" = "s5:uq-tag:uq-m|anon:s5:uq-tag:uq-m" ] && S5_OK=1
# A read that failed for any reason (IllegalAccessError, missing member) is also
# a hard reading of "the access does not work"; it is not "green".
[ "$S5_OK" = "0" ] && S5_RED=1

# Controls: the value must not drift on its own, and no re-weave may happen.
CTRL_OK=1
[ "$S4C_VALUE" = "$S4B_VALUE" ] || CTRL_OK=0
[ "$S7C_VALUE" = "$S7_VALUE" ] || CTRL_OK=0
[ "$S6C_VALUE" = "$S6B_VALUE" ] || CTRL_OK=0
[ "$S5C_VALUE" = "$S5_VALUE" ] || CTRL_OK=0
[ "$S7C_FRESH" = "$S7_FRESH" ] || CTRL_OK=0
[ "$S5C_FRESH" = "$S5_FRESH" ] || CTRL_OK=0
CTRL_ACTIVITY="$(printf '%s\n%s\n%s\n%s\n' "$S4C_LOG" "$S7C_LOG" "$S6C_LOG" "$S5C_LOG" \
  | grep -E 'HotSwap successful|reloaded; affected target|re-woven with updated mixin|DYNAMIC-REDEFINE|no-op' | head -6)"
[ -z "$CTRL_ACTIVITY" ] || CTRL_OK=0

# S6 expectation by mode.
S6_REQ_GREEN=1; S6_REQ_RED=1
case "$ANON_EXPECT" in
  green) S6_REQ_RED=0 ;;
  red)   S6_REQ_GREEN=0 ;;
  auto)  ;;
  *)     die "ANON_EXPECT must be auto|red|green (got '$ANON_EXPECT')" ;;
esac

OLD_GREEN=0; OLD_RED=0
[ "$R3_GREEN" = "1" ] && [ "$R5_GREEN" = "1" ] && OLD_GREEN=1
[ "$R3_RED" = "1" ] && [ "$R5_RED" = "1" ] && OLD_RED=1

EXT_GREEN=0
if [ "$S4_OK" = "1" ] && [ "$S5_OK" = "1" ] && [ "$S7_OK" = "1" ] \
   && [ "$S6_GREEN" = "1" ] && [ "$S6_REQ_GREEN" = "1" ] && [ "$CTRL_OK" = "1" ]; then
  EXT_GREEN=1
fi
EXT_RED=0
if [ "$S4_OK" = "1" ] && [ "$S5_OK" = "1" ] && [ "$S7_RED" = "1" ] \
   && [ "$S6_RED" = "1" ] && [ "$S6_REQ_RED" = "1" ] && [ "$CTRL_OK" = "1" ]; then
  EXT_RED=1
fi

S6_LINES="$(printf '%s\n' "$S6B_LOG" | grep -E '\[ANON_ALIGN\]|kept as orphans|RESTART|inner class hot update|Deterministic|deterministic' | head -6)"
S4B_LINES="$(printf '%s\n' "$S4B_LOG" | grep -E 'inner class hot update|redefined in place|no-op|refused to guess' | head -5)"
S7_LINES="$(printf '%s\n' "$S7_LOG" | grep -E 'Field init patch refused|cannot determine protected status|Nest scan incomplete|REINIT|Applying static field init patch|field init patch' | head -8)"
S5_LINES="$(printf '%s\n' "$S5_LOG" | grep -E 'Nest scan incomplete|IllegalAccessError|REINIT|field init patch|refused' | head -8)"
S5_ERR_LINES="$(printf '%s\n' "$S5_LOG" | grep -E '\[ANON\] probe error|IllegalAccessError|NoSuchFieldError|NoSuchMethodError|AbstractMethodError' | head -4)"

# --- evidence --------------------------------------------------------------
VERDICT="MIXED"
EXIT_CODE=3
if [ "$OLD_GREEN" = "1" ] && [ "$EXT_GREEN" = "1" ]; then
  VERDICT="GREEN"; EXIT_CODE=0
elif [ "$EXT_RED" = "1" ] && { [ "$OLD_GREEN" = "1" ] || [ "$OLD_RED" = "1" ]; }; then
  VERDICT="RED baseline reproduced"; EXIT_CODE=1
fi

mkdir -p "$HERE/evidence"
if [ -n "${ANON_EVIDENCE:-}" ]; then
  EVIDENCE="$ANON_EVIDENCE"
elif [ "$EXIT_CODE" = "0" ]; then
  EVIDENCE="$HERE/evidence/anon-ext-green.txt"
else
  EVIDENCE="$HERE/evidence/anon-ext-red.txt"
fi

{
  echo "# Anonymous-class (Mixin\$N) hot-swap probe — extension stages (deep-water instrument)"
  echo "# result: $VERDICT (exit $EXIT_CODE), ANON_EXPECT=$ANON_EXPECT"
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
  echo "S4 preconditions: outer s4a=$(sha "$WORK/outer-s4a.class" | cut -c1-16) s4b=$(sha "$WORK/outer-s4b.class" | cut -c1-16) | anon s4a=$(sha "$WORK/anon-s4a.class" | cut -c1-16) s4b=$(sha "$WORK/anon-s4b.class" | cut -c1-16)"
  echo "S6 preconditions: baseline \$1=$(sha "$WORK/anon-s6a.class" | cut -c1-16) | renumber \$1=$(sha "$WORK/anon-s6b-1.class" | cut -c1-16) \$2=$(sha "$WORK/anon-s6b-2.class" | cut -c1-16)"
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
  echo "=== extension stage verdicts ==="
  if [ "$S4_OK" = "1" ]; then
    echo "S4 HELD-FIELD  s4a=$S4A_VALUE s4b=$S4B_VALUE -> GREEN (the live instance held by AnonTarget.HELD4 was redefined in place: body-only change reached it)"
  elif [ "$S4_RED" = "1" ]; then
    echo "S4 HELD-FIELD  s4a=$S4A_VALUE s4b=$S4B_VALUE -> RED (body-only change did NOT reach the live held instance; the old anonymous class is an orphan)"
  else
    echo "S4 HELD-FIELD  s4a=$S4A_VALUE s4b=$S4B_VALUE -> UNEXPECTED (expected s4a=held-v1 then s4b=held-v2|held-v1)"
  fi
  if [ "$S7_OK" = "1" ]; then
    echo "S7 FIELD-INIT  poke=$S7_VALUE fresh=$S7_FRESH -> GREEN (the field added with an anonymous-class initializer was initialized on the LIVE instance)"
  elif [ "$S7_RED" = "1" ]; then
    echo "S7 FIELD-INIT  poke=$S7_VALUE fresh=$S7_FRESH -> RED HIT (live instance was never initialized; a fresh instance is correct, so the field patch was refused — see the S7 excerpt)"
  else
    echo "S7 FIELD-INIT  poke=$S7_VALUE fresh=$S7_FRESH -> UNEXPECTED (expected poke=s7=s7-v1, or poke=s7=null with fresh=s7=s7-v1)"
  fi
  if [ "$S6_GREEN" = "1" ]; then
    echo "S6 RENUMBER    s6a=$S6A_VALUE s6b=$S6B_VALUE -> GREEN (renumbered same-shape anonymous class paired and redefined in place)"
  elif [ "$S6_RED" = "1" ]; then
    echo "S6 RENUMBER    s6a=$S6A_VALUE s6b=$S6B_VALUE -> RED HIT (1->2 same-shape anonymous classes: the old class stayed an orphan and its live instance keeps running the old code)"
  else
    echo "S6 RENUMBER    s6a=$S6A_VALUE s6b=$S6B_VALUE -> UNEXPECTED (expected s6a=s6-v1 then s6b=s6-v2|s6-v1)"
  fi
  if [ "$S5_OK" = "1" ]; then
    echo "S5 UNIQUE-ACCESS  poke=$S5_VALUE fresh=$S5_FRESH -> GREEN (@Unique private field+method readable both same-class and through the anonymous class; the added field was patched on the live instance)"
  else
    echo "S5 UNIQUE-ACCESS  poke=$S5_VALUE fresh=$S5_FRESH -> RED (the @Unique private access and/or the field patch failed — see the S5 excerpt)"
  fi
  echo
  if [ "$CTRL_OK" = "1" ]; then
    echo "CONTROLS      s4c=$S4C_VALUE s7c=$S7C_VALUE s6c=$S6C_VALUE s5c=$S5C_VALUE -> PASS (no change => value stable, no redefine/reload activity)"
  else
    echo "CONTROLS      s4c=$S4C_VALUE s7c=$S7C_VALUE s6c=$S6C_VALUE s5c=$S5C_VALUE -> FAIL (the value drifted without a file change, or a re-weave happened; attribution is not established)"
    printf '%s\n' "${CTRL_ACTIVITY:-<none>}"
  fi
  echo
  echo "=== overall ==="
  echo "old stages: R3 red=$R3_RED green=$R3_GREEN | R5 red=$R5_RED green=$R5_GREEN"
  echo "extension : S4_ok=$S4_OK S5_ok=$S5_OK S7_ok=$S7_OK S7_red=$S7_RED S6_green=$S6_GREEN S6_red=$S6_RED ctrl_ok=$CTRL_OK"
  echo "expectation: ANON_EXPECT=$ANON_EXPECT (S6 must be green=$S6_REQ_GREEN red=$S6_REQ_RED)"
  echo "profiles  : EXT_GREEN=$EXT_GREEN EXT_RED=$EXT_RED OLD_GREEN=$OLD_GREEN OLD_RED=$OLD_RED"
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
  echo "=== S4 excerpt (held-instance body-only change) ==="
  printf '%s\n' "${S4B_LINES:-<none>}"
  echo
  echo "=== S6 excerpt (1->2 same-shape renumbering) ==="
  printf '%s\n' "${S6_LINES:-<none>}"
  echo
  echo "=== S7 excerpt (field initializer / constructor visibility) ==="
  printf '%s\n' "${S7_LINES:-<none>}"
  echo
  echo "=== S5 excerpt (@Unique private access / nest scan) ==="
  printf '%s\n' "${S5_LINES:-<none>}"
  printf '%s\n' "${S5_ERR_LINES:-}"
  echo
  echo "=== S0 startup excerpt ==="
  grep -E 'Loading .* mods|anon_probe|\[Remold\] Watch paths|\[Mixin\] Re-weaving bridge|\[Fabric\] Dev-runtime' "$LOG" | head -8
} > "$EVIDENCE"
cat "$EVIDENCE"
log "evidence: $EVIDENCE"

case "$EXIT_CODE" in
  0) log "RESULT: GREEN (old + extension stages all reached their expected end state)" ;;
  1) log "RESULT: RED baseline reproduced (S6 renumbering defect + S7 field-init refusal; S4/S5 controls green)" ;;
  *) log "RESULT: MIXED — $VERDICT; see the per-stage table in $EVIDENCE" ;;
esac
exit "$EXIT_CODE"
