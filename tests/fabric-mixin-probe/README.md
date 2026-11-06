# Real Fabric Mixin probe

This directory contains the probe source and raw evidence for the real Fabric 1.21.1
Mixin retransform investigation. The analysis and hash tables are in
[`ANALYSIS.md`](ANALYSIS.md).

`MixinProbe.java` is **not** part of the root Gradle build. It is copied into a real
Fabric Loom project as a second `main` entrypoint; it compiles without Remold on the
compile classpath because it reaches `remold.RemoldAgent` by reflection (the agent jar
is appended to the bootstrap search at premain).

## What it answers

| Question | Observed answer |
|:--|:--|
| Retransform input: pre- or post-weave? | Post-weave (4871 bytes, contains `@Unique` fields and the `@Inject` handler; `ClassDiffUtil.diff(input, single_weave)` is empty) |
| `fetchOriginalBytecode`: pre- or post-weave? | Pre-weave when a non-watched resource exists (3875 raw); `null` in a pure dev launch because the only resource is under the watched `build/classes` |
| LayoutGate after `retransformLoaded` baseline capture | Initial-load cache is safe; forced `retransformLoaded` double-weaves the already-woven input and gate returns `REMOVED_FIELD` |
| No-baseline `redefineDynamic` fallback + InitFix on stateful `@Unique` | 7 stays 7 (conditional CAS skip); 0 becomes 123 (initializer); dev `null` fallback is a no-op; all baselines missing returns false |
| Rollback | `rollbackSourceCache` only restores `sourceBytecodeCache`; `bytecodeCache` and `fetchOriginalBytecode` do not follow it |

## Setup

Prerequisites seen on this machine: JBR 21.0.9 (DCEVM-capable), Gradle 9.7.1 wrapper,
Fabric Loom 1.17.21 / Minecraft 1.21.1 caches, JBR 21 for the Loom project, JDK 25 for
the root Remold build.

1. Build the branch agent and plugin (root project, JDK 25):

   ```bash
   ./gradlew :core:shadowJar :plugin-mixin:jar
   ```

2. Copy `minecraft-fabric-template/` to a scratch directory and remove
   `build/`, `.gradle/`, `run/`. In the copy:

   - `gradle.properties`: set `remold.version=0.2.1` (the Gradle plugin cached here).
   - `build.gradle.kts`: add a probe-only Remold block:

     ```kotlin
     remold {
         agentJar = "<worktree>/core/build/libs/remold-agent.jar"
         pluginJar(file("<worktree>/plugin-mixin/build/libs/remold-plugin-mixin-0.2.2.jar"))
         plugins("mixin")
         extraJvmArgs("-Dremold.probe=true")
     }
     ```

   - `src/main/resources/fabric.mod.json`: add
     `"com.example.probe.MixinProbe"` to `entrypoints.main`.
   - `src/main/resources/example_mod.mixins.json`: move `"TitleScreenMixin"` from
     `mixins` to `client` (so the dedicated server does not need a client class).
   - `src/main/java/com/example/mixin/RemoldWandItemMixin.java`: add the stateful
     `@Unique` field (the committed probe expects it):

     ```java
     @Unique
     private int remold$state = 123;
     ```

   - Copy `MixinProbe.java` to `src/main/java/com/example/probe/MixinProbe.java`.

3. Compile and generate launch args with JBR 21:

   ```bash
   JAVA_HOME=<jbr21> ./gradlew --offline compileJava processResources generateRemoldScript
   ```

   The generated `build/remold/remold.args` is a client launch. For the server:

   ```bash
   cp build/remold/remold.args build/remold/server.args
   # replace net.fabricmc.loader.impl.launch.knot.KnotClient with
   #   net.fabricmc.loader.impl.launch.knot.KnotServer
   #   --nogui
   # add -Dfabric.development=true (the remold args file does not set it)
   mkdir -p run && printf 'eula=true\n' > run/eula.txt
   ```

4. Production-like resource (needed for `fetchOriginalBytecode` to return bytes):
   build a jar containing only the raw target class and prepend it to the `-cp`
   line. The class still loads from the watched build output, but
   `getResources()` now also sees a non-watched `jar:` resource:

   ```bash
   jar cf build/probe-raw.jar -C build/classes/java/main com/example/item/RemoldWandItem.class
   ```

   Without this jar, the dev run reports `fetchOriginalBytecode = null`.

5. Run a mode:

   ```bash
   # hash answers (Q1-Q3 + rollback)
   env remold.agent.debug=true <jbr21>/bin/java @build/remold/server.args

   # stateful @Unique reset, fresh process
   # add -Dremold.probe.mode=reset and optionally -Dremold.probe.state=0
   env remold.agent.debug=true <jbr21>/bin/java @build/remold/server.args
   ```

   For the client, replace `KnotServer`/`--nogui` with
   `net.fabricmc.loader.impl.launch.knot.KnotClient`; the probe halts the JVM from
   the mod initializer.

