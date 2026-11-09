# Anonymous-class (`Mixin$N`) hot-swap probe

Real Fabric 1.21.1 `KnotServer` + real Remold `-javaagent`, driven end to end by
`run-anon.sh`. It reproduces the Mixin inner-class defects reported from a real
client and asserts them on the **business end result**: the string that the live
`AnonTarget.poke()` returns.

```
bash tests/fabric-mixin-probe/anon/run-anon.sh
# exit 0 = GREEN  — S0-S3 green AND S4/S5/S7 green AND S6 matched ANON_EXPECT
# exit 1 = RED    — the documented red baseline of this campaign is reproduced
#                   (S4/S5 controls green, S6 red as documented, S7 field-init red)
# exit 2 = harness error (build/launch/timeout/precondition), NOT a defect signal
# exit 3 = MIXED  — partial/unexpected readings, read the per-stage table
ANON_EXPECT=green bash tests/fabric-mixin-probe/anon/run-anon.sh   # require the S6 fix
ANON_EXPECT=red   bash tests/fabric-mixin-probe/anon/run-anon.sh   # require the S6 defect
```

`ANON_EXPECT` only constrains S6 (the 1→2 same-shape renumbering, see below):
`auto` (default) accepts either and selects the matching verdict profile, `red`
requires the defect, `green` requires the fix.

Evidence: `evidence/anon-ext-red.txt` (RED/MIXED runs) and
`evidence/anon-ext-green.txt` (GREEN runs), selected automatically, or explicitly
via `ANON_EVIDENCE=<path>`. The older pair `anon-r3r5-red.txt` /
`anon-r3r5-green.txt` belongs to the R3/R5 campaign and is **never overwritten**.

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
| `stages/AnonTargetMixin.s4a.java` | S4a: the handler allocates the anonymous instance once and `AnonTarget.HELD4` keeps it (body `held-v1`) |
| `stages/AnonTargetMixin.s4b.java` | S4b: identical outer source to S4a, anonymous body `held-v2` |
| `stages/AnonTargetMixin.s5.java` | S5: hot update adds `@Unique private` field `uniqueTag` + method `uniqueValue()`, read from the anonymous class |
| `stages/AnonTargetMixin.s6a.java` | S6a: baseline, one anonymous class held by `AnonTarget.HELD6` (body `s6-v1`) |
| `stages/AnonTargetMixin.s6b.java` | S6b: a second same-shape anonymous class inserted in FRONT (`$1`), old one renumbered `$2` with body `s6-v2` |
| `stages/AnonTargetMixin.s7.java` | S7: new field `s7Tag` whose initializer allocates an anonymous-class instance |
| `run-anon.sh` | Builds the repo jars, scaffolds `build/proj`, launches the server, runs S0→S7, writes evidence |

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

## Extension stages (deep-water instrument for batches E/F)

These run after S3 in the same server process. They answer the questions the
production reports left open, each on a business end result rather than on a log
line. Every one of them has (a) its own baseline reading, (b) the change under
test, (c) a no-change control window read from the same trigger mechanism.

| Stage | Real change | Documented pre-fix reading | Expected after a fix |
|:--|:--|:--|:--|
| S4a | handler allocates the anonymous instance ONCE, `AnonTarget.HELD4` keeps it | `poke=held-v1` (baseline: the field holds a live instance) | same |
| S4b | outer bytes **byte-identical** (sha256-asserted), only the anonymous body → `held-v2` | `poke=held-v2` (R5-style in-place redefine already works) | same |
| S4c | nothing | value stable, no re-weave | same |
| S7 | new field `s7Tag` whose initializer allocates an anonymous instance, read back through the **pre-hot-swap** instance | `poke=s7=null` + `-fresh poke=s7=s7-v1`; log: `Field init patch refused ... cannot determine protected status of constructor <Target>$Anonymous$<hash>.<init>(<Target>)` | `poke=s7=s7-v1` (`fresh` also `s7=s7-v1`) |
| S7c | nothing | value stable | same |
| S6a | baseline: ONE anonymous class held by `AnonTarget.HELD6` | `poke=s6-v1` | same |
| S6b | **1→2 same-shape** anonymous classes: new one inserted first (the old one is renumbered `$1→$2`) and only the old body changed (`s6-v1→s6-v2`) | `poke=s6-v1` (the old instance keeps the old code); log: `[ANON_ALIGN] ... refused to guess ... kept as orphans ... RESTART is required` | `poke=s6-v2` |
| S6c | nothing | value stable | same |
| S5 | hot update adds `@Unique private String uniqueTag = "uq-tag"` + `@Unique private String uniqueValue()`; both the handler and the handler's anonymous class read them | `poke=s5:uq-tag:uq-m\|anon:s5:uq-tag:uq-m` (green reading); observation lines: `Nest scan incomplete ...`; an `IllegalAccessError` in the `anon:` half means the cross-class access really fails | same (must stay green) |
| S5c | nothing | value stable | same |

