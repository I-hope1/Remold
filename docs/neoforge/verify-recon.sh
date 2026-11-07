#!/usr/bin/env bash
# Batch A acceptance: the recon brief must actually contain the audited facts.
# Deterministic, offline, no JDK needed — it only inspects the committed document.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
DOC="$HERE/01-loader-and-classinfo-boundary-audit.md"
FAILS=0
ok() { printf '[recon] PASS %s\n' "$*"; }
bad() { printf '[recon] FAIL %s\n' "$*" >&2; FAILS=$((FAILS + 1)); }

need() { # <literal>
  if grep -qF -- "$1" "$DOC"; then ok "contains: $1"; else bad "missing: $1"; fi
}
need_re() { # <regex> <label>
  if grep -qE -- "$1" "$DOC"; then ok "$2"; else bad "$2 (/$1/)"; fi
}

[ -f "$DOC" ] || { echo "[recon] FAIL missing $DOC" >&2; exit 2; }

# §2 truth table: all 16 code-surface rows (A1..A16)
for i in $(seq 1 16); do
  need_re "^\| A$i \|" "truth-table row A$i"
done

# §3 runtime facts: both generations + the decisive artifacts
need "cpw.mods:modlauncher:11.0.5"
need "net.fabricmc:sponge-mixin:0.16.4+mixin.0.8.7"
need "net.neoforged.fml.startup.Client"
need "net.neoforged.fancymodloader:loader:11.0.15"
need "无 \`cpw.mods:*\`"
need '`-p`'
need "org.spongepowered.asm.mixin.transformer"
need "com.google.gson"

# §4 breakpoints B1..B7 with falsification + remediation
for b in B1 B2 B3 B4 B5 B6 B7; do
  need_re "^\| $b \|" "breakpoint $b"
done
need "InaccessibleObjectException"
need "add-opens org.spongepowered.mixin/org.spongepowered.asm.mixin.transformer=ALL-UNNAMED"
need "MixinEnvironment::addConfiguration"

# [[mixins]] registration path is the N3 prerequisite
need "[[mixins]]"
need "ModFileParser.getMixinConfigs"
need "DeferredMixinConfigRegistration"

# §5 SSOT convergence verdict (N1) with the semantic divergence that motivates it
need "ClassInfoMemberSync"
need "半程失败"
need "remold.reflect"

# §6 batch B requirements: positive confirmation, not merely "no error"
need "addedMethods=1"
need "addedFields=1"
need "Failed to sync Mixin class metadata"

echo "[recon] ---------------------------------------------"
if [ "$FAILS" -ne 0 ]; then
  echo "[recon] RESULT FAIL failures=$FAILS"
  exit 1
fi
echo "[recon] RESULT PASS"
