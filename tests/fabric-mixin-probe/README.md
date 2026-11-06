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

   `MixinProbe` now pins the invariants that must hold after the C2 fix and exits
   `0` on `RESULT PASS` / `3` on `RESULT FAIL`, so a shell driver can assert `$?`:

   ```bash
   env remold.agent.debug=true <jbr21>/bin/java @build/remold/server-mixin.args
   echo "probe exit=$?"   # 0 expected with the fixed agent; 3 against f2ff6bf
   ```

   The raw resource jar (step 4) must be prepended to the `-cp` value in that args file.
   See [`evidence/c2-exitcode.txt`](evidence/c2-exitcode.txt) for both runs.

   Regression tests for the same two channels live in the root test suite:
   `tests/src/remold/AnnotationTransformerBridgeCountTest.java` (bridge is not invoked for
   baseline capture / internal retransform, but is for a real source redefine) and
   `tests/src/remold/profiler/EntityProbeRetransformContextTest.java` (the entity-probe
   retransform runs inside an internal retransform context). Mutation check:
   [`evidence/c1c2-mutation-check.txt`](evidence/c1c2-mutation-check.txt).

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

## C1+C2 joint reproduction: entity-probe toggle (`C1C2Probe.java` + `c3/run-c1c2.sh`)

`DynamicProfilerAPI.toggleEntityProbe(base, method, true)` retransforms every loaded subclass
of `base`. For an already-woven Mixin target that retransform used to enter
`AnnotationTransformer`'s external-redefine channel with **no** internal context, so the C2
`captureOnly` guard did not apply and the woven `classfileBuffer` was fed to the Mixin bridge
again. Measured sequence (real KnotServer, real `-javaagent`):

1. `toggleEntityProbe(RemoldWandItem.class, "use", true)`,
2. one real source-edit hot-swap (add a method, real `gradlew compileJava`).

Outputs per run: `bytecodeCache` SHA-256 of the target, its field table (the hash-mangled
duplicates), and the LayoutGate verdict of the following hot-swap.

| Agent jar | after toggle: cache | duplicates | following hot-swap |
|:--|:--|:--|:--|
| `f2ff6bf` (before C2 fix) | 5683 B | `fd…$remold$state$1`, `fd…$remold$useCount$0` | `[LAYOUT-REJECT]` — `field(s) removed: [...]`, edit does not take effect |
| `d5327fe` (C2 fix) | 5683 B | same two fields | `[LAYOUT-REJECT]` — C2 does not cover this entry |
| working tree (C1 fix) | 5482 B (unchanged) | none | `HotSwap successful: 1 classes redefined` |

The fix wraps the profiler retransform in `RemoldAgent.enterInternalRetransform(...)`: the
entity-probe retransform is not a Remold hot-swap, so `AnnotationTransformer` passes the class
through untouched instead of re-weaving already-woven bytes.

Run it as in "Setup", with `C1C2Probe.java` added to `entrypoints.main` and the C1 probe
entrypoint enabled, then:

```bash
bash c3/run-c1c2.sh <remold-agent.jar> <label>
```

`run-c1c2.sh` swaps the given agent jar into `.gradle/remold/remold-agent.jar`, launches the
server, toggles the probe, performs the real source-edit hot-swap, restores the jar, and writes
`c3/c1c2-<label>-summary.txt`. Raw evidence:
[`evidence/c1c2-before-c2.txt`](evidence/c1c2-before-c2.txt),
[`evidence/c1c2-after-c2.txt`](evidence/c1c2-after-c2.txt),
[`evidence/c1c2-after-fix.txt`](evidence/c1c2-after-fix.txt).

Live-class byte capture note: after the toggle both `fetchBytecodeMemory` and a direct
last-in-chain capture transformer returned `null` (`modifiable=true`,
`retransformSupported=true`), so the duplicate-field observation is taken from the bytes
Remold's transformer returned to the JVM (`bytecodeCache`, which `retransformClasses` applies)
plus the LayoutGate rejection that names those exact fields. Reflective metadata is stale on
this JBR after a redefinition and is recorded only.

`retransformLoaded` reachability at `-javaagent` startup (default
`RETRANSFORM_LOADED=false`, so the entry is not executed at all): with
`-Dremold.agent.retransform_loaded=true` the log reports `Found 1120 classes loaded before
Agent start`, and the Mixin bridge is invoked **0** times during that retransform — no woven
Mixin target exists at premain. See
[`evidence/c1c2-retransform-loaded-count.txt`](evidence/c1c2-retransform-loaded-count.txt).

## C5: Forge (ModLauncher) end-to-end (`c5/C5Probe.java` + `c5/run-c5.sh`)

Forge 1.21.1 (`1.21.1-52.1.21`) via the ForgeGradle 7 MDK (Gradle 9.3.1, JBR 21.0.9). The
MDK builds with `./gradlew build` (first run: `BUILD SUCCESSFUL in 9m 36s`, 3 tasks). The
server run is a real `runServer` with the real Remold agent attached via
`-javaagent:<agent.jar>=<proj>/build/sourceSets/main` plus
`-XX:+AllowEnhancedClassRedefinition`.