## C3: main-path end-to-end (`C3Probe.java` + `c3/run-c3.sh`)

`MixinProbe` answers cache/`retransformLoaded` questions by calling agent internals directly.
`C3Probe` instead exercises the **real main path**: a real Fabric 1.21.1 `KnotServer`
launched with the real Remold agent as `-javaagent`, a real source edit compiled by the real
Gradle build into the watched `build/classes/java/main`, then the real file watcher ->
`processChanges` -> Mixin bridge re-weave -> `redefineClasses` -> InitFix pipeline. Nothing
in the driver calls `RemoldAgent` internals.

Stages (each one is a real source edit + real `gradlew compileJava`):

| Stage | Real change | Assertion |
|:--|:--|:--|
| 1 | add `remold$c3Method()` to the Mixin target source | `HotSwap successful: 1 classes redefined`; applied bytes contain exactly one `remold$c3Method`; `@Unique` fields `remold$state`/`remold$useCount` and the `@Inject` handler still exactly one each; no duplicate field names; the live instance's `@OnReload` calls the new method (`[C3-READ] stage1 method=C3-METHOD-V1`) |
| 2 | add `int remold$c3Added = 456` to the same source | applied bytes contain exactly one `remold$c3Added`; `@OnReload` on the existing instance reads `added=456` (InitFix patched it); Mixin `@Inject` handler still runs and reads `state=7` |
| 3/4 | no source change, identical class bytes touched twice | no redefine batch, no `@OnReload` callback, applied bytes unchanged, two `Unchanged: 1 class(es)` lines |
| 5 | edit the Mixin class itself (add `@Unique String remold$author = "Modder"`) | `MixinHotSwap` path: `[Mixin] ... reloaded; affected target(s): [RemoldWandItem]`, target re-woven and redefined; applied bytes contain exactly one `remold$author`; the re-woven `@Inject` handler prints `author=Modder` |

Structural assertions read the bytes actually handed to `redefineClasses`
(`RemoldAgent.bytecodeCache`) and parse their field/method tables with ASM. Behavioural
assertions read the lines printed by the redefined class itself through a `System.out` tee
(the target's `@OnReload` and the Mixin `@Inject` handler), because `Class.getDeclaredFields()`
/ `getDeclaredMethods()` are stale on this JBR after a redefine (measured; recorded in the
probe output but never used as pass/fail evidence).

To run it, set up the Loom probe project exactly as in "Setup" below, then:

1. copy `C3Probe.java` to `src/main/java/com/example/probe/C3Probe.java`, add
   `"com.example.probe.C3Probe"` to `entrypoints.main`, and add the probe-only
   `@Inject(method = "onReload", at = @At("HEAD"))` handler shown in
   `c3/stages/RemoldWandItemMixin.v1.java` to the mixin;
2. copy `c3/stages/RemoldWandItem.v1.java` over `RemoldWandItem.java` and
   `c3/stages/RemoldWandItemMixin.v1.java` over the mixin (they are the v1 baseline);
3. `JAVA_HOME=<jbr21> ./gradlew --offline compileJava processResources generateRemoldScript`,
   then derive `build/remold/server.args` from `remold.args` (replace `KnotClient` with
   `KnotServer`, add `--nogui` and `-Dfabric.development=true`) and put `eula.txt` in the
   server working directory;
4. `bash c3/run-c3.sh` (it resets the sources to v1, launches the server, edits + compiles each
   stage, and writes `c3/summary.txt`; the probe halts the JVM with the verdict).

Raw evidence: [`evidence/c3-server-e2e.txt`](evidence/c3-server-e2e.txt) — `RESULT PASS
failures=[]`, `init_drift_count=0`.

## Evidence

`evidence/` contains the raw stdout/stderr of the runs referenced in the analysis doc:

| File | Run |
|:--|:--|
| `server-hash.txt` | KnotServer, raw resource jar, hash mode |
| `client-hash.txt` | KnotClient, raw resource jar, hash mode |
| `server-reset7.txt` / `client-reset7.txt` | state 7, fallback redefine |
| `server-reset0.txt` / `client-reset0.txt` | state 0, fallback redefine |
| `server-dev-hash.txt` | KnotServer without raw jar: `fetchOriginalBytecode = null` |
| `server-dev-reset.txt` | KnotServer without raw jar: null fallback is a no-op |
| `prior-probes.txt` | `RetransformBaselineProbe` 10/10, `RedefineDynamicNoBaselineProbe` 7/7, `FingerprintDiskHashProbe` 18/18 |
| `c3-server-e2e.txt` | C3 main path, real KnotServer + real `-javaagent`: stages 1/2/3/4/5, `RESULT PASS`, zero `[INIT-DRIFT]` |

Cross-run SHA-256 values are not comparable: Mixin embeds a random
`@MixinMerged(sessionId=...)` in every process. Use the hashes inside one log file.
