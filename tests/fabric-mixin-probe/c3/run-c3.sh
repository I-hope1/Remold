#!/usr/bin/env bash
# C3 end-to-end driver: real Fabric 1.21.1 KnotServer + real Remold -javaagent.
#
# Every stage is a REAL source edit compiled by the REAL Gradle build into the watched
# build/classes/java/main directory. The agent's file watcher triggers the REAL
# processChanges -> bridge re-weave -> redefineClasses -> InitFix pipeline. Nothing here
# calls RemoldAgent internals directly.
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ" || exit 1
JBR="C:/Users/ASUS/.jdks/JBR-21"
JAVA="$JBR/bin/java"
LOG="$PROJ/c3/run.log"
ITEM="src/main/java/com/example/item/RemoldWandItem.java"
MIXIN="src/main/java/com/example/mixin/RemoldWandItemMixin.java"
ITEM_CLASS="build/classes/java/main/com/example/item/RemoldWandItem.class"

mkdir -p c3 run
rm -f run/c3-stage*.trigger

log() { printf '[driver] %s\n' "$*"; }

compile() {
  local tag="$1"
  log "gradle compileJava ($tag)"
  JAVA_HOME="$JBR" ./gradlew --offline compileJava -q >"c3/compile-$tag.log" 2>&1
  local rc=$?
  if [ $rc -ne 0 ]; then
    log "COMPILE FAILED ($tag) rc=$rc"
    tail -30 "c3/compile-$tag.log"
    exit 2
  fi
}

wait_log() {
  local pat="$1" timeout="$2" i=0
  while [ $i -lt "$timeout" ]; do
    if grep -qF "$pat" "$LOG" 2>/dev/null; then return 0; fi
    sleep 1
    i=$((i+1))
  done
  log "TIMEOUT waiting for log pattern: $pat"
  return 1
}

# ---------------------------------------------------------------- reset to v1
log "reset sources to v1 scaffold"
cp c3/stages/RemoldWandItem.v1.java "$ITEM"
cp c3/stages/RemoldWandItemMixin.v1.java "$MIXIN"
compile reset
if [ ! -f "$ITEM_CLASS" ]; then log "missing $ITEM_CLASS"; exit 2; fi
# no-op control snapshot of the freshly compiled v1 class bytes
cp "$ITEM_CLASS" c3/v1-RemoldWandItem.class

# ---------------------------------------------------------------- launch
log "launching real Fabric KnotServer with -javaagent"
rm -f "$LOG"
env remold.agent.debug=true "$JAVA" @build/remold/server.args >"$LOG" 2>&1 &
SERVER_PID=$!
log "server pid=$SERVER_PID"

cleanup() {
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    log "killing server $SERVER_PID"
    kill "$SERVER_PID" 2>/dev/null
    sleep 2
    kill -9 "$SERVER_PID" 2>/dev/null
  fi
}
trap cleanup EXIT

wait_log "[C3] WAIT-STAGE1" 180 || { cleanup; exit 3; }

# ---------------------------------------------------------------- stage 1: add a method
log "stage1: add remold\$c3Method to the Mixin target source"
cp c3/stages/RemoldWandItem.stage1.java "$ITEM"
compile stage1
touch run/c3-stage1.trigger
wait_log "[C3] STAGE1-DONE" 180 || { cleanup; exit 4; }

# ---------------------------------------------------------------- stage 2: add a field
log "stage2: add remold\$c3Added field to the Mixin target source"
cp c3/stages/RemoldWandItem.stage2.java "$ITEM"
compile stage2
touch run/c3-stage2.trigger
wait_log "[C3] STAGE2-DONE" 180 || { cleanup; exit 5; }

# ---------------------------------------------------------------- stage 3/4: no-op rounds
log "stage3: no-op round #1 (touch identical class bytes)"
touch "$ITEM_CLASS"
sleep 4
touch run/c3-stage3.trigger
wait_log "[C3] STAGE3-DONE" 120 || { cleanup; exit 6; }

log "stage4: no-op round #2 (touch identical class bytes)"
touch "$ITEM_CLASS"
sleep 4
touch run/c3-stage4.trigger
wait_log "[C3] STAGE4-DONE" 120 || { cleanup; exit 7; }

# ---------------------------------------------------------------- stage 5: change the Mixin class
log "stage5: edit the Mixin class itself (MixinHotSwap path)"
cp c3/stages/RemoldWandItemMixin.stage5.java "$MIXIN"
compile stage5
touch run/c3-stage5.trigger
wait_log "[C3] STAGE5-DONE" 180 || { cleanup; exit 8; }

# wait for the probe to halt the JVM
for i in $(seq 1 60); do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then break; fi
  sleep 1
done
wait "$SERVER_PID" 2>/dev/null
SERVER_RC=$?
log "server exit rc=$SERVER_RC"
trap - EXIT

# ---------------------------------------------------------------- evidence summary
{
  echo "=== driver summary ==="
  echo "server_exit=$SERVER_RC"
  echo "--- [C3] probe lines ---"
  grep -F "[C3]" "$LOG" || true
  echo "--- [C3-READ] target @OnReload lines ---"
  grep -F "[C3-READ]" "$LOG" || true
  echo "--- [C3-MIXIN] @Inject/@Unique lines ---"
  grep -F "[C3-MIXIN]" "$LOG" || true
  echo "--- hot-swap pipeline lines ---"
  grep -E "HotSwap successful|Unchanged:|\[Mixin\]|\[POST\]|\[BRIDGE|\[HOTSWAP" "$LOG" || true
  echo "--- INIT-DRIFT lines (must be empty) ---"
  grep -F "[INIT-DRIFT]" "$LOG" || true
  echo "init_drift_count=$(grep -cF '[INIT-DRIFT]' "$LOG" 2>/dev/null || echo 0)"
} | tee c3/summary.txt

log "done"
