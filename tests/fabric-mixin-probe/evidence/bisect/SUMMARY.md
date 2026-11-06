# Item 2 bisection matrix — entity-probe `ClassFormatError` (Forge / Fabric × plain / Mixin × d5327fe / 6091057)

Run owner: subagent A (`bisect-matrix`). All work outside the main checkout, under `build/bisect/`.
Date: 2026-11-07 (Asia/Singapore). Main checkout was never built, edited, or committed by this task.
The two agent commits were checked out in isolated `git worktree`s, built once each, then the
worktrees were removed; jars and evidence are kept.

## 1. Matrix (cells actually run)

Probe design for these rows: the toggle runs **at steady state**, after the server has fully started
(Forge `ServerStartedEvent` / Fabric `ServerLifecycleEvents.SERVER_STARTED`), on a live instance of the
target class; the method is invoked right after the toggle and the real `ProfilerData.stats` counter is
read. `counter=1` = the instrumented method really ran on the live instance; `counter=-1` = no stats
entry at all (injection did not take effect).

| env | target | agent sha | toggle outcome | exception | evidence |
|:--|:--|:--|:--|:--|:--|
| Forge 1.21.1 (ModLauncher, ForgeGradle 7 `runServer`) | plain `PlainTarget.probe()` | `d5327fe` | returned normally; `counter=1` | none | [`forge-plain-d5327fe.log`](evidence/forge-plain-d5327fe.log) |
| Forge 1.21.1 (ModLauncher, ForgeGradle 7 `runServer`) | plain `PlainTarget.probe()` | `6091057` | returned normally; `counter=1` | none | [`forge-plain-6091057.log`](evidence/forge-plain-6091057.log) |
| Forge 1.21.1 (ModLauncher, ForgeGradle 7 `runServer`) | Mixin target `HotswapTarget.onReload()` | `d5327fe` | returned normally; `counter=1`; Forge/NeoForge Mixin transformer was acquired inside the toggle (external-redefine bridge path ran) | none | [`forge-mixin-d5327fe.log`](evidence/forge-mixin-d5327fe.log) |
| Forge 1.21.1 (ModLauncher, ForgeGradle 7 `runServer`) | Mixin target `HotswapTarget.onReload()` | `6091057` | returned normally; `counter=1`; no Mixin bridge acquisition during the toggle (internal-retransform pass-through) | none | [`forge-mixin-6091057.log`](evidence/forge-mixin-6091057.log) |
| Fabric 1.21.1 (KnotServer) | plain `PlainTarget.probe()` | `d5327fe` | not run: time cap (Lead wrap-up; first accepted Fabric cell would have been launched next) | — | — |
| Fabric 1.21.1 (KnotServer) | plain `PlainTarget.probe()` | `6091057` | not run: time cap (same) | — | — |
| Fabric 1.21.1 (KnotServer) | Mixin target `RemoldWandItem.remold$c3Method()` | `d5327fe` | not run: time cap (same) | — | — |
| Fabric 1.21.1 (KnotServer) | Mixin target `RemoldWandItem.remold$c3Method()` | `6091057` | not run: time cap (same) | — | — |

**No cell above threw `java.lang.ClassFormatError`.**

### Observed pre-fix / post-fix difference (path only, not outcome)

- `d5327fe` (pre-C1): during the toggle's retransform the log shows
  `[Remold] [Forge/NeoForge] Successfully acquired Mixin transformer across ClassLoader` — the
  external-redefine channel reached the Forge Mixin bridge (bridge re-weave path active).
- `6091057` (C1 fix): that line does not appear for the toggle retransform — `AnnotationTransformer`
  passes the buffer through because the retransform runs inside the internal-retransform context.
- Both agents nevertheless end the cell with `toggle_on=returned-normally … counter=1`.

## 2. Supplementary timing control (early toggle, NOT matrix rows)

Before switching the probe to steady-state timing, the toggle was issued at mod-construction time
(the original `C5Probe` timing). This was measured for 3 cells:

| env | target | agent sha | early-toggle outcome | internal failure |
|:--|:--|:--|:--|:--|
| Forge | plain `PlainTarget.probe()` | `d5327fe` | returned normally; `counter=-1` (class not instrumented) | `ProfilerTransformer.transform` → `NullPointerException: … "this.classReader" is null` |
| Forge | Mixin `HotswapTarget.onReload()` | `d5327fe` | returned normally; `counter=-1` | `ProfilerTransformer.transform` → `NullPointerException: … "this.classReader" is null` |
| Forge | Mixin `HotswapTarget.onReload()` | `6091057` | returned normally; `counter=-1` | `ProfilerTransformer.transform` → `NullPointerException: … "this.this$1.this$0.targetMethodNames" is null` |

Mechanism visible in the logs: the profiler transformer is a shared-state singleton
(`classReader`/`classWriter`/`targetMethodNames` fields) and is called for **every** class transform.
While `DynamicProfilerAPI.toggleEntityProbe` sits inside a synchronous
`InstrumentationImpl.retransformClasses0`, concurrent class loading during server startup (observed:
Forge VersionChecker's `HttpClient`/`TrustManagerFactory`/SSL initialization on another thread) re-enters
`ProfilerTransformer.transform`, whose first statement is `resetState()` — nulling the state of the
outer, in-flight invocation. The transformer then logs `Profiler injection failed for <target>` and
returns `null`, so the JVM keeps the un-instrumented class: the toggle "succeeds" but the counter can
never increment.

These early logs were later overwritten when the same cells were re-run in steady-state mode
(same `evidence/<env>-<target>-<sha>.log` path); only the console output is preserved. The Lead has
this observation and has already refactored `ProfilerTransformer` to per-invocation local state.

Interpretation limit: **`ClassFormatError` was not reproduced in any cell run here.** The only failure
signature observed is the shared-mutable-state race above (silent non-instrumentation, `counter=-1`).
The early-toggle runs are the only condition under which the toggle overlapped concurrent class loading;
that is the environment in which the previously reported non-deterministic `ClassFormatError` is
plausible, but this log set contains no such throwable.

## 3. Artifacts

- Agent/plugin jars (kept): `jars/d5327fe/`, `jars/6091057/`; checksums in
  [`jars/SHA256SUMS.txt`](jars/SHA256SUMS.txt).
  - `d5327fe/remold-agent.jar` sha256 `f1fb5068d300557d275712d40b34ec1a8571d375ec269c47a56ee7121b5b1e40`
    (identical to the previously recorded “after-c2” agent jar).
  - `6091057/remold-agent.jar` sha256 `9bb9fbbfba86d019815c7588088aff00ea9aea853cdee740aac7d6427763636f`
    (identical to the previously recorded “after-fix” agent jar).
  - Plugin jars are byte-identical across both commits: `plugin-forge` `dac03f60…`, `plugin-mixin`
    `9e18006d…`, `plugin-fabric` `526adc19…`.
- Evidence logs (kept): `evidence/forge-{plain,mixin}-{d5327fe,6091057}.log`.
  Headers record env/target/agent sha; the `[BISECT]` lines record target identity (canonical id,
  loader, Mixin `@Unique` field present), each attempt, counter and the final `RESULT`.
- Probe copies (kept, patched for the matrix): `probe-forge/` (ForgeGradle 7 MDK; `C5Probe`,
  `PlainTarget`, `ServerStartedEvent` hook in `ExampleMod`) and `probe-fabric/` (Fabric Loom probe;
  matrix `C1C2Probe` + `PlainTarget`, kept prepared but never launched).
- Drivers: `run-forge-cell.sh <sha> <plain|mixin> [steady|early] [label]`,
  `run-fabric-cell.sh <sha> <plain|mixin> [label]`, `gen-fabric-args.js`.
- Isolated build worktrees `wt-d5327fe` / `wt-6091057` were removed with `git worktree remove --force`.

## 4. Reproduce

```bash
cd build/bisect
bash run-forge-cell.sh d5327fe mixin steady     # one cell; one Gradle run at a time
```
Each run: `JAVA_HOME=C:/Users/ASUS/.jdks/JBR-21 ./gradlew --offline runServer` in `probe-forge`
(agent jar and watch dir re-pointed only inside the copy), probe halts the JVM after printing
`[BISECT] RESULT`. No network and no main-tree access are required after the jars were built.
