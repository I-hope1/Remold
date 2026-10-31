# Remold

**Edit code without restarting — the app keeps running, your change lands in half a second.**

English | [简体中文](README.md)

Remold is a Java Agent: it watches your compiled output directory and swaps the new bytecode into the live JVM in place.
Two ready-to-run templates are included, one for Mindustry mods and one for Minecraft (Fabric) mods.

## Stuck in this loop?

- Change one number → close the game → rebuild → launch → load the save → walk back to that spot → realize it was wrong → do it all again.
- Add a field to a class, hot-swap, and every existing object still holds `0` / `null` — NPE halfway through.
- Add a Mixin `@Inject`, hot-swap once, and the hook is gone. Restart.
- Tweak the logic inside one anonymous class, and after hot-swapping it lands in a different anonymous class.

## What Remold does

| What you hit | What Remold does |
|:--|:--|
| A change only takes effect after a restart | Watches `build/classes`; about 0.5s after you save, the bytecode is replaced in place — no restart, no reloading your save |
| New fields read as zero on existing objects | Backfills initial values into live instances after the swap; only zero values are touched, your manually changed state is never overwritten |
| Anonymous classes / lambdas land in the wrong slot | Aligns old and new anonymous classes by structural fingerprint instead of class names and indices that drift on every compile |
| Mixin hooks get washed out | Re-weaves the source-level bytecode through Mixin before redefining, keeping `@Inject` / `@Unique` / `@Shadow` intact |
| Hot-swapping after deleting a field or changing its type crashes | Refuses the redefinition outright while live instances still exist, instead of corrupting memory |

## Demos

**Mindustry: edit a PNG, the in-game icon updates immediately** (no restart, no reloading your save)

![Mindustry PNG hot-reload demo](docs/media/mindustry-png-hot-reload.gif)

**Minecraft (Fabric): change `message0`'s string value → `Ctrl+Shift+F9` → right-click in-game shows the new text immediately**

![Minecraft hot-reload demo](docs/media/minecraft-hot-reload.gif)

**Minecraft (Fabric): switch the Mixin injection point from `@At("TAIL")` to `@At("HEAD")` — `[MixinReload]` re-weaves the target class**

![Minecraft Mixin re-weaving demo](docs/media/minecraft-mixin-reweave.gif)

## Field note: adding a DPS dummy unit to Mindustry

A full record of one real session — the game stays running, Remold hot-swaps every edit, and IDEA Debugger
pulls the numbers on the main thread. Every figure below is measured output from the debugger, not an estimate.

**What was added**

- `DpsDummyUnit` — the DPS test dummy
  - `DpsDummyEntity extends UnitEntity`, overriding `rawDamage(float)`. Decompiling v160 shows `damage()` is
    `applyArmor → /healthMultiplier → /rules.unitHealth(team) → rawDamage`, and `damagePierce` also feeds
    `rawDamage` directly — so this is the single convergence point for every damage path, one override covers all of it.
  - 1e7 HP, with each hit clamped to `health - 1`, so it always survives with 1 HP and can be measured for minutes.
  - Placed on the enemy team (`rules.waveTeam`) — player turrets and units only lock onto hostiles, so a friendly dummy never gets shot.
  - A 1-second window reports window DPS / peak / cumulative; after 1 second of ceasefire it settles as
    "first hit → last hit" and never charges idle time to the denominator.
  - `EntityMapping.register("dps-dummy-entity", ...)` so saves and multiplayer can restore the custom entity by name.
  - Reuses `UnitTypes.flare`'s texture (checking for the `error` placeholder, not just null) and refreshes
    construction-time properties and localization in `@OnReload`.
- `ExampleJavaMod` — spawn entry: press <kbd>F8</kbd> at the cursor to spawn a dummy (`Trigger.update` polling,
  and it does not steal the key while the chat box is focused); plus `spawnDummyAt(x, y)` for the debugger / console.
  Bundle strings added in both Chinese and English.

