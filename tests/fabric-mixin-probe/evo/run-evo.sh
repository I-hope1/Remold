#!/usr/bin/env bash
# Item 2.6: @Inject SELF-evolution end-to-end driver on Fabric 1.21.1 (real KnotServer + real Remold agent).
#
# Scenarios (each driven by a real `./gradlew compileJava` and a real hot swap):
#   A  cancellable evolution : @Inject v1 non-cancellable -> v2 cancellable + setReturnValue("CANCELLED")
#   B  target-method switch : hook moves from methodOne() to methodTwo(), methodOne restored
#   C  add/remove @Inject method : the Mixin class gains a second handler, then loses it
#
# Usage:
#   bash evo/run-evo.sh                 # green run; exit 0 iff all assertions pass
#   EVO_MUTATION=cancellable bash evo/run-evo.sh
#                                       # mutation control: stage A2 installs a non-cancellable v2,
#                                       # scenario-A assertions must go red (exit 0 only if they do)
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ" || exit 1
JBR="${JBR:-C:/Users/ASUS/.jdks/JBR-21}"
JAVA="$JBR/bin/java"
LOG="$PROJ/run/evo.log"
MUTATION="${EVO_MUTATION:-}"
MODE="normal"
[ -n "$MUTATION" ] && MODE="mutation-$MUTATION"

A="src/main/java/com/example/evo/mixin/EvoAMixin.java"
B="src/main/java/com/example/evo/mixin/EvoBMixin.java"
C="src/main/java/com/example/evo/mixin/EvoCMixin.java"
TA="src/main/java/com/example/evo/EvoTarget.java"
TB="src/main/java/com/example/evo/EvoSwitchTarget.java"
TC="src/main/java/com/example/evo/EvoLifecycleTarget.java"

mkdir -p run evo
rm -f run/evo-stage*.trigger

log() { printf '[driver:evo] %s\n' "$*"; }

compile() {
  local tag="$1"
  log "gradle compileJava ($tag)"
  JAVA_HOME="$JBR" ./gradlew --offline processResources compileJava -q >"evo/compile-$tag.log" 2>&1
  local rc=$?
  if [ $rc -ne 0 ]; then
    log "COMPILE FAILED ($tag) rc=$rc"
    tail -30 "evo/compile-$tag.log"
    exit 2
  fi
}

wait_log() {
  local pat="$1" timeout="$2" i=0
  while [ $i -lt "$timeout" ]; do
    if grep -qF "$pat" "$LOG" 2>/dev/null; then return 0; fi
    # Fail fast instead of burning the whole timeout when the launch itself died:
    # a stale KnotServer on 25565 or an early crash keeps the probe thread from ever
    # seeing the trigger, which would otherwise look like a probe timeout.
    if grep -qF "FAILED TO BIND TO PORT" "$LOG" 2>/dev/null; then
      log "server failed to bind port 25565 (another KnotServer still running?)"
      return 1
    fi
    if [ -n "${SERVER_PID:-}" ] && ! kill -0 "$SERVER_PID" 2>/dev/null; then
      log "server process $SERVER_PID exited before '$pat'"
      return 1
    fi
    sleep 1
    i=$((i+1))
  done
  log "TIMEOUT waiting for log pattern: $pat"
  return 1
}

port_busy() {
  netstat -ano 2>/dev/null | grep -qE "[:.]25565[[:space:]].*LISTENING"
}

