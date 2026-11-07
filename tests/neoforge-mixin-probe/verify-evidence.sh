#!/usr/bin/env bash
# Acceptance probe for the NeoForge native-[[mixins]] Mixin reload E2E (campaign batch B).
#
# Three independent checks, all offline and deterministic:
#
#   1. LAYOUT / STATIC INVARIANTS — the probe tree is complete, the stage sources really add the
#      members the stages claim, the Mixin config is registered ONLY through
#      META-INF/neoforge.mods.toml [[mixins]] (no probe source may call the Mixin registration API),
#      and the driver script parses.
#   2. DISCRIMINATION — Verdict.java is run against synthetic fixtures (red / green / not-attempted
#      / contradictory / degraded / manual-registration). It must return RED, GREEN, INVALID×4 with
#      the expected reasons. This is the anti-vacuity control for the probe itself: if the
#      assertions stop separating "members synced" from "members not synced", this step goes red.
#   3. EVIDENCE — the recorded real-server evidence must evaluate to the verdict it is named for
#      (*-baseline-red => RED with exactly the stage member checks failing, and both stages observed
#      as a reload attempt; *-after-fix => GREEN with both reloads accepted and the class-info sync
#      lines present), with jar provenance and no forbidden lines.
#
# Optional: NP_E2E=1 re-runs the real NeoForge dev server end to end, then re-runs checks 1-3.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"

# JDK 21 resolution: the same script may run under Git Bash (POSIX drive paths) or a shell where
# only Windows drive paths resolve, so try both forms plus JAVA_HOME.
resolve_jbr() {
  local candidate
  for candidate in "${NP_JBR:-}" "${CI_JBR:-}" "/c/Users/ASUS/.jdks/JBR-21" "C:/Users/ASUS/.jdks/JBR-21" "${JAVA_HOME:-}"; do
    [ -n "$candidate" ] || continue
    if [ -x "$candidate/bin/java" ] || [ -x "$candidate/bin/java.exe" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

JBR="$(resolve_jbr)" || { echo "[verify] FAILED: JDK 21 not found (set NP_JBR to a JDK 21 home)" >&2; exit 2; }

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
  local file="$1" want="$2" want_reasons="${3:-}" line code want_code
  read -r code line <<<"$(verdict_of "$file")"
  echo "[verify]   $(basename "$file") -> $line (exit=$code)"
  case "$want" in
    GREEN) want_code=0 ;;
    RED) want_code=1 ;;
    *) want_code=2 ;;
  esac
  if ! grep -q "VERDICT $want" <<<"$line"; then
    bad "$(basename "$file"): expected verdict $want, got: $line"
    return
  fi
  # The oracle's exit code is part of its contract (0/1/2); a verdict line that disagrees with the
  # process status would make callers that test only the exit code misread the run.
  if [ "$code" != "$want_code" ]; then
    bad "$(basename "$file"): verdict $want must exit $want_code, got $code"
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

# ------------------------------------------------------------- 0. layout -----
for f in Verdict.java run-neoforge.sh \
         mdk-overlay/src/main/java/neprobe/NeoProbe.java \
         mdk-overlay/src/main/java/neprobe/NeoMod.java \
         mdk-overlay/src/main/java/neprobe/ProbeTarget.java \
         mdk-overlay/src/main/java/neprobe/mixin/ProbeTargetMixin.java \
         mdk-overlay/src/main/java/neprobe/mixin/ProbeTargetSecondMixin.java \
         mdk-overlay/src/main/resources/META-INF/neoforge.mods.toml \
         mdk-overlay/src/main/resources/neprobe.mixins.json \
         stages/ProbeTargetMixin.v2.java stages/ProbeTargetSecondMixin.v2.java \
         mdk/build.gradle mdk/settings.gradle mdk/gradle.properties; do
  [ -f "$HERE/$f" ] || bad "missing probe file: $f"