Why the readings are attributive:

* **S4 vs S6** — S4 is the positive control for S6. Both change only an anonymous
  body of an instance that is held long-term; S4 has a single anonymous class and
  S6 has two same-shape ones with a renumbering. If S4 is green and S6 is red, the
  difference *is* the ambiguity, not "anonymous body edits never work".
* **S7's `-fresh` line** — a `new AnonTarget()` runs the regenerated constructor,
  so it always shows `s7=s7-v1`. `poke=s7=null` on the long-lived instance while
  `-fresh` is correct isolates "the existing instance was never patched" from
  "the stage itself is broken".
* **S5** is the same machinery as S7 (an added field with an initializer patched on
  the live instance) but with a constant initializer, so it must stay green: if S5
  turns red, the instrument is broken rather than the fix missing.
* **The `s4c/s6c/s7c/s5c` controls** — no file is touched, so a drifting value would
  mean the value is not attributable to the stage change.
* **S5's warning window is not clean by construction** — the `[ANON_ALIGN] ... refused to
  guess` line produced by S6b is still visible in the S5 window (the orphaned classes
  persist). S5's conclusion therefore rests only on its value, not on log absence, and
  the evidence excerpt is labelled as such.

Each extension stage compiles with the fixture's class output wiped first
(`gradle_compile_fresh`) and re-synchronises `out/production` as one tight
delete+copy (`sync_out`): a stale `AnonTargetMixin$N.class` from the previous stage
would otherwise enter the batch as a phantom member.

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

### Acceptance readings for batches E and F

* **Batch E (same-shape anonymous-class ambiguity)** — the reading that must flip is
  **S6**: `s6b` from `s6-v1` to `s6-v2` (and the `[ANON_ALIGN] ... refused to guess`
  line must be replaced by an explicit deterministic-decision line naming the host,
  the conflicting classes and the live-instance counts, per invariant C4). S4 must
  **stay** `held-v2` and S6a must stay `s6-v1`: a fix that makes S6 green by
  redefining whatever is nearby without evidence would show up as a changed S4/S6a
  baseline. With `ANON_EXPECT=green` the script exits `0` only when S6 (and
  S4/S5/S7) are green.
* **Batch F (synthetic-class bytes reachable to the metadata layer)** — the reading
  that must flip is **S7**: `poke` from `s7=null` to `s7=s7-v1` while `-fresh` stays
  `s7=s7-v1`, and `Field init patch refused ... cannot determine protected status of
  constructor <Target>$Anonymous$<hash>.<init>(<Target>)` must disappear from the log.
  **S5** is the guard rail: it exercises the same added-field/`@Unique`-access path
  with a constant initializer and must stay `s5:uq-tag:uq-m\|anon:s5:uq-tag:uq-m` — a fix
  that unblocks S7 by relaxing the protected-status gate must not break or silence S5,
  and the `Nest scan incomplete` warning must not turn into a hard failure there.
* Invariant C1 (instrument first) is satisfied by the fact that both readings are
  the `AnonTarget.poke()` return value of a live instance, not log presence, and each
  one has its own baseline plus a no-change control.

## Constraints

* This probe covers the server side only; client rendering classes are not used.
* `project/build.gradle.kts` must not gain a block/KDoc comment above the
  `anonEnv` registration: with Gradle 9.7.1 + Loom 1.17.21 such a comment makes
  the Kotlin DSL script stop applying the remaining statements silently.
* The probe needs the repo jars rebuilt from the checkout under test; pass
  `ANON_SKIP_BUILD=1` only when you deliberately want the already-built jars.
* **The anonymous classes of all extension stages must keep the same member schema.**
  Mixin caches the conformed name per `mixin:innerName:target` coordinate, so a stage
  whose anonymous class gains or loses a field (typically a `this$0` capture) makes the
  JVM refuse the in-place redefinition and the value stops tracking the source — a
  probe artefact, not a product defect. That is why S4/S6/S7 anonymous bodies return a
  constant instead of touching the enclosing instance, and why S5 (which must read the
  enclosing `@Unique` members) is the last stage: its `this$0` capture is a deliberate
  schema change whose only consequence is a newly generated conformed class (the
  `anon:` half of its value shows whether the new body really landed).