cleanup() {
  if [ -n "${SERVER_PID:-}" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    log "killing server $SERVER_PID"
    kill "$SERVER_PID" 2>/dev/null
    sleep 2
    kill -9 "$SERVER_PID" 2>/dev/null
  fi
  # Let the port settle before the next invocation of the driver.
  local i=0
  while [ $i -lt 30 ] && port_busy; do
    sleep 1
    i=$((i+1))
  done
}

# ---------------------------------------------------------------- reset sources to v1
log "reset sources to v1"
cp evo/stages/EvoTarget.v1.java "$TA"
cp evo/stages/EvoSwitchTarget.v1.java "$TB"
cp evo/stages/EvoLifecycleTarget.v1.java "$TC"
cp evo/stages/EvoAMixin.v1.java "$A"
cp evo/stages/EvoBMixin.v1.java "$B"
cp evo/stages/EvoCMixin.v1.java "$C"
compile reset

# ---------------------------------------------------------------- launcher args
# Regenerate the launcher args from the Remold Gradle plugin so the -javaagent points at
# THIS worktree's agent/plugins, then switch the auto-generated KnotClient entry to KnotServer.
log "regenerate evo/server.args (worktree agent/plugins)"
JAVA_HOME="$JBR" ./gradlew --offline generateRemoldScript -q >"evo/generate-args.log" 2>&1
if [ $? -ne 0 ]; then
  log "GENERATE ARGS FAILED"
  tail -30 evo/generate-args.log
  exit 2
fi
# The auto-generated args target a Loom *client* run; rewrite the entry point to KnotServer.
# -Dfabric.development=true is what applies the dev access wideners (without it Minecraft's
# own package-private accesses fail with IllegalAccessError on startup).
awk '
  $0 == "net.fabricmc.loader.impl.launch.knot.KnotClient" {
    print "-Dfabric.development=true"
    print "net.fabricmc.loader.impl.launch.knot.KnotServer"
    next
  }
  { print }
' build/remold/remold.args > evo/server.args
echo "--nogui" >> evo/server.args
if ! grep -q "KnotServer" evo/server.args; then
  log "args regeneration did not yield a KnotServer entry"
  exit 2
fi
if ! grep -q "wt-inject-evolution" evo/server.args; then
  log "args file does not point at this worktree"
  exit 2
fi
log "agent=$(grep -o 'javaagent:[^=]*' evo/server.args | head -1)"

# ---------------------------------------------------------------- launch
if port_busy; then
  log "port 25565 is already in use; refusing to launch (stale KnotServer?)"
  exit 2
fi
log "launching real Fabric KnotServer with -javaagent ($MODE)"
rm -f "$LOG"
"$JAVA" @evo/server.args >"$LOG" 2>&1 &
SERVER_PID=$!
log "server pid=$SERVER_PID"
trap cleanup EXIT

wait_log "[EVO] WAIT-STAGE1" 300 || { cleanup; trap - EXIT; exit 3; }

# ---------------------------------------------------------------- stage A2
log "stage A2: @Inject cancellable evolution"
if [ "$MUTATION" = "cancellable" ]; then
  log "MUTATION=cancellable: installing v2 without cancellable/forced return"
  cp evo/stages/EvoAMixin.v2mut.java "$A"
else
  cp evo/stages/EvoAMixin.v2.java "$A"
fi
compile a2
touch run/evo-stage-a2.trigger
wait_log "[EVO] STAGE-A2-DONE" 180 || { cleanup; trap - EXIT; exit 4; }

# -------------------------------------------------------------- stage B2
log "stage B2: @Inject target-method switch (methodOne -> methodTwo)"
cp evo/stages/EvoBMixin.v2.java "$B"
compile b2
touch run/evo-stage-b2.trigger
wait_log "[EVO] STAGE-B2-DONE" 180 || { cleanup; trap - EXIT; exit 5; }

# -------------------------------------------------------------- stage C2
log "stage C2: add a second @Inject handler method to the Mixin class"
cp evo/stages/EvoCMixin.v2.java "$C"
compile c2
touch run/evo-stage-c2.trigger
wait_log "[EVO] STAGE-C2-DONE" 180 || { cleanup; trap - EXIT; exit 6; }

# -------------------------------------------------------------- stage C3
log "stage C3: remove the added @Inject handler method"
cp evo/stages/EvoCMixin.v3.java "$C"
compile c3
touch run/evo-stage-c3.trigger
wait_log "[EVO] STAGE-C3-DONE" 180 || { cleanup; trap - EXIT; exit 7; }

wait_log "[EVO] RESULT" 120 || { cleanup; trap - EXIT; exit 8; }

sleep 2
cleanup
trap - EXIT

# ---------------------------------------------------------------- verdict
RESULT_LINE="$(grep -a "\[EVO\] RESULT" "$LOG" | tail -1)"
log "result line: $RESULT_LINE"

{
  echo "=== @Inject SELF-evolution, Fabric 1.21.1 end-to-end (item 2.6) ==="
  echo "mode: $MODE"
  echo "date: $(date -Iseconds)"
  echo "agent: $(grep -o 'javaagent:[^=]*' evo/server.args | head -1)"
  grep -aE "\[EVO\]|\[Mixin\]|HotSwap successful|LAYOUT-REJECT|INIT-DRIFT|RESULT" "$LOG" | tail -260
} | tee "evo/summary-$MODE.txt"

VERDICT=$(printf '%s' "$RESULT_LINE" | grep -o "RESULT \(PASS\|FAIL\)" | awk '{print $2}')
if [ "$MODE" = "normal" ]; then
  if [ "$VERDICT" = "PASS" ]; then
    log "DONE green"
    exit 0
  fi
  log "DONE red (expected PASS, got ${VERDICT:-<missing>})"
  exit 9
fi

# mutation mode: the point is that the assertion dies, so FAIL is the expected outcome
if [ "$VERDICT" = "FAIL" ]; then
  log "DONE mutation-killed (scenario-A assertions went red as required)"
  exit 0
fi
log "DONE mutation-survived (expected FAIL, got ${VERDICT:-<missing>})"
exit 9