done
bash -n "$HERE/run-neoforge.sh" || bad "run-neoforge.sh has a syntax error"
bash -n "$HERE/verify-evidence.sh" || bad "verify-evidence.sh has a syntax error"
[ "$FAILURES" -eq 0 ] && pass "probe layout"

# --------------------------------------------------- 1a. native registration --
# Campaign invariant N3: the Mixin config must come from the loader's own declaration block, and no
# probe source may register it by hand. The forbidden call is matched on the source text so the
# check also guards future edits of the probe.
grep -q '^\[\[mixins\]\]' "$HERE/mdk-overlay/src/main/resources/META-INF/neoforge.mods.toml" \
  || bad "neoforge.mods.toml has no [[mixins]] block (config would not be loader-registered)"
grep -q '^config *= *"neprobe.mixins.json"' "$HERE/mdk-overlay/src/main/resources/META-INF/neoforge.mods.toml" \
  || bad "neoforge.mods.toml [[mixins]] block does not name neprobe.mixins.json"
grep -q 'modLoader *= *"javafml"' "$HERE/mdk-overlay/src/main/resources/META-INF/neoforge.mods.toml" \
  || bad "neoforge.mods.toml is not a javafml mod"
if grep -rn --include='*.java' -e 'addConfiguration' -e 'MixinEnvironment' \
     "$HERE/mdk-overlay" "$HERE/stages" >/dev/null 2>&1; then
  bad "a probe source calls the Mixin registration / environment API (manual registration is forbidden)"
else
  pass "no manual Mixin registration anywhere in the probe sources"
fi

# ------------------------------------------------------- 1b. stage content ----
grep -q "npInject2" "$HERE/stages/ProbeTargetMixin.v2.java" \
  || bad "stage 1 template does not add the new @Inject handler"
grep -q "ciAdded" "$HERE/stages/ProbeTargetSecondMixin.v2.java" \
  || bad "stage 2 template does not add the new @Unique field"
grep -q "npInject2" "$HERE/mdk-overlay/src/main/java/neprobe/mixin/ProbeTargetMixin.java" \
  && bad "baseline template already contains the stage 1 handler (baseline is not clean)"
grep -q "ciAdded" "$HERE/mdk-overlay/src/main/java/neprobe/mixin/ProbeTargetSecondMixin.java" \
  && bad "baseline second template already contains the stage 2 field (baseline is not clean)"
grep -q '@Unique' "$HERE/mdk-overlay/src/main/java/neprobe/mixin/ProbeTargetMixin.java" \
  || bad "baseline template has no @Unique field (NP-BASE could never read 123)"

# ------------------------------------------------------- 2. discrimination -----
# Exit codes: 0 GREEN, 1 RED, 2 INVALID. The reasons pin down *which* checks did the separating.
expect_verdict "$HERE/fixtures/red.txt"                "RED"     "NP-M1,NP-M2"
expect_verdict "$HERE/fixtures/green.txt"              "GREEN"
expect_verdict "$HERE/fixtures/not-attempted.txt"      "INVALID" "PRECOND-RELOAD-stage1"
expect_verdict "$HERE/fixtures/contradictory.txt"      "INVALID" "SELF-CONTRADICTION"
expect_verdict "$HERE/fixtures/degraded.txt"           "INVALID" "DEGRADE=classinfo-sync-failed,DEGRADE=jpms-inaccessible-object"
expect_verdict "$HERE/fixtures/manual-registration.txt" "INVALID" "MANUAL-MIXIN-REGISTRATION"

# ------------------------------------------------------------ 3. evidence -----
FOUND_RED=0
FOUND_GREEN=0

