#!/usr/bin/env bash
# Acceptance probe for batch A (Forge ClassInfo member-sync E2E).
#
# Three independent checks, all offline and deterministic:
#
#   1. DISCRIMINATION  — Verdict.java is run against synthetic fixtures (red / green /
#      not-attempted / self-contradictory). It must return RED, GREEN, INVALID, INVALID. This is the
#      anti-vacuity control for the probe itself: if the assertions stop separating "members synced"
#      from "members not synced", this step goes red.
#   2. EVIDENCE        — the recorded real-server evidence must be present and must evaluate to the
#      verdict it is named for (baseline-red ⇒ RED with exactly {CI-M1, CI-M2}; after-fix ⇒ GREEN),
#      with a reload actually observed for both stages (otherwise the run is INVALID, never RED).
#   3. E2E (opt-in)    — CI_E2E=1 re-runs the real Forge dedicated server end to end
#      (run-forge-classinfo.sh), then re-runs checks 1–2 on the fresh evidence.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"

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

JBR="$(resolve_jbr)" || { echo "[verify] FAILED: JDK 21 not found (set CI_JBR to a JDK 21 home)" >&2; exit 2; }

FAILURES=0
pass() { printf '[verify] PASS %s\n' "$*"; }
bad()  { printf '[verify] FAIL %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }

verdict_of() { # <file> -> "<exit-code> <verdict-line>"
  local out code
  # stdout only: the JVM prints its JAVA_TOOL_OPTIONS notice on stderr.
  out="$("$JBR/bin/java" "$HERE/Verdict.java" "$1" 2>/dev/null)"
  code=$?
  echo "$code $out"
}

expect_verdict() { # <file> <expected-verdict> <expected-reasons>
  local file="$1" want="$2" want_reasons="${3:-}" line code
  read -r code line <<<"$(verdict_of "$file")"
  echo "[verify]   $(basename "$file") -> $line"
  if ! grep -q "VERDICT $want" <<<"$line"; then
    bad "$(basename "$file"): expected verdict $want, got: $line"
    return
  fi
  if [ -n "$want_reasons" ]; then
    local reason
    IFS=',' read -ra reason <<<"$want_reasons"
    for r in "${reason[@]}"; do
      if ! grep -qF "$r" <<<"$line"; then
        bad "$(basename "$file"): expected reason $r, got: $line"
        return
      fi
    done
  fi
  pass "$(basename "$file") verdict=$want"
}

# ---------------------------------------------------------------- 0. layout -----
for f in Verdict.java run-forge-classinfo.sh \
         mdk-overlay/src/main/java/ciprobe/ClassInfoProbe.java \
         mdk-overlay/src/main/java/ciprobe/ClassInfoTarget.java \
         mdk-overlay/src/main/java/ciprobe/mixin/ClassInfoTargetMixin.java \
         mdk-overlay/src/main/java/ciprobe/mixin/ClassInfoTargetSecondMixin.java \
         stages/ClassInfoTargetMixin.v2.java stages/ClassInfoTargetSecondMixin.v2.java \
         mdk/build.gradle mdk-overlay/src/main/resources/ci.mixins.json; do
  [ -f "$HERE/$f" ] || bad "missing probe file: $f"
done
bash -n "$HERE/run-forge-classinfo.sh" || bad "run-forge-classinfo.sh has a syntax error"

# The stage sources must really differ in the member they add (a no-op stage would make the probe
# unable to ever go green).
grep -q "ciInject2" "$HERE/stages/ClassInfoTargetMixin.v2.java" \
  || bad "stage 1 template does not add the new @Inject handler"
grep -q "ciAdded" "$HERE/stages/ClassInfoTargetSecondMixin.v2.java" \
  || bad "stage 2 template does not add the new @Unique field"
grep -q "ciAdded" "$HERE/mdk-overlay/src/main/java/ciprobe/mixin/ClassInfoTargetSecondMixin.java" \
  && bad "baseline second template already contains the stage 2 field (baseline is not clean)"
[ "$FAILURES" -eq 0 ] && pass "probe layout"

# ------------------------------------------------------- 1. discrimination -----
# Exit codes: 0 GREEN, 1 RED, 2 INVALID. The reasons pin down *which* checks did the separating.
expect_verdict "$HERE/fixtures/red.log"           "RED"     "CI-M1,CI-M2"
expect_verdict "$HERE/fixtures/green.log"         "GREEN"
expect_verdict "$HERE/fixtures/not-attempted.log" "INVALID" "PRECOND-RELOAD-stage1"
expect_verdict "$HERE/fixtures/contradiction.log" "INVALID" "SELF-CONTRADICTION"
if [ ! -f "$HERE/fixtures/red.log" ] || [ ! -f "$HERE/fixtures/green.log" ]; then
  bad "fixtures missing; the probe's discriminating power is unproven"
fi

# ------------------------------------------------------------ 2. evidence -----
FOUND_RED=0
FOUND_GREEN=0

# Re-evaluate every recorded evidence file against its own name contract.
for ev in "$HERE"/evidence/*.txt; do
  [ -f "$ev" ] || continue
  case "$(basename "$ev")" in
    *-baseline*)
      FOUND_RED=1
      expect_verdict "$ev" "RED" "CI-M1,CI-M2"
      # A baseline red run is only attributable if the bridge was seen trying the reload for both
      # stages (rejected validation, or a target re-weave that failed); "not attempted" is INVALID.
      grep -qE '\[CI-DRIVER\] phase=stage1 reload=(rejected|weave-failed)' "$ev" \
        || bad "$(basename "$ev"): stage 1 reload was not observed as an attempt"
      grep -qE '\[CI-DRIVER\] phase=stage2 reload=(rejected|weave-failed)' "$ev" \
        || bad "$(basename "$ev"): stage 2 reload was not observed as an attempt"
      grep -qF "[CI] RESULT-PROBE-DONE" "$ev" \
        || bad "$(basename "$ev"): probe did not run to completion"
      ;;
    *-after-fix*)
      FOUND_GREEN=1
      expect_verdict "$ev" "GREEN"
      grep -qF "[CI-DRIVER] phase=stage1 reload=success" "$ev" \
        || bad "$(basename "$ev"): stage 1 reload was not recorded as successful"
      grep -qF "[CI-DRIVER] phase=stage2 reload=success" "$ev" \
        || bad "$(basename "$ev"): stage 2 reload was not recorded as successful"
      ;;
    *)
      expect_verdict "$ev" "RED" || true
      ;;
  esac
  grep -qE '^agent_jar=.*sha256=[0-9a-f]{64}$' "$ev" || bad "$(basename "$ev"): missing agent jar provenance"
  grep -qE '^plugin_jar=.*sha256=[0-9a-f]{64}$' "$ev" || bad "$(basename "$ev"): missing plugin jar provenance"
done
[ "$FOUND_RED" -eq 1 ] || bad "no baseline-red evidence recorded under evidence/"
if [ "$FOUND_GREEN" -eq 0 ]; then
  echo "[verify] NOTE no after-fix evidence yet: the green leg requires the plugin-forge ClassInfo" \
       "sync (batch B). Re-run: bash run-forge-classinfo.sh"
fi

# ----------------------------------------------------------------- 3. E2E -----
if [ "${CI_E2E:-0}" = "1" ]; then
  bash "$HERE/run-forge-classinfo.sh" || bad "real Forge E2E run failed (see the driver output)"
fi

echo "[verify] ---------------------------------------------"
if [ "$FAILURES" -ne 0 ]; then
  echo "[verify] RESULT FAIL failures=$FAILURES"
  exit 1
fi
echo "[verify] RESULT PASS"
exit 0
