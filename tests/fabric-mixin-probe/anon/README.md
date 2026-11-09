# Anonymous-class (`Mixin$N`) hot-swap probe

Real Fabric 1.21.1 `KnotServer` + real Remold `-javaagent`, driven end to end by
`run-anon.sh`. It reproduces the two Mixin inner-class defects reported from a
real client (R3 and R5) and asserts them on the **business end result**: the
string that the live `AnonTarget.poke()` returns.

```
bash tests/fabric-mixin-probe/anon/run-anon.sh
# exit 1 = RED baseline reproduced (current implementation)
# exit 0 = GREEN, both paths fixed
# exit 2 = harness error (build/launch/timeout/precondition), NOT a defect signal
# exit 3 = mixed (only one defect reproduced)
```

The generated evidence is [`evidence/anon-r3r5-red.txt`](evidence/anon-r3r5-red.txt)
(per-stage verdicts, short log excerpts, agent/plugin/mod jar sha256).

## Fixture

| File | Role |
|:--|:--|
| `project/` | Minimal Fabric Loom 1.17.21 / MC 1.21.1 / loader 0.16.9 project (no Remold Gradle plugin) |
| `project/src/main/java/com/example/game/AnonTarget.java` | Plain business class; `poke()` is the observable end result |
| `project/src/main/java/com/example/anon/AnonTargetMixin.java` | S0 Mixin (`@Inject` at HEAD of `poke`, no anonymous class) |
| `project/src/main/java/com/example/probe/AnonProbe.java` | Loops `new AnonTarget().poke()`, prints `[ANON] tick poke=<v>` and answers `<label>.trigger` files |
| `stages/AnonTargetMixin.s1.java` | S1: the `@Inject` handler creates its **first** anonymous class (`Supplier`, body `anon-v1`) |
| `stages/AnonTargetMixin.s1b.java` | S1b: same anonymous body, handler reshaped, so the Mixin reload is really retried |
| `stages/AnonTargetMixin.s2.java` | S2: byte-identical outer source to S1b, anonymous body `anon-v2` |
| `run-anon.sh` | Builds the repo jars, scaffolds `build/proj`, launches the server, runs S0→S3, writes evidence |

## Why the launch looks like this (this is the reproduction, not decoration)

* The mod is loaded **from `run/mods/anon_probe-1.0.0-dev.jar`**; the Gradle
  output directories (`build/classes/java/main`, `build/resources/main`) are
  stripped from the runtime classpath (the driver asserts they are gone).
  Fabric's Mixin bytecode provider reads class bytes through
  `KnotClassLoader` → `getRawClassByteArray` (see `KnotClassDelegate`), i.e. the
  deployed jar. A class that only exists in the freshly compiled output dir is
  therefore **invisible to Mixin** — that is exactly the client-side R3
  precondition (`ClassInfo.forName` → `ClassNotFoundException` →
  `readInnerClasses` NPE on `innerClass`).
* Hot edits land in `out/production`, which is the agent's only watch path
  (`-javaagent:...=<proj>/out/production`). `out/production` is *not* on the
  runtime classpath, so only Remold sees the new bytes.
* `run/probe-classpath` is an empty directory prepended to `-cp`. S1b drops
  `AnonTargetMixin$1.class` into it — the "put the nested class on the real
  classpath" workaround from the client report. Directory classpath entries are
  read live, so this works without a restart.
* `-Dremold.agent.mods_quarantine=false` is required: the dev-mode guard would
  otherwise rename the deployed jar to `*.remold-disabled` (it assumes the loose
  Gradle classes are the ones that should win).
* `AnonProbe` lives in `com.example.probe`, not in the Mixin package:
  Mixin refuses to load classes belonging to a declared mixin package.

## Stages and what each one can prove

