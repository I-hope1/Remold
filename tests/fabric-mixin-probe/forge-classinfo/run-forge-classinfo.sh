#!/usr/bin/env bash
# Forge-side ClassInfo member-sync E2E driver.
#
# Runs a REAL Forge 1.21.1 dedicated server (ForgeGradle 7 `runServer`, offline, cached MDK
# mappers) with the real Remold agent as -javaagent, on a real Mixin target, and drives two real
# Mixin template hot updates:
#
#   stage 1  the template gains a NEW @Inject handler method   (ClassInfo member sync: method)
#   stage 2  the template gains a NEW @Unique field            (ClassInfo member sync: field)
#
# Every stage is a real `javac` into the watched classes directory; the file watcher performs the
# real reload. The probe reads the business end state on a live target instance (counter/field
# values), and this driver records whether a Mixin template reload was actually attempted — so a
# "reload never happened" run can never be mistaken for a red result (see Verdict.java).
#
# Usage: bash run-forge-classinfo.sh [label]
#   label            evidence file name (default: derived from the verdict)
# Env:
#   CI_JBR           JDK 21 home (default C:/Users/ASUS/.jdks/JBR-21)
#   CI_SKIP_BUILD=1  do not rebuild the agent / plugin jars
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$HERE/../../.." && pwd)"
BUILD="$PROJ/build/forge-classinfo"
MDK="$BUILD/mdk"
CTRL="$BUILD/control"
SERVER_LOG="$BUILD/server.log"

log() { printf '[driver] %s\n' "$*"; }
fail() { printf '[driver] FAILED: %s\n' "$*" >&2; exit "${2:-2}"; }

