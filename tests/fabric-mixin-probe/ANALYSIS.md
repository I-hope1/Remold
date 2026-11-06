# 12. Real Fabric 1.21.1 Mixin retransform / baseline probe

This document records a **real** Fabric Loader + SpongePowered Mixin probe run on
`agent/batch-b-mixin-probe` from baseline `ba77be7`. No fake `weaveMixinInjectHook`
was used: every weave came from the runtime `IMixinTransformer` that Fabric itself
installed (`org.spongepowered.asm.mixin.transformer.MixinTransformer`, Mixin 0.8.7,
`sponge-mixin-0.15.4+mixin.0.8.7`).

## 12.1 Environment

| Item | Value |
|:--|:--|
| OS / shell | Windows 11, Git Bash |
| JVM (game) | JBR 21.0.9 (`F:/files/java/jdks/jbrsdk_jcef-21.0.9`) with `-XX:+AllowEnhancedClassRedefinition` |
| Fabric Loader | 0.16.9 |
| Minecraft | 1.21.1 (Yarn 1.21.1+build.3) |
| Mixin | 0.8.7 (`net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7`) |
| Loom | 1.17.21 |
| Remold agent | `core/build/libs/remold-agent.jar` built from this branch at `ba77be7` |
| Remold Mixin plugin | `plugin-mixin/build/libs/remold-plugin-mixin-0.2.2.jar` built from this branch |
| Launch points | real `KnotServer` and real `KnotClient` (both logs retained) |

The probe source is committed at
[`tests/fabric-mixin-probe/MixinProbe.java`](../../tests/fabric-mixin-probe/MixinProbe.java);
raw outputs are under [`tests/fabric-mixin-probe/evidence/`](../../tests/fabric-mixin-probe/evidence/).

## 12.2 Probe design

The stock `minecraft-fabric-template` target was extended with one stateful `@Unique`
field and the existing `@Inject` handler was kept:

```java
@Unique private int remold$useCount = 0;
@Unique private int remold$state = 123;   // non-default initializer so a reset is observable
@Inject(method = "use", at = @At("HEAD"))
private void remold$countUse(...) { ... }
```

The probe runs as a second Fabric `main` entrypoint, before any world/GUI work, and
prints SHA-256 plus field-name presence for each byte source:

- `raw_resource` / `disk_watchdir`: the source-level `.class` (3875 bytes).
- `fetchOriginalBytecode(target)`: Remold's resource read.
- `classfileBuffer` captured from `fetchBytecodeMemory(target)` (a real
  `retransformClasses`): the JVM's retransform input.
- `bytecodeCache` / `sourceBytecodeCache`.
- `mixinTransform(raw)`: one clean call through the real active Mixin transformer.

A copy of the raw class was also placed in `build/probe-raw.jar`, outside the agent's
watch directories, so `fetchOriginalBytecode` can find a non-watched resource. That
run simulates a production mod jar (the normal dev run only has the watched
`build/classes` resource and returns `null`). The probe can therefore report both the
dev-launch and production-like resource behavior.

Cross-run SHA-256 values differ because Mixin embeds a per-process random
`@MixinMerged(sessionId=...)` annotation; lengths and the field-name sets are stable.
All comparisons below are **within one process**.

## 12.3 Q1: retransform input bytes — pre-weave or post-weave?

Post-weave. The JVM hands the transformer the already-woven class, not the javac
output.

Server run (`evidence/server-hash.txt`):

| Byte source | Length | SHA-256 | has `remold$state` | has `remold$countUse` |
|:--|--:|:--|:-:|:-:|
| raw resource / disk | 3875 | `30046ad7f4890a1195334b6e8b673bc58b06f389b194c8e5905d2027146731d9` | false | false |
| `fetchOriginalBytecode` | 3875 | `30046ad7f4890a1195334b6e8b673bc58b06f389b194c8e5905d2027146731d9` | false | false |
| retransform input (`classfileBuffer`) | 4871 | `6c976ae03da1482685b8afd379d19015fa5346501f0564c103d0900320983847` | true | true |
| `bytecodeCache` | 5081 | `8bb18f5e78ad5a5008bcf3a85eee7fbfdafff5af9355ed4fb3a206f57886998f` | true | true |
| `mixinTransform(raw)` (single clean weave) | 5081 | `8bb18f5e78ad5a5008bcf3a85eee7fbfdafff5af9355ed4fb3a206f57886998f` | true | true |

