#!/usr/bin/env bash
# Item 2.5: multi-mixin (two independent configs) end-to-end driver on Fabric 1.21.1.
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ" || exit 1
JBR="C:/Users/ASUS/.jdks/JBR-21"
JAVA="$JBR/bin/java"
LOG="$PROJ/run/mm.log"
A="src/main/java/com/example/mixin/a/MixinA.java"
B="src/main/java/com/example/mixin/b/MixinB.java"
T="src/main/java/com/example/multi/MultiTarget.java"

mkdir -p run mm
rm -f run/mm-stage*.trigger

log() { printf '[driver:mm] %s\n' "$*"; }

compile() {
  local tag="$1"
  log "gradle compileJava ($tag)"
  JAVA_HOME="$JBR" ./gradlew --offline processResources compileJava -q >"mm/compile-$tag.log" 2>&1
  local rc=$?
  if [ $rc -ne 0 ]; then
    log "COMPILE FAILED ($tag) rc=$rc"
    tail -30 "mm/compile-$tag.log"
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

log "reset sources to v1 and compile"
cp mm/stages/MultiTarget.v1.java "$T"
cp mm/stages/MixinA.v1.java "$A"
cp mm/stages/MixinB.v1.java "$B"
compile reset

{ echo "-Dremold.probe.mm=true"; echo "-Dremold.agent.debug=true"; cat build/remold/server.args; } > mm/server.args

log "launching real Fabric KnotServer with -javaagent"
rm -f "$LOG"
"$JAVA" @mm/server.args >"$LOG" 2>&1 &
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

wait_log "[MM] WAIT-STAGE1" 300 || { cleanup; exit 3; }

# stage 2: MixinA-only hot update
log "stage2: MixinA v2 only"
cp mm/stages/MixinA.v2.java "$A"
compile s2
touch run/mm-stage2.trigger
wait_log "[MM] STAGE2-DONE" 180 || { cleanup; exit 4; }

# stage 3: target class hot update
log "stage3: MultiTarget v2"
cp mm/stages/MultiTarget.v2.java "$T"
compile s3
touch run/mm-stage3.trigger
wait_log "[MM] STAGE3-DONE" 180 || { cleanup; exit 5; }

# stage 4: both mixins in one batch
log "stage4: MixinA v3 + MixinB v2 in one compile/batch"
cp mm/stages/MixinA.v3.java "$A"
cp mm/stages/MixinB.v2.java "$B"
compile s4
touch run/mm-stage4.trigger
wait_log "[MM] STAGE4-DONE" 180 || { cleanup; exit 6; }

# stage 5: same-name @Unique final observation
log "stage5: same-name @Unique field drift"
touch run/mm-stage5.trigger
wait_log "[MM] STAGE5-DONE" 120 || { cleanup; exit 7; }
wait_log "[MM] RESULT" 60 || true
sleep 2
cleanup
trap - EXIT

{
  echo "=== Multi-Mixin Fabric end-to-end ==="
  echo "date: $(date -Iseconds)"
  grep -aE "\[MM\]|MixinReload|LAYOUT-REJECT|INIT-DRIFT|HotSwap successful|RESULT" "$LOG" | tail -140
} | tee mm/summary.txt
log done
