#!/usr/bin/env bash
# C1+C2 joint reproduction driver.
#
#   bash c3/run-c1c2.sh <remold-agent.jar> <label>
#
# Runs the real Fabric KnotServer with the given agent jar as -javaagent, toggles the real
# entity probe (DynamicProfilerAPI.toggleEntityProbe(Item.class, "use", true)) and then
# performs one real source-edit hot-swap, recording: bytecodeCache hash, the live class field
# table (duplicates!), and the LayoutGate verdict of the subsequent hot-swap.
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ" || exit 1
AGENT_JAR="${1:?usage: run-c1c2.sh <agent-jar> <label>}"
LABEL="${2:-run}"
JBR="C:/Users/ASUS/.jdks/JBR-21"
JAVA="$JBR/bin/java"
LOG="$PROJ/c3/c1c2-$LABEL.log"
ITEM="src/main/java/com/example/item/RemoldWandItem.java"
MIXIN="src/main/java/com/example/mixin/RemoldWandItemMixin.java"
AGENT_SLOT=".gradle/remold/remold-agent.jar"
BACKUP="c3/agent-backup-$LABEL.jar"

mkdir -p c3 run
rm -f run/c1c2-*.trigger

log() { printf '[driver:%s] %s\n' "$LABEL" "$*"; }

compile() {
  local tag="$1"
  JAVA_HOME="$JBR" ./gradlew --offline compileJava -q >"c3/compile-c1c2-$tag.log" 2>&1
  local rc=$?
  if [ $rc -ne 0 ]; then
    log "COMPILE FAILED ($tag) rc=$rc"
    tail -30 "c3/compile-c1c2-$tag.log"
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
  log "TIMEOUT waiting for: $pat"
  return 1
}

# reset sources to the v1 baseline and recompile
cp c3/stages/RemoldWandItem.v1.java "$ITEM"
cp c3/stages/RemoldWandItemMixin.v1.java "$MIXIN"
compile reset

# swap the agent jar under test into the launch slot
cp "$AGENT_SLOT" "$BACKUP"
cp "$AGENT_JAR" "$AGENT_SLOT"
log "agent jar = $AGENT_JAR ($(sha256sum "$AGENT_SLOT" | cut -d' ' -f1 | cut -c1-16))"

cleanup() {
  if kill -0 "${SERVER_PID:-0}" 2>/dev/null; then
    log "killing server $SERVER_PID"
    kill "$SERVER_PID" 2>/dev/null
    sleep 2
    kill -9 "$SERVER_PID" 2>/dev/null
  fi
  cp "$BACKUP" "$AGENT_SLOT" 2>/dev/null
}
trap cleanup EXIT

rm -f "$LOG"
env remold.agent.debug=true "$JAVA" @build/remold/server-c1c2.args >"$LOG" 2>&1 &
SERVER_PID=$!
log "server pid=$SERVER_PID"

wait_log "[C1C2] WAIT-TOGGLE" 180 || { cleanup; exit 3; }

log "toggle entity probe (base = the Mixin target class)"
touch run/c1c2-toggle.trigger
wait_log "[C1C2] TOGGLE-DONE" 180 || { cleanup; exit 4; }

log "real source-edit hot-swap after the toggle"
cp c3/stages/RemoldWandItem.stage1.java "$ITEM"
compile hotswap
touch run/c1c2-hotswap.trigger
wait_log "[C1C2] DONE" 180 || { cleanup; exit 5; }

for i in $(seq 1 60); do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then break; fi
  sleep 1
done
wait "$SERVER_PID" 2>/dev/null
SERVER_RC=$?
trap - EXIT
cp "$BACKUP" "$AGENT_SLOT" 2>/dev/null

{
  echo "=== C1+C2 joint reproduction: $LABEL ==="
  echo "agent_jar_sha256=$(sha256sum "$AGENT_JAR" | cut -d' ' -f1)"
  echo "server_exit=$SERVER_RC"
  grep -aE "\[C1C2\]|\[MIXIN-DUP\]|LAYOUT|REMOVED_FIELD|EXTERNAL-HOTSWAP|HotSwap successful|Retransform failed|Retransform" "$LOG" || true
} | tee "c3/c1c2-$LABEL-summary.txt"

log "done"