`ClassDiffUtil.diff(retransform_input, single_weave)` returned `[]` and
`LayoutGate.checkChangedFields` returned `COMPATIBLE`; the two byte arrays have the
same field/method structure. The 4871-vs-5081 byte difference is serialization /
debug-attribute level, not a member-set difference: `javap -p -c` output for both is
identical. So the answer is **post-weave**, with the caveat that the retransform input
is the pre-Remold-final form of that woven class (it does not byte-equal
`bytecodeCache`).

Client run (`evidence/client-hash.txt`) shows the same structure:
raw `30046a...`, input 4871 `c2b097d4...`, single weave / cache 5081 `bafe0380...`,
`diff(input, single_weave) = []`, gate `COMPATIBLE`.

## 12.4 Q2: `fetchOriginalBytecode` — pre- or post-weave?

Pre-weave when it returns bytes; `null` in a pure dev launch.

- Production-like run (raw class in `build/probe-raw.jar`, outside watch dirs):
  `fetchOriginalBytecode` = 3875 / `30046a...`, byte-equal to `raw_resource` and
  `disk_watchdir`; it does **not** contain `remold$state` / `remold$countUse`.
- Dev run (`evidence/server-dev-hash.txt`): `fetchOriginalBytecode = null`. The only
  resource is `build/classes/...`, which is inside `activeWatchDirs`; Remold
  deliberately skips it ("Ignored polluted mount path"), and no second resource
  exists. So the dev answer is "returns null", not "returns woven bytes".

## 12.5 Q3: after baseline capture, does LayoutGate treat Mixin-added members as deleted?

It depends on which baseline capture path runs:

1. **Normal initial-load cache (single weave)**: no. `bytecodeCache` = 5081 with
   `remold$state` / `remold$countUse`; diff against a fresh single weave is empty.
2. **`retransformLoaded` on a class missing from `bytecodeCache`**: yes, observed.
   `retransformLoaded` retransforms the class; `AnnotationTransformer`'s external
   path calls `applyTransformerBridges` on the `classfileBuffer`, which is already
   woven. Mixin therefore weaves a second time and appends hash-mangled duplicate
   fields (`fd...$example_mod$remold$state$1`, `fd...$example_mod$remold$useCount$0`).
   The resulting baseline is 5277 bytes:

```
baseline_after_retransformLoaded: len=5277 sha256=45a1b3af...
source_after_retransformLoaded:  len=4871 sha256=6c976ae0...
Q3 diff(baseline, raw).changedFields   = [- fdaaff52$example_mod$remold$useCount$0,
                                          - fdaaff52$example_mod$remold$state$1]
Q3 diff(baseline, woven).changedFields = [same two]
Q3 gate(baseline, raw)   = REMOVED_FIELD
Q3 gate(baseline, woven) = REMOVED_FIELD
```

So the answer for the `retransformLoaded` baseline-capture path is **yes**, and the
cause is double weaving of an already-woven input. The same result reproduced on the
real client (`evidence/client-hash.txt`, baseline 5277 `eb8523e1...`,
duplicates `fde00797$...`).

Real entries that can send already-woven classes through this path:

| Entry | Code | What happens |
|:--|:--|:--|
| Runtime attach / `init0` | `RemoldAgent.init0` line 842-843, gated by `RETRANSFORM_LOADED` (`-Dremold.agent.retransform_loaded=true`) | passes `inst.getAllLoadedClasses()` after mods are live; Mixin targets are already woven |
| Public refresh / UI button B | `RemoldAgent.retransformLoaded()` line 1341 | same `getAllLoadedClasses()` input |
| `retransformLoaded(Class[])` | line 1350 | skips classes already in `bytecodeCache`; the double weave only occurs for a target whose cache entry is missing (late attach, eviction, forced capture) |
| `DynamicProfilerAPI.toggleEntityProbe` | `core/src/remold/profiler/DynamicProfilerAPI.java` line 104 | calls `retransformClasses` without `enterInternalRetransform`; the external path can re-weave already-woven targets |
| `RemovedMemberScanner.warmBytecodeCache` | `core/src/remold/RemovedMemberScanner.java` line 341 | uses `enterInternalRetransform`, so `AnnotationTransformer` returns early and the capture transformer only records `classfileBuffer`; no double weave |
| `RemoldAgent.fetchBytecodeMemory` | line ~2190 | uses `enterInternalRetransform`; capture only, no double weave |

