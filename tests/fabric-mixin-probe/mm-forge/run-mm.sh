#!/usr/bin/env bash
# Item 2.5 Forge half: two independent mixin configs on the same target, 5 stages.
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ" || exit 1
JBR="C:/Users/ASUS/.jdks/JBR-21"
LOG="$PROJ/run/mm.log"
A="src/main/java/com/example/examplemod/mixin/a/MixinA.java"
B="src/main/java/com/example/examplemod/mixin/b/MixinB.java"
T="src/main/java/com/example/examplemod/multi/MultiTarget.java"
CLASSES="build/sourceSets/main"

mkdir -p run mm "$CLASSES"
rm -f run/mm-stage*.trigger

log() { printf '[driver:mm] %s\n' "$*"; }

javac_stage() {
  local tag="$1"; shift
  log "javac $tag"
  "$JBR/bin/javac" --release 21 -proc:none -encoding UTF-8 -cp "$(cat mm/cp.txt);$(cygpath -w "$PROJ/$CLASSES")" -d "$CLASSES" "$@"
  local rc=$?
  if [ $rc -ne 0 ]; then log "JAVAC FAILED ($tag) rc=$rc"; exit 2; fi
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

log "reset sources to v1 and compile the mod"
cp mm/stages/MultiTarget.v1.java "$T"
cp mm/stages/MixinA.v1.java "$A"
cp mm/stages/MixinB.v1.java "$B"
JAVA_HOME="$JBR" ./gradlew compileJava -q > mm/compile-reset.log 2>&1 || {
  log "compileJava (reset) failed"; tail -20 mm/compile-reset.log; exit 2; }
JAVA_HOME="$JBR" ./gradlew -q printCompileCp 2>/dev/null | tail -1 > mm/cp.txt || {
  log "printCompileCp failed"; exit 2; }

log "launching Forge runServer with -javaagent"
rm -f "$LOG"
JAVA_HOME="$JBR" ./gradlew runServer --console=plain >"$LOG" 2>&1 < <(sleep 900) &
SERVER_PID=$!

cleanup() {
  if kill -0 "$SERVER_PID" 2>/dev/null; then kill "$SERVER_PID" 2>/dev/null; sleep 2; kill -9 "$SERVER_PID" 2>/dev/null; fi
  pkill -f "forge_userdev_server" 2>/dev/null
}
trap cleanup EXIT

wait_log "[MM] WAIT-STAGE1" 300 || { cleanup; exit 3; }

log "stage2: MixinA v2 only"
cp mm/stages/MixinA.v2.java "$A"
javac_stage s2 "$A"
touch run/mm-stage2.trigger
wait_log "[MM] STAGE2-DONE" 180 || { cleanup; exit 4; }

log "stage3: MultiTarget v2"
cp mm/stages/MultiTarget.v2.java "$T"
javac_stage s3 "$T"
touch run/mm-stage3.trigger
wait_log "[MM] STAGE3-DONE" 180 || { cleanup; exit 5; }

log "stage4: MixinA v3 + MixinB v2 in one batch"
cp mm/stages/MixinA.v3.java "$A"
cp mm/stages/MixinB.v2.java "$B"
javac_stage s4 "$A" "$B"
touch run/mm-stage4.trigger
wait_log "[MM] STAGE4-DONE" 180 || { cleanup; exit 6; }

log "stage5: same-name @Unique final observation"
touch run/mm-stage5.trigger
wait_log "[MM] STAGE5-DONE" 120 || { cleanup; exit 7; }
wait_log "[MM] RESULT" 60 || true
sleep 2
cleanup
trap - EXIT

{
  echo "=== Multi-Mixin Forge end-to-end ==="
  echo "date: $(date -Iseconds)"
  grep -aE "\[MM\]|MixinReload|LAYOUT-REJECT|INIT-DRIFT|HotSwap successful|RESULT" "$LOG" | tail -140
} | tee mm/summary.txt
log done
