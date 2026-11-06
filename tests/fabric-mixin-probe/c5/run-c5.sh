#!/usr/bin/env bash
# C5: Forge (ModLauncher / ForgeGradle 7) limited-time end-to-end driver.
# Real Forge 1.21.1 server + real Remold agent as -javaagent; stage sources are compiled with
# plain javac (a second Gradle build would block on the run task's project lock).
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ" || exit 1
JBR="C:/Users/ASUS/.jdks/JBR-21"
LOG="$PROJ/c5/run.log"
SRC="src/main/java/com/example/examplemod/HotswapTarget.java"
CLASSES="build/sourceSets/main"
CONTROL="run/c5-control"

mkdir -p c5 "$CONTROL"
rm -f "$CONTROL"/c5-stage*.trigger

log() { printf '[driver:c5] %s\n' "$*"; }

wait_log() {
  local pat="$1" timeout="$2" i=0
  while [ $i -lt "$timeout" ]; do
    if grep -qF "$pat" "$LOG" 2>/dev/null; then return 0; fi
    sleep 1
    i=$((i+1))
  done
  log "TIMEOUT waiting for: $pat"
  return 1
}

javac_stage() {
  local tag="$1"
  log "javac $tag"
  "$JBR/bin/javac" --release 21 -proc:none -encoding UTF-8 -cp "$(cat c5/cp.txt)" -d "$CLASSES" "$SRC"
  local rc=$?
  if [ $rc -ne 0 ]; then log "JAVAC FAILED ($tag) rc=$rc"; exit 2; fi
}

cleanup() {
  if [ -n "${SERVER_PID:-}" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    log "killing server $SERVER_PID"
    kill "$SERVER_PID" 2>/dev/null
    sleep 2
    kill -9 "$SERVER_PID" 2>/dev/null
  fi
}
trap cleanup EXIT

log "reset HotswapTarget to v1 and compile the mod"
cp c5/stages/HotswapTarget.v1.java "$SRC"
JAVA_HOME="$JBR" ./gradlew compileJava -q > c5/compile-v1.log 2>&1 || {
  log "compileJava (v1) failed"; tail -20 c5/compile-v1.log; exit 2; }
JAVA_HOME="$JBR" ./gradlew -q printCompileCp 2>/dev/null | tail -1 > c5/cp.txt || {
  log "printCompileCp failed"; tail -10 c5/cp.err; exit 2; }
log "compile classpath captured: $(wc -c < c5/cp.txt) bytes"

log "launching Forge runServer with -javaagent"
rm -f "$LOG"
# Keep stdin open: with --nogui the Forge server stops when stdin reaches EOF.
JAVA_HOME="$JBR" ./gradlew runServer --console=plain >"$LOG" 2>&1 < <(sleep 900) &
SERVER_PID=$!

wait_log "[C5] WAIT-STAGE1" 300 || { cleanup; exit 3; }

log "stage1: add a method"
cp c5/stages/HotswapTarget.stage1.java "$SRC"
javac_stage stage1
touch "$CONTROL/c5-stage1.trigger"
wait_log "[C5] STAGE1-DONE" 180 || { cleanup; exit 4; }

log "stage2: add a field"
cp c5/stages/HotswapTarget.stage2.java "$SRC"
javac_stage stage2
touch "$CONTROL/c5-stage2.trigger"
wait_log "[C5] STAGE2-DONE" 180 || { cleanup; exit 5; }

for i in $(seq 1 90); do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then break; fi
  sleep 1
done
wait "$SERVER_PID" 2>/dev/null
SERVER_RC=$?
trap - EXIT

{
  echo "=== C5 Forge end-to-end ==="
  echo "date: $(date -Iseconds)"
  echo "server_exit=$SERVER_RC"
  grep -aE "\[C5\]|\[C5-READ\]|HotSwap successful|LAYOUT|INIT-DRIFT|Remold\]" "$LOG" | tail -80 || true
} | tee c5/summary.txt

log "done"
