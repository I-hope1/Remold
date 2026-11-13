#!/usr/bin/env bash
# Run all probe scenarios. Each scenario gets a fresh JVM because state cannot be undone.
set -uo pipefail
cd "$(dirname "$0")"

J21="${JBR21_HOME:-F:/files/java/jdks/jbrsdk_jcef-21.0.9}"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe"; SEP=";";; *) EXE=""; SEP=":";; esac
[ -x "$J21/bin/java$EXE" ] || { echo "missing $J21/bin/java$EXE (set JBR21_HOME)" >&2; exit 1; }
B=b
LOG="$B/logs/all.log"
: > "$LOG"

FLAGS=(
  -XX:+AllowEnhancedClassRedefinition
  -javaagent:"$B/agent.jar"
  --add-exports java.base/jdk.internal.misc=ALL-UNNAMED
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
  --add-opens java.base/java.lang=ALL-UNNAMED
)

CP21="$B/probe$SEP$B/21/v1"
CP21V2="$B/probe$SEP$B/21/v2"
CP8="$B/probe$SEP$B/8/v1"
V1BODY="$B/21/v1body/test/Color.class"
V2_21="$B/21/v2/test/Color.class"
V2_8="$B/8/v2/test/Color.class"
V2CTOR="$B/21/v2ctor/test/Color.class"
V2BODY="$B/21/v2body/test/Color.class"
BODY1="$B/21/v2body/test/Color\$1.class"

run() { # run <label> <scenario> <v2bytes> <cp> [extra jvm args...]
  local label="$1" scenario="$2" v2="$3" cp="$4"
  shift 4
  echo "########## $label  (scenario=$scenario, cp=$cp) ##########" | tee -a "$LOG"
  echo "CMD: java $* ${FLAGS[*]} -cp $cp probe.Probe $scenario $v2" | tee -a "$LOG"
  "$J21/bin/java$EXE" "$@" "${FLAGS[@]}" -cp "$cp" probe.Probe "$scenario" "$v2" 2>&1 | tee -a "$LOG"
  echo "" | tee -a "$LOG"
}

# --- shape / sanity ---
run "S0 sanity (v1 only, javac21 bytes)"          sanity        "$V2_21" "$CP21"
run "S0b sanity (v1 only, jdk8 bytes)"            sanity        "$V2_8"  "$CP8"
run "S0c mirror identity (canonical vs shadow Class)" mirror   "$V2_21" "$CP21"
run "S0d mirror body-only control (no field added)"    mirror-body "$V1BODY" "$CP21"

# --- no-repair baseline ---
run "S0e cache-after-redefine (quiescent JVM)"       cache-after-redefine "$V2_21" "$CP21"
run "S1 baseline javac21"                          baseline       "$V2_21" "$CP21"
run "S1b baseline jdk8"                            baseline       "$V2_8"  "$CP8"

# --- user-proposed repair ---
run "S2 repair javac21"                            repair         "$V2_21" "$CP21"
run "S2b repair jdk8"                              repair         "$V2_8"  "$CP8"

# --- which Class cache matters ---
run "S3 repair javac21, no cache clear"            cache-none     "$V2_21" "$CP21"
run "S3b repair javac21, clear enumConstants only" cache-consts   "$V2_21" "$CP21"
run "S3c repair javac21, clear directory only"     cache-dir      "$V2_21" "$CP21"
run "S3d race: consumer runs in the redefine->patch window" race   "$V2_21" "$CP21"
run "S3e race + clear enumConstants only" race-consts "$V2_21" "$CP21"
run "S3f race + clear directory only"     race-dir   "$V2_21" "$CP21"

# --- switch map ---
run "S4 repair + resize \$SwitchMap (javac21)"      repair-resize  "$V2_21" "$CP21"
run "S4b switch consumer initialized after repair"  swv2-after     "$V2_21" "$CP21"
run "S4c switch consumer initialized before repair" swv2-before    "$V2_21" "$CP21"

# --- JIT ---
run "S5 jit-spin (warm 1e6 + spinner) #1"           jit-spin       "$V2_21" "$CP21"
run "S5 jit-spin (warm 1e6 + spinner) #2"           jit-spin       "$V2_21" "$CP21"
run "S5 jit-spin (warm 1e6 + spinner) #3"           jit-spin       "$V2_21" "$CP21"
run "S5 jit-spin jdk8bytes #1"                      jit-spin       "$V2_8"  "$CP8"
run "S5b jit-spin under -Xint"                      jit-spin       "$V2_21" "$CP21" -Xint
run "S5c jit-spin, exclude probe.Probe::hotLen #1"   jit-spin       "$V2_21" "$CP21" -XX:CompileCommand=exclude,probe/Probe::hotLen
run "S5c jit-spin, exclude probe.Probe::hotLen #2"   jit-spin       "$V2_21" "$CP21" -XX:CompileCommand=exclude,probe/Probe::hotLen
run "S7 jit-spin, direct reader deopt (no 2nd redefine)" jit-spin-reader-deopt "$V2_21" "$CP21"

# --- direct deopt / $VALUES folding (no enum redefine in these JVMs) ---
run "S8a deopt control (Unsafe write only)"            deopt-unsafe-only      "$V2_21" "$CP21"
run "S8b deopt reader via redefine same bytes"        deopt-redefine-reader  "$V2_21" "$CP21"
run "S8c deopt reader via retransformClasses"         deopt-retransform-reader "$V2_21" "$CP21"
run "S8d retransform enum only"                       deopt-retransform-enum "$V2_21" "$CP21"
run "S8e redefine enum again (control)"               deopt-redefine-enum    "$V2_21" "$CP21"
run "S8f pure \$VALUES rewrite + reader deopt (v2 baseline)" jit-values-write "$V2_21" "$CP21V2"
run "S8g values() inlining (PrintInlining)"           jit-inline            "$V2_21" "$CP21" -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining
run "S5d jit-cold (no warmup at all)"                jit-cold       "$V2_21" "$CP21"
run "S5e jit-field (hot GETSTATIC BLUE) #1"           jit-field      "$V2_21" "$CP21"
run "S5e jit-field (hot GETSTATIC BLUE) #2"           jit-field      "$V2_21" "$CP21"

# --- DCEVM capabilities ---
run "S6 new ctor (String,int,int) + new method"      ctor           "$V2CTOR" "$CP21"

echo "########## S7 constant-specific class body (needs aux Color\$1 bytes) ##########" | tee -a "$LOG"
"$J21/bin/java$EXE" "${FLAGS[@]}" -cp "$CP21" probe.Probe body "$V2BODY" "$BODY1" 2>&1 | tee -a "$LOG"
echo "" | tee -a "$LOG"

echo "ALL DONE -> $LOG"