# JDK 21 resolution: the same script may run under Git Bash (POSIX drive paths) or a shell where
# only Windows drive paths resolve, so try both forms plus JAVA_HOME.
resolve_jbr() {
  local candidate
  for candidate in "${CI_JBR:-}" "/c/Users/ASUS/.jdks/JBR-21" "C:/Users/ASUS/.jdks/JBR-21" "${JAVA_HOME:-}"; do
    [ -n "$candidate" ] || continue
    if [ -x "$candidate/bin/java" ] || [ -x "$candidate/bin/java.exe" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

JBR="$(resolve_jbr)" || fail "JDK 21 not found (set CI_JBR to a JDK 21 home)"

[ -x "$JBR/bin/java" ] || fail "JDK 21 not found at $JBR (set CI_JBR)"

# ---------------------------------------------------------------- jars -----
AGENT="$PROJ/core/build/libs/remold-agent.jar"
# Newest plugin jar, not the alphabetically first one: the build directory keeps jars from
# earlier versions, and 0.2.0 sorts before 0.2.4 (an old jar would silently be exercised).
plugin_jar() {
  ls -t "$PROJ"/plugin-forge/build/libs/remold-plugin-forge-*.jar 2>/dev/null | grep -v -- '-sources.jar$' | head -1
}
PLUGIN="$(plugin_jar)"
[ -n "$PLUGIN" ] || fail "missing plugin-forge jar"
log "agent=$(basename "$AGENT") plugin=$(basename "$PLUGIN")"

# ------------------------------------------------------------- MDK copy -----
log "materialising MDK at $MDK"
mkdir -p "$MDK/src/main/java" "$MDK/src/main/resources" "$MDK/run" "$CTRL" "$BUILD"

# wrapper: reuse the repository wrapper (Gradle 9.x, distribution already cached)
cp "$PROJ/gradlew" "$MDK/gradlew"
chmod +x "$MDK/gradlew"
cp "$PROJ/gradlew.bat" "$MDK/gradlew.bat" 2>/dev/null || true
mkdir -p "$MDK/gradle/wrapper"
cp "$PROJ/gradle/wrapper/gradle-wrapper.jar" "$PROJ/gradle/wrapper/gradle-wrapper.properties" "$MDK/gradle/wrapper/"
cp "$HERE/mdk/build.gradle" "$HERE/mdk/settings.gradle" "$HERE/mdk/gradle.properties" "$MDK/"

# sources: MDK resources + overlay mod + Mixin template v1 (the baseline)
rm -rf "$MDK/src"
mkdir -p "$MDK/src/main/java" "$MDK/src/main/resources"
cp -R "$HERE/mdk/src/main/resources/." "$MDK/src/main/resources/"
cp -R "$HERE/mdk-overlay/src/main/java/." "$MDK/src/main/java/"
cp -R "$HERE/mdk-overlay/src/main/resources/." "$MDK/src/main/resources/"
cp "$HERE/mdk-overlay/src/main/java/ciprobe/mixin/ClassInfoTargetMixin.java" \
   "$MDK/src/main/java/ciprobe/mixin/ClassInfoTargetMixin.java"

echo "eula=true" > "$MDK/run/eula.txt"
rm -f "$CTRL"/*.go

cd "$MDK" || fail "cannot cd $MDK"

# ---------------------------------------------------------- compile pass -----
# Compile with javac straight into the watched directory instead of driving Gradle's compileJava:
# an external `rm -rf` of the output dir raced Gradle's up-to-date/VFS state and intermittently left
# the directory without classes ("has no probe classes after compile"), which silently turned a
# real run into a harness failure. javac + a resource copy is deterministic and matches exactly what
# ForgeGradle puts on the run classpath.
log "compiling probe mod (javac into the watched classes dir)"
rm -rf "$MDK/build/sourceSets/main"
mkdir -p "$MDK/build/sourceSets/main"
JAVA_HOME="$JBR" ./gradlew --offline -q printCompileCp 2>/dev/null | tail -1 > "$BUILD/cp.txt"
[ -s "$BUILD/cp.txt" ] || fail "could not capture the compile classpath"
CLASSES_OUT="$(cygpath -m "$MDK/build/sourceSets/main")"
# shellcheck disable=SC2046
"$JBR/bin/javac" --release 21 -proc:none -encoding UTF-8 -cp "$(cat "$BUILD/cp.txt")"   -d "$CLASSES_OUT" $(find "$MDK/src/main/java" -name '*.java')   || fail "javac of the probe sources failed"
cp -R "$MDK/src/main/resources/." "$MDK/build/sourceSets/main/"
log "compile classpath: $(wc -c < "$BUILD/cp.txt") bytes"
[ -f "$MDK/build/sourceSets/main/ciprobe/CiMod.class" ]   || fail "watched classes dir has no probe classes after javac"
[ -f "$MDK/build/sourceSets/main/META-INF/mods.toml" ]   || fail "watched classes dir has no mods.toml after the resource copy"

# ------------------------------------------------------------ server run -----
rm -f "$SERVER_LOG"
log "launching real Forge runServer (offline)"
JAVA_HOME="$JBR" ./gradlew --offline runServer --console=plain \
  -PciAgentJar="$AGENT" -PciPluginJar="$PLUGIN" -PciControlDir="$(cygpath -m "$CTRL")" \
  >> "$SERVER_LOG" 2>&1 < <(sleep 900) &
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
  for pid in $("$JBR/bin/jps" -l 2>/dev/null | awk '/BootstrapLauncher/{print $1}'); do
    log "terminating leftover Forge server JVM pid=$pid"
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
    if grep -qF "Target class ciprobe.ClassInfoTarget re-woven with the updated Mixin and redefined" <<<"$tail_text"        || grep -qF "Mixin re-weaving failed; falling back to source bytecode: ciprobe.ClassInfoTarget" <<<"$tail_text"        || grep -qF "Failed to reload Mixin $mixin;" <<<"$tail_text"        || grep -qF "Unable to read original bytecode for ciprobe.ClassInfoTarget;" <<<"$tail_text"; then
      return 0
    fi
    if ! kill -0 "$GRADLE_PID" 2>/dev/null; then break; fi
    sleep 1
    i=$((i + 1))
  done
  log "WARN no reload attribution for $mixin after ${i}s (stage may be measured before the bridge reported)"
  return 1
}

wait_log "[CI] NEXT stage1" 480 "server start + baseline weave" || fail "probe never reached stage 1; see $SERVER_LOG" 3
log "baseline woven; stage 1 = new @Inject handler"

stage_template() { # <template-class-name> <stage-source-file> <stage>
  local template="$1" staged="$2" stage="$3"
  local source="$MDK/src/main/java/ciprobe/mixin/$template.java"
  local cp
  cp="$(cat "$BUILD/cp.txt");$(cygpath -m "$MDK/build/sourceSets/main")"
  cp "$HERE/stages/$staged" "$source"
  log "javac $template from $staged into the watched classes dir"
  "$JBR/bin/javac" --release 21 -proc:none -encoding UTF-8 -cp "$cp" \
    -d "$MDK/build/sourceSets/main" "$source" \
    || fail "javac of $template ($staged) failed" 4
  touch "$CTRL/$stage.go"
}

# stage 1: the target's first template gains a NEW @Inject handler method
OFF_STAGE1="$(wc -c < "$SERVER_LOG")"
stage_template ClassInfoTargetMixin ClassInfoTargetMixin.v2.java stage1
wait_log "[CI] STAGE1 d1=" 300 "stage 1 measurement" || fail "stage 1 measurement never completed" 5
wait_attribution "$OFF_STAGE1" 180 ciprobe.mixin.ClassInfoTargetMixin || true
log "stage 2 = new @Unique field on the second template"

# stage 2: a second template on the same target gains a NEW @Unique field (separate template, so
# the bridge's once-per-template failure report can attribute each stage on its own)
OFF_STAGE2="$(wc -c < "$SERVER_LOG")"
stage_template ClassInfoTargetSecondMixin ClassInfoTargetSecondMixin.v2.java stage2
wait_log "[CI] STAGE2 d1=" 300 "stage 2 measurement" || fail "stage 2 measurement never completed" 6
wait_attribution "$OFF_STAGE2" 180 ciprobe.mixin.ClassInfoTargetSecondMixin || true

wait_log "[CI] RESULT-PROBE-DONE" 120 "probe completion" || true
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
# Attribute each stage to what the Forge bridge actually did. The stage segment is the log window
# between the probe asking for the stage and the probe reporting its measurement; the reload of
# the Mixin template always happens inside that window.
segment() { # <begin marker> <end marker>
  awk -v b="$1" -v e="$2" 'index($0,b){f=1} f{print} f && index($0,e){exit}' "$SERVER_LOG"
}

classify() { # <template dotted name>; the stage segment arrives on stdin
  local mixin="$1" seg
  seg="$(cat)"
  # Attribution answers ONE question: was the template reload attempted, and was it accepted or
  # rejected? Whether the members actually reached the live target is the probe's job (CI-M1/CI-M2/
  # CI-PRESERVE on business end state) — the bridge re-weaves on a worker thread, and its
  # "re-woven ... redefined" line is emitted from that thread, so it can interleave after the next
  # stage marker. Requiring it inside the stage window made a working run look unattributable.
  if grep -qF "Failed to reload Mixin $mixin;" <<<"$seg"; then
    echo rejected
  elif echo "$seg" | grep -qF "Mixin $mixin reloaded"; then
    echo success
  elif grep -qF "Mixin re-weaving failed; falling back to source bytecode: ciprobe.ClassInfoTarget" <<<"$seg"; then
    echo weave-failed   # no accepted reload, but the bridge did try to re-weave the target
  elif grep -qF "Unable to read original bytecode for ciprobe.ClassInfoTarget;" <<<"$seg"; then
    echo unknown
  elif grep -qF "$mixin is a Mixin template whose class changed" <<<"$seg"; then
    echo unknown
  elif grep -qF "Mixin class-info sync: $mixin" <<<"$seg"; then
    echo unknown   # metadata sync ran but the reload verdict never reached the log
  else
    echo not-attempted
  fi
}

STAGE1_RELOAD="$(segment "[CI] NEXT stage1" "[CI] NEXT stage2" | classify ciprobe.mixin.ClassInfoTargetMixin)"
STAGE2_RELOAD="$(segment "[CI] NEXT stage2" "RESULT-PROBE-DONE" | classify ciprobe.mixin.ClassInfoTargetSecondMixin)"
log "reload attribution: stage1=$STAGE1_RELOAD stage2=$STAGE2_RELOAD"

# -------------------------------------------------------------- evidence -----
LABEL="${1:-}"
if [ -z "$LABEL" ]; then
  if [ "$STAGE1_RELOAD" = "success" ] && [ "$STAGE2_RELOAD" = "success" ]; then
    LABEL="forge-1.21.1-after-fix"
  else
    LABEL="forge-1.21.1-baseline"
  fi
fi
EVIDENCE="$HERE/evidence/$LABEL.txt"
# Never overwrite recorded evidence: an unattributable rerun must not destroy the baseline record.
if [ -f "$EVIDENCE" ]; then
  EVIDENCE="$HERE/evidence/${LABEL}-$(date +%H%M%S).txt"
fi
PROBE_TREE_SHA="$( cd "$HERE" && find . -type f \( -name '*.java' -o -name '*.gradle' -o -name '*.json' -o -name '*.toml' -o -name '*.sh' \) \
  -not -path './evidence/*' -not -path './fixtures/*' -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1 )"

{
  echo "=== Forge ClassInfo member-sync E2E evidence ==="
  echo "label=$LABEL"
  echo "date=$(date -Iseconds)"
  echo "git_head=$(cd "$PROJ" && git rev-parse HEAD)"
  echo "forge=1.21.1-52.1.21 forgegradle=7.0.40 jbr=$("$JBR/bin/java" -version 2>&1 | grep -v 'Picked up' | head -1)"
  echo "launcher=ForgeGradle7 runServer --offline (dedicated server, official mappings)"
  echo "agent_jar=$(basename "$AGENT") sha256=$(sha256sum "$AGENT" | cut -d' ' -f1)"
  echo "plugin_jar=$(basename "$PLUGIN") sha256=$(sha256sum "$PLUGIN" | cut -d' ' -f1)"
  echo "probe_tree_sha256=$PROBE_TREE_SHA"
  echo "gradle_exit=$GRADLE_RC"
  echo "--- server log (Remold + probe markers) ---"
  grep -aE '\[CI|\[Remold\]|\[Forge/NeoForge\]|Mixin|LAYOUT|redefine|Exception' "$SERVER_LOG" | tail -300 || true
  echo "--- driver facts ---"
  echo "[CI-DRIVER] phase=stage1 reload=$STAGE1_RELOAD"
  echo "[CI-DRIVER] phase=stage2 reload=$STAGE2_RELOAD"
} > "$EVIDENCE"

VERDICT_LINE="$("$JBR/bin/java" "$HERE/Verdict.java" "$EVIDENCE")"
echo "$VERDICT_LINE" >> "$EVIDENCE"
log "evidence: $EVIDENCE"
log "$VERDICT_LINE"

case "$VERDICT_LINE" in
  *" VERDICT INVALID"*) exit 7 ;;
  *) exit 0 ;;
esac