**Measured (executed on the debugger's main thread, not derived)**

```plaintext
[VT-0] spawned=true cls=DpsDummyEntity team=crux hp=1.0E7
[VT-A] healthDrop=6000.0 counted=6000.0 diff=0.0          <- 60x100 damage, counter matches actual HP loss exactly
[VT-B] overkillDrop=9993999.0 healthLeft=1.0 alive=true   <- 2x max HP of overkill still leaves 1 HP
[VT-C] windowDamage=0.0 windows=1 bestDps=249.99998       <- 300 damage in a 1.20s window -> 250.0/s
[VT-E] windowsDelta=1 windowStart=-1.0 closed=true        <- settled after ceasefire: 400 damage / 1.00s -> 400.0/s
[VT-D] dummyTotalUnchanged=true                           <- control group: hitting a vanilla flare leaves the counter alone
```

**A real bug found along the way**: `Time.time` is in ticks, while `Time.toSeconds` measures 60 (ticks per second),
not 1/60. The first version multiplied by it, so a 1.2-second window became 4320 seconds and DPS read 0.07/s;
dividing instead landed exactly on 250.0/s. The same pass fixed "the window never closes → 0/s printed every second after ceasefire".

**Signals observed on the hot-swap path**: adding methods printed `+Added: [debugDpsSelfTest, runDpsSelfTest, lambda]`
→ `HotSwap successful`; deleting them printed `[LAYOUT-READERS] ... dangling=0` → `-Removed: [...]` → `HotSwap successful`.
That round both added and removed 3 methods, with no restart at any point. Removing the temporary self-check code took
effect on the next hot-swap, and a cold start afterwards logged `[ExampleJavaMod] Loading content...` with no anomalies.

**On the debugger**: `evaluate_expression` compiles its evaluation class separately, so a lambda that references
`arc`, `mindustry`, or project types fails. The workaround was to write the checks as static methods inside the mod,
call them from the debugger, and delete them afterwards — it works, but it is a detour.

## Up and running in 30 seconds

```bash
git clone https://github.com/I-hope1/Remold && cd Remold
./gradlew :example:runRemold
```

- Mindustry mod template: [`mindustry-mod-template/`](mindustry-mod-template/README.md) (includes a PNG texture hot-reload demo)
- Minecraft Fabric mod template: [`minecraft-fabric-template/`](minecraft-fabric-template/README.md) (includes a Mixin re-weaving demo)

## Want the internals?

Behind those capabilities sits a full stack: anonymous class topological alignment, confidence tiers and safety gates, bytecode slicing with effect analysis, and a patch driver for live instances.
You do not need any of it to use Remold — read §2 "What is solved vs known limitations" and §3 "Modular architecture" when you actually need them.

> Technical positioning: robust JVM class redefinition, anonymous class topological alignment, and legacy state reinitialization.

---

## 1. Quick Start

Remold runs as a Java Agent. For high-performance hot reloading of class structure changes, pair it with the enhanced redefinition support in **JetBrains Runtime (JBR 17/21/25+)** or **DCEVM (8/11)**.

### 1.1 Runtime and version baseline

- **Recommended runtime**: **JetBrains Runtime (JBR 17/21/25+)**, launched with `-XX:+AllowEnhancedClassRedefinition`.
- **Panama module note**: `remold-panama` targets the finalized Foreign Function & Memory (FFM) API from JDK 22+. On JBR 17/21, prefer the JNI native acceleration library (`LibTool`) bundled in `remold-agent`.
- **Java 24+ native access**: newer JDKs need `--enable-native-access=ALL-UNNAMED`.

---

### 1.2 Option 1: Gradle script plugin (recommended, zero setup)

The lightweight script plugin resolves your class output paths, assembles the JVM arguments, and registers a `runRemold` task for you:

> [!IMPORTANT]
> **The script plugin must be version-pinned too.** `apply(from = ...)` has no `plugins { id(...) version ... }`
> coordinate, so always use a **tag** URL (`.../v0.1.3/remold.gradle.kts`) instead of `.../main/...`.
> The script and the `agent`/`api` must stay on the same version, otherwise the script builds arguments for a
> newer agent while the old agent runs — and it only blows up at runtime.

#### Groovy DSL (`build.gradle`):
```groovy
// Apply the script plugin (tag-pinned; for local development use rootProject.file('remold.gradle'))
def remoldRef = (findProperty('remold.version') ?: '0.1.3')
apply from: "https://raw.githubusercontent.com/I-hope1/Remold/v${remoldRef}/remold.gradle"

remold {
    // Optional: pin the Remold version (defaults to remoldVersion in gradle.properties)
    // The plugin resolves the matching agent jar from the repository or Releases and caches it
    // locally, so you never download it by hand.
    // version = '0.1.3'

    // Optional: point at a local remold-agent.jar. Otherwise libs/remold-agent.jar is preferred,
    // and the plugin downloads and caches one if it is missing.
    // agentJar = "libs/remold-agent.jar"

    // Optional: JBR path (defaults to the JBR_HOME env var or the jbr.home system property)
    // jbrPath = "/path/to/jbr"

    // Optional: file-change debounce window in milliseconds (default 1200)
    // fileShakeMs = 1200

    // Optional: enable local JDWP debugging (default false, bound to loopback 127.0.0.1:5005)
    // debug = true
    // debugPort = 5005

    // Optional: main class (inferred automatically if the application plugin is applied)
    // mainClass = "your.package.Main"

    // Optional: JAR launch mode (game mods / standalone clients). Launches the target JAR
    // directly, avoiding class loader conflicts and manual mainClass wiring.
    // jar = "libs/game.jar"
    // args = ["-loadmods"] // Optional: main arguments passed to the launcher
    // autoDeploy = true    // Optional: build and copy the artifact into the deploy directory before launch
    // deployDir = "mods"   // Optional: deploy directory (implies autoDeploy; modsDir is an alias)
    // dependsOn 'jar'      // Optional: extra task to run before launching
}
```

#### Kotlin DSL (`build.gradle.kts`):
```kotlin
// Apply the script plugin (tag-pinned; for local development use file("../remold.gradle.kts"))
val remoldRef = (findProperty("remold.version") as String?) ?: "0.1.3"
apply(from = "https://raw.githubusercontent.com/I-hope1/Remold/v$remoldRef/remold.gradle.kts")

// Note: `remold { }` does NOT work from Kotlin DSL — the extension type lives inside the script plugin,
// so the IDE cannot generate a type-safe accessor for it (Groovy is dynamic and unaffected).
// Configure through gradle.properties or extra instead:
extra["remold.mainClass"] = "your.package.Main"
// extra["remold.version"] = "0.1.3"
// extra["remold.agentJar"] = "libs/remold-agent.jar"
// extra["remold.jbr"] = "/path/to/jbr"
// extra["remold.debug"] = true
// extra["remold.jar"] = "libs/game.jar"       // JAR launch mode
// extra["remold.autoDeploy"] = true
// extra["remold.deployDir"] = "mods"
```

#### Local development / version pinning

Loading a script plugin only has one real decision: **where to take it from**. The templates use these two lines:

```kotlin
// inside the Remold source tree, use the parent script (edits apply immediately); otherwise fetch the tag
apply(from = file("../remold.gradle.kts").takeIf { it.isFile }
    ?: "https://raw.githubusercontent.com/I-Hope1/Remold/v${findProperty("remold.version") ?: "0.1.3"}/remold.gradle.kts")
```

- In the repo: `../remold.gradle.kts` exists → the local script is used, edits take effect immediately, no network;
- Outside: the file does not exist → fetch the tag matching `remold.version`, the same version as the agent and api.

#### Variant: Gradle plugin coordinates (uses Gradle's dependency cache, works offline)

Hand everything to the plugin jar; the build script keeps 4 lines and gets Gradle's dependency cache, checksums and
offline mode:

```kotlin
buildscript {
    repositories { maven("https://jitpack.io") }
    dependencies { classpath("com.github.I-Hope1.Remold:gradle-plugin:v0.1.4") }   // version = Git tag
}
apply(plugin = "io.github.i-hope1.remold")
```

The plugin carries no second implementation: it bundles the repo-root `remold.gradle(.kts)` into its jar, extracts it to
`.gradle/remold/scripts/` and applies it verbatim (`.kts` for Kotlin DSL projects, `.gradle` for Groovy ones), so both
routes behave identically. Configuration is the same as Option 1 (`gradle.properties` / `extra[...]`; Groovy may use `remold { }`).

> [!TIP]
> The `gradle-plugin` module only has artifacts from the **first tag that contains it** (`v0.1.4` onwards). Before that,
> `master-SNAPSHOT` works for a quick look (moves with the branch, not suitable for pinning).

> [!NOTE]
> You may also write `plugins { id("io.github.i-hope1.remold") version "v0.1.4" }`, but JitPack does not host plugin
> markers (`<plugin-id>:<plugin-id>.gradle.plugin`), so you must map it once via
> `pluginManagement.resolutionStrategy.eachPlugin { useModule("com.github.I-Hope1.Remold:gradle-plugin:${requested.version}") }`
> in `settings.gradle.kts`. Publishing to the Gradle Plugin Portal is what removes that block.

Launch with hot reloading:
```bash
./gradlew runRemold
```

> [!TIP]
> **How to avoid tying up the Gradle daemon**
> `runRemold` is a standard Gradle `JavaExec` task, so running it in the foreground blocks the build process. If you want the daemon free to run `./gradlew compileJava` at any time, pick one of these:
> 1. **One-off CLI run**: `./gradlew runRemold --no-daemon` (a temporary JVM, destroyed on exit, never occupies the resident daemon);
> 2. **Detached window**: `./gradlew startRemold` (launches the app in its own window and exits immediately, returning the daemon to IDLE);
> 3. **Generated native script**: `./gradlew generateRemoldScript` (writes `run-remold.bat` / `run-remold.sh` under `build/`, completely outside Gradle);
> 4. **Native IDEA launch**: use an IDEA Application run configuration (see Option 2 below — zero daemon overhead).

> [!TIP]
> **Want to see it immediately?** The repository ships a complete demo module. After cloning:
> ```bash
> ./gradlew :example:runRemold
> ```
> See [`example/README.md`](example/README.md) for details.

---

### 1.3 Option 2: IntelliJ IDEA run configuration (recommended ⭐)

If you prefer clicking "Run / Debug" in IDEA, the script plugin prepares everything for you:
* **Generated automatically**: opening the project or running a build writes a portable `Run_with_Remold.xml` into `.idea/runConfigurations/` (run `./gradlew generateIdeaRunConfig` to refresh it at any time);
* **One canonical location**: the agent and plugin jars are always synced into the project-local `.gradle/remold/` (plugins under `plugins/`), so the generated configuration only ever references `$PROJECT_DIR$/.gradle/remold/...` — never `build/libs` (wiped by `clean`) or `~/.gradle` paths that break on another machine;
* **Ready to use**: the run dropdown in the top-right corner shows **`Run with Remold`** — just hit run or debug;
* **Native and fast**: runs as a plain IDEA JVM process with full breakpoint support and zero Gradle daemon usage.

To configure it by hand instead:

1. Open `Run` → `Edit Configurations...` in IDEA;
2. Select your Application or launch task;
3. Click `Modify options` → enable `Add VM options`;
4. Paste the following into the VM options field:

```text
-XX:+AllowEnhancedClassRedefinition
-javaagent:/path/to/remold-agent.jar=build/classes/java/main;build/classes/kotlin/main
--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED
--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
```

> [!WARNING]
> If you also need JDWP debugging, always bind it to loopback `127.0.0.1:5005`. Never use `*:5005` or `0.0.0.0:5005` (remote code execution risk):
> ```text
> -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005
> ```

---

### 1.4 Option 3: Manual Gradle `JavaExec` task

Without the script plugin, you can register a `JavaExec` task yourself and feed it the compile output directory:

#### Kotlin DSL (`build.gradle.kts`):
```kotlin
tasks.register<JavaExec>("runWithRemold") {
    group = "application"
    description = "Run application with Remold Agent & JBR DCEVM"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("your.package.Main")

    // 1. Point at your JBR (recommended; javaLauncher / toolchains work too)
    // executable = "/path/to/jbr/bin/java"

    // 2. Resolve the compile output directory and hand it to the agent
    val watchDirs = sourceSets["main"].output.classesDirs.asPath
    val agentJar = file("libs/remold-agent.jar").absolutePath

    jvmArgs(
        "-XX:+AllowEnhancedClassRedefinition",
        "-javaagent:$agentJar=$watchDirs",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED"
    )
}
```

#### Groovy DSL (`build.gradle`):
```groovy
tasks.register("runWithRemold", JavaExec) {
    group = "application"
    description = "Run application with Remold Agent & JBR DCEVM"
    classpath = sourceSets.main.runtimeClasspath
    mainClass = "your.package.Main"

    // 1. Point at your JBR (recommended; javaLauncher / toolchains work too)
    // executable = "/path/to/jbr/bin/java"

    // 2. Resolve the compile output directory
    def watchDirs = sourceSets.main.output.classesDirs.asPath
    def agentJar = file("libs/remold-agent.jar").absolutePath

    jvmArgs "-XX:+AllowEnhancedClassRedefinition",
            "-javaagent:${agentJar}=${watchDirs}",
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED"
}
```

---

### 1.5 Option 4: Command line

Download the latest `remold-agent.jar` from [Releases](https://github.com/I-hope1/Remold/releases) (ASM 9.11 and the native acceleration library are shaded and relocated inside it):

```bash
java -XX:+AllowEnhancedClassRedefinition \
     -javaagent:remold-agent.jar=build/classes/java/main \
     --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
     --add-exports java.base/jdk.internal.reflect=ALL-UNNAMED \
     --add-exports java.base/jdk.internal.loader=ALL-UNNAMED \
     --enable-native-access=ALL-UNNAMED \
     -jar your-application.jar
```

---

### 1.6 Gradle dependency (JitPack)

To use the annotations (`@RemoldReinit`, `@OnReload`) or the extension SPI (`HotswapPlugin`, `BytecodeTransformerBridge`) in your own code, add the `api` module:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.I-hope1.Remold:api:0.1.0'
}
```

Maven Central coordinates are reserved: `io.github.i-hope1.remold:api:0.1.0`.

---

## 2. What is Solved vs Known Limitations

Facts first — the capability boundary is stated explicitly.

### 2.1 What is solved

1. **Anonymous class hijacking and misalignment caused by source changes**:
   - `AnonClassAligner` uses confidence tiers 1–4 (exact fingerprint, topological isomorphism, self hash, call-chain tracing) to align anonymous classes across compilations, removing the unreliable name-based pass-through entirely.
2. **New fields on live instances reading as zero after a class grows**:
   - `InitFix` combines constructor parameter tracing, the NestView single-write proof, and reverse bytecode slicing to inject initial values into live heap instances and static state with conditional CAS, so nothing silently reads zero.
3. **Mixin / AOP slices being washed out by redefinition**:
   - `BytecodeTransformerBridge` plus the dual-baseline scheme (source baseline vs effective baseline) re-weaves Mixin-injected hooks and fields on every hot swap, keeping the AOP chain intact.
4. **Destructive field changes breaking live objects**:
   - `LayoutGate` and `LocalClassGuard` refuse the redefinition while live instances exist for a deleted or retyped field, protecting memory safety.
5. **Transparent takeover of redefinitions issued by IDEs / JDWP**:
   - A `ThreadLocal` marker distinguishes the caller, so clicking "Reload Changed Classes" in the IDE still triggers Mixin re-weaving and the safety-gate review.
6. **JVMTI native whole-heap instance lookup with concurrency isolation**:
   - The C++ library scans the whole heap in sub-millisecond time behind a global mutex and dynamic tag isolation; the entire hot-swap pipeline is serialized by the `HOTSWAP_LOCK` reentrant lock.

### 2.2 Known limitations

1. **Orphan lambda stack escape**:
   - Only protected framework dispatch points (registered through `OrphanBreakerPredicate`) get a local circuit-break that throws `NoSuchMethodError` to force deregistration; ordinary uncontrolled business stacks degrade to zero values, and a `CallSite` reference held by a business thread cannot be physically revoked.
2. **Named local classes with nested anonymous classes**:
   - Deeply nested anonymous classes inside local classes have a name-based pass-through blind spot in specific scenarios.
3. **JIT constant folding of `static final`**:
   - Constants already folded into JIT-compiled code cannot be reversed by a memory write alone.
4. **Instance snapshot clock window**:
   - Between the JVMTI heap snapshot and the patch taking effect, objects created by concurrent threads fall into a small timing window.
5. **Complex control-flow slices**:
   - Slices containing branches (if/switch), loops, try-catch, or external mutable builders are firmly rejected by the safety gate and recorded in the `FieldLedger` instead of being written silently.

---

## 3. Modular Architecture

The repository is fully decoupled into modules:

```mermaid
flowchart TD
    API[":api<br/>(Pure Java API and annotations, zero dependencies)"]
    CORE[":core<br/>(Pure JVM agent core, ASM internally relocated)"]
    NATIVE[":native<br/>(JVMTI heap scanning, Panama/JNI bridge)"]
    PLUGIN_ARC[":plugin-arc<br/>(Arc / Mindustry reference plugin)"]
    PLUGIN_MIXIN[":plugin-mixin<br/>(SpongePowered Mixin re-weaving plugin)"]
    TESTS[":tests<br/>(206 JUnit + 318 Oracle + 32 MixinProbe + E2E)"]

    API --> CORE
    NATIVE -.-> CORE
    API --> PLUGIN_ARC
    API --> PLUGIN_MIXIN
    CORE -.-> PLUGIN_ARC
    CORE -.-> PLUGIN_MIXIN
    CORE --> TESTS
    API --> TESTS
    NATIVE --> TESTS
    PLUGIN_ARC --> TESTS
```

- **`:api`**: the lightweight public API (`@RemoldReinit`, `@OnReload`, `@Profile` plus the `HotswapPlugin`, `OrphanBreakerPredicate`, and `BytecodeTransformerBridge` extension SPIs).
- **`:core`**: the agent engine. **ASM 9.11.1 is shaded and relocated to `remold.internal.asm.*`**, so there is never an ASM version or class loader clash with application-level frameworks (Mixin, ByteBuddy, Spring).
- **`:native`**: JVMTI acceleration with Panama/JNI backends, providing sub-millisecond whole-heap instance lookup on Windows (x64), Linux (x64), and macOS (x64/arm64).
- **`:plugin-arc`**: the reference plugin, showing how to extend UI layout-tree comparison and framework-level polling circuit breaks through the SPI.
- **`:plugin-mixin`**: the SpongePowered Mixin ecosystem plugin. It hands the source-level bytecode back to Mixin before redefinition (preserving `@Inject` / `@Unique` / `@Shadow` output), and when a Mixin class itself changes it refreshes the Mixin runtime state and re-weaves the affected target classes. Fabric Loom projects mount it automatically through the Gradle script.
- **`:example`**: a minimal demo app for agent watching, live-instance field initialization, and the `@OnReload` lifecycle callback.
- **`:tests`**: the regression and compatibility suite — 206 multi-version JUnit topology tests, 318 InitFix Oracle positive/negative assertions, and 32 Mixin and JDWP end-to-end assertions.

---

## 4. API & Examples

### 4.1 Plugins & dispatcher

#### Option 1: declarative auto-loading (strongly recommended ⭐)
Remold can load extension plugins declaratively through system properties or Gradle config (for example the Mindustry / Arc plugin `arc`):
- **`gradle.properties`**:
  ```properties
  remold.plugins=arc
  ```
- **Gradle DSL (`build.gradle.kts` / `build.gradle`)**:
  ```kotlin
  remold {
      plugins("arc")
  }
  ```
  The Gradle plugin downloads the matching `remold-plugin-arc.jar` and mounts it on the agent through JVM arguments (`-Dremold.plugins=arc -Dremold.plugin.jars=...`). **Your mod or application code pulls in no Remold dependency at all — zero intrusion, zero residue.**

- **Mixin ecosystems (Fabric / Quilt and friends) need no declaration**: `remold.gradle(.kts)` detects the `fabric-loom` plugin or `net.fabricmc` / `org.spongepowered` dependencies and mounts the `mixin` plugin automatically. Disable it with `-Premold.autoMixin=false`. When developing the plugin itself, point at a local build with `remold { pluginJar("../<remold repo>/plugin-mixin/build/libs/remold-plugin-mixin-<ver>.jar") }`.

#### Option 2: explicit registration in code
To write and register a custom plugin from application code:
```java
import remold.RemoldAgent;

// 1. Install a main-thread dispatcher (default: Runnable::run)
RemoldAgent.setMainThreadDispatcher(myAppDispatcher::post);

// 2. Register a custom framework plugin
RemoldAgent.registerPlugin(new MyFrameworkHotswapPlugin());

// 3. Register a bytecode re-weaving bridge (Mixin compatibility, for example)
RemoldAgent.addTransformerBridge((loader, className, targetClass, currentBytes) -> {
    return myMixinTransformer.transform(className, currentBytes);
});
```

### 4.2 Annotations for live-field initialization and lifecycle
```java
import remold.annotation.RemoldReinit;
import remold.annotation.OnReload;

public class PlayerService {
    // Default behaviour: a new field is only backfilled where the live instance still holds zero (0/null)
    private String nickname = "DefaultName";

    // OVERWRITE: force a CAS write even if the live instance already holds a non-default value
    @RemoldReinit(mode = RemoldReinit.Mode.OVERWRITE)
    private int scoreVersion = 2;

    // Lifecycle callback fired after the hot reload completes
    @OnReload
    private void onReload() {
        System.out.println("PlayerService reloaded for: " + nickname);
    }
}
```

> **`@OnReload` is a callback on the existing instances of *that class*, not a "your mod was reloaded" notification.**
> An instance method runs once per live instance (zero instances means it never runs); a static method runs once
> per hotswap. It looks like a mod-level notification only because a mod main class usually has exactly one
> instance in the process. If the class was not successfully redefined this round (rejected by the layout gate or
> the aligner), the callback never fires. See the `OnReload` Javadoc for the full contract.

> **When `@RemoldReinit` takes effect, the log names the fields**:
> `[REINIT] @RemoldReinit applied to example.ExampleJavaMod: message (OVERWRITE, static)`.
> The patch log itself only says "N fields patched", which cannot tell "a new field was initialized" apart from
> "the annotation asked for a live value to be reset". Fields that did not take effect are marked on the same
> line with `failed` / `skipped` / `no live instance`. Nothing is printed when no field carries the annotation.

> **It resets when the initializer expression changes, not whenever the class is hotswapped**: a method-body-only
> reload no longer throws live values back to their initializers (the log then shows
> `[REINIT] @RemoldReinit kept as-is`). The decision is made from the initializer slice fingerprint, and only for
> **closed expressions** — literals and constant arithmetic whose value is fully determined by their own bytecode.
> If the initializer reads external state — `= ConfigLoader.getDefaultTag()`, `= Other.VALUE` — the slice can stay
> byte-for-byte identical while the callee changes what it returns in that very round. That case cannot be proven
> unchanged, so it **still resets on every reload**: one redundant write beats silently keeping a stale value.
> See the `RemoldReinit` Javadoc for the full contract.

---

## 5. Building & Verification

The project builds with Gradle and requires JDK 21+.

### Run the full verification matrix
```bash
./gradlew check
```

The pipeline enforces:
1. **206 JUnit topology-alignment and native heap-scan tests** (fixtures compiled on the fly across javac 8/11/17/21/25; asserts 0 skipped, 0 failures);
2. **318 InitFix Oracle differential assertions** (covering Java and real Kotlin compiler output, both positive state tracing and negative safety blocking);
3. **32 Mixin and JDWP redefinition end-to-end checks**;
4. **The LayoutGate rule table, GhostProbe stack probing, concurrency hardening tests, and deferred-fingerprint E2E assertions**;
5. **A purity scan of status markers in docs and source (`verifyDocStatusMarkers`)**.

---

## 6. License

This project uses a per-module multi-licensing scheme:

- **Core engine, Panama extension, and API specification** (`:api`, `:core`, `:panama`): **[Apache License 2.0](LICENSE)** — commercially friendly, no copyleft, includes a patent grant.
- **Gradle build plugin** (`:gradle-plugin`): **[Apache License 2.0](LICENSE)**. It only materializes and applies `remold.gradle(.kts)` and assembles JVM arguments at build time; it neither links nor ships Mindustry / Arc code, so it does not inherit the GPL-3.0 of `:plugin-arc`.
- **Mindustry / Arc framework plugin** (`:plugin-arc`): **[GNU General Public License v3.0 (GPL-3.0)](plugin-arc/LICENSE)**, fully aligned with the upstream GPL-3.0 of Mindustry and Arc.
- **Mixin ecosystem plugin** (`:plugin-mixin`): **[GNU General Public License v3.0 (GPL-3.0)](plugin-mixin/LICENSE)**, consistent with `:plugin-arc`. The copyleft here is **a deliberate policy, not license contagion**: SpongePowered Mixin is MIT and Fabric API is Apache-2.0, and both are `compileOnly` dependencies that are never shipped with the artifact.
- **Mod templates** (`mindustry-mod-template`, `minecraft-fabric-template`): **[MIT](mindustry-mod-template/LICENSE)**, so you can copy them into your own project and modify, commercialize, or distribute them closed-source. The templates only reference Remold through build scripts and the `compileOnly` API — they contain no Remold code, so the GPL-3.0 of the plugin layer does not apply to them.