| Stage | Real change | Expected on a broken build | Expected after a fix |
|:--|:--|:--|:--|
| S0 | none (server start) | `poke=hook-s0` | `poke=hook-s0` |
| S1 (R3) | handler gains its first anonymous class | `poke` stays `hook-s0`; log: `Error loading class: ...AnonTargetMixin$1 (ClassNotFoundException)` + `Cannot invoke "ClassInfo.isProbablyStatic()" because "innerClass" is null` | `poke=anon-v1` |
| S1b (R3b) | anon body unchanged, handler reshaped, `AnonTargetMixin$1.class` on the real classpath | `poke=anon-v1`, reload + target re-weave succeed (positive control: the same code path works once the bytes are visible) | `poke=anon-v1` |
| S2 (R5) | outer bytes **byte-identical** (asserted by sha256), only `AnonTargetMixin$1.class` carries `anon-v2` | `poke` stays `anon-v1`; log: `[DYNAMIC-REDEFINE] no-op: ... runtime bytecode equals the baseline` (plus a `...$1 is not registered in the startup Mixin config` warning) | `poke=anon-v2` |
| S3 | nothing at all | value unchanged, no redefine/reload/`no-op` line (negative control) | same |

S2's sha256 preconditions are printed in the evidence file: the outer class file
hash of S1b and S2 must be equal, and the `$1` hashes must differ. Without those
two facts the stage would not isolate "only the anonymous body changed".

The three control stages are what make the assertion attributive rather than a
proxy: S1b shows the value *does* change to `anon-v1` when the bytes are
reachable (so the probe is not blind), S3 shows the value does not drift on its
own (so a stable value is not just "nothing ever re-reads the field").

## Prerequisites (all present on this machine, all offline)

* DCEVM JDK 21 — `C:/Users/ASUS/.jdks/JBR-21` (override `JBR_HOME`).
* JDK 25 for the Remold repo build (`REMOLD_BUILD_JAVA_HOME`, default `JAVA_HOME`
  then `C:/Users/ASUS/.jdks/openjdk-25.0.1`).
* Gradle user home `D:/data/.gradle` with the Fabric Loom 1.21.1 caches and the
  sponge-mixin / fabric-loader artifacts (`--offline` is used throughout).
* Server-only: no display is needed. First startup creates `run/world` (~40 s).

## Interpretation notes for the fix owner

* S1 fails on the **reload** side: `MixinInfo.State.readInnerClasses()` calls
  `ClassInfo.forName(inner.name)`, whose bytecode provider cannot see the new
  nested class, so `innerClass` is `null` and the raw NPE aborts the reload. The
  handler stays unavailable afterwards; a later reload succeeds only once the
  class is reachable (S1b). A fix must make the bytes Remold already holds
  (source-level `out/production` / the deployed jar diff) reachable by the
  provider, without requiring the user to copy files.
* S2 fails on the **rewrite target** side. The outer Mixin reload succeeds and
  the target is re-woven, but `InnerClassGenerator` caches the conformed inner
  class per `owner:innerName:target` (`innerClassNames`) and never regenerates a
  class whose `loadCounter > 0`, so the target bytecode comes out identical and
  the redefine is a silent `no-op` while the loaded anonymous body keeps
  answering `anon-v1`. A fix must either re-define the conformed inner class
  itself or fail closed with an explicit message — silently reporting "re-woven
  with updated mixin and redefined" is the defect (note: in the S2 window the
  success line *is* printed even though the redefine was a no-op).
* After a fix, `run-anon.sh` must exit `0` and print S1 `poke=anon-v1`,
  S2 `poke=anon-v2`, S3 stable. Any exit `2` means the harness broke (build,
  launch, precondition), not that the defect is fixed.

## Constraints

* This probe covers the server side only; client rendering classes are not used.
* `project/build.gradle.kts` must not gain a block/KDoc comment above the
  `anonEnv` registration: with Gradle 9.7.1 + Loom 1.17.21 such a comment makes
  the Kotlin DSL script stop applying the remaining statements silently.
* The probe needs the repo jars rebuilt from the checkout under test; pass
  `ANON_SKIP_BUILD=1` only when you deliberately want the already-built jars.