**Verification scope (narrowed).** Verified: Mixin hot-swap re-weave on Forge 1.21.1-52.1.21
in a **minimal ForgeGradle 7 MDK probe mod**, dedicated-server dev run, official mappings,
with the Mixin config registered **manually** via `MixinEnvironment.addConfiguration` (FG7 dev
classpath mods have no jar manifest). **Not verified:** the `mods.toml` / `MixinConfigs`
manifest declaration path, real modpacks or clients, production refmap; and the **entity
probe does not pass on the woven target** — `toggleEntityProbe` throws `ClassFormatError`
(see `docs/status.md` risk 19). Do not read the `RESULT PASS` below as "Forge end-to-end is
fully verified".

The hot-swapped class `HotswapTarget` **is also a real Mixin target**
(`c5/HotswapTargetMixin.java` adds a `@Unique int c5Unique = 123` and an
`@Inject(method = "onReload")` handler). FG7 dev runs classpath mods without a jar manifest, so
the `MixinConfigs` manifest attribute is not seen; `C5Probe` registers `c5.mixins.json` via
`MixinEnvironment.addConfiguration` before the target class is first loaded. The Forge bridge
is loaded with `-Dremold.plugins=forge -Dremold.plugin.jars=<plugin-forge jar>`.

Measured (`evidence/c5-forge-e2e.txt`):

- hot-swapped class loader identity:
  `TransformingClassLoader[TRANSFORMER]@…` / `cpw.mods.modlauncher.TransformingClassLoader`;
- Q1 retransform input = 1871 B with `has_mixin_field=true has_mixin_handler=true`
  (`bytecodeCache` = 2093 B); Q2 `fetchOriginalBytecode = null` (dev launch, the only
  resource is under the watched dir);
- `[Remold] [Forge/NeoForge] Successfully acquired Mixin transformer across ClassLoader:
  org.spongepowered.asm.mixin.transformer.MixinTransformer`;
- `@Unique` state preservation: the Mixin `@Unique` field is set to a **non-initializer value
  999** before the hot-swap (initializer is 123); after both stages the re-woven `@Inject`
  handler prints `[C5-MIXIN] onReload unique=999` — the existing instance's state is preserved,
  not reset by the re-weave/redefine;
- stage 1 (add a method, real `javac` into the watched dir): `HotSwap successful`,
  `[C5-MIXIN] onReload unique=999`, `[C5-READ] stage1 method=C5-V1 value=7` — the new method is
  callable on the existing instance, the Mixin `@Inject` handler still runs and the `@Unique`
  field is retained (applied bytes still contain `c5Unique`);
- stage 2 (add a field): `Instance field init patch … fields=1`,
  `[C5-MIXIN] onReload unique=999`, `[C5-READ] stage2 method=C5-V1 added=456 value=7` — InitFix
  patched the live instance and the Mixin members survived the re-weave;
- zero `[INIT-DRIFT]`, zero `LAYOUT-REJECT`, `RESULT PASS` **for the Mixin hot-swap path only**;
- **entity probe on the woven target: root cause fixed (shared-state race)** — the bisection
  (Forge plain/Mixin × `d5327fe`/`6091057`) showed no `ClassFormatError` at steady state and
  reproduced the real failure as a `ProfilerTransformer` shared-mutable-state NPE when a
  concurrent class load re-entered the transformer. After the local-state refactor the Forge
  C5 run reports `ENTITY-PROBE counter=1` and `RESULT PASS`. Fabric plain/Mixin probe cells
  were **not run** (time cap); see `evidence/bisect/SUMMARY.md`.

To reproduce: use the ForgeGradle 7 MDK, add `c5/C5Probe.java` and
`c5/stages/HotswapTarget.v1.java` under `src/main/java/com/example/examplemod/`, call
`C5Probe.start()` at the end of the mod constructor, add
`compileOnly "com.github.I-hope1.Remold:api:v0.2.1"`, and wire the `runServer` task with the
`-javaagent` / `-XX:+AllowEnhancedClassRedefinition` / `-Dremold.probe.c5=true` arguments
(see the `tasks.configureEach { if (name == 'runServer') … }` block used by the driver). The
driver compiles each stage with plain `javac` against the classpath printed by
`./gradlew -q printCompileCp` (a second Gradle build would block on the run task's lock), and
keeps stdin open because `--nogui` stops the server on EOF.

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
| `c1c2-before-c2.txt` | entity-probe toggle on `f2ff6bf`: duplicate `@Unique` fields in the baseline + `[LAYOUT-REJECT]` |
| `c1c2-after-c2.txt` | same on `d5327fe`: still duplicates + rejection (C2 fix does not cover this entry) |
| `c1c2-after-fix.txt` | same with the C1 fix: cache unchanged by the toggle, `HotSwap successful` |
| `c1c2-retransform-loaded-count.txt` | `retransformLoaded` startup count: 1120 classes, 0 Mixin targets |
| `c2-exitcode.txt` | `MixinProbe` exit-code assertion: 0 with the fix, 3 against `f2ff6bf` |
| `c1c2-mutation-check.txt` | reverting each fix turns exactly one regression test red |
| `c4-q4-baseline.md` | Q4 baseline table: fallback order, bytes, real-redefine vs no-op vs fail-closed |
| `c5-forge-e2e.txt` | Forge 1.21.1 ModLauncher: classloader identity, Q1/Q2, method+field hot-swap, `RESULT PASS`, zero INIT-DRIFT |

Cross-run SHA-256 values are not comparable: Mixin embeds a random
`@MixinMerged(sessionId=...)` in every process. Use the hashes inside one log file.