for ev in "$HERE"/evidence/*.txt; do
  [ -f "$ev" ] || continue
  case "$(basename "$ev")" in
    *-baseline-red*)
      FOUND_RED=1
      expect_verdict "$ev" "RED" "NP-M1,NP-M2"
      # A baseline red run is only attributable if the bridge was seen trying the reload for both
      # stages (rejected validation, or a target re-weave that failed); "not attempted" is INVALID.
      grep -qE '\[NP-DRIVER\] phase=stage1 reload=(rejected|weave-failed)' "$ev" \
        || bad "$(basename "$ev"): stage 1 reload was not observed as an attempt"
      grep -qE '\[NP-DRIVER\] phase=stage2 reload=(rejected|weave-failed)' "$ev" \
        || bad "$(basename "$ev"): stage 2 reload was not observed as an attempt"
      grep -qF "[NP] RESULT-PROBE-DONE" "$ev" \
        || bad "$(basename "$ev"): probe did not run to completion"
      # The red leg must be the disabled-sync jar, otherwise it cannot be attributed to the sync.
      grep -qE '^plugin_jar=remold-plugin-forge-nosync\.jar sha256=[0-9a-f]{64}$' "$ev" \
        || bad "$(basename "$ev"): not produced by the ClassInfo-sync-disabled plugin jar"
      ;;
    *-after-fix*)
      FOUND_GREEN=1
      expect_verdict "$ev" "GREEN"
      grep -qF "[NP-DRIVER] phase=stage1 reload=success" "$ev" \
        || bad "$(basename "$ev"): stage 1 reload was not recorded as successful"
      grep -qF "[NP-DRIVER] phase=stage2 reload=success" "$ev" \
        || bad "$(basename "$ev"): stage 2 reload was not recorded as successful"
      grep -qF "[NP] RESULT-PROBE-DONE" "$ev" \
        || bad "$(basename "$ev"): probe did not run to completion"
      # Positive evidence that the member sync actually ran before each reload (N1/N2: not a
      # silent no-op that happens to look green).
      grep -qF "Mixin class-info sync: neprobe.mixin.ProbeTargetMixin addedFields=0, addedMethods=1" "$ev" \
        || bad "$(basename "$ev"): no class-info sync line for the stage 1 method"
      grep -qF "Mixin class-info sync: neprobe.mixin.ProbeTargetSecondMixin addedFields=1, addedMethods=0" "$ev" \
        || bad "$(basename "$ev"): no class-info sync line for the stage 2 field"
      ;;
    *)
      # Anything else (including evidence/invalid/* hand-kept runs) is only checked for provenance.
      ;;
  esac
  grep -qE '^agent_jar=.*sha256=[0-9a-f]{64}$' "$ev" || bad "$(basename "$ev"): missing agent jar provenance"
  grep -qE '^plugin_jar=.*sha256=[0-9a-f]{64}$' "$ev" || bad "$(basename "$ev"): missing plugin jar provenance"
  grep -qE '^neoforge=21\.1\.' "$ev" || bad "$(basename "$ev"): missing NeoForge version provenance"
  # N3: a run where the probe registered the Mixin config itself proves nothing.
  if grep -qF "addConfiguration" "$ev"; then
    bad "$(basename "$ev"): log contains a manual Mixin registration call"
  fi
  # N2: degradation markers must never be recorded as a normal outcome.
  if grep -qE "Failed to sync Mixin class metadata|has no new members|InaccessibleObjectException|does not implement HotswapPlugin" "$ev"; then
    bad "$(basename "$ev"): log contains a Mixin degradation marker (must be INVALID, not evidence)"
  fi
done
[ "$FOUND_RED" -eq 1 ] || bad "no baseline-red evidence recorded under evidence/"
[ "$FOUND_GREEN" -eq 1 ] || bad "no after-fix (green) evidence recorded under evidence/"

# ----------------------------------------------------------------- 4. E2E -----
if [ "${NP_E2E:-0}" = "1" ]; then
  bash "$HERE/run-neoforge.sh" || bad "real NeoForge E2E run failed (see the driver output)"
fi

echo "[verify] ---------------------------------------------"
if [ "$FAILURES" -ne 0 ]; then
  echo "[verify] RESULT FAIL failures=$FAILURES"
  exit 1
fi
echo "[verify] RESULT PASS"
exit 0