The minimal product fix indicated by this evidence is to skip
`applyTransformerBridges` when the transform context is baseline capture
(`InternalRedefineContext.suppressesPostRedefine()`), because in that path the input
is the JVM's already-effective bytes, not fresh javac output. This branch records the
defect and the evidence; it does not change core behavior.

## 12.6 Q4: no-baseline `redefineDynamic` fallback + InitFix on a stateful `@Unique`

The fallback order is `bytecodeCache -> fetchOriginalBytecode -> sourceBytecodeCache`
(`RemoldAgent.redefineDynamic`, lines 3668-3672). With a production-like raw resource,
`fetchOriginalBytecode` supplies the raw 3875-byte class, so the diff sees the
`@Unique` fields as newly added and `InitFix` builds a patch. The patch uses the
default conditional CAS, so the observed result depends on the live value:

| Run | Live state before | `redefineDynamic` | State after | InitFix log |
|:--|--:|:-:|--:|:--|
| `evidence/server-reset7.txt` | 7 | true | **7 (preserved)** | `Field init patch skipped 1 conditional write(s) ... remold$state` |
| `evidence/server-reset0.txt` | 0 | true | **123 (reset to initializer)** | `Instance field init patch ... fields=4 ... failedFields=[]` |
| `evidence/client-reset7.txt` | 7 | true | **7 (preserved)** | same conditional-skip path |
| `evidence/client-reset0.txt` | 0 | true | **123 (reset)** | same patch path |

Dev run without a raw resource (`evidence/server-dev-reset.txt`):
`fetchOriginalBytecode = null`; the fallback picks `sourceBytecodeCache` (5081, already
woven), `redefineDynamic` returns `true` without a real redefine (`bytecodeCache`
stays `null`), and state stays 7. With `bytecodeCache` and `sourceBytecodeCache` both
removed, `redefineDynamic` returns `false` and state stays 7 (fail-closed).

Answer: it does **not** unconditionally reset a stateful `@Unique` field. A non-zero
state is preserved by the conditional CAS (with a warning). A state whose current
value is the zero/default value is overwritten with the initializer (0 -> 123),
because the conditional write cannot distinguish "never initialized" from
"legitimately zero".

## 12.7 Rollback baseline correction

`rollbackSourceCache` (lines 3212-3222) only rewrites `sourceBytecodeCache`; it does
not touch `bytecodeCache`, and it is not consulted by `fetchOriginalBytecode`.
Observed in `evidence/server-hash.txt` (production-like resource):

| Step | `sourceBytecodeCache` | `bytecodeCache` | `fetchOriginalBytecode` |
|:--|:--|:--|:--|
| before rollback | 5081 `8bb18f...` | 5277 `45a1b3...` | 3875 `30046a...` |
| rollback to raw | 3875 `30046a...` | 5277 `45a1b3...` | 3875 `30046a...` |
| rollback to previous woven source | 5081 `8bb18f...` | 5277 `45a1b3...` | 3875 `30046a...` |

So the earlier inference "rollback reads the old baseline" is not true for
`fetchOriginalBytecode`: it read the current classloader resource (3875) both before
and after either rollback. It is true only for `sourceBytecodeCache`, which is the one
structure `rollbackSourceCache` actually restores. `bytecodeCache` stays on the last
successfully applied runtime baseline.

## 12.8 Prior probe inventory (task 1)

No prior **real Mixin hash probe** was found in the repository, history, branches, or
stashes. Prior artifacts and what they actually prove:

| Location | Real Mixin? | What it proves |
|:--|:-:|:--|
| `tests/src/remold/MixinCompatibilityTest.java` | No — explicit `weaveMixinInjectHook` ASM helper | Washout/reweave concept only; it cannot answer JVM retransform hash questions |
| `tests/src/FingerprintDiskHashProbe.java` | No — fake `BytecodeTransformerBridge` adds a marker method | `fileDiskHashes` records the raw disk hash, not woven bytes; 18/18 pass in `evidence/prior-probes.txt` |
| `tests/src/RetransformBaselineProbe.java` | No — in-memory javac fixture | Synthetic class only; claims fallback baseline is v2 and `b` survives; 10/10 pass |
| `tests/src/RedefineDynamicNoBaselineProbe.java` | No — in-memory javac fixture | Plain new field `b=42` gets InitFix; all baselines missing -> false; 7/7 pass |
| `tests/src/MixinRawSourcePriorityTest.java` | No — plain fixture + reflection | `MixinHotSwap.readOriginalBytes` prefers disk bytes over a polluted `sourceBytecodeCache` |
| `tests/src/MixinClassInfoSyncTest.java` | No — fake `ClassInfo` | `MixinClassInfoSync.addMissingFields` contract only |
| `tests/src/ForgePluginTest.java` | No — mocks | Forge/NeoForge plugin discovery and safe pass-through only |
| `docs/status.md` §0.1 and row "Mixin re-weave" | Manual client claim | Prior real Fabric 1.21.1 client acceptance was manual, with no byte hashes |
| `E:/Users/ASUS/Desktop/Mods/minecraft-fabric-template/run/logs/debug.txt` | Yes (real client, Nov 5) | Real Mixin lines such as `Mixing RemoldWandItemMixin ... into com.example.item.RemoldWandItem` and `Target class ... re-woven ... and redefined`; no hash-level Q1-Q4 evidence |
| `copy.bat` | N/A | Copies local agent/plugin into the external template `.gradle/remold`; no probe logic |
| `eval-cases/` | N/A | `prediction-copy-v3` is unrelated to Mixin (prediction/rollback grading case) |
| Git history | — | Commits `aa293e2`, `076e5ce`, `4f0366c`, `83c9a3b`, `628e36a` are plugin fixes and fake-bridge probes; no real Mixin hash probe; no stashes; only `main`, `agent/batch-a-p3-and-l0`, `agent/batch-b-mixin-probe` |

The in-repo `minecraft-fabric-template/` is a real Fabric 1.21.1 + Loom 1.17.21 +
Mixin project and is usable here (this probe ran on a copy of it).

## 12.9 Two columns

| Item | Fabric 1.21.1 | Forge / NeoForge |
|:--|:--|:--|
| Real Mixin transformer available | Yes: Mixin 0.8.7 through Fabric Knot, both `KnotServer` and `KnotClient` | No |
| Q1 retransform input pre/post weave | Observed post-weave (4871, contains `@Unique` fields; `diff` vs single weave empty) | Not run |
| Q2 `fetchOriginalBytecode` pre/post weave | Observed pre-weave (3875 raw) with a non-watched resource; `null` in pure dev | Not run |
| Q3 LayoutGate after baseline capture | Observed: initial cache safe; forced `retransformLoaded` double-weaves and gate returns `REMOVED_FIELD` | Not run |
| Q4 no-baseline + stateful `@Unique` | Observed: 7 preserved; 0 -> 123; dev null -> no-op; all-missing -> false | Not run |
| Rollback source-cache correction | Observed | Not run |

Forge / NeoForge missing pieces (concrete):

- No Forge/NeoForge userdev, installer, `net.minecraftforge`, `cpw.mods`, or
  `net.neoforged` artifacts in `D:/data/.gradle/caches` (`find` returned none).
- No Forge/NeoForge run directory, game jar, mappings, `mods.toml` /
  `neoforge.mods.toml`, or launch configuration in the workspace.
- `plugin-forge` is only covered by `ForgePluginTest` with mock annotations; no real
  `TransformingClassLoader` / ModLauncher `IMixinTransformer` instance exists here.
- In a plain JVM, `MixinEnvironment` initialization fails with
  `ServiceNotAvailableError: No mixin host service is available` (observed in
  `evidence/prior-probes.txt` while `ModLauncherMixinBridge` probed it). That is not a
  Forge environment and cannot verify `ModLauncherMixinBridge`.

To close the Forge column, install a real Forge or NeoForge MDK, add a Mixin with
`@Unique`/`@Inject`, attach the agent and `plugin-forge`, and repeat the same probe.

## 12.10 Reproduction

See [`tests/fabric-mixin-probe/README.md`](../../tests/fabric-mixin-probe/README.md)
for the exact template edits and launch commands. Raw logs are in
[`tests/fabric-mixin-probe/evidence/`](../../tests/fabric-mixin-probe/evidence/).
