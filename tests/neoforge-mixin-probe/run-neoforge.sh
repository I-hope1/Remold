#!/usr/bin/env bash
# NeoForge native-[[mixins]] Mixin template hot-update E2E driver.
#
# Runs a REAL NeoForge 21.1.x dedicated server dev runtime (ModDevGradle `runServer`, MC 1.21.1)
# with the real Remold agent as -javaagent, on a real Mixin target whose config is registered by
# the loader itself (META-INF/neoforge.mods.toml `[[mixins]] config="neprobe.mixins.json"`), and
# drives two real Mixin template hot updates:
#
#   stage 1  the template gains a NEW @Inject handler method   (ClassInfo member sync: method)
#   stage 2  the template gains a NEW @Unique field            (ClassInfo member sync: field)
#
# Every stage is a real `javac` into the watched classes directory; the file watcher performs the
# real reload. The probe reads the business end state on a live target instance (counter/field
# values), and this driver records whether a Mixin template reload was actually attempted — so a
# "reload never happened" run can never be mistaken for a red result (see Verdict.java).
#
# Usage: bash run-neoforge.sh [label]
#   label                evidence file name (default: derived from the verdict)
# Env:
#   NP_JBR               JDK 21 home (default C:/Users/ASUS/.jdks/JBR-21)
#   NP_AGENT_JAR         remold agent jar (default <repo>/core/build/libs/remold-agent.jar)
#   NP_PLUGIN_JAR        plugin jar (default: newest remold-plugin-forge-*.jar, -sources excluded)
#   NP_MDK_DIR           ModDevGradle workspace (default <repo>/build/neoforge-probe/mdk)
#   NP_SKIP_BUILD=1      do not rebuild the agent / plugin jars
#   NP_OFFLINE=1         pass --offline to Gradle (default 0: the first run must download NeoForge)
#   NP_ADD_OPENS=1       add --add-opens for the sponge-mixin JPMS module (see NEOPROBE.md §6)
#   NP_RED_BASELINE=1    run the red baseline: a plugin jar copy whose ForgeClassInfoSync.sync(...)
#                        returns SyncToken.EMPTY (i.e. member sync disabled), compiled from a
#                        scratch copy of plugin-forge/src/remold/forge/ForgeClassInfoSync.java
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$HERE/../.." && pwd)"
BUILD="${NP_BUILD_DIR:-$PROJ/build/neoforge-probe}"
MDK="${NP_MDK_DIR:-$BUILD/mdk}"
CTRL="$BUILD/control"
SERVER_LOG="$BUILD/server.log"
CLASSES_REL="build/classes/java/main"

log() { printf '[driver] %s\n' "$*"; }
fail() { printf '[driver] FAILED: %s\n' "$*" >&2; exit "${2:-2}"; }

