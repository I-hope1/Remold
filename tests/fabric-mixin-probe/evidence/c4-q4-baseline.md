# Q4 baseline table (`redefineDynamic` without `bytecodeCache`)

All rows are measured on a real Fabric 1.21.1 KnotServer with the real Remold agent as
`-javaagent` (`MixinProbe` mode `reset`, see `server-reset7.txt`, `server-reset0.txt`,
`server-dev-reset.txt`), plus the no-op row measured by
`./gradlew :tests:redefineDynamicNoBaselineProbe -Pp05.jbr=<JBR21>`.

`redefineDynamic` falls back for its old baseline in this order:
`bytecodeCache` → `fetchOriginalBytecode` → `sourceBytecodeCache`. `return true` means one of
two different things and the caller cannot tell them apart without the log line:

1. a real `redefineClasses` happened;
2. the runtime bytes equal the chosen baseline — **no redefine, no InitFix, no `@OnReload`,
   no batch callback** (the no-op branch).

| Scenario | Baseline used | Baseline bytes | `redefineDynamic` | Observed terminal state |
|:--|:--|:--|:--|:--|
| production-like, `remold$state` set to 7 | `fetchOriginalBytecode` (raw, pre-weave) | 3875 B, `sha256=30046ad7…` | `true` (real redefine) | state stays **7** (conditional CAS skips non-zero) |
| production-like, `remold$state` set to 0 | `fetchOriginalBytecode` (raw) | 3875 B, `sha256=30046ad7…` | `true` (real redefine) | state becomes **123** (InitFix writes the initializer) |
| dev launch, `fetchOriginalBytecode == null` | `sourceBytecodeCache` (woven) | 5081 B | `true` (**no-op**: runtime == baseline) | state stays 7; no redefine occurred |
| all baselines missing | none | — | `false` (fail-closed) | state stays 7, no redefine |
| runtime bytes equal baseline (unit probe) | `bytecodeCache` | current runtime bytes | `true` (**no-op**) | batch listener **not called**, log line `[DYNAMIC-REDEFINE] no-op: …` emitted |
| real source change (unit probe control) | `bytecodeCache` | previous runtime bytes | `true` (real redefine) | batch listener called **exactly once** |

The no-op branch is now always visible (`info`, not `DEBUG`-gated):

```
[Remold] [DYNAMIC-REDEFINE] no-op: <class> runtime bytecode equals the baseline; no redefine performed (nothing to change).
```

`RedefineDynamicNoBaselineProbe` pins the distinction with two controls: a no-op call must not
fire the batch listener while a real source change must fire it exactly once. Removing the
no-op branch makes the first assertion fail (it would redefine and fire the listener).
