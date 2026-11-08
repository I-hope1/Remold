# Remold Gradle Plugin

Gradle plugin for [Remold](https://github.com/I-hope1/Remold), the JVM hot-reload agent with schema repair
(InitFix) and bytecode topological alignment.

The plugin turns Remold from "assemble the `-javaagent` arguments yourself" into a single Gradle task:

- resolves the compiled class output directories to watch (Java and Kotlin source sets);
- downloads and caches the matching `remold-agent.jar` (or uses a local copy);
- picks the JetBrains Runtime (JBR / DCEVM) launcher and adds `-XX:+AllowEnhancedClassRedefinition`;
- registers `runRemold`, `startRemold`, `generateRemoldScript` and `generateIdeaRunConfig`;
- supports both `application`-style runs and JAR launch mode for game mods / standalone clients;
- works with the Gradle configuration cache (all values are resolved lazily).

Full English guide, demos and safety model: **[README_EN.md](https://github.com/I-hope1/Remold/blob/main/README_EN.md)**

## Requirements

| Requirement | Notes |
|:--|:--|
| JDK 8+ for the build itself | The agent runs on JDK 8, 11, 17, 21 and 25. |
| A DCEVM-capable runtime for hot-swapping | JetBrains Runtime 17/21/25 launched with `-XX:+AllowEnhancedClassRedefinition`. Plain HotSpot only supports method-body changes. |
| `remold.version` or `version = "..."` | The plugin must know which agent release to resolve. |

## Applying the plugin

### From the Gradle Plugin Portal (once published)

```kotlin
plugins {
    id("io.github.i-hope1.remold") version "0.2.1"
}
```

### From JitPack today

The Portal hosts a plugin marker artifact; JitPack does not, so map the plugin id to the module once:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.i-hope1.remold") {
                useModule("com.github.I-Hope1.Remold:gradle-plugin:${requested.version}")
            }
        }
    }
}
```

The same artifact is attached to every [GitHub Release](https://github.com/I-hope1/Remold/releases)
(`remold-gradle-plugin.jar`) if you prefer a plain `buildscript { classpath(files(...)) }`.

### As a script plugin (no plugin resolution at all)

The repository ships `remold.gradle` / `remold.gradle.kts` as thin shims that apply this plugin and nothing else.
They are also bundled inside the plugin jar under `remold/`, so offline users can extract them.

```groovy
apply from: "https://raw.githubusercontent.com/I-hope1/Remold/v0.2.1/remold.gradle"
```

## Configuring

```groovy
remold {
    version = "0.2.1"            // agent version to resolve (or gradle.properties: remold.version)
    // agentJar   = "libs/remold-agent.jar"   // use a local agent jar instead of downloading one
    // jbrPath    = "/path/to/jbr"            // JBR home (default: JBR_HOME env / jbr.home system property)
    // mainClass  = "your.package.Main"       // inferred when the application plugin is applied
    // fileShakeMs = 800                      // class/jar quiet window; every class event resets it
    // assetShakeMs = 250                     // asset/config quiet window; independent from class batches
    // debug      = true                      // JDWP on 127.0.0.1:5005 (loopback only)
    // debugPort  = 5005

    // JAR launch mode (game mods, standalone clients)
    // jar        = "libs/game.jar"
    // args       = ["-loadmods"]
    // autoDeploy = true                      // build and copy the artifact before launching
    // deployDir  = "mods"                    // implies autoDeploy (modsDir is an alias)

    // plugins("arc", "mixin")                // Remold ecosystem plugins to attach
    // extraJvmArgs("--enable-native-access=ALL-UNNAMED")
    // dependsOn("jar")
}
```

Every option also has a `remold.<name>` project property / `extra` entry (`remold.mainClass`, `remold.jbr`,
`remold.deployDir`, ...), which is how Kotlin DSL builds configure the plugin without a `remold { }` accessor.

### Tasks

| Task | Purpose |
|:--|:--|
| `runRemold` | `JavaExec` task that launches the application with the agent attached (foreground). |
| `startRemold` | Launches the same configuration in a detached window/process and returns immediately. |
| `generateRemoldScript` | Writes `build/run-remold.bat` / `build/run-remold.sh` so the app can start without Gradle. |
| `generateIdeaRunConfig` | Writes `.idea/runConfigurations/Run_with_Remold.xml` with portable paths. |
| `deployArtifact`, `deployMod` | Copy the built artifact (remapping Loom jars when present) into the deploy directory. |

Typical loop: `./gradlew runRemold`, then edit a file and let the compile task / IDE recompile it — the agent
swaps the new bytecode into the running JVM.

## Notes

- The plugin never ships a second implementation of Remold: it bundles the repository-root `remold.gradle(.kts)`
  shims and applies the same `RemoldPlugin` class either way.
- Loader run injection covers every run environment: Fabric Loom / ForgeGradle / NeoForge ModDevGradle
  `server` runs and Fabric's `clientGameTest` / `gameTest` gametest runs get the same agent JVM
  arguments as `client` runs, and the IDEA sync updates `Minecraft_Server.xml` as well as
  `Minecraft_Client.xml`.

- Downstream templates: [`mindustry-mod-template`](https://github.com/I-hope1/Remold/tree/main/mindustry-mod-template)
  and [`minecraft-fabric-template`](https://github.com/I-hope1/Remold/tree/main/minecraft-fabric-template)
  (MC 1.21.1 baseline). Fabric client gametests need MC 1.21.4, so the `runClientGameTest` demo lives in the
  standalone probe [`tests/fabric-gametest-1.21.4/`](https://github.com/I-hope1/Remold/tree/main/tests/fabric-gametest-1.21.4).
- License: Apache-2.0.

## Building from source

```bash
./gradlew :gradle-plugin:build      # compile + unit tests
./gradlew :gradle-plugin:jar        # build/libs/remold-gradle-plugin-<version>.jar
./gradlew :gradle-plugin:publishPlugins   # requires GRADLE_PUBLISH_KEY / GRADLE_PUBLISH_SECRET
```