# JDK 21 resolution: this script may run under Git Bash (POSIX drive paths) or from a shell where
# only Windows drive paths resolve, so try both forms plus JAVA_HOME.
resolve_jbr() {
  local candidate
  for candidate in "${NP_JBR:-}" "/c/Users/ASUS/.jdks/JBR-21" "C:/Users/ASUS/.jdks/JBR-21" "${JAVA_HOME:-}"; do
    [ -n "$candidate" ] || continue
    if [ -x "$candidate/bin/java" ] || [ -x "$candidate/bin/java.exe" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

JBR="$(resolve_jbr)" || fail "JDK 21 not found (set NP_JBR to a JDK 21 home)"
log "jbr=$JBR"

# Windows-mixed paths for anything handed to Gradle/Java: MSYS path mangling is not something the
# driver should rely on for -P values that end up inside JVM arguments.
winpath() { cygpath -m "$1" 2>/dev/null || printf '%s' "$1"; }

# ---------------------------------------------------------------- jars -----
if [ "${NP_SKIP_BUILD:-0}" != "1" ]; then
  log "building remold agent + plugin-forge jar (offline)"
  ( cd "$PROJ" && JAVA_HOME="$JBR" ./gradlew --offline -q :core:shadowJar :plugin-forge:jar ) \
    || fail "building the agent / plugin jars failed" 2
fi

AGENT="${NP_AGENT_JAR:-$PROJ/core/build/libs/remold-agent.jar}"
[ -f "$AGENT" ] || fail "missing agent jar: $AGENT"

# Newest plugin jar, not the alphabetically first one: the build directory keeps jars from earlier
# versions, and 0.2.0 sorts before 0.2.4 (an old jar would silently be exercised).
newest_plugin_jar() {
  ls -t "$PROJ"/plugin-forge/build/libs/remold-plugin-forge-*.jar 2>/dev/null \
    | grep -v -- '-sources.jar$' | head -1
}
PLUGIN="${NP_PLUGIN_JAR:-$(newest_plugin_jar)}"
[ -n "$PLUGIN" ] && [ -f "$PLUGIN" ] || fail "missing plugin-forge jar"

# ------------------------------------------------------- red baseline jar -----
# JBR ships javac/jps but no `jar`/`javap` (it is a runtime image); a full JDK on PATH provides
# them and reads any class-file version, so the tool lookup falls back to PATH.
resolve_tool() { # <name>
  local name="$1" candidate
  for candidate in "$JBR/bin/$name" "$JBR/bin/$name.exe"; do
    [ -x "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  command -v "$name" 2>/dev/null && return 0
  return 1
}

# NP_RED_BASELINE=1 rebuilds ONLY the ClassInfo-sync entry point on top of an untouched copy of the
# plugin jar: sync(...) returns SyncToken.EMPTY before doing anything. That is exactly "member sync
# disabled", it exercises the same jar byte-for-byte elsewhere, and it never touches the repo's
# plugin sources.
if [ "${NP_RED_BASELINE:-0}" = "1" ]; then
  JAR_TOOL="$(resolve_tool jar)" || fail "no 'jar' tool found (needed by the red baseline)"
  STUB="$BUILD/stub"
  rm -rf "$STUB"
  mkdir -p "$STUB/src/remold/forge" "$STUB/classes" "$STUB/tree"
  SRC="$PROJ/plugin-forge/src/remold/forge/ForgeClassInfoSync.java"
  [ -f "$SRC" ] || fail "missing $SRC (red baseline needs the real sync implementation)"
  # Insert `return SyncToken.EMPTY;` as the first statement of sync(...): keep everything else
  # (imports, nested types, Javadoc) identical so the compiled class only differs in that body.
  awk '
    { print }
    /static SyncToken sync\(Object transformer, String mixinClassName, Object classNode\) \{/ {
      print "\t\tif (true) { return SyncToken.EMPTY; } // NP red baseline: member sync disabled"
    }
  ' "$SRC" > "$STUB/src/remold/forge/ForgeClassInfoSync.java"
  grep -q "NP red baseline" "$STUB/src/remold/forge/ForgeClassInfoSync.java" \
    || fail "could not inject the red-baseline early return into ForgeClassInfoSync.sync"
  "$JBR/bin/javac" --release 21 -proc:none -encoding UTF-8 -cp "$AGENT" \
    -d "$STUB/classes" "$STUB/src/remold/forge/ForgeClassInfoSync.java" \
    || fail "javac of the red-baseline ForgeClassInfoSync failed" 4
  ( cd "$STUB/tree" && "$JAR_TOOL" xf "$(winpath "$PLUGIN")" ) \
    || fail "could not unpack the plugin jar for the red baseline"
  cp "$STUB"/classes/remold/forge/ForgeClassInfoSync*.class "$STUB/tree/remold/forge/" \
    || fail "could not install the red-baseline class into the plugin jar tree"
  PLUGIN_NOSYNC="$BUILD/remold-plugin-forge-nosync.jar"
  rm -f "$PLUGIN_NOSYNC"
  ( cd "$STUB/tree" && "$JAR_TOOL" --create --file "$(winpath "$PLUGIN_NOSYNC")" . ) \
    || fail "could not repack the red-baseline plugin jar"
  grep -q "SyncToken.EMPTY" "$STUB/src/remold/forge/ForgeClassInfoSync.java" || fail "unexpected stub"
  PLUGIN="$PLUGIN_NOSYNC"
  log "RED BASELINE: plugin jar is a ClassInfo-sync-disabled copy: $(basename "$PLUGIN")"
fi
log "agent=$(basename "$AGENT") plugin=$(basename "$PLUGIN")"

# ------------------------------------------------------------- MDK copy -----
log "materialising ModDevGradle workspace at $MDK"
mkdir -p "$MDK/src/main/java" "$MDK/src/main/resources" "$MDK/run" "$CTRL" "$BUILD"

# wrapper: Gradle 8.12 (ModDevGradle 2.x supports 8.x, not Gradle 9.x; the distribution is cached)
cp "$PROJ/gradlew" "$MDK/gradlew"
chmod +x "$MDK/gradlew"
cp "$PROJ/gradlew.bat" "$MDK/gradlew.bat" 2>/dev/null || true
mkdir -p "$MDK/gradle/wrapper"
cp "$PROJ/gradle/wrapper/gradle-wrapper.jar" "$MDK/gradle/wrapper/"
sed 's|gradle-9\.7\.1-bin\.zip|gradle-8.12-bin.zip|' \
  "$PROJ/gradle/wrapper/gradle-wrapper.properties" > "$MDK/gradle/wrapper/gradle-wrapper.properties"
cp "$HERE/mdk/build.gradle" "$HERE/mdk/settings.gradle" "$HERE/mdk/gradle.properties" "$MDK/"

# sources: MDK resources + overlay mod + Mixin template v1 (the baseline)
rm -rf "$MDK/src/main/java" "$MDK/src/main/resources"
mkdir -p "$MDK/src/main/java" "$MDK/src/main/resources"
cp -R "$HERE/mdk/src/main/resources/." "$MDK/src/main/resources/"
cp -R "$HERE/mdk-overlay/src/main/java/." "$MDK/src/main/java/"
cp -R "$HERE/mdk-overlay/src/main/resources/." "$MDK/src/main/resources/"

# The server's working directory may be <project>/run (ModDevGradle default) or <project> itself;
# the probe reads an absolute trigger directory, so the extra write locations below are belt and
# braces for a human re-running a stage by hand.
mkdir -p "$MDK/run/run" "$CTRL/run"
echo "eula=true" > "$MDK/run/eula.txt"
rm -f "$CTRL"/*.go "$CTRL"/run/*.go "$MDK"/run/*.go "$MDK"/run/run/*.go 2>/dev/null
rm -f "$SERVER_LOG"

GW_OFF=()
[ "${NP_OFFLINE:-0}" = "1" ] && GW_OFF=(--offline)

# gradlew invocation helper: always from the MDK directory with the JDK 21 toolchain.
gw() { ( cd "$MDK" && JAVA_HOME="$JBR" ./gradlew "${GW_OFF[@]}" "$@" ); }

# ---------------------------------------------------------- compile pass -----
log "compiling the probe mod (ModDevGradle, toolchain 21)"
gw -q classes >> "$SERVER_LOG" 2>&1 || { tail -40 "$SERVER_LOG" >&2; fail "compiling the probe mod failed" 2; }
gw -q printCompileCp 2>/dev/null | tail -1 > "$BUILD/cp.txt"
[ -s "$BUILD/cp.txt" ] || fail "could not capture the compile classpath"
CLASSES_MIXED="$(winpath "$MDK/$CLASSES_REL")"
[ -f "$MDK/$CLASSES_REL/neprobe/NeoMod.class" ] || fail "no compiled probe classes under $MDK/$CLASSES_REL"
# Mod metadata is processed into the resources output dir (Gradle `processResources`), which the
# dev launch puts on the mod classpath next to the classes dir.
[ -f "$MDK/build/resources/main/META-INF/neoforge.mods.toml" ] \
  || fail "the resources output has no META-INF/neoforge.mods.toml (loader would not see the mod)"
log "compile classpath: $(wc -c < "$BUILD/cp.txt") bytes"

# --------------------------------------------------------- javap markers -----
# A stale / up-to-date Gradle compile would silently feed the previous stage's mixin bodies into
# the server, making a later stage look green for the wrong reason. Assert on the class file the
# JVM will load before every stage.
javap_of() {
  local class="$1"
  if [ -x "$JBR/bin/javap" ] || [ -x "$JBR/bin/javap.exe" ]; then
    "$JBR/bin/javap" -v -p -cp "$CLASSES_MIXED" "$class" 2>/dev/null && return 0
  fi
  javap -v -p -cp "$CLASSES_MIXED" "$class" 2>/dev/null
}
expect_marker() { # <class> <marker>
  local class="$1" marker="$2"
  if ! javap_of "$class" | grep -qF -- "$marker"; then
    fail "STALE-COMPILE: $class does not contain marker '$marker'" 2
  fi
  log "marker ok: $class contains '$marker'"
}
reject_marker() { # <class> <marker>
  local class="$1" marker="$2"
  if javap_of "$class" | grep -qF -- "$marker"; then
    fail "STALE-COMPILE: $class already contains stage marker '$marker' (baseline is not clean)" 2
  fi
}

expect_marker neprobe.ProbeTarget "String T0"
expect_marker neprobe.mixin.ProbeTargetMixin "uniqueA"
reject_marker neprobe.mixin.ProbeTargetMixin "npInject2"
reject_marker neprobe.mixin.ProbeTargetSecondMixin "ciAdded"

# ------------------------------------------------------------ server run -----
log "launching real NeoForge runServer (ModDevGradle)"
RUN_ARGS=(
  runServer --console=plain
  "-PnpAgentJar=$(winpath "$AGENT")"
  "-PnpPluginJar=$(winpath "$PLUGIN")"
  "-PnpControlDir=$(winpath "$CTRL")"
)
[ "${NP_ADD_OPENS:-0}" = "1" ] && RUN_ARGS+=("-PnpAddOpens=1")
# exec: the subshell becomes the gradlew shell, so GRADLE_PID is the process the trap must kill
# (a non-exec subshell would swallow the signal and leave the JVM running).
( cd "$MDK" && exec env JAVA_HOME="$JBR" ./gradlew "${GW_OFF[@]}" "${RUN_ARGS[@]}" ) \
    >> "$SERVER_LOG" 2>&1 < <(sleep 3600) &
GRADLE_PID=$!

cleanup() {
  if [ -n "${GRADLE_PID:-}" ] && kill -0 "$GRADLE_PID" 2>/dev/null; then
    log "killing gradle runServer pid=$GRADLE_PID"
    kill "$GRADLE_PID" 2>/dev/null
    sleep 2
    kill -9 "$GRADLE_PID" 2>/dev/null
  fi
  # A dedicated-server JVM can outlive a killed Gradle run task; it would hold the run-dir lock.
  local pid
  for pid in $("$JBR/bin/jps" -l 2>/dev/null | awk '/BootstrapLauncher|net.neoforged.devlaunch/{print $1}'); do
    log "terminating leftover NeoForge server JVM pid=$pid"
    kill -9 "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT

wait_log() { # <fixed string> <seconds> <what>
  local pattern="$1" seconds="$2" what="$3" i=0
  while [ "$i" -lt "$seconds" ]; do
    if grep -aqF -- "$pattern" "$SERVER_LOG" 2>/dev/null; then return 0; fi
    if ! kill -0 "$GRADLE_PID" 2>/dev/null; then break; fi
    sleep 2
    i=$((i + 2))
  done
  log "TIMEOUT waiting for $what ($pattern) after ${i}s"
  return 1
}

wait_attribution() { # <log byte offset> <seconds> <mixin>
  # The template reload is handled by a worker thread: its verdict line ("re-woven ... redefined",
  # "Failed to reload Mixin ...", "re-weaving failed") can land AFTER the probe has already measured
  # the stage. Wait for a post-offset verdict line before moving on, otherwise the attribution
  # window closes too early and a successful stage degrades to "unknown".
  local offset="$1" seconds="$2" mixin="$3" i=0
  while [ "$i" -lt "$seconds" ]; do
    local tail_text
    tail_text="$(tail -c +"$offset" "$SERVER_LOG" 2>/dev/null)"
    if grep -qF "Target class neprobe.ProbeTarget re-woven with the updated Mixin and redefined" <<<"$tail_text" \
       || grep -qF "Mixin re-weaving failed; falling back to source bytecode: neprobe.ProbeTarget" <<<"$tail_text" \
       || grep -qF "Failed to reload Mixin $mixin;" <<<"$tail_text" \
       || grep -qF "Unable to read original bytecode for neprobe.ProbeTarget;" <<<"$tail_text"; then
      return 0
    fi
    if ! kill -0 "$GRADLE_PID" 2>/dev/null; then break; fi
    sleep 1
    i=$((i + 1))
  done
  log "WARN no reload attribution for $mixin after ${i}s (stage may be measured before the bridge reported)"
  return 1
}

# The probe starts only after the driver drops probe.start, which happens once the dev server is
# really up ("Done" is the vanilla end-of-startup line). Every trigger is written to four paths: the
# absolute control dir (what the probe polls), a nested control/run variant, and the same two
# conventions under <MDK>/run, so a human re-running a stage by hand cannot miss the difference
# between "the project root" and "the server's working directory".
trigger() { # <name>
  mkdir -p "$CTRL/run" "$MDK/run/run"
  touch "$CTRL/$1" "$CTRL/run/$1" "$MDK/run/$1" "$MDK/run/run/$1"
}

wait_log "Done (" 600 "dedicated server startup" || fail "the NeoForge dev server never reached \"Done\"; see $SERVER_LOG" 3
log "server up; starting the probe"
trigger "probe.start"
wait_log "[NP] NEXT stage1" 300 "probe baseline weave" || fail "the probe never reached stage 1; see $SERVER_LOG" 3
log "baseline woven; stage 1 = new @Inject handler"

stage_template() { # <template-simple-name> <stage-source-file> <stage>
  local template="$1" staged="$2" stage="$3"
  local source="$MDK/src/main/java/neprobe/mixin/$template.java"
  local cp
  cp="$(cat "$BUILD/cp.txt");$CLASSES_MIXED"
  cp "$HERE/stages/$staged" "$source"
  log "javac $template from $staged into the watched classes dir"
  "$JBR/bin/javac" --release 21 -proc:none -encoding UTF-8 -cp "$cp" \
    -d "$CLASSES_MIXED" "$source" \
    || fail "javac of $template ($staged) failed" 4
  trigger "$stage.go"
}

# stage 1: the target's first template gains a NEW @Inject handler method
OFF_STAGE1="$(wc -c < "$SERVER_LOG")"
stage_template ProbeTargetMixin ProbeTargetMixin.v2.java stage1
expect_marker neprobe.mixin.ProbeTargetMixin "npInject2"
wait_log "[NP] STAGE1 d1=" 300 "stage 1 measurement" || fail "stage 1 measurement never completed" 5
wait_attribution "$OFF_STAGE1" 180 neprobe.mixin.ProbeTargetMixin || true

# stage 2: a second template on the same target gains a NEW @Unique field (separate template, so
# the bridge's once-per-template failure report can attribute each stage on its own)
log "stage 2 = new @Unique field on the second template"
OFF_STAGE2="$(wc -c < "$SERVER_LOG")"
stage_template ProbeTargetSecondMixin ProbeTargetSecondMixin.v2.java stage2
expect_marker neprobe.mixin.ProbeTargetSecondMixin "ciAdded"
wait_log "[NP] STAGE2 d1=" 300 "stage 2 measurement" || fail "stage 2 measurement never completed" 6
wait_attribution "$OFF_STAGE2" 180 neprobe.mixin.ProbeTargetSecondMixin || true

# ------------------------------------------------ config diff: remove / re-add ----
CFG="$MDK/build/resources/main/neprobe.mixins.json"
CFG_ORIGINAL="$BUILD/neprobe.mixins.original.json"
cp "$HERE/mdk-overlay/src/main/resources/neprobe.mixins.json" "$CFG_ORIGINAL"

log "config-diff stage: remove ProbeTargetMixin from neprobe.mixins.json"
grep -v '"ProbeTargetMixin"' "$CFG_ORIGINAL" > "$CFG"
touch "$CTRL/config-remove.go"
wait_log "[NP] CONFIG-REMOVE " 240 "config removal measurement" || fail "config removal measurement never completed" 7

log "config-diff stage: re-add ProbeTargetMixin and add ConfigNewMixin"
awk '{ print } /"ProbeTargetSecondMixin"/ { print "    \"ConfigNewMixin\"," }' "$CFG_ORIGINAL" > "$CFG"
touch "$CTRL/config-readd.go"
wait_log "[NP] CONFIG-READD " 240 "config readd measurement" || fail "config readd measurement never completed" 8

wait_log "[NP] RESULT-PROBE-DONE" 120 "probe completion" || true
for _ in $(seq 1 90); do kill -0 "$GRADLE_PID" 2>/dev/null || break; sleep 1; done
if kill -0 "$GRADLE_PID" 2>/dev/null; then
  log "gradle runServer did not exit after the probe halt; terminating"
  kill "$GRADLE_PID" 2>/dev/null; sleep 2; kill -9 "$GRADLE_PID" 2>/dev/null
fi
wait "$GRADLE_PID" 2>/dev/null
GRADLE_RC=$?
trap - EXIT
log "gradle runServer exit=$GRADLE_RC"

# --------------------------------------------------------- reload facts -----
# Attribute each stage to what the Forge/NeoForge bridge actually did. The stage segment is the log
# window between the probe asking for the stage and the probe reporting its measurement; the reload
# of the Mixin template always happens inside that window.
segment() { # <begin marker> <end marker>
  awk -v b="$1" -v e="$2" 'index($0,b){f=1} f{print} f && index($0,e){exit}' "$SERVER_LOG"
}

classify() { # <template dotted name>; the stage segment arrives on stdin
  local mixin="$1" seg
  seg="$(cat)"
  # Attribution answers ONE question: was the template reload attempted, and did it take effect on
  # the target? Whether the members actually reached the live target is the probe's job
  # (NP-M1/NP-M2/NP-PRESERVE on business end state) — the bridge re-weaves on a worker thread, and
  # its "re-woven ... redefined" line can interleave after the next stage marker.
  #
  # Order matters: the bridge can accept the template reload ("Mixin X reloaded") and still fail to
  # re-weave the target, falling back to the source bytecode (which drops the woven members). That
  # shape is `weave-failed`, not `success`, so the target-level failure is checked before the
  # template-level acceptance line.
  if grep -qF "Failed to reload Mixin $mixin;" <<<"$seg"; then
    echo rejected
  elif grep -qF "Mixin re-weaving failed; falling back to source bytecode: neprobe.ProbeTarget" <<<"$seg"; then
    echo weave-failed
  elif echo "$seg" | grep -qF "Mixin $mixin reloaded"; then
    echo success
  elif grep -qF "Unable to read original bytecode for neprobe.ProbeTarget;" <<<"$seg"; then
    echo unknown
  elif grep -qF "$mixin is a Mixin template whose class changed" <<<"$seg"; then
    echo unknown
  elif grep -qF "Mixin class-info sync: $mixin" <<<"$seg"; then
    echo unknown   # metadata sync ran but the reload verdict never reached the log
  else
    echo not-attempted
  fi
}

STAGE1_RELOAD="$(segment "[NP] NEXT stage1" "[NP] NEXT stage2" | classify neprobe.mixin.ProbeTargetMixin)"
STAGE2_RELOAD="$(segment "[NP] NEXT stage2" "RESULT-PROBE-DONE" | classify neprobe.mixin.ProbeTargetSecondMixin)"
log "reload attribution: stage1=$STAGE1_RELOAD stage2=$STAGE2_RELOAD"

# -------------------------------------------------------------- evidence -----
LABEL="${1:-}"
if [ -z "$LABEL" ]; then
  if [ "$STAGE1_RELOAD" = "success" ] && [ "$STAGE2_RELOAD" = "success" ]; then
    LABEL="neoforge-21.1-after-fix"
  else
    LABEL="neoforge-21.1-baseline-red"
  fi
fi
EVIDENCE="$HERE/evidence/$LABEL.txt"
# Never overwrite recorded evidence: an unattributable rerun must not destroy the baseline record.
if [ -f "$EVIDENCE" ]; then
  EVIDENCE="$HERE/evidence/invalid/${LABEL}-$(date +%H%M%S).txt"
  mkdir -p "$HERE/evidence/invalid"
  log "evidence file exists; writing this run to ${EVIDENCE#$HERE/}"
fi
PROBE_TREE_SHA="$( cd "$HERE" && find . -type f \( -name '*.java' -o -name '*.gradle' -o -name '*.json' -o -name '*.toml' -o -name '*.sh' \) \
  -not -path './evidence/*' -not -path './fixtures/*' -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1 )"
MIXIN_JAR_LINE="$(grep -aom1 'sponge-mixin[-0-9.+a-zA-Z]*\.jar' "$SERVER_LOG" 2>/dev/null || true)"

{
  echo "=== NeoForge native-[[mixins]] Mixin reload E2E evidence ==="
  echo "label=$LABEL"
  echo "date=$(date -Iseconds)"
  echo "git_head=$(cd "$PROJ" && git rev-parse HEAD)"
  echo "neoforge=21.1.265 mc=1.21.1 moddevgradle=2.0.148 gradle=8.12 jbr=$("$JBR/bin/java" -version 2>&1 | grep -v 'Picked up' | head -1)"
  echo "launcher=ModDevGradle runServer --offline=${NP_OFFLINE:-0} (dev dedicated server)${MIXIN_JAR_LINE:+ mixin_jar=$MIXIN_JAR_LINE}"
  echo "mixin_registration=neoForge-native META-INF/neoforge.mods.toml [[mixins]] config=\"neprobe.mixins.json\""
  echo "add_opens=${NP_ADD_OPENS:-0}"
  echo "agent_jar=$(basename "$AGENT") sha256=$(sha256sum "$AGENT" | cut -d' ' -f1)"
  echo "plugin_jar=$(basename "$PLUGIN") sha256=$(sha256sum "$PLUGIN" | cut -d' ' -f1)"
  echo "probe_tree_sha256=$PROBE_TREE_SHA"
  echo "gradle_exit=$GRADLE_RC"
  echo "--- server log (Remold + probe markers) ---"
  grep -aE '\[NP|\[Remold\]|\[Forge/NeoForge\]|Mixin|mixin|LAYOUT|redefine|Exception' "$SERVER_LOG" | tail -300 || true
  echo "--- driver facts ---"
  echo "[NP-DRIVER] phase=stage1 reload=$STAGE1_RELOAD"
  echo "[NP-DRIVER] phase=stage2 reload=$STAGE2_RELOAD"
} > "$EVIDENCE"

VERDICT_LINE="$("$JBR/bin/java" "$HERE/Verdict.java" "$EVIDENCE")"
echo "$VERDICT_LINE" >> "$EVIDENCE"
log "evidence: ${EVIDENCE#$PROJ/}"
log "$VERDICT_LINE"

case "$VERDICT_LINE" in
  *" VERDICT INVALID"*) exit 7 ;;
  *) exit 0 ;;
esac
